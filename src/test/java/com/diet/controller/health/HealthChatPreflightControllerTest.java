package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.intent.HealthBriefRouter;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.health.intent.HealthSlotDictionary;
import com.diet.health.intent.IntentRuleService;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.orchestrator.HealthOrchestratorService;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionMessageRow;
import com.diet.model.SessionRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推荐前预检 Controller 层真实 API 回归（简报补充回路规格 v3.2）：
 * 档案存在的前提下，候选 1/2/3 个均先预检、确认后直出、ADJUST 直出。
 * 使用真实编排器 + 内存会话 Mapper + MockMvc（standalone），档案服务为已配置档案的 Mock。
 */
class HealthChatPreflightControllerTest {

    private static final class FakeSessionMapper implements SessionMapper {
        private final Map<String, SessionRow> rows = new java.util.HashMap<>();

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
    private final MealModule mealModule = mock(MealModule.class);
    private MockMvc mockMvc;
    private String sessionIdRef;

    @BeforeEach
    void setUp() {
        when(traceMapper.findByRequestId(any(), any(), any())).thenReturn(null);
        when(traceMapper.findByTraceId(any(), any())).thenReturn(null);
        when(traceMapper.findBySessionId(any(), any(), any(java.lang.Integer.class))).thenReturn(List.of());
        when(traceMapper.findByTimeRange(any(), any(), any(), any(java.lang.Boolean.class), any(java.lang.Integer.class)))
                .thenReturn(List.of());
        when(traceMapper.updateLabel(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        FakeSessionMapper sessionMapper = new FakeSessionMapper();
        AgentTraceService trace = new AgentTraceService(traceMapper, objectMapper);
        SessionService messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
        HealthSessionService sessionService = new HealthSessionService(sessionMapper, objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(sessionService, "sessionSecret", "test-secret");
        AgentContractModule contract = new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper), trace);
        HealthInputNormalizer normalizer = new HealthInputNormalizer();
        HealthProfileService profileService = mock(HealthProfileService.class);
        when(profileService.getProfile(any())).thenReturn(mock(HealthProfileService.HealthProfileView.class));

        HealthOrchestratorService orchestrator = new HealthOrchestratorService(
                sessionService, messageService,
                new HealthIntentAgentService(contract, new PromptLoader(),
                        new HealthSlotDictionary(TestSupport.slotOptionService()),
                        new IntentRuleService(normalizer), normalizer, "qwen-turbo", "v1", 1000),
                new HealthIntentRevisionService(normalizer, new HealthBriefRouter()), normalizer,
                new HealthClarifyRuleService(),
                new HealthClarifyAgentService(contract, new PromptLoader(), new HealthClarifyRuleService(),
                        "qwen-turbo", "v1", 1000),
                new HealthRiskRuleService(), mealModule,
                new ExerciseModule(new SeedResourceProvider(), new PreferenceService(mock(FeedbackMapper.class))),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                new HealthRecommendResponseService(contract, new PromptLoader(), "qwen-max", "v1", 1000),
                trace, objectMapper, profileService, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthChatController(orchestrator)).build();
    }

    private void stubCandidates(int count) {
        List<HealthResourceStub> all = List.of(
                new HealthResourceStub("5", "清蒸鲈鱼"),
                new HealthResourceStub("7", "鸡胸肉沙拉"),
                new HealthResourceStub("9", "番茄豆腐"));
        when(mealModule.recommendMeals(any(), any(), any())).thenReturn(all.subList(0, count).stream()
                .map(stub -> new com.diet.health.module.HealthResource("MEAL", stub.id(), stub.name(),
                        "PUBLIC", "公共餐食库", null, false, Map.of()))
                .toList());
    }

    private MvcResult chat(String message) throws Exception {
        return mockMvc.perform(post("/api/v1/health/chat")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-" + System.nanoTime() + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 候选一二三个时Controller层均先预检() throws Exception {
        for (int count : List.of(1, 2, 3)) {
            stubCandidates(count);
            String body = chat("今晚想吃清淡的").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            org.junit.jupiter.api.Assertions.assertEquals("ANSWER", node.path("responseType").asText(), "候选 " + count);
            org.junit.jupiter.api.Assertions.assertTrue(node.path("displayBlocks").isEmpty(), "候选 " + count + " 不得直出");
            org.junit.jupiter.api.Assertions.assertEquals("CONFIRM_RECOMMENDATION",
                    node.path("actions").get(0).path("type").asText());
            org.junit.jupiter.api.Assertions.assertEquals("开始推荐", node.path("actions").get(0).path("label").asText());
            sessionIdRef = node.path("sessionId").asText();
        }
    }

    @Test
    void 确认后直出且ADJUST直出() throws Exception {
        stubCandidates(3);
        chat("今晚想吃清淡的");
        String confirmed = chat("开始推荐").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(confirmed);
        org.junit.jupiter.api.Assertions.assertFalse(node.path("displayBlocks").isEmpty(), "确认后直出");
        org.junit.jupiter.api.Assertions.assertTrue(node.path("recommendationConfirmed").asBoolean());

        String adjust = chat("换一批").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode adjustNode = objectMapper.readTree(adjust);
        org.junit.jupiter.api.Assertions.assertEquals("ADJUST", adjustNode.path("task").asText(), "替代推荐直出");
        org.junit.jupiter.api.Assertions.assertFalse(adjustNode.path("displayBlocks").isEmpty());
    }

    private record HealthResourceStub(String id, String name) {
    }
}
