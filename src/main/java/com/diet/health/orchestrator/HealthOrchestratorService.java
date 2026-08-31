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
import com.diet.health.enums.PlanScope;
import com.diet.health.intent.AmbiguityArbitrationAgentService;
import com.diet.health.intent.BriefEscape;
import com.diet.health.intent.BriefRoutingDecision;
import com.diet.health.intent.BriefSide;
import com.diet.health.intent.HealthBriefRouter;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentResult;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.health.intent.RecommendationTopicPolicy;
import com.diet.health.intent.HealthPlanIntentMatcher;
import com.diet.health.intent.HealthSlotLabels;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.model.HealthAction;
import com.diet.health.model.SupplementableItem;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.PlanBriefExtractionAgentService;
import com.diet.health.plan.PlanBriefService;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.MealPlanBriefService;
import com.diet.health.plan.BriefInterpretationStatus;
import com.diet.health.plan.EnabledPlanContextService;
import com.diet.health.plan.WeekAnchorProvider;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.recommend.ConfirmationFingerprints;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.BriefLifecycle;
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
 * <p>
 * 简报补充回路（规格 v3.2）：
 * 计划简报续轮由共享结构化判定 {@link HealthBriefRouter} 统一裁决（风险 &gt; 切域/作息 &gt;
 * 替代推荐 &gt; 普通推荐 &gt; 生命周期 &gt; 侧归属 &gt; 字段解析）；活跃简报中除逃生口外的
 * 自由文本全部进入对应简报处理器；推荐前预检按“候选非空且任务非 ADJUST 且 escape 非
 * ALTERNATIVE 且确认指纹未命中”触发；会话保存改为行锁合并写，保护并发 GENERATED
 * 生命周期、并发简报字段与确认指纹。
 */
@Service
public class HealthOrchestratorService {

    /** 替代推荐候选耗尽时对外返回的稳定领域结果码。 */
    static final String CANDIDATES_EXHAUSTED = "CANDIDATES_EXHAUSTED";

    /** 计划“新建 vs 修改”澄清挂起标记（ADR-0018 状态策略）。 */
    private static final String PENDING_MEAL_NEW_VS_MODIFY = "MEAL_NEW_VS_MODIFY";
    private static final String PENDING_MEAL_MODIFY_OR_NEW = "MEAL_MODIFY_OR_NEW";
    private static final String PENDING_EXERCISE_MODIFY_OR_NEW = "EXERCISE_MODIFY_OR_NEW";

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
    private final EnabledPlanContextService enabledPlanContextService;
    private final boolean intentFastPathEnabled;
    private final boolean recommendationPreflightEnabled;

    /** 简报续轮共享结构化判定（三处调用点复用同一实现；Spring 注入行为一致的实例）。 */
    private final HealthBriefRouter briefRouter;

    /** 歧义任务单次受约束仲裁（ADR-0018）；直接构造的旧契约测试为 null 时保持既有意图链。 */
    private final AmbiguityArbitrationAgentService arbitrationService;

    /** CHAT 通道回答服务（2026-08-31 规格：身份/能力/健康常识，不检索资源）；旧契约测试可为 null。 */
    private final com.diet.health.chat.HealthChatAnswerService chatAnswerService;

    /** 当前轮任务硬证据词表的唯一消费者入口（HealthTaskEvidence 单一所有者）。 */
    private final com.diet.health.intent.HealthTaskEvidence taskEvidence = new com.diet.health.intent.HealthTaskEvidence();

