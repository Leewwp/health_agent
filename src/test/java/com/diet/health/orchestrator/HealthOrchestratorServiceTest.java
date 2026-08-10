package com.diet.health.orchestrator;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthSlotDictionary;
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
        when(traceMapper.updateLabel(any(), anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        AgentTraceService trace = new AgentTraceService(traceMapper, objectMapper);
        SessionService messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
        HealthSessionService sessionService = new HealthSessionService(sessionMapper, objectMapper);
        ReflectionTestUtils.setField(sessionService, "sessionSecret", "test-secret");
        AgentContractModule contract = new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper), trace);
        HealthSlotDictionary dictionary = new HealthSlotDictionary(TestSupport.slotOptionService());
        HealthIntentAgentService intent = new HealthIntentAgentService(contract, new PromptLoader(), dictionary, new IntentRuleService(dictionary), "qwen-turbo", "v1", 1000);
        HealthClarifyAgentService clarify = new HealthClarifyAgentService(contract, new PromptLoader(), new HealthClarifyRuleService(), "qwen-turbo", "v1", 1000);
        HealthRecommendResponseService recommend = new HealthRecommendResponseService(contract, new PromptLoader(), "qwen-max", "v1", 1000);

        mealModule = mock(MealModule.class);
        when(mealModule.recommendMeals(any(), any())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));

        orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intent, new HealthClarifyRuleService(), clarify,
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
    void 健身链路返回planReady动作与署名() {
        HealthChatResponse response = chat("想练胸");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertEquals("EXERCISE", response.domain().name());
        assertFalse(response.displayBlocks().isEmpty());
        assertTrue(response.displayBlocks().get(0).planReady());
        assertEquals("Gym visual", response.displayBlocks().get(0).sourceName());
        assertEquals("俯卧撑", response.displayBlocks().get(0).name());
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
    void 信息不足先澄清再继续会话() {
        String sessionId = "sess_clarify_continue";
        HealthChatResponse first = chatInSession(sessionId, "帮我推荐");
        assertEquals(HealthResponseType.CLARIFY, first.responseType());
        assertEquals(List.of("mealTime"), first.missingSlots());
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
        assertEquals(List.of("bodyParts"), first.missingSlots());

        HealthChatResponse second = chatInSession(sessionId, "练背");
        assertEquals(HealthResponseType.ANSWER, second.responseType());
        assertTrue(second.displayBlocks().stream().anyMatch(block -> block.name().contains("划船")));
    }

    @Test
    void 风险信号被拦截返回固定文案() {
        HealthChatResponse response = chat("我怀孕了怎么安排饮食");
        assertEquals(HealthResponseType.BLOCKED, response.responseType());
        assertEquals(HealthRiskRuleService.BLOCK_PLAN_COPY, response.speechText());
        assertTrue(response.riskFlags().contains("PREGNANCY"));
    }

    @Test
    void 风险信号跨轮累积并透出ADVISORY文案() {
        String sessionId = "sess_senior_advisory";
        HealthChatResponse first = chatInSession(sessionId, "65岁老人想练胸");
        assertEquals(HealthResponseType.CLARIFY, first.responseType());
        assertTrue(first.riskFlags().contains("SENIOR"));

        HealthChatResponse second = chatInSession(sessionId, "练胸");
        assertEquals(HealthResponseType.ANSWER, second.responseType());
        assertTrue(second.riskFlags().contains("SENIOR"), "历史风险信号应跨轮保留");
        assertTrue(second.speechText().contains("仅供参考"), "ADVISORY 文案应透出");
    }

    @Test
    void 候选为空返回空结果提示() {
        when(mealModule.recommendMeals(any(), any())).thenReturn(List.of());
        HealthChatResponse response = chat("午餐想吃清淡的");
        assertEquals(HealthResponseType.ANSWER, response.responseType());
        assertTrue(response.displayBlocks().isEmpty());
        assertTrue(response.speechText().contains("没有匹配"));
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
    void Trace记录角色契约版本与解析状态() throws Exception {
        chat("午餐想吃清淡的");
        assertFalse(insertedTraces.isEmpty());
        JsonNode root = objectMapper.readTree(insertedTraces.get(0).getTraceJson());
        boolean hasIntentCall = false;
        boolean hasContractMeta = false;
        for (JsonNode event : root.path("events")) {
            if ("AGENT_CALL".equals(event.path("eventType").asText())
                    && "IntentAgent".equals(event.path("agentName").asText())) {
                hasIntentCall = true;
                JsonNode input = objectMapper.readTree(event.path("inputPayload").asText());
                hasContractMeta = "intent-v1".equals(input.path("contractVersion").asText())
                        && input.path("promptVersion").isTextual();
                break;
            }
        }
        assertTrue(hasIntentCall, "Trace 应包含 IntentAgent 的 AGENT_CALL 事件");
        assertTrue(hasContractMeta, "AGENT_CALL 输入应携带契约与 Prompt 版本");
    }
}
