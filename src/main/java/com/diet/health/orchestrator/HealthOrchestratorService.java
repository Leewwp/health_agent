package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.exception.DietException;
import com.diet.exception.HealthApiException;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthNextAction;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentResult;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.health.intent.HealthPlanIntentMatcher;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.model.HealthAction;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.PlanBriefExtractionAgentService;
import com.diet.health.plan.PlanBriefService;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.MealPlanBriefService;
import com.diet.health.profile.HealthProfileService;
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
import java.time.Duration;

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
    private final HealthIntentRevisionService intentRevisionService;
    private final HealthInputNormalizer inputNormalizer;
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
    private final PlanBriefService planBriefService;
    private final MealPlanBriefService mealPlanBriefService;
    private final PlanBriefExtractionAgentService planBriefExtractionAgentService;
    private final HealthProfileService profileService;
    private final boolean intentFastPathEnabled;

    @org.springframework.beans.factory.annotation.Value("${diet.request.timeout-ms:5000}")
    private long requestTimeoutMs = 5000L;

    /** 会话级锁，保证同一 session 串行写状态。 */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public HealthOrchestratorService(
            HealthSessionService sessionService,
            SessionService messageService,
            HealthIntentAgentService intentAgentService,
            HealthIntentRevisionService intentRevisionService,
            HealthInputNormalizer inputNormalizer,
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
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper, null, null, true);
    }

    /** 可关闭快路径供模型契约基准使用；线上默认开启。 */
    public HealthOrchestratorService(
            HealthSessionService sessionService,
            SessionService messageService,
            HealthIntentAgentService intentAgentService,
            HealthIntentRevisionService intentRevisionService,
            HealthInputNormalizer inputNormalizer,
            HealthClarifyRuleService clarifyRuleService,
            HealthClarifyAgentService clarifyAgentService,
            HealthRiskRuleService riskRuleService,
            MealModule mealModule,
            ExerciseModule exerciseModule,
            RoutineModule routineModule,
            HealthResourceProvider resourceProvider,
            HealthRecommendResponseService recommendResponseService,
            AgentTraceService agentTraceService,
            ObjectMapper objectMapper,
            boolean intentFastPathEnabled
    ) {
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper, null,
                null, intentFastPathEnabled);
    }

    /** Spring 入口：健康档案只用于计划简报的缺档案提示，最终风险仍由计划服务 Guard 决定。 */
    @org.springframework.beans.factory.annotation.Autowired
    public HealthOrchestratorService(
            HealthSessionService sessionService,
            SessionService messageService,
            HealthIntentAgentService intentAgentService,
            HealthIntentRevisionService intentRevisionService,
            HealthInputNormalizer inputNormalizer,
            HealthClarifyRuleService clarifyRuleService,
            HealthClarifyAgentService clarifyAgentService,
            HealthRiskRuleService riskRuleService,
            MealModule mealModule,
            ExerciseModule exerciseModule,
            RoutineModule routineModule,
            HealthResourceProvider resourceProvider,
            HealthRecommendResponseService recommendResponseService,
            AgentTraceService agentTraceService,
            ObjectMapper objectMapper,
            HealthProfileService profileService,
            PlanBriefExtractionAgentService planBriefExtractionAgentService
    ) {
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper,
                profileService, planBriefExtractionAgentService, true);
    }

    private HealthOrchestratorService(
            HealthSessionService sessionService,
            SessionService messageService,
            HealthIntentAgentService intentAgentService,
            HealthIntentRevisionService intentRevisionService,
            HealthInputNormalizer inputNormalizer,
            HealthClarifyRuleService clarifyRuleService,
            HealthClarifyAgentService clarifyAgentService,
            HealthRiskRuleService riskRuleService,
            MealModule mealModule,
            ExerciseModule exerciseModule,
            RoutineModule routineModule,
            HealthResourceProvider resourceProvider,
            HealthRecommendResponseService recommendResponseService,
            AgentTraceService agentTraceService,
            ObjectMapper objectMapper,
            HealthProfileService profileService,
            PlanBriefExtractionAgentService planBriefExtractionAgentService,
            boolean intentFastPathEnabled
    ) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.intentAgentService = intentAgentService;
        this.intentRevisionService = intentRevisionService;
        this.inputNormalizer = inputNormalizer;
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
        this.planBriefService = new PlanBriefService(inputNormalizer);
        this.mealPlanBriefService = new MealPlanBriefService();
        this.planBriefExtractionAgentService = planBriefExtractionAgentService;
        this.profileService = profileService;
        this.intentFastPathEnabled = intentFastPathEnabled;
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
                    HealthChatResponse response = handleTurn(userId, request, initialState, traceId,
                            System.nanoTime() + requestTimeoutMs * 1_000_000L);
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
    private HealthChatResponse handleTurn(Long userId, HealthChatRequest request, HealthSessionState state, String traceId,
                                           long deadlineNanos) {
        String userInput = request.message();
        String sessionId = state.sessionId();
        messageService.appendMessage(sessionId, "user", userInput, null, traceId);
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", userInput, Map.of("sessionId", sessionId));

        HealthIntentAgentService.Recognition recognition = intentRevisionService.continueBeforeAgent(userInput, state)
                .map(result -> new HealthIntentAgentService.Recognition(result, "STATE_CONTINUATION"))
                .orElseGet(() -> intentFastPathEnabled
                        ? intentAgentService.recognizeWithDiagnostics(userInput, state.slots(),
                        recentHistory(userId, sessionId), remaining(deadlineNanos))
                        : new HealthIntentAgentService.Recognition(
                        intentAgentService.recognize(userInput, state.slots(), recentHistory(userId, sessionId)), "AGENT"));
        HealthIntentResult rawIntent = recognition.result();
        HealthIntentRevisionService.Revision revision = intentRevisionService.revise(userInput, state, rawIntent);
        HealthIntentResult intent = revision.intent();
        Map<String, Object> intentPayload = new LinkedHashMap<>();
        intentPayload.put("domain", intent.domain());
        intentPayload.put("task", intent.task());
        intentPayload.put("riskFlags", intent.riskFlags());
        intentPayload.put("confidence", intent.confidence());
        intentPayload.put("degraded", intent.degraded());
        intentPayload.put("fallbackReason", intent.fallbackReason());
        intentPayload.put("resolutionSource", recognition.source());
        intentPayload.put("rawDomain", rawIntent.domain());
        intentPayload.put("rawTask", rawIntent.task());
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", userInput, intentPayload);

        // 风险信号跨轮累积：会话历史信号 + 本轮意图信号，由 Java 规则统一确认
        List<String> historicalRiskFlags = state.riskFlags();
        List<String> sessionRiskFlags = mergeFlags(historicalRiskFlags, intent.riskFlags());
        HealthRiskRuleService.RiskDecision risk = riskRuleService.assess(userInput, sessionRiskFlags);
        agentTraceService.recordEvent("RISK_ASSESSED", "RISK",
                Map.of("rulesVersion", HealthRiskRuleService.RULES_VERSION,
                        "historicalRiskFlags", historicalRiskFlags,
                        "intentRiskFlags", intent.riskFlags(),
                        "assessedRiskFlags", sessionRiskFlags),
                Map.of("level", risk.level(), "matchedFlags", risk.matchedFlags()));
        if (risk.blocked()) {
            HealthChatResponse blocked = HealthChatResponse.blocked(sessionId, traceId, intent.domain(), intent.task(),
                    risk.matchedFlags(), risk.copy() == null ? HealthRiskRuleService.BLOCK_PLAN_COPY : risk.copy());
            return persistAndRespond(state, intent, state.slots(), blocked, traceId, deadlineNanos);
        }
        // ADVISORY 等级不阻止单次推荐，但在最终回复中透出固定提示文案
        String advisoryCopy = risk.level() == HealthRiskLevel.ADVISORY ? risk.copy() : null;

        Map<String, List<String>> mergedSlots = mergeSlots(state.slots(), intent.slots());
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        if (revision.clarifyDomain()) {
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.OTHER,
                    HealthTask.CHAT, risk.matchedFlags(), "你想看餐食推荐、健身动作，还是查询作息建议？", List.of("domain"));
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId, deadlineNanos);
        }

        if (intent.domain() == HealthDomain.OTHER || intent.task() == HealthTask.CHAT) {
            HealthChatResponse chat = HealthChatResponse.answer(sessionId, traceId, HealthDomain.OTHER,
                    HealthTask.CHAT, risk.matchedFlags(), HealthPhase.RESPOND,
                    "我可以帮你处理饮食、健身和作息相关的问题。这个问题不在当前健康助手的能力范围内。", List.of());
            return persistAndRespond(state, intent, mergedSlots, chat, traceId, deadlineNanos);
        }

        if (intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.EXERCISE
                && (HealthPlanIntentMatcher.matchesExercise(userInput)
                || isPlanBriefContinuation(state, HealthDomain.EXERCISE, userInput))) {
            return handlePlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                    risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        if (intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.MEAL
                && (HealthPlanIntentMatcher.matchesMeal(userInput)
                || isPlanBriefContinuation(state, HealthDomain.MEAL, userInput))) {
            return handleMealPlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                    risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        if (intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.COMPOSITE
                && (HealthPlanIntentMatcher.matchesComposite(userInput)
                || isPlanBriefContinuation(state, HealthDomain.COMPOSITE, userInput))) {
            return handleCompositePlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                    risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        if (intent.domain() == HealthDomain.COMPOSITE || intent.task() == HealthTask.PLAN || intent.task() == HealthTask.BROWSE) {
            String copy = switch (intent.domain()) {
                case COMPOSITE -> "我可以分别帮你安排饮食、训练和作息，也可以先从其中一个方面开始，你想先看哪个？";
                default -> intent.task() == HealthTask.PLAN
                        ? "周计划功能已经上线：进入「我的计划」页面即可查看和激活每周安排；如尚未完善健康档案，请先前往「健康档案」页面填写基础信息，再返回计划页面生成草稿。我不会在聊天里直接替你创建计划，避免生成重复草稿。"
                        : "餐食和动作可以在对应页面浏览，也可以直接告诉我你的需求，我来帮你筛选。";
            };
            HealthChatResponse notice = HealthChatResponse.answer(sessionId, traceId, intent.domain(), intent.task(),
                    risk.matchedFlags(), HealthPhase.RESPOND, withAdvisory(copy, advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, notice, traceId, deadlineNanos);
        }

        // ADJUST 排除（43 号票 + #69 类型化契约）：只取 MEAL/EXERCISE 类型化字符串 resourceId，
        // 作息事实不参与排除；fixture 种子 ID 原样传递，reviewed 数值解析在餐食模块查询前完成
        List<String> excludeIds = intent.task() == HealthTask.ADJUST
                ? state.excludeIdsFor(intent.domain() == HealthDomain.EXERCISE ? "EXERCISE" : "MEAL")
                : List.of();
        boolean switchedDomain = state.domain() != null && state.domain() != intent.domain();
        Map<String, List<String>> activeSlots = inputNormalizer.project(intent.domain(),
                switchedDomain ? intent.slots() : mergedSlots);
        agentTraceService.recordEvent("DOMAIN_SLOTS_PROJECTED", "SLOT",
                Map.of("domain", intent.domain(), "switchedDomain", switchedDomain), activeSlots);
        return handleRecommend(sessionId, traceId, state, intent, mergedSlots, activeSlots, excludeIds,
                risk.matchedFlags(), advisoryCopy, userInput, revision.clarifyUnsafe(), deadlineNanos);
    }

    /** 训练简报闭环：解析/合并只在 PLAN 上下文运行，普通推荐不会触碰 planBrief。 */
    private HealthChatResponse handlePlanBrief(Long userId, HealthSessionState state, HealthIntentResult intent,
                                                Map<String, List<String>> mergedSlots, String sessionId, String traceId,
                                                String userInput, List<String> riskFlags, String advisoryCopy,
                                                String requestId, long deadlineNanos) {
        PlanBriefService.UpdateResult update = planBriefService.update(state.planBrief(), userInput);
        if (planBriefExtractionAgentService != null && update.agentEligible()) {
            PlanBriefExtractionAgentService.ExtractionResult extraction = planBriefExtractionAgentService.extract(
                    userInput, state.planBrief(), state.planBrief() == null ? null : state.planBrief().expectedField(),
                    remaining(deadlineNanos));
            agentTraceService.recordEvent("PLAN_BRIEF_EXTRACTION_RESULT", "AGENT",
                    Map.of("status", update.status(), "agentInvoked", true),
                    Map.of("parsed", extraction.parsed(), "confidence", extraction.confidence(),
                            "candidateFields", extraction.candidateFields(), "failureReason", extraction.failureReason()));
            if (extraction.parsed()) {
                update = planBriefService.applyAgentCandidate(state.planBrief(), extraction.candidateFields(), extraction.evidence());
            }
        }
        PlanBrief brief = update.brief();
        agentTraceService.recordEvent("PLAN_BRIEF_UPDATED", "PLAN", Map.of("input", userInput),
                Map.of("brief", brief, "missingFields", update.missingFields(), "confirmedNow", update.confirmedNow(),
                        "status", update.status(), "evidence", update.evidence()));
        HealthChatResponse response;
        if (!brief.isComplete()) {
            String guidance = (update.status() == com.diet.health.plan.BriefInterpretationStatus.INVALID
                    && (state.planBrief() == null || state.planBrief().expectedField() == null
                    || state.planBrief().expectedField().isBlank()))
                    || update.guidance() == null || update.guidance().isBlank()
                    ? planBriefService.question(update.missingFields()) : update.guidance();
            response = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.EXERCISE, HealthTask.PLAN,
                    riskFlags, guidance, update.missingFields())
                    .withPlanBrief(brief, List.of(), HealthNextAction.ASK_CLARIFY);
        } else if (!hasHealthProfile(userId)) {
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.EXERCISE, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("我已保留这份训练偏好。健康档案还缺少生成计划必需的年龄、身高、体重、活动水平和主要目标，请补齐后回到当前会话继续确认。", advisoryCopy), List.of())
                    .withPlanBrief(brief, List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
        } else if (brief.isConfirmedAndComplete()) {
            String generationRequestId = requestId + "-plan";
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.EXERCISE, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("训练偏好已确认，可以生成本周训练计划。", advisoryCopy), List.of())
                    .withPlanBrief(brief, List.of(new HealthAction("GENERATE_PLAN", "生成训练计划", generationRequestId)),
                            HealthNextAction.GENERATE_PLAN);
        } else {
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.EXERCISE, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("训练偏好已整理：" + planBriefService.summary(brief) + "。请确认后生成计划。", advisoryCopy), List.of())
                    .withPlanBrief(brief, List.of(new HealthAction("CONFIRM_PLAN_BRIEF", "确认训练偏好", requestId)),
                            HealthNextAction.CONFIRM_PLAN_BRIEF);
        }
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos, brief);
    }

    /** 餐食简报闭环：不读取或改写训练简报，生成动作只指向 MEAL 范围。 */
    private HealthChatResponse handleMealPlanBrief(Long userId, HealthSessionState state, HealthIntentResult intent,
                                                   Map<String, List<String>> mergedSlots, String sessionId,
                                                   String traceId, String userInput, List<String> riskFlags,
                                                   String advisoryCopy, String requestId, long deadlineNanos) {
        com.diet.health.plan.MealPlanBriefService.UpdateResult update =
                mealPlanBriefService.update(state.mealPlanBrief(), userInput);
        MealPlanBrief brief = update.brief();
        HealthChatResponse response;
        if (!brief.isComplete()) {
            response = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, update.guidance(), update.missingFields())
                    .withMealPlanBrief(brief);
        } else if (!hasHealthProfile(userId)) {
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("我已保留这份餐食偏好。健康档案还缺少生成计划必需的信息，请补齐后回到当前会话继续确认。", advisoryCopy),
                    List.of())
                    .withMealPlanBrief(brief)
                    .withPlanBrief(state.planBrief(), List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
        } else if (brief.isConfirmedAndComplete()) {
            String generationRequestId = requestId + "-meal";
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("餐食偏好已确认，可以生成本周餐食计划。", advisoryCopy), List.of())
                    .withMealPlanBrief(brief)
                    .withPlanBrief(state.planBrief(), List.of(new HealthAction("GENERATE_PLAN", "生成餐食计划", generationRequestId)),
                            HealthNextAction.GENERATE_PLAN);
        } else {
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("餐食偏好已整理：" + mealPlanBriefService.summary(brief) + "。请确认后生成计划。", advisoryCopy),
                    List.of())
                    .withMealPlanBrief(brief)
                    .withPlanBrief(state.planBrief(), List.of(new HealthAction("CONFIRM_MEAL_PLAN_BRIEF", "确认餐食计划", requestId)),
                            HealthNextAction.CONFIRM_PLAN_BRIEF);
        }
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                state.planBrief(), brief);
    }

    /** 综合简报闭环：训练和餐食分别收集/确认，最后才允许一次性生成 COMPOSITE。 */
    private HealthChatResponse handleCompositePlanBrief(Long userId, HealthSessionState state,
                                                        HealthIntentResult intent,
                                                        Map<String, List<String>> mergedSlots, String sessionId,
                                                        String traceId, String userInput, List<String> riskFlags,
                                                        String advisoryCopy, String requestId, long deadlineNanos) {
        PlanBrief training = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
        MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
        boolean mealInput = mealPlanBriefService.looksLikeMealInput(userInput)
                && !looksLikeTrainingBriefInput(userInput);
        if (!training.isConfirmedAndComplete() && !mealInput) {
            PlanBriefService.UpdateResult update = updateTrainingBrief(training, userInput, deadlineNanos);
            training = update.brief();
            if (!training.isComplete()) {
                return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                        HealthChatResponse.clarify(sessionId, traceId, HealthDomain.COMPOSITE, HealthTask.PLAN,
                                riskFlags, update.guidance(), update.missingFields())
                                .withPlanBrief(training, List.of(), HealthNextAction.ASK_CLARIFY),
                        training, meal, traceId, deadlineNanos);
            }
            if (!training.isConfirmedAndComplete()) {
                HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId,
                        HealthDomain.COMPOSITE, HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                        withAdvisory("训练简报已整理：" + planBriefService.summary(training) + "。请先确认训练简报，再补充餐食简报。", advisoryCopy),
                        List.of()).withPlanBrief(training,
                        List.of(new HealthAction("CONFIRM_PLAN_BRIEF", "确认训练偏好", requestId)),
                        HealthNextAction.CONFIRM_PLAN_BRIEF);
                return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                        response, training, meal, traceId, deadlineNanos);
            }
        }

        if (!meal.isConfirmedAndComplete()) {
            com.diet.health.plan.MealPlanBriefService.UpdateResult mealUpdate =
                    mealPlanBriefService.update(meal, userInput);
            meal = mealUpdate.brief();
            if (!meal.isComplete()) {
                String guidance = training.isConfirmedAndComplete()
                        ? mealUpdate.guidance()
                        : "训练简报已确认。请补充餐食计划的目标周和餐次，例如“下周安排早餐、午餐和晚餐”。";
                HealthChatResponse response = HealthChatResponse.clarify(sessionId, traceId,
                        HealthDomain.COMPOSITE, HealthTask.PLAN, riskFlags, guidance,
                        mealUpdate.missingFields()).withPlanBrief(training, List.of(), HealthNextAction.ASK_CLARIFY);
                return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                        response, training, meal, traceId, deadlineNanos);
            }
            if (!meal.isConfirmedAndComplete()) {
                HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId,
                        HealthDomain.COMPOSITE, HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                        withAdvisory("餐食简报已整理：" + mealPlanBriefService.summary(meal) + "。请确认餐食简报。", advisoryCopy),
                        List.of()).withPlanBrief(training,
                        List.of(new HealthAction("CONFIRM_MEAL_PLAN_BRIEF", "确认餐食计划", requestId)),
                        HealthNextAction.CONFIRM_PLAN_BRIEF);
                return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                        response, training, meal, traceId, deadlineNanos);
            }
        }

        if (!hasHealthProfile(userId)) {
            HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.COMPOSITE,
                    HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                    withAdvisory("训练和餐食简报都已确认。请先完善健康档案，再回来生成综合计划。", advisoryCopy), List.of())
                    .withPlanBrief(training, List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
            return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                    response, training, meal, traceId, deadlineNanos);
        }
        HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.COMPOSITE,
                HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                withAdvisory("训练和餐食简报都已确认，可以生成综合计划。", advisoryCopy), List.of())
                .withPlanBrief(training,
                        List.of(new HealthAction("GENERATE_PLAN", "生成综合计划", requestId + "-composite")),
                        HealthNextAction.GENERATE_PLAN);
        return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                response, training, meal, traceId, deadlineNanos);
    }

    private PlanBriefService.UpdateResult updateTrainingBrief(PlanBrief brief, String input, long deadlineNanos) {
        PlanBriefService.UpdateResult update = planBriefService.update(brief, input);
        if (planBriefExtractionAgentService != null && update.agentEligible()) {
            PlanBriefExtractionAgentService.ExtractionResult extraction = planBriefExtractionAgentService.extract(
                    input, brief, brief.expectedField(), remaining(deadlineNanos));
            if (extraction.parsed()) {
                update = planBriefService.applyAgentCandidate(brief, extraction.candidateFields(), extraction.evidence());
            }
        }
        return update;
    }

    private boolean looksLikeTrainingBriefInput(String input) {
        String text = input == null ? "" : input;
        return containsAny(text, "训练", "健身", "练", "胸", "背", "腿", "核心", "徒手", "哑铃", "杠铃",
                "入门", "进阶", "挑战", "周一", "周二", "周三", "周四", "周五", "周六", "周日",
                "一三五", "二四六", "时间", "点", ":") || text.contains("确认训练");
    }

    private boolean isPlanBriefContinuation(HealthSessionState state, HealthDomain domain, String input) {
        if (state == null || state.task() != HealthTask.PLAN || state.domain() != domain) return false;
        String text = input == null ? "" : input;
        return state.phase() == HealthPhase.CLARIFY
                || text.contains("确认") || text.contains("改成") || text.contains("按这个生成");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private HealthChatResponse persistCompositeBrief(HealthSessionState state, HealthIntentResult intent,
                                                     Map<String, List<String>> mergedSlots, String sessionId,
                                                     String traceId, List<String> riskFlags, HealthChatResponse response,
                                                     PlanBrief training, MealPlanBrief meal, String ignoredTraceId,
                                                     long deadlineNanos) {
        return persistAndRespond(state, intent, mergedSlots, response.withMealPlanBrief(meal), traceId,
                deadlineNanos, training, meal);
    }

    private boolean hasHealthProfile(Long userId) {
        if (profileService == null) {
            return true;
        }
        try {
            profileService.getProfile(userId);
            return true;
        } catch (HealthApiException error) {
            if (HealthApiException.CODE_NOT_FOUND.equals(error.code())) {
                return false;
            }
            throw error;
        }
    }

    /** 单品类推荐主链路：澄清 → 检索 → 解释候选。 */
    private HealthChatResponse handleRecommend(String sessionId, String traceId, HealthSessionState state,
                                               HealthIntentResult intent, Map<String, List<String>> mergedSlots,
                                               Map<String, List<String>> activeSlots,
                                               List<String> excludeIds, List<String> riskFlags, String advisoryCopy,
                                               String userInput, boolean clarifyUnsafe, long deadlineNanos) {
        HealthDomain domain = intent.domain();
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("domain", domain, "task", intent.task()));

        List<String> missing = domain == HealthDomain.ROUTINE && routineModule.supportsFactQuery(userInput)
                ? List.of()
                : clarifyRuleService.missingSlots(domain, activeSlots);
        if (clarifyUnsafe && missing.isEmpty()) {
            missing = List.of(domain == HealthDomain.EXERCISE ? "bodyParts" : "mealTime");
        }
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", activeSlots, missing);
        if (!missing.isEmpty()) {
            String question = clarifyAgentService.wording(domain, userInput, missing, activeSlots);
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, domain, intent.task(),
                    riskFlags, question, missing);
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId, deadlineNanos);
        }

        List<HealthResource> candidates = retrieve(domain, activeSlots, excludeIds, userInput).stream()
                .filter(candidate -> expectedResourceType(domain).equals(candidate.resourceType()))
                .toList();
        agentTraceService.recordEvent("CANDIDATES_RETRIEVED", "RETRIEVE",
                Map.of("domain", domain, "providerMode", resourceProvider.providerMode().name(),
                        "resourceVersion", resourceProvider.resourceVersion(), "excludeIds", excludeIds),
                Map.of("candidateCount", candidates.size(), "candidateIds", candidates.stream().map(HealthResource::resourceId).toList()));
        if (candidates.isEmpty()) {
            HealthChatResponse empty = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                    riskFlags, HealthPhase.RESPOND, withAdvisory(emptyCopy(domain), advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, empty, traceId, deadlineNanos);
        }

        List<HealthResource> topN = candidates.stream().limit(TOP_N).toList();
        HealthRecommendResponseService.RecommendOutcome outcome = deterministicOutcome(topN);
        Map<String, Object> responseAgentInput = new LinkedHashMap<>();
        responseAgentInput.put("domain", domain);
        responseAgentInput.put("candidateIds", topN.stream().map(HealthResource::resourceId).toList());
        Map<String, Object> responseAgentOutput = new LinkedHashMap<>();
        responseAgentOutput.put("fallbackReason", outcome.fallbackReason());
        responseAgentOutput.put("referencedIds", outcome.reasons().keySet());
        responseAgentOutput.put("generationSource", "DETERMINISTIC_FAST_PATH");
        // 保留既有事件名供评估读取，generationSource 明确这是确定性快路径而非模型解释。
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
                        outcome.reasons().getOrDefault(candidate.resourceId(), "匹配你选择的偏好条件"),
                        candidate.tags(),
                        candidate.ingredients(),
                        candidate.nutrition()
                ))
                .toList();
        HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                riskFlags, HealthPhase.RESPOND, withAdvisory(outcome.speechText(), advisoryCopy), blocks);
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos);
    }

    private HealthRecommendResponseService.RecommendOutcome deterministicOutcome(List<HealthResource> candidates) {
        StringBuilder text = new StringBuilder("根据你的需求，为你推荐了");
        Map<String, String> reasons = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) text.append("、");
            HealthResource candidate = candidates.get(i);
            text.append(candidate.name());
            reasons.put(candidate.resourceId(), "匹配你选择的偏好条件");
        }
        text.append("。");
        return new HealthRecommendResponseService.RecommendOutcome(text.toString(), reasons, null);
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
                                                 String traceId, long deadlineNanos) {
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                state.planBrief(), state.mealPlanBrief());
    }

    private HealthChatResponse persistAndRespond(HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, HealthChatResponse response,
                                                 String traceId, long deadlineNanos, PlanBrief planBrief) {
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                planBrief, state.mealPlanBrief());
    }

    private HealthChatResponse persistAndRespond(HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, HealthChatResponse response,
                                                 String traceId, long deadlineNanos, PlanBrief planBrief,
                                                 com.diet.health.plan.MealPlanBrief mealPlanBrief) {
        ensureBeforePersistence(deadlineNanos);
        HealthSessionState saved = state
                .withPhase(response.phase())
                .withIntent(intent.domain(), intent.task(), mergeFlags(state.riskFlags(), intent.riskFlags()))
                .withSlots(mergedSlots)
                .withPreferenceSignals(intent.preferenceSignals())
                .withPlanBrief(planBrief)
                .withMealPlanBrief(mealPlanBrief);
        if (response.responseType() == HealthResponseType.ANSWER) {
            saved = saved.replaceLastResources(response.displayBlocks().stream()
                    .map(block -> new SessionResourceRef(block.resourceType(), block.resourceId()))
                    .toList());
        }
        sessionService.save(saved);
        messageService.appendMessage(state.sessionId(), "assistant", response.speechText(), null, traceId);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", saved, response);
        return response;
    }

    private void ensureBeforePersistence(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            agentTraceService.recordEvent("REQUEST_DEADLINE_EXCEEDED", "HTTP",
                    Map.of("requestTimeoutMs", requestTimeoutMs), Map.of("persisted", false));
            throw new HealthApiException(HealthApiException.CODE_TIMEOUT, "请求处理超时，请重试");
        }
    }

    private Duration remaining(long deadlineNanos) {
        long nanos = deadlineNanos - System.nanoTime();
        return nanos <= 0 ? Duration.ofMillis(1) : Duration.ofNanos(nanos);
    }

    private List<String> recentHistory(Long userId, String sessionId) {
        return messageService.recentConversationTurns(sessionId, userId, 2).stream()
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
            case OTHER, COMPOSITE -> "暂时没有找到匹配的内容。";
        };
    }

    private String expectedResourceType(HealthDomain domain) {
        return switch (domain) {
            case MEAL -> "MEAL";
            case EXERCISE -> "EXERCISE";
            case ROUTINE -> "ROUTINE";
            case OTHER, COMPOSITE -> "";
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