    /** CHAT 通道确定性能力文案（模型不可用/服务未注入时的降级回答）。 */
    private static final String CHAT_CAPABILITY_COPY =
            "我是你的健康助手，可以帮你推荐餐食和健身动作、制定餐食、训练或综合的一周计划，也可以提供作息建议和一般健康常识。"
                    + "你可以直接说，例如“推荐一份清淡的晚餐”或“帮我安排这周的健身计划”，也可以先和我聊聊健康相关的话题。";

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
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper, null, null, null, true, null, null, null);
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
                null, null, intentFastPathEnabled, null, null, null);
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
            PlanBriefExtractionAgentService planBriefExtractionAgentService,
            EnabledPlanContextService enabledPlanContextService,
            HealthBriefRouter briefRouter,
            AmbiguityArbitrationAgentService arbitrationService,
            com.diet.health.chat.HealthChatAnswerService chatAnswerService
    ) {
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper,
                profileService, planBriefExtractionAgentService, enabledPlanContextService, true, briefRouter,
                arbitrationService, chatAnswerService);
    }

    /** 兼容旧签名入口：无 CHAT 回答服务时 CHAT 通道使用确定性能力文案。 */
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
            PlanBriefExtractionAgentService planBriefExtractionAgentService,
            EnabledPlanContextService enabledPlanContextService,
            HealthBriefRouter briefRouter,
            AmbiguityArbitrationAgentService arbitrationService
    ) {
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper,
                profileService, planBriefExtractionAgentService, enabledPlanContextService, true, briefRouter,
                arbitrationService, null);
    }

    /** 旧契约测试兼容入口：等价路由器实例，行为与生产一致；仲裁未启用时走既有意图链。 */
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
            PlanBriefExtractionAgentService planBriefExtractionAgentService,
            EnabledPlanContextService enabledPlanContextService
    ) {
        this(sessionService, messageService, intentAgentService, intentRevisionService, inputNormalizer,
                clarifyRuleService, clarifyAgentService, riskRuleService, mealModule, exerciseModule,
                routineModule, resourceProvider, recommendResponseService, agentTraceService, objectMapper,
                profileService, planBriefExtractionAgentService, enabledPlanContextService, true,
                new HealthBriefRouter(), null, null);
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
            EnabledPlanContextService enabledPlanContextService,
            boolean intentFastPathEnabled,
            HealthBriefRouter briefRouter,
            AmbiguityArbitrationAgentService arbitrationService,
            com.diet.health.chat.HealthChatAnswerService chatAnswerService
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
        this.enabledPlanContextService = enabledPlanContextService;
        this.intentFastPathEnabled = intentFastPathEnabled;
        this.chatAnswerService = chatAnswerService;
        // 直接构造的旧契约测试保持旧行为；Spring 生产入口带有档案服务，开启新的推荐前确认。
        this.recommendationPreflightEnabled = profileService != null;
        this.briefRouter = briefRouter == null ? new HealthBriefRouter() : briefRouter;
        this.arbitrationService = arbitrationService;
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

        // ADR-0018：“修改当前 vs 新建”澄清的后续回答消费（不猜测执行）：
        // “修改”继续当前简报/给出编辑副本入口；“新建”重置简报重新收集；
        // 其余输入（如直接补充条件）清除挂起后按正常流程处理。
        String pendingClarify = state.pendingPlanClarify();
        if (pendingClarify != null) {
            PlanClarifyConsumption consumed = consumePlanClarifyReply(userId, state, pendingClarify, userInput,
                    sessionId, traceId);
            if (consumed != null) {
                HealthIntentResult clarIntent = HealthIntentResult.parsed(
                        consumed.state().domain() == null ? HealthDomain.OTHER : consumed.state().domain(),
                        consumed.state().task() == null ? HealthTask.CHAT : consumed.state().task(),
                        consumed.state().riskFlags(), Map.of(), List.of(), 1.0);
                // 变更检测基准是“轮开始快照”（可能含待重置简报）+ 已清除的挂起标记；
                // 若直接以消费后的状态为基准，重置（如新建空简报）会被 mergeForWrite 当作未变更。
                HealthSessionState base = state.withPendingPlanClarify(null);
                return persistAndRespond(base, clarIntent, Map.of(), consumed.response(),
                        traceId, deadlineNanos, consumed.lifecycleTransitions(), null,
                        consumed.state().planBrief(), consumed.state().mealPlanBrief());
            }
            state = state.withPendingPlanClarify(null);
            agentTraceService.recordEvent("PLAN_CLARIFY_CONSUMED", "PLAN",
                    Map.of("pendingPlanClarify", pendingClarify), Map.of("input", userInput));
        }

        // 共享结构化判定：三处调用点（模型前续轮、模型后修正、本简报门槛）复用同一实现
        BriefRoutingDecision routing = briefRouter.decide(state, userInput);
        agentTraceService.recordEvent("BRIEF_ROUTING_DECIDED", "ROUTE", Map.of("input", userInput,
                        "modificationExpression", briefRouter.hasModificationExpression(userInput)),
                Map.of("briefActive", routing.briefActive(), "activeSide", routing.activeSide().name(),
                        "escape", routing.escape().name(), "reason", routing.reason()));

        // ADR-0018：纯日期/周表达不进入简报收集、不改变计划语义，只返回统一说明。
        // 这是计划上下文的确定性快路径，不调用意图模型。
        if (routing.escape() == BriefEscape.DATE_ONLY_EXPLANATION) {
            HealthDomain domain = state.domain() == null ? HealthDomain.OTHER : state.domain();
            HealthTask task = state.task() == null ? HealthTask.CHAT : state.task();
            HealthIntentResult dateIntent = HealthIntentResult.parsed(domain, task,
                    state.riskFlags(), Map.of(), List.of(), 1.0);
            HealthChatResponse dateAnswer = HealthChatResponse.answer(sessionId, traceId, domain, task,
                    state.riskFlags(), HealthPhase.RESPOND,
                    withAdvisory(WeekAnchorProvider.DATE_ONLY_EXPLANATION_COPY, null), List.of())
                    .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.WAIT_USER)
                    .withMealPlanBrief(state.mealPlanBrief());
            return persistAndRespond(state, dateIntent, Map.of(), dateAnswer, traceId, deadlineNanos,
                    Map.of(), null, state.planBrief(), state.mealPlanBrief());
        }

        // ADR-0018：裸“餐食计划”在已有餐食计划上下文时澄清“修改当前还是新建”，不调用模型猜测。
        if (routing.escape() == BriefEscape.NEW_VS_MODIFY) {
            HealthIntentResult clarifyIntent = HealthIntentResult.parsed(HealthDomain.MEAL, HealthTask.PLAN,
                    state.riskFlags(), Map.of(), List.of(), 1.0);
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.MEAL,
                    HealthTask.PLAN, state.riskFlags(),
                    "已经有一份餐食计划条件。要修改当前餐食计划，还是新建一份？", List.of())
                    .withMealPlanBrief(state.mealPlanBrief())
                    .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.ASK_CLARIFY);
            return persistAndRespond(state.withPendingPlanClarify(PENDING_MEAL_NEW_VS_MODIFY), clarifyIntent,
                    Map.of(), clarify, traceId, deadlineNanos, Map.of(), null,
                    state.planBrief(), state.mealPlanBrief());
        }

        HealthChatRequest.AlternativeRequest alternative = request.alternative();
        // 挂起消费可能产生新的轮开始快照；后续 lambda 使用不可变引用（effectively final）。
        HealthSessionState turnState = state;
        HealthSessionState requestState = turnState;
        HealthIntentAgentService.Recognition recognition;
        // 预检确认短语是结构化动作证据（用户点击“开始推荐”按钮）：确定性直达推荐，
        // 不得落入仲裁链——真实模型对确认短语的低置信会把已确认请求劫持成任务澄清
        // （2026-08-31 浏览器验收实测发现）。
        boolean preflightConfirmation = alternative == null && state.recommendationPreflightPending()
                && taskEvidence.isRecommendationConfirmation(userInput)
                && (state.domain() == HealthDomain.MEAL || state.domain() == HealthDomain.EXERCISE);
        if (preflightConfirmation) {
            recognition = new HealthIntentAgentService.Recognition(
                    HealthIntentResult.parsed(state.domain(), HealthTask.RECOMMEND,
                            state.riskFlags(), Map.of(), List.of(), 1.0),
                    "PREFLIGHT_CONFIRMATION");
        } else if (alternative != null) {
            HealthDomain domain = alternativeDomain(alternative.resourceType());
            List<SessionResourceRef> added = alternative.addedExclusions().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .limit(HealthSessionState.MAX_RESOURCE_HISTORY)
                    .map(id -> new SessionResourceRef(expectedResourceType(domain), id.trim()))
                    .toList();
            requestState = turnState.appendLastResources(added);
            HealthIntentResult actionIntent = HealthIntentResult.parsed(domain, HealthTask.ADJUST,
                    List.of(), Map.of(), List.of(), 1.0);
            recognition = new HealthIntentAgentService.Recognition(actionIntent, "ALTERNATIVE_ACTION");
        } else {
            recognition = intentRevisionService.continueBeforeAgent(userInput, turnState)
                    .map(result -> new HealthIntentAgentService.Recognition(result, "STATE_CONTINUATION"))
                    .orElseGet(() -> recognizeWithArbitration(userInput, turnState, userId, sessionId, deadlineNanos));
        }
        HealthIntentResult rawIntent = recognition.result();
        boolean arbitrationAuthoritative = "ARBITRATION".equals(recognition.source())
                || "ARBITRATION_FAILED".equals(recognition.source());
        HealthIntentRevisionService.Revision revision = alternative == null
                ? intentRevisionService.revise(userInput, turnState, rawIntent, arbitrationAuthoritative)
                : new HealthIntentRevisionService.Revision(rawIntent, false, false);
        HealthIntentResult intent = revision.intent();
        String currentAssignmentContext = currentAssignmentContext(userId, intent.domain());
        if (currentAssignmentContext != null) {
            agentTraceService.recordEvent("CURRENT_ASSIGNMENT_CONTEXT", "CONTEXT",
                    Map.of("domain", intent.domain()), currentAssignmentContext);
        }
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
            return persistAndRespond(state, intent, state.slots(), blocked, traceId, deadlineNanos, Map.of(), null);
        }
        // ADVISORY 等级不阻止单次推荐，但在最终回复中透出固定提示文案
        String advisoryCopy = risk.level() == HealthRiskLevel.ADVISORY ? risk.copy() : null;

        Map<String, List<String>> mergedSlots = mergeSlots(state.slots(), intent.slots());
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        // ADR-0018 歧义仲裁：危险路径复核（风险已在上面按最终意图评估）。
        // 仲裁失败（超时/非法 JSON/低置信）不猜测执行，进入可理解澄清并保留规则已抽取槽位。
        if ("ARBITRATION_FAILED".equals(recognition.source())) {
            agentTraceService.recordEvent("ARBITRATION_FAILED", "ARBITRATION",
                    Map.of("input", userInput, "sessionId", sessionId),
                    Map.of("fallbackReason", rawIntent.fallbackReason() == null ? "" : rawIntent.fallbackReason()));
            HealthDomain hintDomain = briefRouter.domainEvidence(userInput);
            Map<String, List<String>> ruleSlots = inputNormalizer.normalize(
                    hintDomain == null ? HealthDomain.OTHER : hintDomain,
                    userInput, Map.of()).slots();
            // 澄清不改变会话的计划上下文：保留当前领域/任务，防止澄清把简报会话拉出 PLAN 流程。
            HealthIntentResult keepIntent = HealthIntentResult.parsed(
                    state.domain() == null ? HealthDomain.OTHER : state.domain(),
                    state.task() == null ? HealthTask.CHAT : state.task(),
                    state.riskFlags(), Map.of(), List.of(), 1.0);
            // 澄清响应契约（2026-08-31 规格 RC-4/RC-6）：响应不继承展示旧 domain/task 标签
            // （会话上下文仍保留供合法续轮），taskFocus 伪槽位 key 不进渲染通道；
            // 四个任务选项以结构化动作下发，点击发送携带任务证据的短语。
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId,
                    HealthDomain.OTHER, HealthTask.CHAT,
                    state.riskFlags(),
                    "你的这句话有几种理解，为了不猜错，请说得更明确一些：是想新建一周计划、修改已有计划、做一次单次推荐，还是问作息建议？",
                    List.of())
                    .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.ASK_CLARIFY)
                    .withMealPlanBrief(state.mealPlanBrief())
                    .withActions(List.of(
                            new HealthAction("SELECT_TASK", "新建一周计划", "帮我制定一份饮食和训练的综合计划"),
                            new HealthAction("SELECT_TASK", "修改已有计划", "修改当前计划"),
                            new HealthAction("SELECT_TASK", "做一次单次推荐", "帮我推荐一下"),
                            new HealthAction("SELECT_TASK", "问作息建议", "给我一些作息建议")));
            return persistAndRespond(state, keepIntent, mergeSlots(state.slots(), ruleSlots), clarify,
                    traceId, deadlineNanos, Map.of(), null);
        }
        // 会话状态复核：仲裁不得覆盖确定性的计划上下文——仲裁判定为修改（REVISE_PLAN）
        // 但既无简报又无已保存计划时澄清；仲裁判定新建（NEW_PLAN）时直接进入新建流程。
        if ("ARBITRATION_REVISE".equals(recognition.source()) && intent.task() == HealthTask.PLAN
                && !hasAnyPlanContext(userId, state, intent.domain())) {
            agentTraceService.recordEvent("ARBITRATION_CONFLICTED", "ARBITRATION",
                    Map.of("input", userInput), Map.of("task", intent.task(), "domain", intent.domain()));
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId,
                    intent.domain(), HealthTask.PLAN, state.riskFlags(),
                    "当前没有正在进行或已保存的" + domainPlanLabel(intent.domain())
                            + "计划。你是想新建一份计划，还是做点别的？",
                    List.of("planAction"))
                    .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.ASK_CLARIFY)
                    .withMealPlanBrief(state.mealPlanBrief());
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId, deadlineNanos, Map.of(), null);
        }
        if ("ARBITRATION".equals(recognition.source())) {
            agentTraceService.recordEvent("ARBITRATION_AGENT_RESULT", "ARBITRATION",
                    Map.of("input", userInput), Map.of("task", intent.task(), "domain", intent.domain(),
                            "confidence", intent.confidence(), "resolutionSource", recognition.source()));
        }

        // 同域换主题（演示召回规格 P1）：澄清短答继续合并；显式清除/只看餐次/新推荐带餐次
        // 替换或清除历史推荐条件，避免“清淡晚餐”→“中午吃什么”被旧偏好悄悄卡住。
        if (RecommendationTopicPolicy.applies(state.domain(), intent.domain(), intent.task())) {
            RecommendationTopicPolicy.Decision topic = RecommendationTopicPolicy.decide(
                    state, intent.slots(), mergedSlots, userInput);
            mergedSlots = topic.slots();
            if (topic.reason() != RecommendationTopicPolicy.Reason.CLARIFY_INHERIT
                    && topic.reason() != RecommendationTopicPolicy.Reason.PLAIN_MERGE) {
                agentTraceService.recordEvent("SLOT_TOPIC_APPLIED", "SLOT",
                        Map.of("reason", topic.reason().name(), "input", userInput), mergedSlots);
            }
        }

        // 健康档案页回到聊天后的确认短句不应重新走通用 CHAT/旧计划入口，直接恢复原计划简报。
        if (isProfileCompletionInput(userInput) && state.task() == HealthTask.PLAN && state.domain() != null) {
            HealthIntentResult planIntent = HealthIntentResult.parsed(state.domain(), HealthTask.PLAN,
                    state.riskFlags(), Map.of(), List.of(), 1.0);
            if (state.domain() == HealthDomain.EXERCISE) {
                return handlePlanBrief(userId, state, planIntent, mergedSlots, sessionId, traceId, userInput,
                        risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
            }
            if (state.domain() == HealthDomain.MEAL) {
                return handleMealPlanBrief(userId, state, planIntent, mergedSlots, sessionId, traceId, userInput,
                        risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
            }
            if (state.domain() == HealthDomain.COMPOSITE) {
                return handleCompositePlanBrief(userId, state, planIntent, routing, mergedSlots, sessionId, traceId,
                        userInput, risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
            }
        }

        HealthChatResponse appendResponse = appendToCurrentPlanResponse(userId, intent, sessionId, traceId,
                risk.matchedFlags(), advisoryCopy, userInput);
        if (appendResponse != null) {
            return persistAndRespond(state, intent, mergedSlots, appendResponse, traceId, deadlineNanos, Map.of(), null);
        }

        // “开始推荐”等确认语义复用同一推荐通道；只在当前会话已有待确认摘要时生效。
        if (alternative == null && state.recommendationPreflightPending()
                && isRecommendationConfirmation(userInput)
                && state.domain() != null
                && (state.domain() == HealthDomain.MEAL || state.domain() == HealthDomain.EXERCISE)) {
            intent = HealthIntentResult.parsed(state.domain(), HealthTask.RECOMMEND, state.riskFlags(), Map.of(),
                    List.of(), 1.0);
            requestState = state.withRecommendationState(false, true,
                    state.recommendationConfirmationVersion() + 1);
            mergedSlots = state.slots();
        }

        if (revision.clarifyDomain()) {
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.OTHER,
                    HealthTask.CHAT, risk.matchedFlags(), "你想看餐食推荐、健身动作，还是查询作息建议？", List.of("domain"));
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId, deadlineNanos, Map.of(), null);
        }

        // 社交短句：未关闭会话只返回确认并保留简报；已生成态返回已生成确认，不重新捕获简报。
        if (briefRouter.isSocialPhrase(userInput)) {
            if (routing.briefActive() && routing.escape() == BriefEscape.NONE) {
                return persistAndRespond(state, intent, mergedSlots,
                        socialAckResponse(sessionId, traceId, state, risk.matchedFlags(), advisoryCopy),
                        traceId, deadlineNanos, Map.of(), null);
            }
            if (hasGeneratedScope(state)) {
                return persistAndRespond(state, intent, mergedSlots,
                        HealthChatResponse.answer(sessionId, traceId, state.domain() == null ? HealthDomain.OTHER
                                        : state.domain(), HealthTask.CHAT, risk.matchedFlags(), HealthPhase.RESPOND,
                                withAdvisory("不客气！计划已生成完成。如需调整可以说“再调整餐食计划”，或随时问我其他问题。", advisoryCopy), List.of()),
                        traceId, deadlineNanos, Map.of(), null);
            }
        }

        if (intent.domain() == HealthDomain.OTHER || intent.task() == HealthTask.CHAT) {
            HealthChatResponse chat = HealthChatResponse.answer(sessionId, traceId, HealthDomain.OTHER,
                    HealthTask.CHAT, risk.matchedFlags(), HealthPhase.RESPOND,
                    withAdvisory(chatBoundaryCopy(userInput), advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, chat, traceId, deadlineNanos, Map.of(), null);
        }

        // 活跃简报门槛：无逃生口命中的自由文本一律进入对应简报处理器（含 GENERATED/PAUSED 的显式计划词重开）。
        // COMPOSITE 会话始终进入综合处理器（侧归属只决定写入目标，不改变领域）。
        if (routing.briefActive() && routing.escape() == BriefEscape.NONE) {
            boolean compositeSession = state.domain() == HealthDomain.COMPOSITE;
            switch (routing.activeSide()) {
                case MEAL -> {
                    if (compositeSession) {
                        return handleCompositePlanBrief(userId, state, intent, routing, mergedSlots, sessionId,
                                traceId, userInput, risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
                    }
                    return handleMealPlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                            risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
                }
                case EXERCISE -> {
                    if (compositeSession) {
                        return handleCompositePlanBrief(userId, state, intent, routing, mergedSlots, sessionId,
                                traceId, userInput, risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
                    }
                    return handlePlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                            risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
                }
                case BOTH -> {
                    // 两侧都完整时无前缀不猜测归属，要求“餐食：/训练：”前缀（BOTH 侧回归例）。
                    return sideClarification(userId, state, intent, mergedSlots, sessionId, traceId,
                            risk.matchedFlags(), advisoryCopy, deadlineNanos);
                }
                default -> {
                    // NONE：无活跃侧，继续既有意图链
                }
            }
        }

        // 显式计划词入口：仅在没有活跃简报捕获时到达（新计划请求）。
        boolean exercisePlanEntry = intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.EXERCISE
                && (HealthPlanIntentMatcher.matchesExercise(userInput)
                // ADR-0018：孤立修改表达（无活动简报）也按明确任务词进入 PLAN 创建侧
                || (briefRouter.hasModificationExpression(userInput)
                && briefRouter.domainEvidence(userInput) == HealthDomain.EXERCISE));
        if (exercisePlanEntry) {
            HealthChatResponse savedPlanClarify = modifyOrNewSavedPlanClarify(userId, state, PlanScope.EXERCISE,
                    intent, sessionId, traceId, risk.matchedFlags(), advisoryCopy);
            if (savedPlanClarify != null) {
                return persistAndRespond(state.withPendingPlanClarify(PENDING_EXERCISE_MODIFY_OR_NEW), intent,
                        mergedSlots, savedPlanClarify, traceId, deadlineNanos, Map.of(), null,
                        state.planBrief(), state.mealPlanBrief());
            }
            return handlePlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                    risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        boolean mealPlanEntry = intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.MEAL
                && HealthPlanIntentMatcher.matchesMeal(userInput);
        if (mealPlanEntry) {
            HealthChatResponse savedPlanClarify = modifyOrNewSavedPlanClarify(userId, state, PlanScope.MEAL,
                    intent, sessionId, traceId, risk.matchedFlags(), advisoryCopy);
            if (savedPlanClarify != null) {
                return persistAndRespond(state.withPendingPlanClarify(PENDING_MEAL_MODIFY_OR_NEW), intent,
                        mergedSlots, savedPlanClarify, traceId, deadlineNanos, Map.of(), null,
                        state.planBrief(), state.mealPlanBrief());
            }
            return handleMealPlanBrief(userId, state, intent, mergedSlots, sessionId, traceId, userInput,
                    risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        if (intent.task() == HealthTask.PLAN && intent.domain() == HealthDomain.COMPOSITE
                && HealthPlanIntentMatcher.matchesComposite(userInput)) {
            return handleCompositePlanBrief(userId, state, intent, routing, mergedSlots, sessionId, traceId,
                    userInput, risk.matchedFlags(), advisoryCopy, request.requestId(), deadlineNanos);
        }

        if (intent.domain() == HealthDomain.COMPOSITE || intent.task() == HealthTask.PLAN || intent.task() == HealthTask.BROWSE) {
            String copy = switch (intent.domain()) {
                case COMPOSITE -> "我可以分别帮你安排饮食、训练和作息，也可以先从其中一个方面开始，你想先看哪个？";
                default -> intent.task() == HealthTask.PLAN
                        ? "可以说“帮我安排这周的餐食计划”或“帮我制定一份训练和餐食的综合计划”，我会先和你确认条件，再开始生成。"
                        : "餐食和动作可以在对应页面浏览，也可以直接告诉我你的需求，我来帮你筛选。";
            };
            HealthChatResponse notice = HealthChatResponse.answer(sessionId, traceId, intent.domain(), intent.task(),
                    risk.matchedFlags(), HealthPhase.RESPOND, withAdvisory(copy, advisoryCopy), List.of());
            return persistAndRespond(state, intent, mergedSlots, notice, traceId, deadlineNanos, Map.of(), null);
        }

        // 最终任务闸门（2026-08-31 规格）：在所有确定性路由与模型/仲裁修正之后、任何资源检索
        // 或任务执行之前，当前轮必须存在“可执行证据”。没有证据的 RECOMMEND/ADJUST/ROUTINE
        // 一律降级为 CHAT（封堵模型/仲裁单独启动任务，RC-5 与“能陪我聊天吗”直接复现路径）。
        boolean gateAllowed = hasExecutableTaskEvidence(userInput, state, intent, routing, alternative,
                recognition.source());
        agentTraceService.recordEvent("FINAL_TASK_GATE", "ROUTE",
                Map.of("input", userInput,
                        "domain", intent.domain() == null ? "" : intent.domain().name(),
                        "task", intent.task() == null ? "" : intent.task().name(),
                        "resolutionSource", recognition.source()),
                Map.of("allowed", gateAllowed,
                        "reason", gateAllowed ? "EVIDENCE_PRESENT" : "NO_EXECUTABLE_TASK_EVIDENCE"));
        if (!gateAllowed) {
            // 降级不删除会话上下文（合法续轮仍可恢复），但本轮绝不检索资源。
            HealthIntentResult keepIntent = HealthIntentResult.parsed(
                    state.domain() == null ? HealthDomain.OTHER : state.domain(),
                    state.task() == null ? HealthTask.CHAT : state.task(),
                    state.riskFlags(), Map.of(), List.of(), 1.0);
            HealthChatResponse denied = HealthChatResponse.answer(sessionId, traceId, HealthDomain.OTHER,
                    HealthTask.CHAT, risk.matchedFlags(), HealthPhase.RESPOND,
                    withAdvisory(chatBoundaryCopy(userInput), advisoryCopy), List.of());
            return persistAndRespond(state, keepIntent, mergedSlots, denied, traceId, deadlineNanos, Map.of(), null);
        }

        // ADJUST 排除（43 号票 + #69 类型化契约）：只取 MEAL/EXERCISE 类型化字符串 resourceId，
        // 作息事实不参与排除；fixture 种子 ID 原样传递，reviewed 数值解析在餐食模块查询前完成
        String resourceType = intent.domain() == HealthDomain.EXERCISE ? "EXERCISE" : "MEAL";
        boolean sameTask = state.domain() == intent.domain() && state.task() == intent.task();
        List<String> excludeIds = intent.task() == HealthTask.ADJUST || sameTask
                ? requestState.excludeIdsFor(resourceType) : List.of();
        excludeIds = mergeExcludedIds(excludeIds, currentPlanExclusions(userId, intent.domain(), userInput));
        if (alternative != null && alternative.allowRepeat()) {
            excludeIds = alternative.addedExclusions().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(HealthSessionState.MAX_RESOURCE_HISTORY)
                    .toList();
        }
        boolean switchedDomain = state.domain() != null && state.domain() != intent.domain();
        Map<String, List<String>> activeSlots = inputNormalizer.project(intent.domain(),
                switchedDomain ? intent.slots() : mergedSlots);
        if (alternative != null && !alternative.addedSlots().isEmpty()) {
            activeSlots = appendSlots(intent.domain(), activeSlots, alternative.addedSlots());
        }
        if (alternative != null && alternative.relaxConstraints()) {
            activeSlots = relaxedSlots(intent.domain(), activeSlots);
        }
        Map<String, List<String>> recommendationSlots = mergeSlots(mergedSlots, activeSlots);
        agentTraceService.recordEvent("DOMAIN_SLOTS_PROJECTED", "SLOT",
                Map.of("domain", intent.domain(), "switchedDomain", switchedDomain), activeSlots);
        // 显式切出（作息提问/跨域推荐）时把仍处于 OPEN 的其他侧简报置为 PAUSED（暂停不等于关闭）
        Map<String, String> pauseTransitions = pauseTransitions(state, intent.domain());
        return handleRecommend(sessionId, traceId, requestState, intent, recommendationSlots, activeSlots, excludeIds,
                risk.matchedFlags(), advisoryCopy, currentAssignmentContext, userInput,
                revision.clarifyUnsafe(), alternative, routing, pauseTransitions, deadlineNanos);
    }

    /** 社交短句确认：保留简报与生命周期（OPEN），只返回一行确认。 */
    private HealthChatResponse socialAckResponse(String sessionId, String traceId, HealthSessionState state,
                                                 List<String> riskFlags, String advisoryCopy) {
        HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId,
                state.domain() == null ? HealthDomain.OTHER : state.domain(),
                state.task() == null ? HealthTask.CHAT : state.task(),
                riskFlags, HealthPhase.RESPOND,
                withAdvisory("好的，当前条件已保留。可以继续补充，或直接开始生成。", advisoryCopy), List.of());
        return response.withPlanBrief(state.planBrief(), List.of(), HealthNextAction.WAIT_USER)
                .withMealPlanBrief(state.mealPlanBrief());
    }

    /** 是否存在任一侧已 GENERATED（生成后“谢谢”走已生成确认）。 */
    private boolean hasGeneratedScope(HealthSessionState state) {
        return BriefLifecycle.GENERATED.name().equals(state.briefLifecycle().get("MEAL"))
                || BriefLifecycle.GENERATED.name().equals(state.briefLifecycle().get("EXERCISE"));
    }

    /**
     * 存在已保存（启用）计划但无活动简报时的“修改当前计划 vs 新建简报”澄清；
     * 无启用计划返回 null（进入正常的新计划简报收集，不猜测执行）。
     */
    private HealthChatResponse modifyOrNewSavedPlanClarify(Long userId, HealthSessionState state, PlanScope scope,
                                                           HealthIntentResult intent, String sessionId,
                                                           String traceId, List<String> riskFlags,
                                                           String advisoryCopy) {
        if (enabledPlanContextService == null) {
            return null;
        }
        Long planId;
        try {
            planId = enabledPlanContextService.enabledPlanId(userId, scope);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (planId == null) {
            return null;
        }
        String sideLabel = scope == PlanScope.MEAL ? "餐食" : "训练";
        HealthDomain domain = scope == PlanScope.MEAL ? HealthDomain.MEAL : HealthDomain.EXERCISE;
        String actionId = "APPEND:" + planId + ":" + scope.name();
        // 结构化澄清选项（2026-08-31 规格）：修改=打开计划页编辑副本（不覆盖启用计划），
        // 新建=点击发送“新建”，由挂起消费确定性进入新计划收集（RC-1 原子 OPEN）。
        return HealthChatResponse.clarify(sessionId, traceId, domain, HealthTask.PLAN, riskFlags,
                withAdvisory("当前已有一份启用的" + sideLabel + "计划。要修改当前计划，还是新建一份？", advisoryCopy),
                List.of())
                .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.ASK_CLARIFY)
                .withMealPlanBrief(state.mealPlanBrief())
                .withActions(List.of(new HealthAction("MODIFY_CURRENT_PLAN", "修改当前计划", actionId),
                        new HealthAction("NEW_PLAN_BRIEF", "新建一份", "新建")));
    }

    /** “修改当前 vs 新建”澄清的后续回答处理；null 表示不是明确的修改/新建回答（继续正常流程）。 */
    private PlanClarifyConsumption consumePlanClarifyReply(Long userId, HealthSessionState state, String pending,
                                                           String userInput, String sessionId, String traceId) {
        String compact = userInput == null ? "" : userInput.replaceAll("\\s+", "");
        boolean modify = containsAny(compact, "修改", "调整现有", "改现有", "继续修改", "接着改");
        boolean redo = containsAny(compact, "新建", "重新建", "重来", "另起", "换一份新");
        if (PENDING_MEAL_NEW_VS_MODIFY.equals(pending)) {
            MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
            if (modify && !redo) {
                List<SupplementableItem> supplementable = mealPlanBriefService.supplementable(meal);
                String supplementableCopy = supplementable.isEmpty() ? "" : "还可以补充："
                        + String.join("、", supplementable.stream().map(SupplementableItem::label).toList()) + "。";
                HealthChatResponse response;
                if (meal.isComplete()) {
                    response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                            state.riskFlags(), HealthPhase.RESPOND,
                            withAdvisory("好的，继续当前餐食计划。可以直接说想修改的条件。"
                                    + supplementableCopy, null), List.of())
                            .withMealPlanBrief(meal)
                            .withPlanBrief(state.planBrief(), List.of(
                                    new HealthAction("GENERATE_PLAN", "开始生成", traceId + "-meal"),
                                    new HealthAction("CONTINUE_MEAL_PLAN_BRIEF", "补充", traceId)),
                                    HealthNextAction.GENERATE_PLAN);
                } else {
                    response = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                            state.riskFlags(), withAdvisory(mealPlanBriefService.update(meal, "").guidance(), null),
                            List.of("mealTimes"))
                            .withMealPlanBrief(meal);
                }
                return new PlanClarifyConsumption(state.withPendingPlanClarify(null), response, Map.of());
            }
            if (redo && !modify) {
                // 澄清消费是显式生命周期转移点（2026-08-31 规格 RC-1）：选择“新建”的同一轮
                // 原子写入 侧=OPEN + 空简报 + (MEAL, PLAN)，残留的 GENERATED/PAUSED 不得让
                // 下一轮落回通用意图链。
                return new PlanClarifyConsumption(
                        state.withPendingPlanClarify(null).withMealPlanBrief(MealPlanBrief.empty()),
                        newMealBriefStartResponse(sessionId, traceId, state),
                        Map.of("MEAL", BriefLifecycle.OPEN.name()));
            }
            return null;
        }
        boolean mealSide = PENDING_MEAL_MODIFY_OR_NEW.equals(pending);
        if (mealSide || PENDING_EXERCISE_MODIFY_OR_NEW.equals(pending)) {
            PlanScope scope = mealSide ? PlanScope.MEAL : PlanScope.EXERCISE;
            if (modify && !redo) {
                Long planId = enabledPlanContextService == null ? null
                        : enabledPlanContextService.enabledPlanId(userId, scope);
                if (planId != null) {
                    String sideLabel = mealSide ? "餐食" : "训练";
                    String actionId = "APPEND:" + planId + ":" + scope.name();
                    HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId,
                            mealSide ? HealthDomain.MEAL : HealthDomain.EXERCISE, HealthTask.PLAN,
                            state.riskFlags(), HealthPhase.RESPOND,
                            withAdvisory("好的，我会先为当前已启用的" + sideLabel
                                    + "计划创建编辑副本，保留原计划不变；进入计划页后调整并保存。", null), List.of())
                            .withActions(List.of(new HealthAction("APPEND_TO_CURRENT_PLAN",
                                    "追加到当前计划", actionId)))
                            .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.WAIT_USER)
                            .withMealPlanBrief(state.mealPlanBrief());
                    return new PlanClarifyConsumption(state.withPendingPlanClarify(null), response, Map.of());
                }
                // 已无启用计划：退回新建简报流程
            }
            if (redo || (modify && enabledPlanContextService == null)) {
                if (mealSide) {
                    return new PlanClarifyConsumption(
                            state.withPendingPlanClarify(null).withMealPlanBrief(MealPlanBrief.empty()),
                            newMealBriefStartResponse(sessionId, traceId, state),
                            Map.of("MEAL", BriefLifecycle.OPEN.name()));
                }
                // “接下来开始新建一份训练计划”+ 点名第一个缺失必要条件（RC-1 原子 OPEN 转移同轮生效）。
                List<String> missing = planBriefService.missing(PlanBrief.empty());
                HealthChatResponse response = HealthChatResponse.clarify(sessionId, traceId,
                        HealthDomain.EXERCISE, HealthTask.PLAN, state.riskFlags(),
                        withAdvisory("好的，接下来开始新建一份训练计划。"
                                + planBriefService.question(missing), null),
                        missing.isEmpty() ? List.of() : List.of(missing.get(0)))
                        .withPlanBrief(PlanBrief.empty(), List.of(), HealthNextAction.ASK_CLARIFY);
                return new PlanClarifyConsumption(
                        state.withPendingPlanClarify(null).withPlanBrief(PlanBrief.empty()), response,
                        Map.of("EXERCISE", BriefLifecycle.OPEN.name()));
            }
            return null;
        }
        return null;
    }

    /** 新建餐食计划的开场引导：点名第一个缺失必要条件，不使用“简报/当前字段”内部术语。 */
    private HealthChatResponse newMealBriefStartResponse(String sessionId, String traceId, HealthSessionState state) {
        MealPlanBrief empty = MealPlanBrief.empty();
        String guidance = mealPlanBriefService.update(empty, "").guidance();
        List<String> missing = mealPlanBriefService.missing(empty);
        return HealthChatResponse.clarify(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                state.riskFlags(), withAdvisory("好的，接下来开始新建一份餐食计划。" + guidance, null),
                missing.isEmpty() ? List.of() : List.of(missing.get(0)))
                .withMealPlanBrief(empty);
    }

    /**
     * 澄清消费结果：state 为消费后的会话状态，lifecycleTransitions 为本轮显式生命周期转移
     * （“新建”消费同轮原子写对应侧 OPEN，2026-08-31 规格 RC-1）。
     */
    private record PlanClarifyConsumption(HealthSessionState state, HealthChatResponse response,
                                          Map<String, String> lifecycleTransitions) {
    }

    /** 综合简报两侧都完整时的侧归属澄清：要求“餐食：/训练：”前缀，不得猜测写入某一侧。 */
    private HealthChatResponse sideClarification(Long userId, HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, String sessionId,
                                                 String traceId, List<String> riskFlags, String advisoryCopy,
                                                 long deadlineNanos) {
        HealthChatResponse response = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.COMPOSITE,
                HealthTask.PLAN, riskFlags,
                withAdvisory("餐食和训练条件都已整理完成。请说明要修改餐食还是训练，例如“餐食：清淡”或“训练：改练背”。",
                        advisoryCopy), List.of("side"))
                .withPlanBrief(state.planBrief(), List.of(), HealthNextAction.ASK_CLARIFY)
                .withMealPlanBrief(state.mealPlanBrief());
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos, Map.of(), null);
    }

    /**
     * 显式切出（作息提问/跨域推荐）时，把仍处于 OPEN 的简报侧置为 PAUSED；
     * 同领域推荐与 OTHER/CHAT 不暂停（用户可能马上回来继续补充）。
     */
    private Map<String, String> pauseTransitions(HealthSessionState state, HealthDomain finalDomain) {
        Map<String, String> transitions = new LinkedHashMap<>();
        if (finalDomain == null || finalDomain == HealthDomain.OTHER || finalDomain == HealthDomain.COMPOSITE) {
            return Map.of();
        }
        for (BriefSide side : List.of(BriefSide.MEAL, BriefSide.EXERCISE)) {
            if (briefRouter.lifecycleOf(state, side) != BriefLifecycle.OPEN) {
                continue;
            }
            boolean sameDomain = side == BriefSide.MEAL
                    ? finalDomain == HealthDomain.MEAL
                    : finalDomain == HealthDomain.EXERCISE;
            if (!sameDomain) {
                transitions.put(side.name(), BriefLifecycle.PAUSED.name());
            }
        }
        return Map.copyOf(transitions);
    }

    /** 将聊天中的“追加到当前计划”转成显式编辑副本操作，不直接静默改写 ENABLED 计划。 */
    private HealthChatResponse appendToCurrentPlanResponse(Long userId, HealthIntentResult intent,
                                                            String sessionId, String traceId,
                                                            List<String> riskFlags, String advisoryCopy,
                                                            String userInput) {
        if (!isAppendToCurrentPlan(userInput) || enabledPlanContextService == null) return null;
        PlanScope scope = intent.domain() == HealthDomain.EXERCISE ? PlanScope.EXERCISE
                : intent.domain() == HealthDomain.MEAL ? PlanScope.MEAL : null;
        if (scope == null) return null;
        Long planId = enabledPlanContextService.enabledPlanId(userId, scope);
        if (planId == null) {
            return HealthChatResponse.answer(sessionId, traceId, intent.domain(), HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("当前没有可追加的已启用" + (scope == PlanScope.MEAL ? "餐食" : "训练")
                            + "计划，请先生成并启用一份计划。", advisoryCopy), List.of());
        }
        String actionId = "APPEND:" + planId + ":" + scope.name();
        return HealthChatResponse.answer(sessionId, traceId, intent.domain(), HealthTask.PLAN,
                riskFlags, HealthPhase.RESPOND,
                withAdvisory("我会先为当前已启用计划创建编辑副本，保留原计划不变；进入计划页后选择要追加的"
                        + (scope == PlanScope.MEAL ? "餐食" : "动作") + "并保存。", advisoryCopy), List.of())
                .withPlanBrief(PlanBrief.empty(), List.of(new HealthAction("APPEND_TO_CURRENT_PLAN",
                        "追加到当前计划", actionId)), HealthNextAction.WAIT_USER);
    }

    private boolean isAppendToCurrentPlan(String input) {
        // 共享词表唯一所有者（HealthTaskEvidence），编排器不持有第二份追加词清单。
        return taskEvidence.isAppendToCurrentPlanExpression(input);
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
                Map.of("brief", brief, "missingFields", update.missingFields(),
                        "status", update.status(), "evidence", update.evidence()));
        // 进入简报处理器 → 对应侧 OPEN；同时把其他仍 OPEN 的侧置为 PAUSED（显式切换其他领域）
        Map<String, String> lifecycle = new LinkedHashMap<>();
        lifecycle.put("EXERCISE", BriefLifecycle.OPEN.name());
        if (briefRouter.lifecycleOf(state, BriefSide.MEAL) == BriefLifecycle.OPEN) {
            lifecycle.put("MEAL", BriefLifecycle.PAUSED.name());
        }
        List<SupplementableItem> supplementable = trainingSupplementable(brief);
        String supplementableCopy = supplementable.isEmpty() ? "" : "还可以补充："
                + String.join("、", supplementable.stream().map(SupplementableItem::label).toList()) + "。";
        HealthChatResponse response;
        if (!brief.isComplete()) {
            String guidance = (update.status() == BriefInterpretationStatus.INVALID
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
                    withAdvisory("我已保留这份训练偏好。健康档案还缺少生成计划必需的年龄、身高、体重、活动水平和主要目标，请补齐后回到当前会话开始生成。", advisoryCopy), List.of())
                    .withPlanBrief(brief, List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
        } else {
            // 简报完成分支统一输出“已整理 + 可补充项 + 开始/补充”，服务端在生成时重读当前简报并校验。
            String note = update.guidance() == null || update.guidance().isBlank()
                    ? "" : update.guidance();
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.EXERCISE, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("训练偏好已整理：" + planBriefService.summary(brief) + "。"
                            + note + supplementableCopy + "可以直接开始生成，或回复想补充的条件。", advisoryCopy), List.of())
                    .withPlanBrief(brief, List.of(
                                    new HealthAction("GENERATE_PLAN", "开始生成", requestId + "-plan"),
                                    new HealthAction("CONTINUE_PLAN_BRIEF", "补充", requestId)),
                            HealthNextAction.GENERATE_PLAN);
        }
        return persistAndRespond(state, intent, mergedSlots,
                response.withSupplementable(supplementable), traceId, deadlineNanos, lifecycle, null,
                brief, state.mealPlanBrief());
    }

    /** 餐食简报闭环：不读取或改写训练简报，生成动作只指向 MEAL 范围。 */
    private HealthChatResponse handleMealPlanBrief(Long userId, HealthSessionState state, HealthIntentResult intent,
                                                   Map<String, List<String>> mergedSlots, String sessionId,
                                                   String traceId, String userInput, List<String> riskFlags,
                                                   String advisoryCopy, String requestId, long deadlineNanos) {
        MealPlanBriefService.UpdateResult update =
                mealPlanBriefService.update(state.mealPlanBrief(), userInput);
        MealPlanBrief brief = update.brief();
        // 进入简报处理器 → 对应侧 OPEN；同时把其他仍 OPEN 的侧置为 PAUSED（显式切换其他领域）
        Map<String, String> lifecycle = new LinkedHashMap<>();
        lifecycle.put("MEAL", BriefLifecycle.OPEN.name());
        if (briefRouter.lifecycleOf(state, BriefSide.EXERCISE) == BriefLifecycle.OPEN) {
            lifecycle.put("EXERCISE", BriefLifecycle.PAUSED.name());
        }
        List<SupplementableItem> supplementable = mealPlanBriefService.supplementable(brief);
        String supplementableCopy = supplementable.isEmpty() ? "" : "还可以补充："
                + String.join("、", supplementable.stream().map(SupplementableItem::label).toList()) + "。";
        // ADR-0018：未支持偏好统一说明（“已记录，暂不按它筛选”+ 受支持菜系继续提示）
        String unsupportedNote = mealPlanBriefService.unsupportedNote(brief);
        HealthChatResponse response;
        if (!brief.isComplete()) {
            String guidance = update.guidance() == null || update.guidance().isBlank()
                    ? mealPlanBriefService.update(brief, "").guidance() : update.guidance();
            response = HealthChatResponse.clarify(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, withAdvisory(guidance + unsupportedNote, advisoryCopy), update.missingFields())
                    .withMealPlanBrief(brief);
        } else if (!hasHealthProfile(userId)) {
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("我已保留这份餐食偏好。健康档案还缺少生成计划必需的信息，请补齐后回到当前会话开始生成。" + unsupportedNote, advisoryCopy),
                    List.of())
                    .withMealPlanBrief(brief)
                    .withPlanBrief(state.planBrief(), List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
        } else {
            // 简报完成分支统一输出“已整理 + 可补充项 + 开始/补充”；生成时服务端重读当前简报并按所选餐次生成。
            // ADR-0018：note 透传本轮 update 指引（如纯日期表达的“不需要指定日期”说明）。
            String note = update.guidance() == null || update.guidance().isBlank() ? "" : update.guidance();
            response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.MEAL, HealthTask.PLAN,
                    riskFlags, HealthPhase.RESPOND,
                    withAdvisory("餐食偏好已整理：" + mealPlanBriefService.summary(brief)
                            + "。" + note + unsupportedNote + supplementableCopy + "可以直接开始生成，或回复想补充的条件。", advisoryCopy),
                    List.of())
                    .withMealPlanBrief(brief)
                    .withPlanBrief(state.planBrief(), List.of(
                                    new HealthAction("GENERATE_PLAN", "开始生成", requestId + "-meal"),
                                    new HealthAction("CONTINUE_MEAL_PLAN_BRIEF", "补充", requestId)),
                            HealthNextAction.GENERATE_PLAN);
        }
        return persistAndRespond(state, intent, mergedSlots,
                response.withSupplementable(supplementable), traceId, deadlineNanos, lifecycle, null,
                state.planBrief(), brief);
    }

    /** 综合简报闭环：侧归属由共享判定的 activeSide 决定，两侧收集完成才允许一次性生成 COMPOSITE。 */
    private HealthChatResponse handleCompositePlanBrief(Long userId, HealthSessionState state,
                                                        HealthIntentResult intent, BriefRoutingDecision routing,
                                                        Map<String, List<String>> mergedSlots, String sessionId,
                                                        String traceId, String userInput, List<String> riskFlags,
                                                        String advisoryCopy, String requestId, long deadlineNanos) {
        PlanBrief training = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
        MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
        BriefSide side = routing.escape() == BriefEscape.NONE
                ? routing.activeSide()
                : briefRouter.compositeActiveSide(state, userInput);
        Map<String, String> lifecycle = new LinkedHashMap<>();
        lifecycle.put("MEAL", BriefLifecycle.OPEN.name());
        lifecycle.put("EXERCISE", BriefLifecycle.OPEN.name());
        PlanBriefService.UpdateResult trainingUpdate = null;
        MealPlanBriefService.UpdateResult mealUpdate = null;
        if (side == BriefSide.EXERCISE) {
            trainingUpdate = updateTrainingBrief(training, userInput, deadlineNanos);
            if (isUsableBriefUpdate(trainingUpdate.status())) {
                training = trainingUpdate.brief();
            }
        } else if (side == BriefSide.MEAL) {
            mealUpdate = mealPlanBriefService.update(meal, userInput);
            if (isUsableBriefUpdate(mealUpdate.status())) {
                meal = mealUpdate.brief();
            }
        }

        // 综合计划允许共享健康目标在两侧继承，但两侧简报的字段保持独立、互不覆盖。
        if ((training.trainingGoal() == null || training.trainingGoal().isBlank())
                && meal.healthGoal() != null && !meal.healthGoal().isBlank()) {
            PlanBriefService.UpdateResult inherited = updateTrainingBrief(training, meal.healthGoal(), deadlineNanos);
            if (isUsableBriefUpdate(inherited.status())) training = inherited.brief();
        }
        if ((meal.healthGoal() == null || meal.healthGoal().isBlank())
                && training.trainingGoal() != null && !training.trainingGoal().isBlank()) {
            MealPlanBriefService.UpdateResult inherited = mealPlanBriefService.update(meal, training.trainingGoal());
            if (isUsableBriefUpdate(inherited.status())) meal = inherited.brief();
        }

        List<SupplementableItem> supplementable = new java.util.ArrayList<>();
        supplementable.addAll(mealPlanBriefService.supplementable(meal));
        supplementable.addAll(trainingSupplementable(training));

        // 默认先收集餐食，再收集训练；子简报完整即视为该侧就绪，不存在独立确认阶段。
        if (!meal.isComplete()) {
            String guidance = mealUpdate != null && mealUpdate.guidance() != null && !mealUpdate.guidance().isBlank()
                    ? mealUpdate.guidance() : mealGuidance(meal);
            List<String> missing = mealUpdate == null ? mealPlanBriefService.missing(meal) : mealUpdate.missingFields();
            HealthChatResponse response = HealthChatResponse.clarify(sessionId, traceId,
                    HealthDomain.COMPOSITE, HealthTask.PLAN, riskFlags, guidance, missing)
                    .withPlanBrief(training, List.of(), HealthNextAction.ASK_CLARIFY);
            return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                    response.withSupplementable(supplementable), training, meal, lifecycle, deadlineNanos);
        }

        if (!training.isComplete()) {
            String trainingGuidance = trainingUpdate != null && trainingUpdate.guidance() != null && !trainingUpdate.guidance().isBlank()
                    ? trainingUpdate.guidance() : planBriefService.question(planBriefService.missing(training));
            String guidance = "餐食需求已整理：" + mealPlanBriefService.summary(meal) + "。接下来请补充训练条件。"
                    + trainingGuidance;
            List<String> missing = trainingUpdate == null ? planBriefService.missing(training) : trainingUpdate.missingFields();
            HealthChatResponse response = HealthChatResponse.clarify(sessionId, traceId,
                    HealthDomain.COMPOSITE, HealthTask.PLAN, riskFlags, guidance, missing)
                    .withPlanBrief(training, List.of(), HealthNextAction.ASK_CLARIFY);
            return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                    response.withSupplementable(supplementable), training, meal, lifecycle, deadlineNanos);
        }

        if (!hasHealthProfile(userId)) {
            HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.COMPOSITE,
                    HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                    withAdvisory("餐食和训练需求都已整理完成。请先完善健康档案，再回来生成综合计划。", advisoryCopy), List.of())
                    .withPlanBrief(training, List.of(new HealthAction("COMPLETE_PROFILE", "完善健康档案", null)),
                            HealthNextAction.COMPLETE_PROFILE);
            return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                    response.withSupplementable(supplementable), training, meal, lifecycle, deadlineNanos);
        }
        String supplementableCopy = supplementable.isEmpty() ? "" : "还可以补充："
                + String.join("、", supplementable.stream().map(SupplementableItem::label).toList()) + "。";
        HealthChatResponse response = HealthChatResponse.answer(sessionId, traceId, HealthDomain.COMPOSITE,
                HealthTask.PLAN, riskFlags, HealthPhase.RESPOND,
                withAdvisory("餐食需求：" + mealPlanBriefService.summary(meal) + "。训练需求：" + planBriefService.summary(training)
                        + "。" + supplementableCopy + "可以直接开始生成综合计划，或回复想补充的条件。", advisoryCopy), List.of())
                .withPlanBrief(training,
                        List.of(new HealthAction("GENERATE_PLAN", "开始生成", requestId + "-composite"),
                                new HealthAction("CONTINUE_PLAN_BRIEF", "补充", requestId)),
                        HealthNextAction.GENERATE_PLAN);
        return persistCompositeBrief(state, intent, mergedSlots, sessionId, traceId, riskFlags,
                response.withSupplementable(supplementable), training, meal, lifecycle, deadlineNanos);
    }

    /** 训练简报可补充项：只列未填字段（契约 {key, label, examples, filled}；不含内部周锚点）。 */
    private List<SupplementableItem> trainingSupplementable(PlanBrief brief) {
        PlanBrief value = brief == null ? PlanBrief.empty() : brief;
        List<SupplementableItem> items = new java.util.ArrayList<>();
        if (value.trainingGoal() == null || value.trainingGoal().isBlank()) {
            items.add(new SupplementableItem("trainingGoal", "训练目标", List.of("增肌", "减脂"), false));
        }
        if (value.bodyParts().isEmpty()) {
            items.add(new SupplementableItem("bodyParts", "训练部位", List.of("胸", "背"), false));
        }
        if (value.equipment().isEmpty()) {
            items.add(new SupplementableItem("equipment", "器械", List.of("徒手", "哑铃"), false));
        }
        if (value.difficulty() == null || value.difficulty().isBlank()) {
            items.add(new SupplementableItem("difficulty", "难度", List.of("入门", "进阶"), false));
        }
        if (value.trainingDays().isEmpty()) {
            items.add(new SupplementableItem("trainingDays", "训练日", List.of("周一到周三", "一三五"), false));
        }
        if (value.timeWindow() == null) {
            items.add(new SupplementableItem("timeWindow", "训练时段", List.of("19:00-20:00"), false));
        }
        return List.copyOf(items);
    }

    private boolean isUsableBriefUpdate(BriefInterpretationStatus status) {
        return status == BriefInterpretationStatus.EXTRACTED || status == BriefInterpretationStatus.PARTIAL;
    }

    private String mealGuidance(MealPlanBrief brief) {
        return mealPlanBriefService.update(brief, "").guidance();
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

    private boolean isProfileCompletionInput(String input) {
        return input != null && containsAny(input.replaceAll("\\s+", ""),
                "我已完成档案信息补充", "档案信息已补充", "健康档案已完成", "我已经补齐档案");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private HealthChatResponse persistCompositeBrief(HealthSessionState state, HealthIntentResult intent,
                                                     Map<String, List<String>> mergedSlots, String sessionId,
                                                     String traceId, List<String> riskFlags, HealthChatResponse response,
                                                     PlanBrief training, MealPlanBrief meal,
                                                     Map<String, String> lifecycle, long deadlineNanos) {
        return persistAndRespond(state, intent, mergedSlots, response.withMealPlanBrief(meal), traceId,
                deadlineNanos, lifecycle, null, training, meal);
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
                                               String currentAssignmentContext,
                                               String userInput, boolean clarifyUnsafe,
                                               HealthChatRequest.AlternativeRequest alternative,
                                               BriefRoutingDecision routing,
                                               Map<String, String> pauseTransitions, long deadlineNanos) {
        HealthDomain domain = intent.domain();
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("domain", domain, "task", intent.task()));

        boolean requestOnlyConstraint = isRequestOnlyConstraint(domain, userInput);
        List<String> missing = domain == HealthDomain.ROUTINE && routineModule.supportsFactQuery(userInput)
                ? List.of()
                : recommendationPreflightEnabled
                        ? clarifyRuleService.minimumRecommendationSlots(domain, activeSlots)
                        : clarifyRuleService.missingSlots(domain, activeSlots);
        if (requestOnlyConstraint && activeSlots.isEmpty()) {
            missing = List.of();
        }
        if (clarifyUnsafe && missing.isEmpty()) {
            missing = List.of(domain == HealthDomain.EXERCISE ? "bodyParts" : "mealTime");
        }
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", activeSlots, missing);
        if (!missing.isEmpty()) {
            String question = clarifyAgentService.wording(domain, userInput, missing, activeSlots);
            HealthChatResponse clarify = HealthChatResponse.clarify(sessionId, traceId, domain, intent.task(),
                    riskFlags, question, missing);
            return persistAndRespond(state, intent, mergedSlots, clarify, traceId, deadlineNanos, pauseTransitions, null);
        }

        List<HealthResource> candidates = applyRequestExclusions(domain, userInput,
                        retrieve(domain, activeSlots, excludeIds, userInput)).stream()
                .filter(candidate -> expectedResourceType(domain).equals(candidate.resourceType()))
                .toList();
        agentTraceService.recordEvent("CANDIDATES_RETRIEVED", "RETRIEVE",
                Map.of("domain", domain, "providerMode", resourceProvider.providerMode().name(),
                        "resourceVersion", resourceProvider.resourceVersion(), "excludeIds", excludeIds),
                Map.of("candidateCount", candidates.size(), "candidateIds", candidates.stream().map(HealthResource::resourceId).toList()));

        // 推荐前预检（设计驱动，无候选数门槛）：候选非空 且 任务非 ADJUST 且 escape 非 ALTERNATIVE
        // 且当前条件的确认指纹未命中。作息事实问答不属于单次推荐，直接返回事实卡。
        String confirmationKey = ConfirmationFingerprints.recommendation(domain, intent.task(),
                mergedSlots, resourceProvider.resourceVersion());
        boolean confirmationHit = state.recommendationConfirmed()
                && state.recommendationConfirmationKey() != null
                && state.recommendationConfirmationKey().equals(confirmationKey);
        boolean preflight = recommendationPreflightEnabled
                && domain != HealthDomain.ROUTINE
                && !candidates.isEmpty()
                && intent.task() != HealthTask.ADJUST
                && (routing == null || routing.escape() != BriefEscape.ALTERNATIVE)
                && alternative == null
                && !confirmationHit;
        if (preflight) {
            List<String> confirmed = slotSummary(activeSlots);
            List<String> optional = clarifyRuleService.optionalRecommendationSlots(domain, activeSlots);
            String summary = confirmed.isEmpty() ? "我已理解你的基本需求" : "我已确认：" + String.join("；", confirmed);
            HealthChatResponse preflightResponse = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                    riskFlags, HealthPhase.RESPOND,
                    withContext(summary + "。还可以补充：" + (optional.isEmpty() ? "无" : String.join("、", optional.stream().map(HealthSlotLabels::label).toList()))
                            + "。要现在为你推荐吗？", advisoryCopy, currentAssignmentContext), List.of())
                    .withActions(List.of(new HealthAction("CONFIRM_RECOMMENDATION", "开始推荐", traceId),
                            new HealthAction("CONTINUE_RECOMMENDATION", "补充", traceId)))
                    .withRecommendationPreflight(confirmed, optional, false);
            return persistAndRespond(state, intent, mergedSlots, preflightResponse, traceId, deadlineNanos,
                    pauseTransitions, confirmationKey);
        }

        if (candidates.isEmpty()) {
            boolean exhausted = intent.task() == HealthTask.ADJUST;
            List<HealthAction> appendActions = additionActions(domain, activeSlots, excludeIds, userInput, traceId);
            String copy = appendActions.isEmpty()
                    ? (exhausted ? "当前条件下没有尚未展示的候选。你可以明确选择放宽条件，或重复已展示结果。" : emptyCopy(domain))
                    : "当前条件下没有严格匹配结果。你可以在原条件上追加以下可用选项：";
            List<HealthAction> actions = new java.util.ArrayList<>(appendActions);
            if (exhausted) {
                actions.add(new HealthAction("RELAX_CONSTRAINTS", "放宽条件", traceId));
                actions.add(new HealthAction("REPEAT_SHOWN", "重复已展示结果", traceId));
            }
            HealthChatResponse empty = HealthChatResponse.answer(sessionId, traceId, domain, intent.task(),
                    riskFlags, HealthPhase.RESPOND, withContext(copy, advisoryCopy, currentAssignmentContext), List.of()).withActions(actions);
            if (exhausted) {
                empty = empty.withResultCode(CANDIDATES_EXHAUSTED);
            }
            return persistAndRespond(state, intent, mergedSlots, empty, traceId, deadlineNanos, pauseTransitions, confirmationKey);
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
                riskFlags, HealthPhase.RESPOND, withContext(outcome.speechText(), advisoryCopy, currentAssignmentContext), blocks)
                .withActions(List.of(new HealthAction("GET_ALTERNATIVE", "换一批", traceId)))
                .withRecommendationPreflight(slotSummary(activeSlots),
                        clarifyRuleService.optionalRecommendationSlots(domain, activeSlots), true);
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                pauseTransitions, confirmationKey);
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

    private String withContext(String speechText, String advisoryCopy, String currentAssignmentContext) {
        String result = withAdvisory(speechText, advisoryCopy);
        return currentAssignmentContext == null || currentAssignmentContext.isBlank()
                ? result : result + " " + currentAssignmentContext;
    }

    private String currentAssignmentContext(Long userId, HealthDomain domain) {
        if (enabledPlanContextService == null || userId == null || domain == null) {
            return null;
        }
        try {
            return switch (domain) {
                case MEAL -> enabledPlanContextService.contextForToday(userId, PlanScope.MEAL);
                case EXERCISE -> enabledPlanContextService.contextForToday(userId, PlanScope.EXERCISE);
                case COMPOSITE -> joinContexts(
                        enabledPlanContextService.contextForToday(userId, PlanScope.MEAL),
                        enabledPlanContextService.contextForToday(userId, PlanScope.EXERCISE));
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String joinContexts(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + " " + second;
    }

    private List<String> currentPlanExclusions(Long userId, HealthDomain domain, String userInput) {
        if (enabledPlanContextService == null || userId == null || userInput == null
                || !containsAny(userInput, "当前计划", "当前安排", "计划里的这个", "安排里的这个")) {
            return List.of();
        }
        try {
            PlanScope scope = domain == HealthDomain.MEAL ? PlanScope.MEAL
                    : domain == HealthDomain.EXERCISE ? PlanScope.EXERCISE : null;
            return scope == null ? List.of() : enabledPlanContextService.resourceIdsForToday(userId, scope);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<String> mergeExcludedIds(List<String> first, List<String> second) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (first != null) merged.addAll(first);
        if (second != null) merged.addAll(second);
        return List.copyOf(merged);
    }

    private List<String> slotSummary(Map<String, List<String>> slots) {
        if (slots == null || slots.isEmpty()) return List.of();
        return slots.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .flatMap(entry -> entry.getValue().stream().map(value -> HealthSlotLabels.label(entry.getKey()) + "：" + value))
                .toList();
    }

    /**
     * 最终闸门的可执行证据白名单（2026-08-31 规格）：
     * ① 共享硬证据词表命中（该领域明确任务词/计划词/调整请求词）——确定性证据单独即可执行，
     *    保留 ADR-0018 确定性直路由与无模型降级能力；
     * ② 活跃简报解析器续轮（PLAN 字段收集）；
     * ③ 结构化动作证据（alternative payload / 预检确认短语 / 计划澄清消费轮）；
     * ④ 作息事实问答（结构化事实检索）；
     * ⑤ 经边界复核的澄清续轮继承（本轮解析出字段值或含任务证据）。
     * 模型/仲裁单独判定（无任何确定性证据）不得启动任务——这正是 authoritative 信任接缝的封堵点。
     */
    private boolean hasExecutableTaskEvidence(String userInput, HealthSessionState state, HealthIntentResult intent,
                                              BriefRoutingDecision routing,
                                              HealthChatRequest.AlternativeRequest alternative,
                                              String resolutionSource) {
        String text = userInput == null ? "" : userInput;
        HealthDomain domain = intent.domain();
        if (domain != null && taskEvidence.hasTaskEvidence(text, domain)) {
            return true;
        }
        if (HealthPlanIntentMatcher.matches(text) || HealthPlanIntentMatcher.matchesComposite(text)) {
            return true;
        }
        if (taskEvidence.hasAdjustRequestEvidence(text)) {
            return true;
        }
        if ((intent.task() == HealthTask.RECOMMEND || intent.task() == HealthTask.ADJUST)
                && taskEvidence.hasRecommendRequestEvidence(text)) {
            return true;
        }
        if (routing != null && routing.briefActive() && routing.escape() == BriefEscape.NONE) {
            return true;
        }
        if (alternative != null) {
            return true;
        }
        if (state.recommendationPreflightPending() && taskEvidence.isRecommendationConfirmation(text)) {
            return true;
        }
        if (state.pendingPlanClarify() != null) {
            return true;
        }
        if (domain == HealthDomain.ROUTINE && routineModule.supportsFactQuery(text)) {
            return true;
        }
        return state.phase() == HealthPhase.CLARIFY && isRecommendDomain(domain)
                && (state.clarifyEpoch() == null
                    || state.clarifyEpoch().equals(java.time.LocalDate.now().toString()))
                && (!intent.slots().isEmpty() || taskEvidence.hasTaskEvidence(text, domain));
    }

    private boolean isRecommendDomain(HealthDomain domain) {
        return domain == HealthDomain.MEAL || domain == HealthDomain.EXERCISE || domain == HealthDomain.ROUTINE;
    }

    /** CHAT 通道回答：优先专用回答服务（模型生成，受提示词边界约束），降级为确定性能力文案。 */
    private String chatBoundaryCopy(String userInput) {
        if (chatAnswerService != null) {
            String answer = chatAnswerService.answer(userInput);
            if (answer != null && !answer.isBlank()) {
                return answer;
            }
        }
        if (taskEvidence.isChatEscapeExpression(userInput)
                || briefRouter.domainEvidence(userInput) == null) {
            return CHAT_CAPABILITY_COPY;
        }
        return "这个问题不在当前健康助手的能力范围内。你可以问我饮食、训练、作息相关的问题，"
                + "或让我开始一次推荐、制定一周计划。";
    }

    private boolean isRecommendationConfirmation(String input) {
        // 共享词表唯一所有者（HealthTaskEvidence），编排器不持有第二份短语清单。
        return taskEvidence.isRecommendationConfirmation(input);
    }

    /** 只处理明确的请求级否定，不写入会话槽位或长期偏好。 */
    private List<HealthResource> applyRequestExclusions(HealthDomain domain, String userInput,
                                                        List<HealthResource> candidates) {
        if (candidates == null || candidates.isEmpty() || userInput == null) {
            return candidates == null ? List.of() : candidates;
        }
        String text = userInput.replaceAll("\\s+", "");
        if (domain == HealthDomain.MEAL && containsAny(text, "不想吃沙拉", "不要沙拉", "不吃沙拉", "避免沙拉")) {
            return candidates.stream().filter(resource -> !containsResourceTerm(resource, "沙拉")).toList();
        }
        if (domain == HealthDomain.EXERCISE && containsAny(text, "不练腿", "不练腿部", "不要练腿", "避免腿部")) {
            return candidates.stream().filter(resource -> !containsResourceTag(resource, "腿")).toList();
        }
        return candidates;
    }

    private boolean containsResourceTerm(HealthResource resource, String term) {
        return (resource.name() != null && resource.name().contains(term))
                || resource.tags().values().stream().flatMap(List::stream).anyMatch(value -> value.contains(term));
    }

    private boolean containsResourceTag(HealthResource resource, String term) {
        return resource.tags().values().stream().flatMap(List::stream).anyMatch(value -> value.contains(term));
    }

    private boolean isRequestOnlyConstraint(HealthDomain domain, String userInput) {
        if (userInput == null) return false;
        String text = userInput.replaceAll("\\s+", "");
        return (domain == HealthDomain.MEAL && containsAny(text, "不想吃沙拉", "不要沙拉", "不吃沙拉", "避免沙拉"))
                || (domain == HealthDomain.EXERCISE && containsAny(text, "不练腿", "不练腿部", "不要练腿", "避免腿部"))
                || containsAny(text, "当前计划里的这个", "当前安排里的这个", "不想吃当前计划", "不想练当前计划");
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

    /** 统一收尾：行锁合并保存会话状态 → 落库助手消息 → Trace → 返回。 */
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
                                                 MealPlanBrief mealPlanBrief) {
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                Map.of(), null, planBrief, mealPlanBrief);
    }

    private HealthChatResponse persistAndRespond(HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, HealthChatResponse response,
                                                 String traceId, long deadlineNanos, Map<String, String> lifecycleTransitions,
                                                 String confirmationKey) {
        return persistAndRespond(state, intent, mergedSlots, response, traceId, deadlineNanos,
                lifecycleTransitions, confirmationKey, state.planBrief(), state.mealPlanBrief());
    }

    private HealthChatResponse persistAndRespond(HealthSessionState state, HealthIntentResult intent,
                                                 Map<String, List<String>> mergedSlots, HealthChatResponse response,
                                                 String traceId, long deadlineNanos, Map<String, String> lifecycleTransitions,
                                                 String confirmationKey, PlanBrief planBrief, MealPlanBrief mealPlanBrief) {
        ensureBeforePersistence(deadlineNanos);
        HealthSessionState saved = state
                .withPhase(response.phase())
                .withIntent(intent.domain(), intent.task(), mergeFlags(state.riskFlags(), intent.riskFlags()))
                .withSlots(mergedSlots)
                .withPreferenceSignals(intent.preferenceSignals())
                .withPlanBrief(planBrief)
                .withMealPlanBrief(mealPlanBrief)
                .withBriefLifecycle(applyLifecycle(state.briefLifecycle(), lifecycleTransitions))
                // 澄清时效边界（RC-4）：澄清轮记录当天日期，跨天后澄清续轮不再被继承。
                .withClarifyEpoch(response.phase() == HealthPhase.CLARIFY
                        ? java.time.LocalDate.now().toString() : null);
        if (confirmationKey != null) {
            saved = saved.withRecommendationConfirmationKey(confirmationKey);
        }
        if (response.nextAction() == HealthNextAction.CONFIRM_RECOMMENDATION) {
            saved = saved.withRecommendationState(true, false,
                    Math.max(1, state.recommendationConfirmationVersion()));
        } else if (response.recommendationConfirmed()) {
            saved = saved.withRecommendationState(false, true,
                    Math.max(1, state.recommendationConfirmationVersion()));
        } else if (state.recommendationConfirmed() && intent.slots() != null && !intent.slots().isEmpty()) {
            // 用户修改已确认槽位后，旧确认只对旧条件有效。
            saved = saved.withRecommendationState(false, false, state.recommendationConfirmationVersion());
        }
        if (response.responseType() == HealthResponseType.ANSWER && !response.displayBlocks().isEmpty()) {
            List<SessionResourceRef> refs = response.displayBlocks().stream()
                    .map(block -> new SessionResourceRef(block.resourceType(), block.resourceId()))
                    .toList();
            boolean sameTask = state.domain() == intent.domain() && state.task() == intent.task();
            saved = (sameTask || intent.task() == HealthTask.ADJUST)
                    ? saved.appendLastResources(refs) : saved.replaceLastResources(refs);
        }
        // 行锁合并保存：旧聊天快照不能覆盖并发写入的 GENERATED 生命周期、新简报字段与确认指纹。
        sessionService.saveMerged(state, saved);
        messageService.appendMessage(state.sessionId(), "assistant", response.speechText(), null, traceId);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", saved, response);
        return response;
    }

    /** 在轮开始快照的生命周期上应用本轮显式转移（OPEN/PAUSED）。 */
    private Map<String, String> applyLifecycle(Map<String, String> current, Map<String, String> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return current;
        }
        Map<String, String> merged = new LinkedHashMap<>(current);
        merged.putAll(transitions);
        return Map.copyOf(merged);
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

    /**
     * 识别（ADR-0018 两层模型）：规则快路径优先（无模型调用）；规则无法唯一裁决时
     * 调用一次受约束仲裁；仲裁失败返回 {@code ARBITRATION_FAILED} 标记交由编排器澄清。
     */
    private HealthIntentAgentService.Recognition recognizeWithArbitration(String userInput,
                                                                          HealthSessionState state,
                                                                          Long userId, String sessionId,
                                                                          long deadlineNanos) {
        if (taskEvidence.isChatEscapeExpression(userInput)) {
            // P0 哨兵（2026-08-31 规格）：显式聊天/能力问句确定性进入 OTHER + CHAT，
            // 不消耗模型预算、不受会话历史/仲裁影响。
            return new HealthIntentAgentService.Recognition(
                    HealthIntentResult.parsed(HealthDomain.OTHER, HealthTask.CHAT, List.of(), Map.of(), List.of(), 1.0),
                    "CHAT_ESCAPE");
        }
        if (arbitrationService == null) {
            return intentFastPathEnabled
                    ? intentAgentService.recognizeWithDiagnostics(userInput, state.slots(),
                    recentHistory(userId, sessionId), remaining(deadlineNanos))
                    : new HealthIntentAgentService.Recognition(
                    intentAgentService.recognize(userInput, state.slots(),
                            recentHistory(userId, sessionId)), "AGENT");
        }
        HealthIntentResult fast = intentAgentService.fastPathIfPresent(userInput, state.slots());
        if (fast != null) {
            return new HealthIntentAgentService.Recognition(fast, "FAST_PATH");
        }
        java.util.Optional<AmbiguityArbitrationAgentService.ArbitrationResult> decision =
                arbitrationService.arbitrate(userInput,
                        AmbiguityArbitrationAgentService.sessionContextOf(state), remaining(deadlineNanos));
        if (decision.isEmpty()) {
            // 不猜测执行：返回失败标记，编排器进入可理解澄清并保留规则槽位
            return new HealthIntentAgentService.Recognition(
                    HealthIntentResult.parsed(HealthDomain.OTHER, HealthTask.CHAT,
                            List.of(), Map.of(), List.of(), 0.1),
                    "ARBITRATION_FAILED");
        }
        return new HealthIntentAgentService.Recognition(
                AmbiguityArbitrationAgentService.toIntentResult(decision.get(), userInput, inputNormalizer),
                "REVISE_PLAN".equals(decision.get().task()) ? "ARBITRATION_REVISE" : "ARBITRATION");
    }

    /** 是否存在任一计划简报内容或已开启的生命周期（仲裁复核使用）。 */
    private boolean hasAnyPlanContext(Long userId, HealthSessionState state, HealthDomain domain) {
        PlanBrief training = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
        MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
        boolean briefContent = (domain == HealthDomain.MEAL || domain == HealthDomain.COMPOSITE)
                && (!meal.mealTimes().isEmpty() || !isBlank(meal.healthGoal())
                || !meal.cuisines().isEmpty() || !meal.foodTypes().isEmpty()
                || !meal.tastePreferences().isEmpty() || !isBlank(meal.convenience())
                || !meal.unsupportedPreferences().isEmpty())
                || (domain == HealthDomain.EXERCISE || domain == HealthDomain.COMPOSITE)
                && (!isBlank(training.trainingGoal()) || !training.bodyParts().isEmpty()
                || !training.equipment().isEmpty() || !isBlank(training.difficulty())
                || !training.trainingDays().isEmpty() || training.timeWindow() != null);
        if (briefContent) {
            return true;
        }
        boolean lifecycleActive = state.briefLifecycle() != null && state.briefLifecycle().values().stream()
                .anyMatch(value -> !com.diet.health.session.BriefLifecycle.PAUSED.name().equals(value));
        if (lifecycleActive) {
            return true;
        }
        if (enabledPlanContextService == null || userId == null || domain == null) {
            return false;
        }
        try {
            return switch (domain) {
                case MEAL -> enabledPlanContextService.enabledPlanId(userId, PlanScope.MEAL) != null;
                case EXERCISE -> enabledPlanContextService.enabledPlanId(userId, PlanScope.EXERCISE) != null;
                default -> false;
            };
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String domainPlanLabel(HealthDomain domain) {
        return switch (domain) {
            case MEAL -> "餐食";
            case EXERCISE -> "训练";
            case COMPOSITE -> "综合";
            default -> "周";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private HealthDomain alternativeDomain(String resourceType) {
        if ("MEAL".equalsIgnoreCase(resourceType)) {
            return HealthDomain.MEAL;
        }
        if ("EXERCISE".equalsIgnoreCase(resourceType)) {
            return HealthDomain.EXERCISE;
        }
        throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "替代推荐资源类型必须为 MEAL 或 EXERCISE");
    }

    /** 显式放宽只移除次要筛选，核心餐次/训练部位仍然保留。 */
    private Map<String, List<String>> relaxedSlots(HealthDomain domain, Map<String, List<String>> slots) {
        if (slots == null || slots.isEmpty()) {
            return Map.of();
        }
        String keep = domain == HealthDomain.MEAL ? "mealTime" : "bodyParts";
        Map<String, List<String>> relaxed = new LinkedHashMap<>();
        if (slots.containsKey(keep)) {
            relaxed.put(keep, slots.get(keep));
        }
        return relaxed;
    }

    /** 在现有槽位上追加可选值，原值永不被替换。 */
    private Map<String, List<String>> appendSlots(HealthDomain domain, Map<String, List<String>> slots,
                                                   Map<String, List<String>> additions) {
        Map<String, List<String>> merged = new LinkedHashMap<>(slots == null ? Map.of() : slots);
        additions.forEach((slot, values) -> {
            if (slot == null || !inputNormalizer.slotsFor(domain).contains(slot) || values == null) return;
            java.util.LinkedHashSet<String> combined = new java.util.LinkedHashSet<>(merged.getOrDefault(slot, List.of()));
            values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(combined::add);
            if (!combined.isEmpty()) merged.put(slot, List.copyOf(combined));
        });
        return merged;
    }

    /** 只返回经目录验证后确实能产生候选的追加值，最多三个。 */
    private List<HealthAction> additionActions(HealthDomain domain, Map<String, List<String>> slots,
                                                List<String> excludeIds, String userInput, String traceId) {
        Map<String, List<String>> options = new LinkedHashMap<>();
        if (domain == HealthDomain.EXERCISE) {
            options.put("equipment", List.of("徒手", "哑铃", "杠铃", "弹力带", "壶铃", "器械"));
            options.put("difficulty", List.of("入门", "进阶", "挑战"));
            options.put("trainingGoal", List.of("增肌", "减脂", "耐力", "力量", "柔韧", "保持健康"));
        } else if (domain == HealthDomain.MEAL) {
            options.putAll(mealModule.availableSlotValues());
        }
        List<HealthAction> result = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> option : options.entrySet()) {
            for (String value : option.getValue()) {
                if (slots.getOrDefault(option.getKey(), List.of()).contains(value)) continue;
                Map<String, List<String>> candidateSlots = appendSlots(domain, slots,
                        Map.of(option.getKey(), List.of(value)));
                if (!retrieve(domain, candidateSlots, excludeIds, userInput).isEmpty()) {
                    result.add(new HealthAction("APPEND_SLOT", "追加" + HealthSlotLabels.label(option.getKey()) + "：" + value,
                            traceId, option.getKey(), value));
                }
                if (result.size() >= 3) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
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
