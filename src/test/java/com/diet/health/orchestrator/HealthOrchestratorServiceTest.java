package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.health.intent.HealthSlotDictionary;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.intent.IntentRuleService;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.SessionMapper;
import com.diet.model.RequestTraceRow;
import com.diet.model.SessionMessageRow;
import com.diet.model.SessionRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康编排器固定场景集（无 API key，FixtureAgentInvoker）：
 * 三品类路由、澄清继续会话、候选/事实查询、风险拒绝、候选为空、幂等与 Trace 内容。
 */
class HealthOrchestratorServiceTest {

    /** 内存版 SessionMapper：支持多轮会话状态续接。 */
    private static final class FakeSessionMapper implements SessionMapper {
        private final Map<String, SessionRow> rows = new HashMap<>();

        @Override
        public int insert(SessionRow row) {
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public SessionRow findById(String sessionId, Long userId) {
            return rows.get(sessionId);
        }

        @Override
        public SessionRow findByIdForUpdate(String sessionId, Long userId) {
            return rows.get(sessionId);
        }

        @Override
        public int update(SessionRow row) {
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public int insertMessage(String sessionId, String role, String content, String intent, String traceId) {
            return 1;
        }

        @Override
        public List<SessionMessageRow> listRecentMessages(String sessionId, Long userId, int limit) {
            return List.of();
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
    private final List<RequestTraceRow> insertedTraces = new ArrayList<>();
    private final FakeSessionMapper sessionMapper = new FakeSessionMapper();
    private final PreferenceService preferenceService = new PreferenceService(mock(FeedbackMapper.class));
    private MealModule mealModule;
    private HealthSessionService sessionService;
    private HealthOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            RequestTraceRow row = invocation.getArgument(0);
            insertedTraces.add(row);
            return 1;
        }).when(traceMapper).insert(any());
        when(traceMapper.findByRequestId(any(), any(), any())).thenAnswer(invocation ->
                insertedTraces.stream()
                        .filter(row -> row.getRequestId() != null && row.getRequestId().equals(invocation.getArgument(2)))
                        .findFirst()
                        .orElse(null));
        when(traceMapper.findByTraceId(any(), anyString())).thenReturn(null);
        when(traceMapper.findBySessionId(any(), anyString(), anyInt())).thenReturn(List.of());
        when(traceMapper.findByTimeRange(any(), any(), any(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(traceMapper.updateLabel(any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        AgentTraceService trace = new AgentTraceService(traceMapper, objectMapper);
        SessionService messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
        sessionService = new HealthSessionService(sessionMapper, objectMapper);
        ReflectionTestUtils.setField(sessionService, "sessionSecret", "test-secret");
        AgentContractModule contract = new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper), trace);
        HealthSlotDictionary dictionary = new HealthSlotDictionary(TestSupport.slotOptionService());
        HealthInputNormalizer normalizer = new HealthInputNormalizer();
        HealthIntentAgentService intent = new HealthIntentAgentService(contract, new PromptLoader(), dictionary,
                new IntentRuleService(normalizer), normalizer, "qwen-turbo", "v1", 1000);
        HealthClarifyAgentService clarify = new HealthClarifyAgentService(contract, new PromptLoader(), new HealthClarifyRuleService(), "qwen-turbo", "v1", 1000);
        HealthRecommendResponseService recommend = new HealthRecommendResponseService(contract, new PromptLoader(), "qwen-max", "v1", 1000);

        mealModule = mock(MealModule.class);
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));

        orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intent, new HealthIntentRevisionService(normalizer), normalizer,
                new HealthClarifyRuleService(), clarify,
                new HealthRiskRuleService(), mealModule, new ExerciseModule(new SeedResourceProvider(), preferenceService),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                recommend, trace, objectMapper);
    }

    private HealthChatResponse chat(String message) {
        return orchestrator.healthChat(1L, new HealthChatRequest(null, "req-" + System.nanoTime(), message, Map.of()));
    }

    private HealthChatResponse chatInSession(String sessionId, String message) {
        return orchestrator.healthChat(1L, new HealthChatRequest(sessionId, "req-" + System.nanoTime(), message, Map.of()));
    }

    @Test
    void 饮食链路返回候选解释与餐食块() {
        HealthChatResponse response = chat("午餐想吃清淡的");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("MEAL", response.domain().name());
        assertFalse(response.displayBlocks().isEmpty());
        assertEquals("MEAL", response.displayBlocks().get(0).resourceType());
        assertEquals("5", response.displayBlocks().get(0).resourceId());
    }

