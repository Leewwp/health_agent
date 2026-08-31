package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.enums.HealthTask;
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
import com.diet.health.plan.EnabledPlanContextService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * 健康助手计划引导、严格路由与日常聊天规格（2026-08-31）P0 回归：
 * 新建计划消费的生命周期原子转移（RC-1）、澄清继承保真与边界（RC-2/RC-3/RC-4）、
 * 最终任务闸门（RC-5）、CHAT 通道与能力问句、自由输入任务入口与作息启发式防劫持（RC-8）。
 * 关键前置：新建链路回归必须种子非 OPEN 生命周期——全新会话会被 task==PLAN 推导掩盖
 * 状态缺口而假绿（历史漏测机制）。
 */
class ChatRoutingAndPlanGuidanceOrchestratorTest {

    private HealthSessionService sessionService;
    private MealModule mealModule;
    private HealthOrchestratorService orchestrator;
    private AmbiguityArbitrationAgentService arbitrationService;
    private EnabledPlanContextService enabledPlanContextService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ConcurrentHashMap<String, SessionRow> sessionRows = new ConcurrentHashMap<>();
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

        AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
        when(traceMapper.insert(any(RequestTraceRow.class))).thenReturn(1);
        when(traceMapper.findByRequestId(any(), any(), any())).thenReturn(null);
        when(traceMapper.findByTraceId(any(), anyString())).thenReturn(null);
        when(traceMapper.findBySessionId(any(), anyString(), anyInt())).thenReturn(List.of());
        when(traceMapper.findByTimeRange(any(), any(), any(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(traceMapper.updateLabel(any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        AgentTraceService traceService = new AgentTraceService(traceMapper, objectMapper);
        SessionService messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
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
        // 默认仲裁失败（低置信/超时 → 澄清）；需要特定裁决的测试单独覆写。
        when(arbitrationService.arbitrate(anyString(), anyString(), any())).thenReturn(Optional.empty());
        enabledPlanContextService = mock(EnabledPlanContextService.class);

        orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intent, new HealthIntentRevisionService(normalizer, new HealthBriefRouter()),
                normalizer, new HealthClarifyRuleService(), clarify, new HealthRiskRuleService(),
                mealModule, new ExerciseModule(new SeedResourceProvider(), preferenceService),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                recommend, traceService, objectMapper,
                profileService, null, enabledPlanContextService, new HealthBriefRouter(), arbitrationService);
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

    /** 直接落库一个构造好的会话状态（种子陈旧澄清/生成态场景）。 */
    private void seedSession(HealthSessionState state) {
        sessionService.save(state);
    }

    // ---------- 票据 01：新建消费的生命周期原子转移（RC-1，P0 哨兵“周一到周三”） ----------

    @Test
    void 曾生成训练计划的会话新建后字段短答保持计划流程不跳推荐() {
        when(enabledPlanContextService.enabledPlanId(any(), any())).thenReturn(5L);
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new com.diet.health.module.HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                        null, false, Map.of())));
        String sessionId = "sess_guide_exercise_new_plan";
        // 对齐真实 08-31 会话形态：曾生成训练计划（GENERATED 残留）→ 会话域切到餐食推荐 →
        // 再问训练计划。这正是缺陷时隐时现的机制——全新/纯训练会话会被推导或活跃简报掩盖。
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门，周一周三，19:00-20:00");
        sessionService.markBriefGenerated(1L, sessionId, List.of("EXERCISE"));
        assertEquals("GENERATED", stateOf(sessionId).briefLifecycle().get("EXERCISE"),
                "回归前置必须种子 GENERATED（否则假绿）");
        assertEquals("MEAL", chatInSession(sessionId, "给我推荐一份晚餐").domain().name(),
                "会话域切到餐食（模拟推荐后问训练计划的用户路径）");
        org.mockito.Mockito.clearInvocations(mealModule);

        // 显式计划词重开 → 修改/新建澄清
        HealthChatResponse clarify = chatInSession(sessionId, "帮我安排一下这周的健身计划");
        assertEquals(HealthResponseType.CLARIFY, clarify.responseType(), clarify.toString());
        assertTrue(clarify.speechText().contains("要修改当前计划，还是新建一份"), clarify.speechText());
        assertTrue(clarify.actions().stream().anyMatch(action -> "MODIFY_CURRENT_PLAN".equals(action.type())),
                "修改当前计划动作可点击");
        assertTrue(clarify.actions().stream().anyMatch(action -> "NEW_PLAN_BRIEF".equals(action.type())),
                "新建动作以结构化按钮下发");

        // 消费“新建”：同一轮原子 OPEN + 空简报 + 引导点名第一个缺失条件
        HealthChatResponse redo = chatInSession(sessionId, "新建");
        assertTrue(redo.speechText().contains("接下来开始新建一份训练计划"), redo.toString());
        assertTrue(redo.speechText().contains("什么目标"), "开场必须点名第一个缺失必要条件：" + redo.speechText());
        assertFalse(redo.speechText().contains("简报") || redo.speechText().contains("当前字段"),
                "用户可见文案不得出现内部术语：" + redo.speechText());
        assertEquals("OPEN", stateOf(sessionId).briefLifecycle().get("EXERCISE"),
                "“新建”消费同轮原子转 OPEN（RC-1）");
        assertNull(stateOf(sessionId).pendingPlanClarify(), "消费后清除挂起标记");
        assertTrue(stateOf(sessionId).planBrief().trainingDays().isEmpty(), "新建重置训练简报");

        // P0 哨兵：字段短答必须留在 EXERCISE + PLAN，不触发推荐澄清
        HealthChatResponse days = chatInSession(sessionId, "周一到周三");
        assertEquals("PLAN", days.task().name(), "周一到周三不得跳出计划流程：" + days);
        assertEquals("EXERCISE", days.domain().name(), days.toString());
        assertTrue(days.displayBlocks().isEmpty(), "字段短答不得返回资源块");
        assertEquals(List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY),
                stateOf(sessionId).planBrief().trainingDays(), "训练日写入简报");
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());

