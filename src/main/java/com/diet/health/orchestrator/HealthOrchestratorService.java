package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.exception.DietException;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthIntentResult;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.health.session.SessionResourceRef;
import com.diet.model.ConversationTurn;
import com.diet.model.RequestTraceRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.ChatIdempotencySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康编排器：统一健康聊天主流程。
 * <p>
 * 意图（IntentAgent，契约模块）→ Java 风险确认 → 澄清规则 → 领域模块检索 → 响应 Agent 解释候选 → 持久化 + Trace。
 * Agent 失败一律立即确定性降级，不自动重试。
 */
@Service
public class HealthOrchestratorService {

    /** 单次推荐解释的候选数上限。 */
    private static final int TOP_N = 3;

    private final HealthSessionService sessionService;
    private final SessionService messageService;
    private final HealthIntentAgentService intentAgentService;
    private final HealthClarifyRuleService clarifyRuleService;
    private final HealthClarifyAgentService clarifyAgentService;
    private final HealthRiskRuleService riskRuleService;
    private final MealModule mealModule;
    private final ExerciseModule exerciseModule;
    private final RoutineModule routineModule;
    private final HealthResourceProvider resourceProvider;
    private final HealthRecommendResponseService recommendResponseService;
    private final AgentTraceService agentTraceService;
    private final ObjectMapper objectMapper;

    /** 会话级锁，保证同一 session 串行写状态。 */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public HealthOrchestratorService(
            HealthSessionService sessionService,
            SessionService messageService,
            HealthIntentAgentService intentAgentService,
            HealthClarifyRuleService clarifyRuleService,
            HealthClarifyAgentService clarifyAgentService,
            HealthRiskRuleService riskRuleService,
            MealModule mealModule,
            ExerciseModule exerciseModule,
            RoutineModule routineModule,
            HealthResourceProvider resourceProvider,
            HealthRecommendResponseService recommendResponseService,
            AgentTraceService agentTraceService,
            ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.intentAgentService = intentAgentService;
        this.clarifyRuleService = clarifyRuleService;
        this.clarifyAgentService = clarifyAgentService;
        this.riskRuleService = riskRuleService;
        this.mealModule = mealModule;
        this.exerciseModule = exerciseModule;
        this.routineModule = routineModule;
        this.resourceProvider = resourceProvider;
        this.recommendResponseService = recommendResponseService;
        this.agentTraceService = agentTraceService;
        this.objectMapper = objectMapper;
    }

    /** 处理一轮健康聊天。 */
    public HealthChatResponse healthChat(Long userId, HealthChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new DietException("用户问题不能为空");
        }
        String requestId = normalizeRequestId(request.requestId());
        HealthSessionState initialState = sessionService.loadOrCreate(blankToNull(request.sessionId()), userId);

