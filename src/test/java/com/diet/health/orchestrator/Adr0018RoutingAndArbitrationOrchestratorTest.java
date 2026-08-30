package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.intent.AmbiguityArbitrationAgentService;
import com.diet.health.intent.HealthBriefRouter;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.health.intent.HealthSlotDictionary;
import com.diet.health.intent.IntentRuleService;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.SessionMapper;
import com.diet.model.RequestTraceRow;
import com.diet.model.SessionRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0018 端到端行为（票据 02/03/05）：
 * 计划上下文优先路由（问题 4）、日期表达不改变简报语义、裸计划词新建/修改澄清、
 * 歧义任务单次受约束仲裁与失败澄清。测试直接构造生产等价实例（含注入的试用路由器与仲裁服务）。
 */
class Adr0018RoutingAndArbitrationOrchestratorTest {

    private HealthSessionService sessionService;
    private SessionService messageService;
    private AgentTraceService traceService;
    private ObjectMapper objectMapper;
    private MealModule mealModule;
    private HealthOrchestratorService orchestrator;
    private AgentTraceMapper traceMapper;
    private SessionMapper sessionMapper;
    private AmbiguityArbitrationAgentService arbitrationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sessionMapper = mock(SessionMapper.class);
        java.util.Map<String, SessionRow> sessionRows = new java.util.concurrent.ConcurrentHashMap<>();
        when(sessionMapper.findByIdForUpdate(anyString(), any())).thenAnswer(invocation ->
                sessionRows.get(invocation.getArgument(0)));
        when(sessionMapper.findById(anyString(), any())).thenAnswer(invocation ->
                sessionRows.get(invocation.getArgument(0)));
        when(sessionMapper.insert(any(SessionRow.class))).thenAnswer(invocation -> {
            SessionRow row = invocation.getArgument(0);
            sessionRows.put(row.getId(), row);
            return 1;
        });
        when(sessionMapper.update(any(SessionRow.class))).thenAnswer(invocation -> {
            SessionRow row = invocation.getArgument(0);
            sessionRows.put(row.getId(), row);
            return 1;
        });