        // 时间窗字段同样保持计划流程（P0 哨兵第二条）
        HealthChatResponse window = chatInSession(sessionId, "下午六点到七点");
        assertEquals("PLAN", window.task().name(), "下午六点到七点不得被作息启发式劫持：" + window);
        assertNotNull(stateOf(sessionId).planBrief().timeWindow(), "时间窗写入简报");
    }

    @Test
    void 曾生成餐食计划的会话裸餐食计划新建后字段短答保持计划流程() {
        String sessionId = "sess_guide_meal_new_plan";
        chatInSession(sessionId, "帮我安排这周的餐食计划");
        chatInSession(sessionId, "早餐午餐想减脂");
        sessionService.markBriefGenerated(1L, sessionId, List.of("MEAL"));
        assertEquals("GENERATED", stateOf(sessionId).briefLifecycle().get("MEAL"),
                "回归前置必须种子 GENERATED（否则假绿）");

        HealthChatResponse bare = chatInSession(sessionId, "餐食计划");
        assertEquals(HealthResponseType.CLARIFY, bare.responseType(), bare.toString());
        assertEquals("MEAL_NEW_VS_MODIFY", stateOf(sessionId).pendingPlanClarify());

        HealthChatResponse redo = chatInSession(sessionId, "新建");
        assertTrue(redo.speechText().contains("接下来开始新建一份餐食计划"), redo.toString());
        assertFalse(redo.speechText().contains("简报"), "用户可见文案不得出现内部术语：" + redo.speechText());
        assertEquals("OPEN", stateOf(sessionId).briefLifecycle().get("MEAL"),
                "餐食侧“新建”消费同轮原子转 OPEN（RC-1 MEAL 同病修复）");

        HealthChatResponse times = chatInSession(sessionId, "早餐和午餐");
        assertEquals("PLAN", times.task().name(), "餐次短答必须留在计划流程：" + times);
        assertEquals("MEAL", times.domain().name(), times.toString());
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());
    }

    // ---------- 票据 03：澄清继承保真与边界（RC-2/RC-3/RC-4） ----------

    @Test
    void 陈旧餐食调整澄清状态下的聊天语句不触发检索() {
        // RC-3 直接复现：两天前遗留的 (MEAL, ADJUST, CLARIFY) + “能陪我聊天吗”
        String sessionId = "sess_guide_stale_chat";
        seedSession(new HealthSessionState(sessionId, 1L, HealthPhase.CLARIFY, HealthDomain.MEAL,
                HealthTask.ADJUST, List.of(), Map.of("mealTime", List.of("晚餐")), List.of(), List.of(),
                null, null, false, false, 0,
                Map.of("MEAL", "PAUSED", "EXERCISE", "GENERATED"), null, null,
                LocalDate.now().toString()));

        HealthChatResponse chat = chatInSession(sessionId, "能陪我聊天吗");
        assertEquals("OTHER", chat.domain().name(), "聊天语句必须打断澄清继承：" + chat);
        assertEquals("CHAT", chat.task().name(), chat.toString());
        assertTrue(chat.displayBlocks().isEmpty(), "聊天语句不得返回推荐卡片");
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());
    }

    @Test
    void 跨天澄清状态不再被继承() {
        // RC-4 时效边界：clarifyEpoch 是过去的日期 → 不继承，按新输入路由
        String sessionId = "sess_guide_stale_epoch";
        seedSession(new HealthSessionState(sessionId, 1L, HealthPhase.CLARIFY, HealthDomain.MEAL,
                HealthTask.ADJUST, List.of(), Map.of("mealTime", List.of("晚餐")), List.of(), List.of(),
                null, null, false, false, 0,
                Map.of("MEAL", "PAUSED"), null, null, "2020-01-01"));

        HealthChatResponse response = chatInSession(sessionId, "清淡一点");
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());
    }

    @Test
    void 当天澄清短答仍正常继承并推进推荐() {
        String sessionId = "sess_guide_fresh_clarify";
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new com.diet.health.module.HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                        null, false, Map.of())));
        seedSession(new HealthSessionState(sessionId, 1L, HealthPhase.CLARIFY, HealthDomain.MEAL,
                HealthTask.RECOMMEND, List.of(), Map.of("mealTime", List.of("晚餐")), List.of(), List.of(),
                null, null, false, false, 0, Map.of(), null, null, LocalDate.now().toString()));

        HealthChatResponse response = chatInSession(sessionId, "清淡一点");
        assertEquals("MEAL", response.domain().name(), "合法澄清短答必须继续继承：" + response);
        assertEquals("RECOMMEND", response.task().name(), response.toString());
        verify(mealModule).recommendMeals(any(), any(), anyString());
    }

    // ---------- 票据 04：最终任务闸门（RC-5） ----------

    @Test
    void 仲裁高置信推荐但无本轮证据时降级为聊天() {
        when(arbitrationService.arbitrate(anyString(), anyString(), any()))
                .thenReturn(Optional.of(new AmbiguityArbitrationAgentService.ArbitrationResult(
                        "RECOMMEND", HealthDomain.MEAL, 0.95, "模型认为想看餐食推荐")));
        HealthChatResponse response = chat("能陪我聊天吗");
        assertTrue(response.displayBlocks().isEmpty(), "模型单独判定不得启动检索：" + response);
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());
        assertEquals("CHAT", response.task().name(), "无证据推荐统一降级 CHAT：" + response);
    }

    @Test
    void 自由输入给我推荐一份餐食先澄清餐次字段值继承后执行检索() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new com.diet.health.module.HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                        null, false, Map.of())));
        String sessionId = "sess_guide_free_meal_input";
        // 自由输入（非快捷问题）必须能触发任务流程：先按最低槽位澄清餐次
        HealthChatResponse first = chatInSession(sessionId, "给我推荐一份餐食");
        assertEquals("MEAL", first.domain().name(), "自由输入必须能触发餐食推荐：" + first);
        assertEquals("RECOMMEND", first.task().name(), first.toString());
        verify(mealModule, never()).recommendMeals(any(), any(), anyString());

        // 字段短答经澄清继承边界（epoch 当天 + 字段值可解析）执行检索
        HealthChatResponse second = chatInSession(sessionId, "晚餐");
        assertEquals("RECOMMEND", second.task().name(), second.toString());
        verify(mealModule).recommendMeals(any(), any(), anyString());
    }

    @Test
    void 自由输入给我推荐一个训练动作走训练推荐() {
        HealthChatResponse response = chat("给我推荐一个训练动作");
        assertEquals("EXERCISE", response.domain().name(), "自由输入必须能触发动作推荐：" + response);
        assertEquals("RECOMMEND", response.task().name(), response.toString());
    }

    // ---------- 票据 05：CHAT 通道与能力问句 ----------

    @Test
    void 能力问句获得健康助手能力回答且不调用仲裁() {
        HealthChatResponse response = chat("你能帮我做什么");
        assertEquals("OTHER", response.domain().name(), response.toString());
        assertEquals("CHAT", response.task().name(), response.toString());
        assertTrue(response.speechText().contains("健康助手"), "必须表明健康助手身份：" + response.speechText());
        assertTrue(response.speechText().contains("推荐") && response.speechText().contains("计划"),
                "能力回答必须声明推荐与计划能力（不得自称只是聊天助手）：" + response.speechText());
        assertTrue(response.displayBlocks().isEmpty());
        verify(arbitrationService, never()).arbitrate(anyString(), anyString(), any());
    }

    @Test
    void 仲裁失败澄清不继承旧任务标签且选项为结构化动作() {
        // 默认桩仲裁失败 → taskFocus 澄清：响应标签 OTHER/CHAT，无伪槽位 key
        HealthChatResponse response = chat("同时想练胸和吃清淡的");
        assertEquals(HealthResponseType.CLARIFY, response.responseType(), response.toString());
        assertEquals("OTHER", response.domain().name(), "澄清响应不得继承展示旧 domain 标签：" + response);
        assertEquals("CHAT", response.task().name(), response.toString());
        assertTrue(response.missingSlots().isEmpty(), "taskFocus 伪槽位 key 不得进渲染通道：" + response.missingSlots());
        assertTrue(response.actions().stream().allMatch(action -> "SELECT_TASK".equals(action.type())),
                "任务选项以结构化动作下发");
        assertTrue(response.actions().stream().anyMatch(action -> "新建一周计划".equals(action.label())));
    }

    // ---------- 浏览器验收补强：结构化确认与问候语 ----------

    @Test
    void 预检确认短语直达推荐不被仲裁失败劫持() {
        // 生产形态：真实仲裁对确认短语可能低置信失败；确认按钮发送的“开始推荐”
        // 是结构化动作证据，必须确定性直达推荐（浏览器验收实测发现的劫持路径）。
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new com.diet.health.module.HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                        null, false, Map.of())));
        String sessionId = "sess_guide_preflight_confirm";
        HealthChatResponse preflight = chatInSession(sessionId, "给我推荐一份晚餐");
        assertEquals("RECOMMEND", preflight.task().name(), preflight.toString());

        HealthChatResponse confirmed = chatInSession(sessionId, "开始推荐");
        assertEquals("MEAL", confirmed.domain().name(), "确认短语必须直达推荐：" + confirmed);
        assertEquals("RECOMMEND", confirmed.task().name(), confirmed.toString());
        assertFalse(confirmed.displayBlocks().isEmpty(), "确认后必须返回推荐卡片");
        verify(arbitrationService, never()).arbitrate(anyString(), anyString(), any());
    }

    @Test
    void 问候语获得聊天回答而不是任务澄清() {
        HealthChatResponse response = chat("你好");
        assertEquals("OTHER", response.domain().name(), response.toString());
        assertEquals("CHAT", response.task().name(), "问候语不得触发任务澄清：" + response);
        assertTrue(response.speechText().contains("健康助手"), response.speechText());
        assertTrue(response.displayBlocks().isEmpty());
    }

    // ---------- RC-8：作息启发式不得劫持 OPEN 简报的字段值 ----------

    @Test
    void 活跃训练简报中训练时段前缀与裸时间字段保持计划流程() {
        String sessionId = "sess_guide_chip_time";
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门");

        // supplement-chip 注入的“训练时段：”前缀文本（活动词×时间词组合）必须留在 PLAN
        HealthChatResponse prefixed = chatInSession(sessionId, "训练时段：下午六点到七点");
        assertEquals("PLAN", prefixed.task().name(), "chip 前缀字段值不得被作息启发式劫持：" + prefixed);
        assertEquals("EXERCISE", prefixed.domain().name(), prefixed.toString());
        assertNotNull(stateOf(sessionId).planBrief().timeWindow(), "时间窗写入简报");

        // 裸时间字段同规（无修改表达前缀）
        HealthChatResponse bare = chatInSession(sessionId, "晚上八点到九点");
        assertEquals("PLAN", bare.task().name(), "裸时间字段必须留在计划流程：" + bare);
    }

    @Test
    void 真作息问句在计划上下文中仍进入作息域() {
        String sessionId = "sess_guide_true_routine";
        chatInSession(sessionId, "帮我安排一周训练计划");
        chatInSession(sessionId, "增肌，练胸，徒手，入门");
        for (String question : List.of("什么时候训练合适", "训练时段建议")) {
            HealthChatResponse response = chatInSession(sessionId, question);
            assertEquals("ROUTINE", response.domain().name(), question + " 必须留在作息域：" + response);
            assertEquals("RECOMMEND", response.task().name(), question);
        }
    }
}