    @Test
    void 编排器合并一条消息中的多个口语餐食槽位() {
        org.mockito.ArgumentCaptor<Map<String, List<String>>> slots =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        when(mealModule.recommendMeals(slots.capture(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));

        HealthChatResponse response = chat("今晚胃口不好，想吃素，想吃便利店能买的酸甜口味速食");

        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals(List.of("晚餐"), slots.getValue().get("mealTime"));
        assertEquals(List.of("没胃口"), slots.getValue().get("mood"));
        assertEquals(List.of("素食"), slots.getValue().get("cuisine"));
        assertEquals(List.of("酸甜"), slots.getValue().get("taste"));
        assertEquals(List.of("快速"), slots.getValue().get("convenience"));
    }

    @Test
    void 推荐前确认摘要使用中文槽位文案() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "9", "番茄豆腐", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));
        HealthOrchestratorService preflight = new HealthOrchestratorService(
                sessionService, new SessionService(sessionMapper, new JsonService(objectMapper), 10),
                new HealthIntentAgentService(
                        new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper),
                                new AgentTraceService(traceMapper, objectMapper)),
                        new PromptLoader(), new HealthSlotDictionary(TestSupport.slotOptionService()),
                        new IntentRuleService(new HealthInputNormalizer()), new HealthInputNormalizer(),
                        "qwen-turbo", "v1", 1000),
                new HealthIntentRevisionService(new HealthInputNormalizer()), new HealthInputNormalizer(),
                new HealthClarifyRuleService(),
                new HealthClarifyAgentService(
                        new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper),
                                new AgentTraceService(traceMapper, objectMapper)),
                        new PromptLoader(), new HealthClarifyRuleService(), "qwen-turbo", "v1", 1000),
                new HealthRiskRuleService(), mealModule,
                new ExerciseModule(new SeedResourceProvider(), preferenceService),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                new HealthRecommendResponseService(
                        new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper),
                                new AgentTraceService(traceMapper, objectMapper)),
                        new PromptLoader(), "qwen-max", "v1", 1000),
                new AgentTraceService(traceMapper, objectMapper), objectMapper,
                mock(HealthProfileService.class), null, null);

        HealthChatResponse response = preflight.healthChat(1L,
                new HealthChatRequest(null, "req-preflight-labels", "今晚胃口不好，想吃素，想吃便利店能买的酸甜口味速食", Map.of()));

        assertTrue(response.speechText().contains("今天的心情：没胃口"));
        assertTrue(response.speechText().contains("菜系或食材：素食"));
        assertTrue(response.speechText().contains("口味：酸甜"));
        assertTrue(response.speechText().contains("能接受的耗时和购买方式：快速"));
        assertFalse(response.speechText().contains("mealTime"));
        assertFalse(response.speechText().contains("mood"));
        assertFalse(response.speechText().contains("cuisine"));
        assertFalse(response.speechText().contains("taste"));
        assertFalse(response.speechText().contains("convenience"));
    }

    @Test
    void 追加槽位写回会话并保留原条件() {
        when(mealModule.recommendMeals(any(), any(), anyString()))
                .thenReturn(List.of())
                .thenReturn(List.of(new HealthResource("MEAL", "5", "素食午餐", "PUBLIC", "公共餐食库",
                        null, false, Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡"),
                        "cuisine", List.of("素食")))));
        when(mealModule.availableSlotValues()).thenReturn(Map.of("cuisine", List.of("素食")));
        String sessionId = "sess_append_slots";

        HealthChatResponse empty = chatInSession(sessionId, "午餐想吃清淡的");
        assertTrue(empty.actions().stream().anyMatch(action -> "APPEND_SLOT".equals(action.type())));
        orchestrator.healthChat(1L, new HealthChatRequest(sessionId, "req-append-slots", "追加素食", Map.of(),
                new HealthChatRequest.AlternativeRequest("MEAL", empty.traceId(), List.of(), false, false,
                        Map.of("cuisine", List.of("素食")))));

        HealthSessionState saved = sessionService.loadOrCreate(sessionId, 1L);
        assertEquals(List.of("午餐"), saved.slots().get("mealTime"));
        assertEquals(List.of("清淡"), saved.slots().get("healthGoal"));
        assertEquals(List.of("素食"), saved.slots().get("cuisine"));
    }

    @Test
    void 餐食检索透传用户原话与槽位() {
        org.mockito.ArgumentCaptor<Map<String, List<String>>> slots =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<List<String>> exclude =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(mealModule.recommendMeals(slots.capture(), exclude.capture(), text.capture())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));

        HealthChatResponse response = chat("晚上想要高蛋白清淡晚餐");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("MEAL", response.domain().name());
        assertEquals("晚上想要高蛋白清淡晚餐", text.getValue(), "用户原话应进入检索文本");
        assertFalse(slots.getValue().isEmpty(), "槽位应透传");
        assertTrue(slots.getValue().containsKey("mealTime"));
        assertEquals(List.of(), exclude.getValue(), "非 ADJUST 的普通推荐排除集必须显式为空并透传");
    }

    @Test
    void ADJUST检索透传历史排除ID() {
        // 先在同会话内完成一轮餐食推荐（记录 lastResources），再发起 ADJUST 换一批：
        // 排除 ID 必须来自会话历史类型化资源引用并透传给 MealModule。
        org.mockito.ArgumentCaptor<Map<String, List<String>>> slots =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<List<String>> exclude =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(mealModule.recommendMeals(slots.capture(), exclude.capture(), text.capture()))
                .thenReturn(List.of(
                        new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of())
                ));

        String sessionId = "sess_adjust_exclude";
        HealthChatResponse first = chatInSession(sessionId, "午餐想吃清淡的");
        assertEquals(HealthResponseType.ANSWER, first.responseType());
        assertFalse(first.displayBlocks().isEmpty());

        HealthChatResponse second = chatInSession(sessionId, "换一批");
        assertEquals(HealthResponseType.ANSWER, second.responseType());
        assertEquals("MEAL", second.domain().name());
        assertEquals(List.of("5"), exclude.getValue(),
                "ADJUST 换一批必须把上一轮推荐资源作为排除 ID 透传");
        assertEquals("换一批", text.getValue(), "ADJUST 检索同样透传用户原话");
    }

    @Test
    void 健身链路返回planReady动作与署名() {
        HealthChatResponse response = chat("想减脂练胸，徒手入门");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("EXERCISE", response.domain().name());
        assertTrue(response.displayBlocks().isEmpty());
        assertTrue(response.actions().stream().anyMatch(action -> "APPEND_SLOT".equals(action.type())));
    }

    @Test
    void 作息链路返回事实与来源() {
        HealthChatResponse response = chat("睡多久合适");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("ROUTINE", response.domain().name());
        assertFalse(response.displayBlocks().isEmpty());
        assertEquals("ROUTINE", response.displayBlocks().get(0).resourceType());
        assertNotNull(response.displayBlocks().get(0).sourceName());
    }

    @Test
    void 咖啡事实在餐食历史后仍直接返回作息来源() {
        String sessionId = "sess_meal_to_routine";
        assertEquals("MEAL", chatInSession(sessionId, "午餐想吃清淡的").domain().name());

        HealthChatResponse response = chatInSession(sessionId, "晚上几点前停止喝咖啡？");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("ROUTINE", response.domain().name());
        assertEquals("RECOMMEND", response.task().name());
        assertTrue(response.missingSlots().isEmpty());
        assertFalse(response.displayBlocks().isEmpty());
        assertTrue(response.displayBlocks().stream().allMatch(block ->
                "ROUTINE".equals(block.resourceType()) && block.sourceName() != null));
    }

    @Test
    void 信息不足先澄清再继续会话() {
        String sessionId = "sess_clarify_continue";
        HealthChatResponse first = chatInSession(sessionId, "帮我推荐");
        assertEquals(HealthResponseType.CLARIFY, first.responseType());
        assertEquals("OTHER", first.domain().name());
        assertEquals(List.of("domain"), first.missingSlots());
        assertNotNull(first.clarifyQuestion());

        HealthChatResponse second = chatInSession(sessionId, "午餐");
        assertEquals(HealthResponseType.CLARIFY, second.responseType());
        assertEquals(List.of("healthGoal"), second.missingSlots());

        HealthChatResponse third = chatInSession(sessionId, "清淡点");
        assertEquals(HealthResponseType.ANSWER, third.responseType());
        assertFalse(third.displayBlocks().isEmpty());
    }

    @Test
    void 健身澄清继续会话() {
        String sessionId = "sess_exercise_continue";
        HealthChatResponse first = chatInSession(sessionId, "推荐健身动作");
        assertEquals(HealthResponseType.CLARIFY, first.responseType());
        assertEquals(List.of("trainingGoal"), first.missingSlots());

        assertEquals(List.of("bodyParts"), chatInSession(sessionId, "减脂").missingSlots());
        assertEquals(List.of("equipment"), chatInSession(sessionId, "练背").missingSlots());
        assertEquals(List.of("difficulty"), chatInSession(sessionId, "徒手").missingSlots());
        HealthChatResponse completed = chatInSession(sessionId, "入门");
        assertEquals(HealthResponseType.ANSWER, completed.responseType());
        assertTrue(completed.displayBlocks().isEmpty());
        assertTrue(completed.actions().stream().noneMatch(action ->
                "CONFIRM_RECOMMENDATION".equals(action.type())),
                "严格匹配无候选时不应误发确认推荐动作");
    }

    @Test
    void 新手轻量训练经常见部位短答完成健身推荐() {
        // 种子库中没有“减脂+胸/臀+徒手+入门”候选：短答后仍无结果，只能提供追加条件操作。
        for (String bodyPart : List.of("胸肌", "胸部", "胸大肌", "臀部", "臀肌", "臀大肌")) {
            String sessionId = "sess_exercise_alias_" + bodyPart;
            HealthChatResponse first = chatInSession(sessionId, "帮我推荐一份减脂、徒手、适合新手的轻量训练");
            assertEquals(HealthResponseType.CLARIFY, first.responseType());
            assertEquals("EXERCISE", first.domain().name());
            assertEquals(List.of("bodyParts"), first.missingSlots());

            HealthChatResponse second = chatInSession(sessionId, bodyPart);
            assertEquals(HealthResponseType.ANSWER, second.responseType(), bodyPart);
            assertEquals("EXERCISE", second.domain().name(), bodyPart);
            assertTrue(second.displayBlocks().isEmpty(), bodyPart);
            assertTrue(second.actions().stream().anyMatch(action -> "APPEND_SLOT".equals(action.type())), bodyPart);
            assertTrue(second.displayBlocks().stream().allMatch(block -> "EXERCISE".equals(block.resourceType())), bodyPart);
        }
    }

    @Test
    void 腿部短答在存在全身减脂候选时返回动作卡() {
        // 9009 开合跳（全身/徒手/入门/减脂）加入种子后，“减脂+腿”短答应直接给出动作结果。
        String sessionId = "sess_exercise_alias_legs";
        chatInSession(sessionId, "帮我推荐一份减脂、徒手、适合新手的轻量训练");

        HealthChatResponse second = chatInSession(sessionId, "大腿");
        assertEquals(HealthResponseType.ANSWER, second.responseType());
        assertEquals("EXERCISE", second.domain().name());
        assertFalse(second.displayBlocks().isEmpty());
        assertTrue(second.displayBlocks().stream().allMatch(block -> "EXERCISE".equals(block.resourceType())),
                second.toString());
    }

    @Test
    void 否定部位不作为正向约束并继续澄清() {
        HealthChatResponse response = chat("不要练胸，推荐一个训练");
        assertEquals(HealthResponseType.CLARIFY, response.responseType());
        assertEquals("EXERCISE", response.domain().name());
        assertTrue(response.displayBlocks().isEmpty());
    }

    @Test
    void 无关对话走OTHER且不返回健康资源() {
        for (String input : List.of("推荐电影", "你是 AI 吗")) {
            HealthChatResponse response = chat(input);
            assertEquals(HealthResponseType.ANSWER, response.responseType());
            assertEquals("OTHER", response.domain().name());
            assertEquals("CHAT", response.task().name());
            assertTrue(response.displayBlocks().isEmpty());
        }
    }

    @Test
    void 健身切换餐食时检索只接收餐食槽位() {
        String sessionId = "sess_exercise_to_meal";
        assertEquals("EXERCISE", chatInSession(sessionId, "想减脂练胸，徒手入门").domain().name());

        org.mockito.ArgumentCaptor<Map<String, List<String>>> slots = org.mockito.ArgumentCaptor.forClass(Map.class);
        when(mealModule.recommendMeals(slots.capture(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));
        HealthChatResponse meal = chatInSession(sessionId, "午餐想吃清淡的");
        assertEquals("MEAL", meal.domain().name());
        assertTrue(meal.displayBlocks().stream().allMatch(block -> "MEAL".equals(block.resourceType())));
        assertTrue(slots.getValue().keySet().stream().allMatch(HealthSlotDictionary.MEAL_SLOTS::contains));
        assertFalse(slots.getValue().containsKey("bodyParts"));
    }

    @Test
    void 餐食切换健身不携带餐食资源或澄清() {
        String sessionId = "sess_meal_to_exercise";
        assertEquals("MEAL", chatInSession(sessionId, "午餐想吃清淡的").domain().name());

        HealthChatResponse exercise = chatInSession(sessionId, "适合新手减脂的徒手胸肌训练");
        assertEquals(HealthResponseType.ANSWER, exercise.responseType());
        assertEquals("EXERCISE", exercise.domain().name());
        assertTrue(exercise.displayBlocks().stream().allMatch(block -> "EXERCISE".equals(block.resourceType())));
    }

    @Test
    void 风险信号被拦截返回固定文案() {
        HealthChatResponse response = chat("我怀孕了怎么安排饮食");
        assertEquals(HealthResponseType.BLOCKED, response.responseType());
        assertEquals(HealthRiskRuleService.BLOCK_PLAN_COPY, response.speechText());
        assertTrue(response.riskFlags().contains("PREGNANCY"));
    }

    @Test
    void 历史风险导致拦截时Trace记录风险来源() throws Exception {
        String sessionId = "sess_historical_risk_trace";
        chatInSession(sessionId, "我胸痛");

        HealthChatResponse response = chatInSession(sessionId, "帮我推荐晚餐");
        assertEquals(HealthResponseType.BLOCKED, response.responseType());

        JsonNode root = objectMapper.readTree(insertedTraces.get(1).getTraceJson());
        JsonNode riskInput = null;
        for (JsonNode event : root.path("events")) {
            if ("RISK_ASSESSED".equals(event.path("eventType").asText())) {
                riskInput = objectMapper.readTree(event.path("inputPayload").asText());
                break;
            }
        }
        assertNotNull(riskInput);
        assertEquals(List.of("ACUTE_SYMPTOMS"), objectMapper.convertValue(
                riskInput.path("historicalRiskFlags"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertEquals(List.of(), objectMapper.convertValue(
                riskInput.path("intentRiskFlags"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertEquals(List.of("ACUTE_SYMPTOMS"), objectMapper.convertValue(
                riskInput.path("assessedRiskFlags"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
    }

    @Test
    void PLAN意图返回非写入引导且不含即将上线() {
        HealthChatResponse response = chat("帮我安排一周的计划");
        assertEquals(HealthResponseType.ANSWER, response.responseType(), "PLAN 响应保持非写入的 ANSWER");
        assertEquals("PLAN", response.task().name());
        assertTrue(response.displayBlocks().isEmpty(), "PLAN 引导回复不携带资源卡");
        assertFalse(response.speechText().contains("即将上线"), "不得宣称周计划尚未上线");
        assertFalse(response.speechText().contains("我的计划"), "不再引导进入旧的计划页面生成入口");
        assertTrue(response.speechText().contains("当前对话"), "应在当前对话继续计划流程");
        assertEquals(1, insertedTraces.size(), "单轮 PLAN 聊天只产生一条 Trace，无任何计划写入");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                orchestrator.healthChat(1L, new HealthChatRequest(null, "plan-no-write-" + System.nanoTime(),
                        "帮我安排一周的计划", Map.of())));
    }

    @Test
    void 一周健身计划先进入训练简报澄清闭环() {
        HealthChatResponse response = chat("一周健身计划");
        assertEquals(HealthResponseType.CLARIFY, response.responseType());
        assertEquals("EXERCISE", response.domain().name());
        assertEquals("PLAN", response.task().name());
        assertEquals("CLARIFY", response.phase().name());
        assertTrue(response.displayBlocks().isEmpty());
        assertTrue(response.speechText().contains("训练主要"), response.speechText());
        assertTrue(response.actions().isEmpty());

        HealthChatResponse currentWeekResponse = chat("帮我安排一下这周的健身计划");
        assertEquals("PLAN", currentWeekResponse.task().name(), "前端快捷问题必须进入周计划入口");
        assertEquals(HealthResponseType.CLARIFY, currentWeekResponse.responseType());
        assertTrue(currentWeekResponse.displayBlocks().isEmpty());
        assertTrue(currentWeekResponse.speechText().contains("训练主要"));
    }

    @Test
    void 新会话模糊短句返回OTHER领域澄清且不携带资源卡() {
        // ADR-0016：新会话只有明确任务词才进入推荐；槽位别名短句不猜测领域。
        for (String input : List.of("清淡一点", "入门徒手", "便利店速食", "胸肌")) {
            HealthChatResponse response = chat(input);
            assertEquals(HealthResponseType.CLARIFY, response.responseType(), input);
            assertEquals("OTHER", response.domain().name(), input);
            assertEquals("CHAT", response.task().name(), input);
            assertTrue(response.displayBlocks().isEmpty(), input + " 不得返回任何资源卡");
            assertTrue(response.speechText().contains("餐食推荐"), input + " 应给出领域澄清提示");
            HealthSessionState state = sessionService.loadOrCreate(response.sessionId(), 1L);
            assertTrue(state.slots().isEmpty(), input + " 不得把模糊短句写成会话槽位");
        }
    }

    @Test
    void 五类明确任务词仍进入正确领域与任务() {
        assertEquals("MEAL", chat("今晚想吃清淡的").domain().name());
        assertEquals("EXERCISE", chat("帮我推荐一份适合新手的轻量训练").domain().name());
        assertEquals("PLAN", chat("帮我安排一下这周的餐食计划").task().name());
        assertEquals("PLAN", chat("帮我安排一下这周的健身计划").task().name());
        HealthChatResponse composite = chat("帮我制定一份饮食和训练的综合计划");
        assertEquals("COMPOSITE", composite.domain().name());
        assertEquals("PLAN", composite.task().name());
    }

    @Test
    void 训练简报澄清中的作息提问仍返回作息事实且简报暂停可恢复() {
        // 回归：计划澄清上下文中显式提问作息事实，不得被 planContinuation 强制为 PLAN。
        String sessionId = "sess_training_clarify_routine";
        chatInSession(sessionId, "帮我安排一周健身计划");
        HealthChatResponse goal = chatInSession(sessionId, "训练目标减脂");
        assertEquals("减脂", goal.planBrief().trainingGoal(), goal.toString());

        HealthChatResponse response = chatInSession(sessionId, "晚上几点后停止锻炼");
        assertEquals("ROUTINE", response.domain().name());
        assertEquals("RECOMMEND", response.task().name());
        assertTrue(response.displayBlocks().stream().allMatch(block -> "ROUTINE".equals(block.resourceType())),
                response.toString());

        // 作息提问后训练简报暂停保留；裸字段走普通推荐，不恢复也不污染计划简报。
        HealthChatResponse plain = chatInSession(sessionId, "训练目标增肌");
        assertEquals("EXERCISE", plain.domain().name());
        assertEquals("RECOMMEND", plain.task().name());

        // 明确的继续计划表达恢复暂停的训练简报，已收集字段不丢失。
        HealthChatResponse resume = chatInSession(sessionId, "回到训练计划");
        assertEquals("EXERCISE", resume.domain().name());
        assertEquals("PLAN", resume.task().name());
        assertEquals("减脂", resume.planBrief().trainingGoal(), "恢复后的简报保持暂停前字段");
    }

    @Test
    void 停止锻炼时段问题只返回有来源的作息事实() {
        HealthChatResponse response = chat("晚上几点后停止锻炼");
        assertEquals("ROUTINE", response.domain().name());
        assertEquals("RECOMMEND", response.task().name());
        assertFalse(response.displayBlocks().isEmpty());
        assertTrue(response.displayBlocks().stream().allMatch(block ->
                "ROUTINE".equals(block.resourceType())), "作息问题不得返回餐食或动作卡");
        assertTrue(response.displayBlocks().stream().allMatch(block ->
                block.sourceName() != null && !block.sourceName().isBlank()), "作息事实必须带来源");
    }

    @Test
    void 餐食简报进行中显式切换训练暂停旧简报且可恢复() {
        String sessionId = "sess_cross_domain_pause";
        chatInSession(sessionId, "一周餐食计划");
        HealthChatResponse meal = chatInSession(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        assertEquals("GENERATE_PLAN", meal.actions().get(0).type());

        // 显式切换训练：旧餐食简报保留但不进入当前训练澄清。
        HealthChatResponse training = chatInSession(sessionId, "帮我安排一周健身计划");
        assertEquals("EXERCISE", training.domain().name());
        assertEquals("PLAN", training.task().name());
        assertEquals(HealthResponseType.CLARIFY, training.responseType());
        HealthSessionState state = sessionService.loadOrCreate(sessionId, 1L);
        assertTrue(state.mealPlanBrief().isComplete(), "切换领域只暂停简报，不清空");
        assertFalse(state.planBrief().isComplete(), "训练简报尚未收集完成");

        // 明确继续表达后恢复餐食简报，已收集条件不丢失。
        HealthChatResponse resume = chatInSession(sessionId, "回到餐食计划");
        assertEquals("MEAL", resume.domain().name());
        assertEquals("PLAN", resume.task().name());
        assertEquals("GENERATE_PLAN", resume.actions().get(0).type());
        assertEquals(List.of("早餐", "午餐", "晚餐"), resume.mealPlanBrief().mealTimes());
    }

    @Test
    void 一周餐食计划进入独立餐食简报且完整即提供开始生成() {
        String sessionId = "sess_meal_plan_brief";
        HealthChatResponse first = chatInSession(sessionId, "一周餐食计划");
        assertEquals(HealthResponseType.CLARIFY, first.responseType());
        assertEquals("MEAL", first.domain().name());
        assertEquals("PLAN", first.task().name());
        assertTrue(first.speechText().contains("目标周") || first.speechText().contains("餐次"));

        HealthChatResponse collected = chatInSession(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        assertFalse(collected.actions().isEmpty(), collected.toString());
        assertEquals("GENERATE_PLAN", collected.actions().get(0).type());
        assertEquals("开始生成", collected.actions().get(0).label());
        assertEquals("CONTINUE_MEAL_PLAN_BRIEF", collected.actions().get(1).type());
        assertEquals(List.of("早餐", "午餐", "晚餐"), collected.mealPlanBrief().mealTimes());
        assertTrue(collected.mealPlanBrief().isComplete());
        assertTrue(collected.planBrief().bodyParts().isEmpty());

        // 简报完整后继续补充餐次：服务端以当前简报为准，不存在独立确认阶段。
        HealthChatResponse adjusted = chatInSession(sessionId, "改成只要早餐和午餐");
        assertEquals("GENERATE_PLAN", adjusted.actions().get(0).type());
        assertEquals(List.of("早餐", "午餐"), adjusted.mealPlanBrief().mealTimes());
    }

    @Test
    void 餐食计划只有餐次时不能直接生成且允许继续补充() {
        String sessionId = "sess_meal_plan_requires_goal";
        chatInSession(sessionId, "一周餐食计划");

        HealthChatResponse partial = chatInSession(sessionId, "下周安排早餐、午餐和晚餐");
        assertFalse(partial.mealPlanBrief().isComplete(), partial.toString());
        assertTrue(partial.speechText().contains("餐食目标"), partial.speechText());
        assertTrue(partial.actions().isEmpty());
        assertFalse(partial.actions().stream().anyMatch(action -> "GENERATE_PLAN".equals(action.type())));

        HealthChatResponse collected = chatInSession(sessionId, "餐食计划目标想减脂");
        assertEquals("GENERATE_PLAN", collected.actions().get(0).type());
        assertEquals("CONTINUE_MEAL_PLAN_BRIEF", collected.actions().get(1).type());
        assertTrue(collected.mealPlanBrief().isComplete());

        HealthChatResponse adjusted = chatInSession(sessionId, "改成只要早餐和午餐");
        assertEquals("GENERATE_PLAN", adjusted.actions().get(0).type());
        assertEquals(List.of("早餐", "午餐"), adjusted.mealPlanBrief().mealTimes(), "补充餐次后使用最新简报");
    }

    @Test
    void 综合计划两侧子简报完整后才提供开始生成且单侧修改即时生效() {
        String sessionId = "sess_composite_plan_brief";
        HealthChatResponse first = chatInSession(sessionId, "一周训练和餐食计划");
        assertEquals("COMPOSITE", first.domain().name());
        assertEquals("PLAN", first.task().name());
        assertEquals(HealthResponseType.CLARIFY, first.responseType());

        // 餐食子简报完整后不再有确认动作，直接进入训练收集阶段。
        HealthChatResponse meal = chatInSession(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        assertTrue(meal.actions().isEmpty(), meal.toString());
        assertTrue(meal.speechText().contains("训练"), meal.toString());
        assertTrue(meal.mealPlanBrief().isComplete());

        HealthChatResponse training = chatInSession(sessionId,
                "重点练胸，徒手，入门，目标周 2026-08-24，周一周三周五，19:00-20:00");
        assertEquals("GENERATE_PLAN", training.actions().get(0).type());
        assertTrue(training.mealPlanBrief().isComplete(), training.toString());
        assertTrue(training.planBrief().isComplete(), training.toString());

        // 单侧修改只更新该侧并重新提供开始生成，另一侧保持不变。
        HealthChatResponse mealRevision = chatInSession(sessionId, "餐次改成只要早餐和午餐");
        assertEquals("GENERATE_PLAN", mealRevision.actions().get(0).type());
        assertEquals(List.of("早餐", "午餐"), mealRevision.mealPlanBrief().mealTimes());
        assertEquals("胸", mealRevision.planBrief().bodyParts().get(0), "餐食侧修改不得清空训练简报");

        HealthChatResponse trainingRevision = chatInSession(sessionId, "训练日改成周二、周四");
        assertEquals("GENERATE_PLAN", trainingRevision.actions().get(0).type());
        assertTrue(trainingRevision.planBrief().trainingDays().contains(java.time.DayOfWeek.TUESDAY));
        assertEquals(List.of("早餐", "午餐"), trainingRevision.mealPlanBrief().mealTimes(), "训练侧修改不得改写餐食简报");

        // 目标周表达在训练阶段写入训练简报，不得静默写入已完整的餐食侧。
        HealthChatResponse weekRevision = chatInSession(sessionId, "目标周 2026-09-07");
        org.junit.jupiter.api.Assertions.assertTrue(weekRevision.speechText().contains("2026-09-07"), weekRevision.toString());
        assertEquals("GENERATE_PLAN", weekRevision.actions().get(0).type());
        assertEquals(java.time.LocalDate.of(2026, 9, 7), weekRevision.planBrief().weekStart());
        assertEquals(List.of("早餐", "午餐"), weekRevision.mealPlanBrief().mealTimes());
    }

    @Test
    void 综合计划训练阶段的裸目标不应回写餐食简报() {
        String sessionId = "sess_composite_training_goal_isolated";
        chatInSession(sessionId, "一周训练和餐食计划");
        chatInSession(sessionId, "本周安排早餐、午餐和晚餐，想减脂");

        HealthChatResponse training = chatInSession(sessionId,
                "目标 增肌，重点练胸，徒手，入门，目标周 2026-08-24，周一周三周五，19:00-20:00");
        assertEquals("增肌", training.planBrief().trainingGoal(), training.toString());
        assertEquals("减脂", training.mealPlanBrief().healthGoal(), training.toString());
        assertTrue(training.mealPlanBrief().isComplete(), training.toString());
        assertTrue(training.planBrief().isComplete(), training.toString());
        assertEquals("GENERATE_PLAN", training.actions().get(0).type(), training.toString());
    }

    @Test
    void 训练简报完整即出现开始生成且字段纠正即时生效且普通餐食不污染简报() {
        String sessionId = "sess_plan_brief_flow";
        HealthChatResponse collected = chatInSession(sessionId,
                "帮我安排一周健身计划：我想减脂，重点练胸，徒手，入门，目标周 2026-08-24，周一周三周五，19:00-20:00");
        assertEquals(HealthResponseType.ANSWER, collected.responseType());
        assertEquals("GENERATE_PLAN", collected.nextAction().name());
        assertEquals("GENERATE_PLAN", collected.actions().get(0).type());
        assertEquals("开始生成", collected.actions().get(0).label());
        HealthSessionState collectedState = sessionService.loadOrCreate(sessionId, 1L);
        assertTrue(collectedState.planBrief().isComplete(), collectedState.toString());
        assertEquals("PLAN", collectedState.task().name(), collectedState.toString());
        assertEquals("EXERCISE", collectedState.domain().name(), collectedState.toString());

        HealthChatResponse corrected = chatInSession(sessionId, "改成 20:00-21:00");
        assertEquals("GENERATE_PLAN", corrected.nextAction().name());
        assertEquals("20:00", corrected.planBrief().timeWindow().start().toString());
        assertEquals("GENERATE_PLAN", corrected.actions().get(0).type());

        HealthChatResponse meal = chatInSession(sessionId, "午餐想吃清淡的");
        assertEquals("MEAL", meal.domain().name());
        assertFalse(meal.actions().stream().anyMatch(action -> "GENERATE_PLAN".equals(action.type())));
        HealthSessionState saved = sessionService.loadOrCreate(sessionId, 1L);
        assertEquals("20:00", saved.planBrief().timeWindow().start().toString(), "餐食推荐不能覆盖训练简报");
    }

    @Test
    void 风险信号跨轮累积并透出ADVISORY文案() {
        String sessionId = "sess_senior_advisory";
        HealthChatResponse first = chatInSession(sessionId, "65岁老人想减脂练胸，徒手入门");
        assertEquals(HealthResponseType.ANSWER, first.responseType());
        assertTrue(first.riskFlags().contains("SENIOR"));
        assertTrue(first.speechText().contains("仅供参考"), "ADVISORY 文案应透出");

        HealthChatResponse second = chatInSession(sessionId, "想减脂练背，徒手入门");
        assertEquals(HealthResponseType.ANSWER, second.responseType());
        assertTrue(second.riskFlags().contains("SENIOR"), "历史风险信号应跨轮保留");
        assertTrue(second.speechText().contains("仅供参考"), "ADVISORY 文案应透出");
    }

    @Test
    void 候选为空返回空结果提示() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of());
        HealthChatResponse response = chat("午餐想吃清淡的");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertTrue(response.displayBlocks().isEmpty());
        assertTrue(response.speechText().contains("没有匹配"));
        assertEquals(null, response.resultCode(), "普通推荐无候选不应伪装成替代推荐耗尽");
    }

    @Test
    void 替代推荐候选耗尽返回稳定结果码和显式操作() {
        HealthResource resource = new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库",
                null, false, Map.of());
        when(mealModule.recommendMeals(any(), any(), anyString()))
                .thenReturn(List.of(resource))
                .thenReturn(List.of());

        String sessionId = "sess_adjust_exhausted";
        HealthChatResponse first = chatInSession(sessionId, "午餐想吃清淡的");
        assertFalse(first.displayBlocks().isEmpty());

        HealthChatResponse exhausted = chatInSession(sessionId, "换一批");

        assertEquals(HealthOrchestratorService.CANDIDATES_EXHAUSTED, exhausted.resultCode());
        assertTrue(exhausted.displayBlocks().isEmpty());
        assertEquals(List.of("RELAX_CONSTRAINTS", "REPEAT_SHOWN"),
                exhausted.actions().stream().map(action -> action.type()).toList());
    }

    @Test
    void 同requestId重复提交返回已保存结果且只落库一次Trace() {
        String requestId = "dup-request-1";
        HealthChatResponse first = orchestrator.healthChat(1L,
                new HealthChatRequest("sess_dup", requestId, "想练胸", Map.of()));
        int tracesAfterFirst = insertedTraces.size();
        HealthChatResponse second = orchestrator.healthChat(1L,
                new HealthChatRequest("sess_dup", requestId, "想练胸", Map.of()));
        assertEquals(first.speechText(), second.speechText());
        assertEquals(first.traceId(), second.traceId());
        assertEquals(tracesAfterFirst, insertedTraces.size(), "重复请求不应新增 Trace");
    }

    @Test
    void 缺省sessionId重复requestId命中同一响应快照() {
        String requestId = "no-session-dup-1";
        HealthChatResponse first = orchestrator.healthChat(1L,
                new HealthChatRequest(null, requestId, "想练胸", Map.of()));
        HealthChatResponse second = orchestrator.healthChat(1L,
                new HealthChatRequest(null, requestId, "想练胸", Map.of()));
        assertEquals(first.sessionId(), second.sessionId(), "缺省会话按匿名身份稳定派生");
        assertEquals(first.speechText(), second.speechText(), "幂等响应快照一致");
        assertEquals(first.traceId(), second.traceId());
    }

    @Test
    void 不同匿名身份缺省会话互不相同() {
        HealthChatResponse one = orchestrator.healthChat(1L,
                new HealthChatRequest(null, "no-session-a-1", "想练胸", Map.of()));
        HealthChatResponse two = orchestrator.healthChat(2L,
                new HealthChatRequest(null, "no-session-a-2", "想练胸", Map.of()));
        assertNotEquals(one.sessionId(), two.sessionId());
    }

    @Test
    void requestId缺失抛出参数错误() {
        org.junit.jupiter.api.Assertions.assertThrows(com.diet.exception.DietException.class,
                () -> orchestrator.healthChat(1L, new HealthChatRequest(null, null, "你好", Map.of())));
    }

    @Test
    void 明确推荐Trace标记快路径且不产生意图模型调用() throws Exception {
        chat("午餐想吃清淡的");
        assertFalse(insertedTraces.isEmpty());
        JsonNode root = objectMapper.readTree(insertedTraces.get(0).getTraceJson());
        boolean hasFastPath = false;
        boolean hasAgentCall = false;
        for (JsonNode event : root.path("events")) {
            if ("INTENT_RECOGNIZED".equals(event.path("eventType").asText())) {
                JsonNode output = objectMapper.readTree(event.path("outputPayload").asText());
                hasFastPath = "FAST_PATH".equals(output.path("resolutionSource").asText());
            }
            hasAgentCall |= "AGENT_CALL".equals(event.path("eventType").asText());
        }
        assertTrue(hasFastPath, "明确请求应标记确定性快路径");
        assertFalse(hasAgentCall, "明确请求不应调用模型");
    }
}