        List<RequestTraceRow> insertedTraces = new java.util.ArrayList<>();
        traceMapper = mock(AgentTraceMapper.class);
        when(traceMapper.insert(any(RequestTraceRow.class))).thenAnswer(invocation -> {
            insertedTraces.add(invocation.getArgument(0));
            return 1;
        });
        when(traceMapper.findByRequestId(any(), any(), any())).thenAnswer(invocation ->
                insertedTraces.stream()
                        .filter(row -> row.getRequestId() != null && row.getRequestId().equals(invocation.getArgument(2)))
                        .findFirst()
                        .orElse(null));
        when(traceMapper.findByTraceId(any(), anyString())).thenReturn(null);
        when(traceMapper.findBySessionId(any(), anyString(), anyInt())).thenReturn(List.of());
        when(traceMapper.findByTimeRange(any(), any(), any(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(traceMapper.updateLabel(any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
        traceService = new AgentTraceService(traceMapper, objectMapper);
        messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
        sessionService = new HealthSessionService(sessionMapper, objectMapper);
        ReflectionTestUtils.setField(sessionService, "sessionSecret", "test-secret");
        AgentContractModule contract = new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper), traceService);
        HealthSlotDictionary dictionary = new HealthSlotDictionary(TestSupport.slotOptionService());
        HealthInputNormalizer normalizer = new HealthInputNormalizer();
        HealthIntentAgentService intent = new HealthIntentAgentService(contract, new PromptLoader(), dictionary,
                new IntentRuleService(normalizer), normalizer, "qwen-turbo", "v1", 1000);
        HealthClarifyAgentService clarify = new HealthClarifyAgentService(contract, new PromptLoader(),
                new HealthClarifyRuleService(), "qwen-turbo", "v1", 1000);
        HealthRecommendResponseService recommend = new HealthRecommendResponseService(contract, new PromptLoader(),
                "qwen-max", "v1", 1000);
        mealModule = mock(MealModule.class);
        PreferenceService preferenceService = mock(PreferenceService.class);

        HealthProfileService profileService = mock(HealthProfileService.class);
        when(profileService.getProfile(any())).thenReturn(null);
        arbitrationService = mock(AmbiguityArbitrationAgentService.class);
        // 默认仲裁失败（超时/非法 JSON/低置信 → 澄清）；需要特定裁决的测试单独覆写。
        when(arbitrationService.arbitrate(anyString(), anyString(), any())).thenReturn(Optional.empty());

        orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intent, new HealthIntentRevisionService(normalizer, new HealthBriefRouter()),
                normalizer, new HealthClarifyRuleService(), clarify, new HealthRiskRuleService(),
                mealModule, new ExerciseModule(new SeedResourceProvider(), preferenceService),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                recommend, traceService, objectMapper,
                profileService, null, null, new HealthBriefRouter(), arbitrationService);
    }

    private HealthChatResponse chat(String message) {
        return orchestrator.healthChat(1L, new HealthChatRequest(null, "req-" + System.nanoTime(), message, Map.of()));
    }

    private HealthChatResponse chatInSession(String sessionId, String message) {
        return orchestrator.healthChat(1L, new HealthChatRequest(sessionId, "req-" + System.nanoTime(), message, Map.of()));
    }

    private HealthSessionState stateOf(String sessionId) {
        return sessionService.loadOrCreate(sessionId, 1L);
    }

    // ---------- 票据 02：计划上下文优先路由（问题 4） ----------

    @Test
    void 完整综合计划后修改训练时间不再路由为作息并重新出现开始生成() {
        String sessionId = "sess_adr18_composite_modify";
        chatInSession(sessionId, "一周训练和餐食计划");
        chatInSession(sessionId, "早餐午餐想减脂");
        chatInSession(sessionId, "练胸，徒手，入门，周一周三，19:00-20:00");

        HealthChatResponse modify = chatInSession(sessionId, "训练时间改为下午五到六点");
        assertEquals("PLAN", modify.task().name(), modify.toString());
        assertEquals("COMPOSITE", modify.domain().name(), modify.toString());
        assertEquals(new com.diet.health.plan.TrainingTimeWindow(LocalTime.of(17, 0), LocalTime.of(18, 0)),
                modify.planBrief().timeWindow());
        assertEquals(List.of("早餐", "午餐"), modify.mealPlanBrief().mealTimes(), "训练侧修改不得改写餐食简报");
        assertEquals("GENERATE_PLAN", modify.actions().get(0).type(), "修改后重新出现开始生成入口");
    }

    @Test
    void 原始短句训练调整与安排到回归通过() {
        String sessionId = "sess_adr18_short_phrases";
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门，周一周三，19:00-20:00");

        HealthChatResponse adjusted = chatInSession(sessionId, "训练：调整为 17:00-18:00");
        assertEquals("PLAN", adjusted.task().name(), adjusted.toString());
        assertEquals(new com.diet.health.plan.TrainingTimeWindow(LocalTime.of(17, 0), LocalTime.of(18, 0)),
                adjusted.planBrief().timeWindow());

        HealthChatResponse arranged = chatInSession(sessionId, "把训练安排到晚上七点");
        assertEquals("PLAN", arranged.task().name(), arranged.toString());
        assertEquals(LocalTime.of(19, 0), arranged.planBrief().partialStartTime(),
                "安排到晚上七点写入训练时段起点（等结束时间）");
    }

    @Test
    void 真作息问句无论有无计划上下文仍返回作息事实() {
        String sessionId = "sess_adr18_routine_context";
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门，周一周三，19:00-20:00");

        // 事实类作息问句返回作息事实卡（域与任务正确，卡片只含作息资源）
        for (String question : List.of("晚上几点后停止锻炼", "几点睡")) {
            HealthChatResponse response = chatInSession(sessionId, question);
            assertEquals("ROUTINE", response.domain().name(), question + " 必须留在作息域：" + response.toString());
            assertEquals("RECOMMEND", response.task().name(), question);
            assertTrue(response.displayBlocks().stream().allMatch(block -> "ROUTINE".equals(block.resourceType())),
                    question + " 只展示作息事实：" + response.toString());
        }
        // 建议/时机类作息问句（fixture 无对应事实时澄清）仍必须留在作息域，不得被捕获为计划字段
        for (String question : List.of("什么时候训练合适", "训练时段建议")) {
            HealthChatResponse response = chatInSession(sessionId, question);
            assertEquals("ROUTINE", response.domain().name(), question + " 必须留在作息域：" + response.toString());
            assertEquals("RECOMMEND", response.task().name(), question);
        }
        // 无计划上下文的作息问句同样进入作息域
        HealthChatResponse fresh = chat("训练时段建议");
        assertEquals("ROUTINE", fresh.domain().name());
        assertEquals("RECOMMEND", fresh.task().name());
    }

    @Test
    void 已生成简报收到明确修改表达重新打开对应侧而普通短句不捕获() {
        String sessionId = "sess_adr18_generated_reopen";
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门，周一周三，19:00-20:00");
        sessionService.markBriefGenerated(1L, sessionId, List.of("EXERCISE"));
        HealthSessionState generated = stateOf(sessionId);
        assertEquals("GENERATED", generated.briefLifecycle().get("EXERCISE"));

        // 普通字段短句在 GENERATED 状态不进入简报处理器（不得无条件捕获）
        HealthChatResponse ordinary = chatInSession(sessionId, "周二");
        assertEquals(HealthResponseType.CLARIFY, ordinary.responseType(),
                "普通字段短句在 GENERATED 状态不得无条件捕获：" + ordinary.toString());
        HealthSessionState after = stateOf(sessionId);
        assertEquals("GENERATED", after.briefLifecycle().get("EXERCISE"), "普通短句不得重新打开简报");
        assertTrue(after.planBrief().trainingDays().isEmpty() || !after.planBrief().trainingDays().contains(
                java.time.DayOfWeek.TUESDAY), "普通短句不得写入简报");

        // 明确计划修改表达重新打开训练侧并更新字段
        HealthChatResponse reopen = chatInSession(sessionId, "训练时间改成 20:00-21:00");
        assertEquals("PLAN", reopen.task().name(), reopen.toString());
        assertEquals(new com.diet.health.plan.TrainingTimeWindow(LocalTime.of(20, 0), LocalTime.of(21, 0)),
                reopen.planBrief().timeWindow(), "GENERATED 状态下的明确修改表达重新打开训练侧");
        assertEquals("OPEN", stateOf(sessionId).briefLifecycle().get("EXERCISE"), "修改后简报重新打开");
    }

    @Test
    void 已有餐食上下文时裸餐食计划澄清新建或修改且消费后续回答() {
        String sessionId = "sess_adr18_bare_meal_plan";
        chatInSession(sessionId, "帮我安排这周的餐食计划");
        chatInSession(sessionId, "早餐午餐想减脂");

        HealthChatResponse bare = chatInSession(sessionId, "餐食计划");
        assertEquals(HealthResponseType.CLARIFY, bare.responseType(), bare.toString());
        assertTrue(bare.speechText().contains("修改当前餐食计划") || bare.speechText().contains("新建一份"),
                bare.speechText());
        assertNotNull(stateOf(sessionId).pendingPlanClarify(), "澄清挂起标记必须持久化");

        // “新建”重置餐食简报并开始重新收集
        HealthChatResponse redo = chatInSession(sessionId, "新建");
        assertTrue(redo.speechText().contains("新建一份餐食简报"), redo.toString());
        assertNull(stateOf(sessionId).pendingPlanClarify(), "消费后清除挂起标记");
        assertTrue(stateOf(sessionId).mealPlanBrief().mealTimes().isEmpty(), "新建重置餐食简报");

        // 重新收集后再裸“餐食计划” → “修改”保留当前简报
        chatInSession(sessionId, "早餐午餐想均衡");
        chatInSession(sessionId, "餐食计划");
        HealthChatResponse modify = chatInSession(sessionId, "修改");
        assertTrue(modify.speechText().contains("继续当前餐食计划"), modify.toString());
        assertEquals(List.of("早餐", "午餐"), stateOf(sessionId).mealPlanBrief().mealTimes(),
                "修改保留当前餐食简报");
    }

    @Test
    void 无活动简报的孤立修改表达按任务词进入训练计划创建侧() {
        HealthChatResponse response = chat("把训练安排到晚上七点");
        assertEquals("PLAN", response.task().name(), response.toString());
        assertEquals("EXERCISE", response.domain().name(), response.toString());
        assertTrue(response.speechText().contains("已记下开始时间"), response.speechText());
        assertEquals(LocalTime.of(19, 0), response.planBrief().partialStartTime());
    }

    // ---------- 票据 03：日期表达不改变简报语义 ----------

    @Test
    void 日期表达只返回统一说明且不改变简报() {
        String sessionId = "sess_adr18_date_only";
        chatInSession(sessionId, "帮我安排这周的餐食计划");
        chatInSession(sessionId, "早餐午餐想减脂");

        HealthChatResponse dateOnly = chatInSession(sessionId, "改成 2026-09-07");
        assertTrue(dateOnly.speechText().contains("不需要指定日期"), dateOnly.toString());
        HealthSessionState state = stateOf(sessionId);
        assertNull(state.mealPlanBrief().weekStart(), "日期表达不得写入简报锚点");
        assertEquals(List.of("早餐", "午餐"), state.mealPlanBrief().mealTimes());

        HealthChatResponse nextWeek = chatInSession(sessionId, "下周安排");
        assertTrue(nextWeek.speechText().contains("不需要指定日期"), nextWeek.toString());
    }

    @Test
    void 周锚点缺失时生成接口在生成边界派生当天所在周周一() {
        // 编排器级只验证生成入口链路不要求目标周（简报缺锚点不影响完整性判定）
        String sessionId = "sess_adr18_no_anchor";
        HealthChatResponse first = chatInSession(sessionId, "帮我安排这周的餐食计划");
        assertTrue(first.mealPlanBrief() == null || !first.mealPlanBrief().isComplete());
        HealthChatResponse second = chatInSession(sessionId, "早餐午餐想减脂");
        assertTrue(second.mealPlanBrief().isComplete(), second.toString());
        assertNull(second.mealPlanBrief().weekStart(), "简报完整不依赖周锚点");
        assertEquals(List.of("GENERATE_PLAN", "CONTINUE_MEAL_PLAN_BRIEF"),
                second.actions().stream().map(action -> action.type()).toList(), "开始生成入口出现");
    }

    // ---------- 票据 05：歧义任务单次受约束仲裁 ----------

    @Test
    void 无歧义输入不调用仲裁且直接确定性路由() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new com.diet.health.module.HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                        null, false, Map.of())));
        HealthChatResponse response = chat("午餐想吃清淡的");
        assertEquals("MEAL", response.domain().name(), response.toString());
        assertEquals("RECOMMEND", response.task().name(), response.toString());
        verify(arbitrationService, never()).arbitrate(anyString(), anyString(), any());
    }

    @Test
    void 规则无法唯一裁决时调用一次仲裁并复核会话上下文() {
        when(arbitrationService.arbitrate(anyString(), anyString(), any())).thenReturn(Optional.of(
                new AmbiguityArbitrationAgentService.ArbitrationResult(
                        "ROUTINE", HealthDomain.ROUTINE, 0.9, "训练问题偏作息建议")));
        HealthChatResponse response = chat("同时想练胸又想知道什么时候练合适");
        verify(arbitrationService, org.mockito.Mockito.times(1)).arbitrate(anyString(), anyString(), any());
        assertEquals("ROUTINE", response.domain().name(), response.toString());
        assertEquals("RECOMMEND", response.task().name(), response.toString());
    }

    @Test
    void 仲裁结果与计划生命周期冲突时澄清而不猜测执行() {
        when(arbitrationService.arbitrate(anyString(), anyString(), any())).thenReturn(Optional.of(
                new AmbiguityArbitrationAgentService.ArbitrationResult(
                        "REVISE_PLAN", HealthDomain.EXERCISE, 0.9, "看起来想修改计划")));
        HealthChatResponse response = chat("这个计划帮我调整一下");
        assertEquals(HealthResponseType.CLARIFY, response.responseType(), response.toString());
        assertTrue(response.speechText().contains("当前没有正在进行或已保存"), response.speechText());
    }

    @Test
    void 仲裁失败时澄清并保留规则已抽取槽位() {
        // 默认桩已使仲裁失败（超时/非法 JSON/低置信）
        HealthChatResponse response = chat("同时想练胸和吃清淡的");
        assertEquals(HealthResponseType.CLARIFY, response.responseType(), response.toString());
        assertTrue(response.speechText().contains("几种理解"), response.speechText());
        HealthSessionState state = stateOf(response.sessionId());
        assertEquals(List.of("胸"), state.slots().get("bodyParts"), "规则已抽取的槽位在澄清中保留");
    }

    @Test
    void 仲裁路径在综合简报上下文中不被无意义触发() {
        // 活跃综合简报的续轮走共享路由（无模型调用），仲裁服务不得被调用
        String sessionId = "sess_adr18_arbitration_idle";
        chatInSession(sessionId, "一周训练和餐食计划");
        chatInSession(sessionId, "早餐午餐想减脂");
        HealthChatResponse response = chatInSession(sessionId, "练胸改成周二周四");
        assertEquals("PLAN", response.task().name(), response.toString());
        verify(arbitrationService, never()).arbitrate(anyString(), anyString(), any());
    }
}