        RequestTraceRow previous = agentTraceService.findByRequestId(userId, initialState.sessionId(), requestId);
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), HealthChatResponse.class);
        }

        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        try (AgentTraceService.TraceScope scope = agentTraceService.openTrace(traceId, initialState.sessionId(), userId, requestId)) {
            try {
                Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                synchronized (lock) {
                    RequestTraceRow concurrent = agentTraceService.findByRequestId(userId, initialState.sessionId(), requestId);
                    if (ChatIdempotencySupport.hasSnapshot(concurrent)) {
                        scope.discard();
                        return ChatIdempotencySupport.restore(objectMapper, concurrent.getResponseJson(), HealthChatResponse.class);
                    }
                    long startedAt = System.nanoTime();
                    agentTraceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);
                    HealthChatResponse response = handleTurn(userId, request, initialState, traceId);
                    scope.setResponse(response);
                    agentTraceService.recordEvent("REQUEST_FINISHED", "HTTP", request, response, ChatIdempotencySupport.elapsedMs(startedAt));
                    scope.close();
                    return response;
                }
            } catch (RuntimeException error) {
                agentTraceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                throw error;
            }
        }
    }

    /** 一轮完整状态机。 */
    private HealthChatResponse handleTurn(Long userId, HealthChatRequest request, HealthSessionState state, String traceId) {
        String userInput = request.message();
        String sessionId = state.sessionId();
        messageService.appendMessage(sessionId, "user", userInput, null, traceId);
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", userInput, Map.of("sessionId", sessionId));

        HealthIntentResult intent = intentAgentService.recognize(userInput, state.slots(), recentHistory(userId, sessionId));
        Map<String, Object> intentPayload = new LinkedHashMap<>();
        intentPayload.put("domain", intent.domain());
        intentPayload.put("task", intent.task());
        intentPayload.put("riskFlags", intent.riskFlags());
        intentPayload.put("confidence", intent.confidence());
        intentPayload.put("degraded", intent.degraded());
        intentPayload.put("fallbackReason", intent.fallbackReason());
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", userInput, intentPayload);

        // 风险信号跨轮累积：会话历史信号 + 本轮意图信号，由 Java 规则统一确认
        List<String> sessionRiskFlags = mergeFlags(state.riskFlags(), intent.riskFlags());
        HealthRiskRuleService.RiskDecision risk = riskRuleService.assess(userInput, sessionRiskFlags);
        agentTraceService.recordEvent("RISK_ASSESSED", "RISK",
                Map.of("rulesVersion", HealthRiskRuleService.RULES_VERSION, "intentRiskFlags", intent.riskFlags()),
                Map.of("level", risk.level(), "matchedFlags", risk.matchedFlags()));
        if (risk.blocked()) {
            HealthChatResponse blocked = HealthChatResponse.blocked(sessionId, traceId, intent.domain(), intent.task(),
                    risk.matchedFlags(), risk.copy() == null ? HealthRiskRuleService.BLOCK_PLAN_COPY : risk.copy());
            return persistAndRespond(state, intent, state.slots(), blocked, traceId);
        }
        // ADVISORY 等级不阻止单次推荐，但在最终回复中透出固定提示文案
        String advisoryCopy = risk.level() == HealthRiskLevel.ADVISORY ? risk.copy() : null;

        Map<String, List<String>> mergedSlots = mergeSlots(state.slots(), intent.slots());
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        if (intent.domain() == HealthDomain.COMPOSITE || intent.task() == HealthTask.PLAN || intent.task() == HealthTask.BROWSE) {
            String copy = switch (intent.domain()) {
                case COMPOSITE -> "我可以分别帮你安排饮食、训练和作息，也可以先从其中一个方面开始，你想先看哪个？";
                default -> intent.task() == HealthTask.PLAN
                        ? "周计划功能已经上线：进入「我的计划」页面即可查看和激活每周安排；如尚未完善健康档案，请先前往「健康档案」页面填写基础信息，再返回计划页面生成草稿。我不会在聊天里直接替你创建计划，避免生成重复草稿。"
                        : "餐食和动作可以在对应页面浏览，也可以直接告诉我你的需求，我来帮你筛选。";
            };
            HealthChatResponse notice = HealthChatResponse.answer(sessionId, traceId, intent.domain(), intent.task(),
                    risk.matchedFlags(), HealthPhase.RESPOND, withAdvisory(copy, advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, notice, traceId);
        }

        // ADJUST 排除（43 号票 + #69 类型化契约）：只取 MEAL/EXERCISE 类型化字符串 resourceId，
        // 作息事实不参与排除；fixture 种子 ID 原样传递，reviewed 数值解析在餐食模块查询前完成
        List<String> excludeIds = intent.task() == HealthTask.ADJUST
                ? state.excludeIdsFor(intent.domain() == HealthDomain.EXERCISE ? "EXERCISE" : "MEAL")
                : List.of();
        return handleRecommend(sessionId, traceId, state, intent, mergedSlots, excludeIds,
                risk.matchedFlags(), advisoryCopy, userInput);
    }

    /** 单品类推荐主链路：澄清 → 检索 → 解释候选。 */
    private HealthChatResponse handleRecommend(String sessionId, String traceId, HealthSessionState state,
                                               HealthIntentResult intent, Map<String, List<String>> mergedSlots,
                                               List<String> excludeIds, List<String> riskFlags, String advisoryCopy,
                                               String userInput) {
        HealthDomain domain = intent.domain();
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("domain", domain, "task", intent.task()));

        List<String> missing = clarifyRuleService.missingSlots(domain, mergedSlots);
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", mergedSlots, missing);
        if (!missing.isEmpty()) {
            String question = clarifyAgentService.wording(domain, userInput, missing, mergedSlots);
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, domain, intent.task(),
                    riskFlags, question, missing);
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId);
        }

        List<HealthResource> candidates = retrieve(domain, mergedSlots, excludeIds, userInput);
        agentTraceService.recordEvent("CANDIDATES_RETRIEVED", "RETRIEVE",
                Map.of("domain", domain, "providerMode", resourceProvider.providerMode().name(),
                        "resourceVersion", resourceProvider.resourceVersion(), "excludeIds", excludeIds),
                Map.of("candidateCount", candidates.size(), "candidateIds", candidates.stream().map(HealthResource::resourceId).toList()));
        if (candidates.isEmpty()) {
            HealthChatResponse empty = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                    riskFlags, HealthPhase.RESPOND, withAdvisory(emptyCopy(domain), advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, empty, traceId);
        }

        List<HealthResource> topN = candidates.stream().limit(TOP_N).toList();
        HealthRecommendResponseService.RecommendOutcome outcome = recommendResponseService.respond(domain,
                userInput, topN);
        Map<String, Object> responseAgentInput = new LinkedHashMap<>();
        responseAgentInput.put("domain", domain);
        responseAgentInput.put("candidateIds", topN.stream().map(HealthResource::resourceId).toList());
        Map<String, Object> responseAgentOutput = new LinkedHashMap<>();
        responseAgentOutput.put("fallbackReason", outcome.fallbackReason());
        responseAgentOutput.put("referencedIds", outcome.reasons().keySet());
        agentTraceService.recordEvent("RESPONSE_AGENT_RESULT", "RESPONSE", responseAgentInput, responseAgentOutput);

        List<HealthDisplayBlock> blocks = topN.stream()
                .map(candidate -> new HealthDisplayBlock(
                        candidate.resourceType(),
                        candidate.resourceId(),
                        candidate.name(),
                        candidate.sourceType(),
                        candidate.sourceName(),
                        candidate.mediaUrl(),
                        candidate.planReady(),
                        outcome.reasons().getOrDefault(candidate.resourceId(), "匹配你选择的偏好条件")
                ))
                .toList();
        HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                riskFlags, HealthPhase.RESPOND, withAdvisory(outcome.speechText(), advisoryCopy), blocks);
        return persistAndRespond(state, intent, mergedSlots, response, traceId);
    }

    /** 拼接 ADVISORY 固定提示文案（有值才拼）。 */
    private String withAdvisory(String speechText, String advisoryCopy) {
        if (advisoryCopy == null || advisoryCopy.isBlank()) {
            return speechText;
        }
        return speechText + " " + advisoryCopy;
    }

    /** 领域检索：餐食走 MealModule（reviewed 检索 / fixture 种子），动作走 Provider 筛选，作息走事实查询。 */
    private List<HealthResource> retrieve(HealthDomain domain, Map<String, List<String>> slots,
                                          List<String> excludeIds, String userInput) {
        return switch (domain) {
            case MEAL -> mealModule.recommendMeals(slots, excludeIds, userInput);
            case EXERCISE -> exerciseModule.recommend(slots, excludeIds, TOP_N);
            case ROUTINE -> routineModule.lookup(userInput, slots).stream()
                    .map(routineModule::toResource)
                    .toList();
            default -> List.of();
        };
    }

    /** 统一收尾：保存会话状态 → 落库助手消息 → Trace → 返回。 */
    private HealthChatResponse persistAndRespond(HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, HealthChatResponse response,
                                                 String traceId) {
        HealthSessionState saved = state
                .withPhase(response.phase())
                .withIntent(intent.domain(), intent.task(), mergeFlags(state.riskFlags(), intent.riskFlags()))
                .withSlots(mergedSlots)
                .withPreferenceSignals(intent.preferenceSignals());
        if (response.responseType() == HealthResponseType.ANSWER) {
            saved = saved.appendLastResources(response.displayBlocks().stream()
                    .map(block -> new SessionResourceRef(block.resourceType(), block.resourceId()))
                    .toList());
        }
        sessionService.save(saved);
        messageService.appendMessage(state.sessionId(), "assistant", response.speechText(), null, traceId);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", saved, response);
        return response;
    }

    private List<String> recentHistory(Long userId, String sessionId) {
        return messageService.recentConversationTurns(sessionId, userId, 3).stream()
                .map(ConversationTurn::toString)
                .toList();
    }

    private Map<String, List<String>> mergeSlots(Map<String, List<String>> history, Map<String, List<String>> current) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        if (history != null) {
            merged.putAll(history);
        }
        if (current != null) {
            current.forEach((slot, values) -> {
                if (values != null && !values.isEmpty()) {
                    merged.put(slot, values);
                }
            });
        }
        return merged;
    }

    /** 合并两批风险信号，去重保序。 */
    private List<String> mergeFlags(List<String> history, List<String> current) {
        LinkedHashMap<String, Boolean> merged = new LinkedHashMap<>();
        if (history != null) {
            history.forEach(flag -> merged.put(flag, Boolean.TRUE));
        }
        if (current != null) {
            current.forEach(flag -> merged.put(flag, Boolean.TRUE));
        }
        return List.copyOf(merged.keySet());
    }

    private String emptyCopy(HealthDomain domain) {
        return switch (domain) {
            case MEAL -> "餐食库里暂时没有匹配的结果，可以补充口味、菜系或换个说法试试。";
            case EXERCISE -> "当前动作库里暂时没有匹配的动作，可以换一下部位或器材试试。";
            case ROUTINE -> "暂时没有找到对应的作息建议，可以换个说法试试。";
            default -> "暂时没有找到匹配的内容。";
        };
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new DietException("requestId 不能为空");
        }
        String normalized = requestId.trim();
        if (normalized.length() > 128) {
            throw new DietException("requestId 长度不能超过 128 个字符");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
