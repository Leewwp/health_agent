package com.diet.health.orchestrator;

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
import com.diet.health.enums.HealthResponseType;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
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
import com.diet.model.SessionMessageRow;
import com.diet.model.SessionRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.diet.util.LlmJsonService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 简报生命周期与推荐前预检的编排器级行为（简报补充回路规格 v3.2）：
 * 生成关闭、生成后“谢谢”、显式计划词重开、社交短句保留简报、预检 1/2/3 候选、
 * 确认后直出、ADJUST/作息直出、替代推荐不重复预检。
 */
class HealthBriefLifecycleOrchestratorTest {

    /** 内存版 SessionMapper：支持多轮会话状态续接与行锁语义。 */
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
    private final FakeSessionMapper sessionMapper = new FakeSessionMapper();
    private final PreferenceService preferenceService = new PreferenceService(mock(FeedbackMapper.class));
    private final MealModule mealModule = mock(MealModule.class);
    private final HealthProfileService profileService = mock(HealthProfileService.class);
    private HealthSessionService sessionService;
    private HealthOrchestratorService orchestrator;
    /** 预检开启的编排器（生产入口形态，带档案服务）。 */
    private HealthOrchestratorService preflightOrchestrator;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            RequestTraceRow row = invocation.getArgument(0);
            return 1;
        }).when(traceMapper).insert(any());
        when(traceMapper.findByRequestId(any(), any(), any())).thenReturn(null);
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
        HealthClarifyAgentService clarify = new HealthClarifyAgentService(contract, new PromptLoader(),
                new HealthClarifyRuleService(), "qwen-turbo", "v1", 1000);
        HealthRecommendResponseService recommend = new HealthRecommendResponseService(contract, new PromptLoader(),
                "qwen-max", "v1", 1000);

        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of())
        ));

        orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intent, new HealthIntentRevisionService(normalizer, new HealthBriefRouter()),
                normalizer, new HealthClarifyRuleService(), clarify,
                new HealthRiskRuleService(), mealModule, new ExerciseModule(new SeedResourceProvider(), preferenceService),
                new RoutineModule(new SeedResourceProvider()), new SeedResourceProvider(),
                recommend, trace, objectMapper);

        preflightOrchestrator = new HealthOrchestratorService(
                sessionService, new SessionService(sessionMapper, new JsonService(objectMapper), 10),
                new HealthIntentAgentService(
                        new AgentContractModule(new FixtureAgentInvoker(), new LlmJsonService(objectMapper),
                                new AgentTraceService(traceMapper, objectMapper)),
                        new PromptLoader(), new HealthSlotDictionary(TestSupport.slotOptionService()),
                        new IntentRuleService(new HealthInputNormalizer()), new HealthInputNormalizer(),
                        "qwen-turbo", "v1", 1000),
                new HealthIntentRevisionService(new HealthInputNormalizer(), new HealthBriefRouter()),
                new HealthInputNormalizer(),
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
                profileService, null, null);
    }

    private HealthChatResponse chat(String sessionId, String message) {
        return orchestrator.healthChat(1L, new HealthChatRequest(sessionId, "req-" + System.nanoTime(), message, Map.of()));
    }

    private HealthChatResponse preflightChat(String sessionId, String message) {
        return preflightOrchestrator.healthChat(1L, new HealthChatRequest(sessionId, "req-" + System.nanoTime(), message, Map.of()));
    }

    @Test
    void 简报完成后补充口味菜系与烹饪时长均进入简报() {
        String sessionId = "sess-lifecycle-supplement";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");

        HealthChatResponse taste = chat(sessionId, "我喜欢清淡的餐食");
        assertEquals("MEAL", taste.domain().name(), "活跃简报中偏好补充不得误路由为单次推荐");
        assertEquals("PLAN", taste.task().name());
        assertEquals("GENERATE_PLAN", taste.actions().get(0).type(), "可选偏好永不阻断生成");
        assertEquals(List.of("清淡"), taste.mealPlanBrief().tastePreferences());

        HealthChatResponse cuisine = chat(sessionId, "我喜欢中餐、川菜");
        assertEquals("PLAN", cuisine.task().name());
        assertEquals("川菜", cuisine.mealPlanBrief().cuisine());
        assertTrue(cuisine.mealPlanBrief().unsupportedPreferences().contains("cuisine:中餐"));
        assertTrue(cuisine.speechText().contains("川菜"), "未支持回应给出可选菜系列表");

        HealthChatResponse foodType = chat(sessionId, "我想吃素");
        assertEquals(List.of("素食"), foodType.mealPlanBrief().foodTypes());

        HealthChatResponse convenience = chat(sessionId, "烹饪时间短一些");
        assertEquals("快速", convenience.mealPlanBrief().convenience());

        // 摘要与补充项更新：口味/菜系/烹饪时长已填后不再出现在可补充项
        HealthChatResponse done = chat(sessionId, "确认一下");
        assertEquals(HealthResponseType.ANSWER, done.responseType());
        assertTrue(done.speechText().contains("清淡"));
        assertTrue(done.supplementable().isEmpty(), done.supplementable().toString());
    }

    @Test
    void 社交短句只返回确认并保留简报() {
        String sessionId = "sess-social-ack";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        HealthChatResponse ack = chat(sessionId, "谢谢");
        assertEquals(HealthResponseType.ANSWER, ack.responseType());
        assertTrue(ack.speechText().contains("保留"), ack.speechText());
        HealthSessionState state = sessionService.loadOrCreate(sessionId, 1L);
        assertTrue(state.mealPlanBrief().isComplete(), "社交短句保留简报");
        assertEquals("OPEN", state.briefLifecycle().get("MEAL"));
        assertEquals("GENERATE_PLAN", chat(sessionId, "好的呀").actions().get(0).type(),
                "社交确认后仍可直接开始生成");
    }

    @Test
    void 生成后谢谢不重新捕获简报且显式计划词重开() {
        String sessionId = "sess-generated-close";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        // 模拟生成成功后的会话关闭（生成入口回写 GENERATED）
        sessionService.markBriefGenerated(1L, sessionId, List.of("MEAL"));

        HealthChatResponse thanks = chat(sessionId, "谢谢");
        assertTrue(thanks.speechText().contains("已生成") || thanks.speechText().contains("再调整"),
                thanks.speechText());
        HealthSessionState state = sessionService.loadOrCreate(sessionId, 1L);
        assertEquals("GENERATED", state.briefLifecycle().get("MEAL"));

        // 显式计划语句重新打开对应简报，已收集条件不丢失
        HealthChatResponse reopen = chat(sessionId, "再调整餐食计划");
        assertEquals("MEAL", reopen.domain().name());
        assertEquals("PLAN", reopen.task().name());
        assertEquals("GENERATE_PLAN", reopen.actions().get(0).type());
        assertEquals("OPEN", sessionService.loadOrCreate(sessionId, 1L).briefLifecycle().get("MEAL"));
        assertEquals(List.of("早餐", "午餐", "晚餐"), reopen.mealPlanBrief().mealTimes());
    }

    @Test
    void 显式切域暂停简报且作息提问返回事实卡() {
        String sessionId = "sess-pause-routine";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");

        HealthChatResponse routine = chat(sessionId, "晚上几点前停止喝咖啡？");
        assertEquals("ROUTINE", routine.domain().name());
        assertFalse(routine.displayBlocks().isEmpty());
        assertEquals("PAUSED", sessionService.loadOrCreate(sessionId, 1L).briefLifecycle().get("MEAL"),
                "显式切换其他领域暂停简报而不是关闭");

        HealthChatResponse resume = chat(sessionId, "回到餐食计划");
        assertEquals("MEAL", resume.domain().name());
        assertEquals("PLAN", resume.task().name());
        assertEquals("OPEN", sessionService.loadOrCreate(sessionId, 1L).briefLifecycle().get("MEAL"));
        assertTrue(sessionService.loadOrCreate(sessionId, 1L).mealPlanBrief().isComplete());
    }

    @Test
    void 明确推荐词从活跃简报放行到单次推荐() {
        String sessionId = "sess-escape-recommend";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        HealthChatResponse recommend = chat(sessionId, "有什么推荐吗");
        assertEquals("MEAL", recommend.domain().name());
        assertEquals("RECOMMEND", recommend.task().name());
        // 简报保留（同领域推荐不暂停）
        assertEquals("OPEN", sessionService.loadOrCreate(sessionId, 1L).briefLifecycle().get("MEAL"));
    }

    @Test
    void 换一批保持替代推荐通道() {
        String sessionId = "sess-escape-alternative";
        chat(sessionId, "一周餐食计划");
        chat(sessionId, "下周安排早餐、午餐和晚餐，想减脂");
        HealthChatResponse alternative = chat(sessionId, "换一批");
        assertEquals("ADJUST", alternative.task().name(), "替代推荐/换一批保持 ADJUST 通道");
        assertEquals("OPEN", sessionService.loadOrCreate(sessionId, 1L).briefLifecycle().get("MEAL"),
                "替代推荐不改变简报生命周期");
    }

    @Test
    void 综合简报显式前缀跨侧修改且两侧完整后无前缀要求澄清() {
        String sessionId = "sess-composite-prefix";
        chat(sessionId, "一周训练和餐食计划");
        chat(sessionId, "本周安排早餐、午餐和晚餐，想减脂");
        HealthChatResponse training = chat(sessionId,
                "重点练胸，徒手，入门，目标周 2026-08-24，周一周三周五，19:00-20:00");
        assertEquals("GENERATE_PLAN", training.actions().get(0).type());

        // 显式“餐食：”前缀跨侧补充餐食口味
        HealthChatResponse mealPrefix = chat(sessionId, "餐食：口味清淡");
        assertEquals("COMPOSITE", mealPrefix.domain().name());
        assertEquals(List.of("清淡"), mealPrefix.mealPlanBrief().tastePreferences(), mealPrefix.toString());
        assertEquals("GENERATE_PLAN", mealPrefix.actions().get(0).type(), "跨侧修改后重新展示生成入口");

        // 显式“训练：”前缀跨侧修改训练部位
        HealthChatResponse trainingPrefix = chat(sessionId, "训练：改成练背");
        assertEquals("GENERATE_PLAN", trainingPrefix.actions().get(0).type());
        assertTrue(trainingPrefix.planBrief().bodyParts().contains("背"), trainingPrefix.toString());
        assertEquals(List.of("清淡"), trainingPrefix.mealPlanBrief().tastePreferences(), "训练侧修改不改写餐食简报");
    }

    // ---------- 推荐前预检矩阵（预检开启的生产形态） ----------

    @Test
    void 候选一二三个时均先预检() {
        for (int candidateCount : List.of(1, 2, 3)) {
            String sessionId = "sess-preflight-" + candidateCount;
            when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(new ArrayList<>(
                    List.of(
                            new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                            new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of()),
                            new HealthResource("MEAL", "9", "番茄豆腐", "PUBLIC", "公共餐食库", null, false, Map.of())
                    )).subList(0, candidateCount));
            when(profileService.getProfile(1L)).thenReturn(mock(HealthProfileService.HealthProfileView.class));

            HealthChatResponse response = preflightChat(sessionId, "今晚想吃清淡的");
            assertEquals(HealthResponseType.ANSWER, response.responseType(), "候选 " + candidateCount);
            assertTrue(response.displayBlocks().isEmpty(), "候选 " + candidateCount + " 不得直接展示");
            assertEquals("CONFIRM_RECOMMENDATION", response.actions().get(0).type());
            assertEquals("开始推荐", response.actions().get(0).label(), "按钮规范标签 ADR-0016");
            assertEquals("补充", response.actions().get(1).label());
            assertFalse(response.recommendationConfirmed());
        }
    }

    @Test
    void 确认后直出且槽位变化后重新预检() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of())));
        when(profileService.getProfile(1L)).thenReturn(mock(HealthProfileService.HealthProfileView.class));
        String sessionId = "sess-preflight-confirm";

        HealthChatResponse preflight = preflightChat(sessionId, "今晚想吃清淡的");
        assertEquals("CONFIRM_RECOMMENDATION", preflight.actions().get(0).type());

        // 确认短语清单包含规范短语“开始推荐”
        HealthChatResponse confirmed = preflightChat(sessionId, "开始推荐");
        assertFalse(confirmed.displayBlocks().isEmpty(), "确认后直出结果");
        assertTrue(confirmed.recommendationConfirmed());
        HealthSessionState state = sessionService.loadOrCreate(sessionId, 1L);
        assertEquals(Boolean.TRUE, state.recommendationConfirmed());
        assertTrue(state.recommendationConfirmationKey() != null && !state.recommendationConfirmationKey().isBlank(),
                "确认指纹写入会话 _meta");

        // 同条件再次推荐：确认指纹命中，不重复预检
        HealthChatResponse again = preflightChat(sessionId, "今晚想吃清淡的");
        assertFalse(again.displayBlocks().isEmpty(), "确认指纹命中后直出");
    }

    @Test
    void ADJUST与替代推荐和作息事实直出不预检() {
        when(mealModule.recommendMeals(any(), any(), anyString())).thenReturn(List.of(
                new HealthResource("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of()),
                new HealthResource("MEAL", "7", "鸡胸肉沙拉", "PUBLIC", "公共餐食库", null, false, Map.of())));
        when(profileService.getProfile(1L)).thenReturn(mock(HealthProfileService.HealthProfileView.class));
        String sessionId = "sess-preflight-adjust";

        // 先完成一次推荐建立会话历史（预检 → 确认直出）
        preflightChat(sessionId, "今晚想吃清淡的");
        HealthChatResponse direct = preflightChat(sessionId, "开始推荐");
        assertFalse(direct.displayBlocks().isEmpty());

        // ADJUST（换一批）直出，不重复预检
        HealthChatResponse adjust = preflightChat(sessionId, "换一批");
        assertEquals("ADJUST", adjust.task().name());
        assertFalse(adjust.displayBlocks().isEmpty(), "替代推荐不重复预检");

        // 作息事实问答直出事实卡
        HealthChatResponse routine = preflightChat(sessionId, "晚上几点前停止喝咖啡？");
        assertEquals("ROUTINE", routine.domain().name());
        assertTrue(routine.displayBlocks().stream().allMatch(block -> "ROUTINE".equals(block.resourceType())));
    }

    @Test
    void 零候选仍提供追加条件流() {
        // 只有追加“素食”后检索才有候选：验证追加条件动作经目录验证确实能产生候选
        when(mealModule.recommendMeals(any(), any(), anyString())).thenAnswer(invocation -> {
            Map<String, List<String>> slots = invocation.getArgument(0);
            if (slots.getOrDefault("cuisine", List.of()).contains("素食")) {
                return List.of(new HealthResource("MEAL", "8", "素食沙拉", "PUBLIC", "公共餐食库", null, false, Map.of()));
            }
            return List.of();
        });
        when(mealModule.availableSlotValues()).thenReturn(Map.of("cuisine", List.of("素食")));
        when(profileService.getProfile(1L)).thenReturn(mock(HealthProfileService.HealthProfileView.class));
        HealthChatResponse empty = preflightChat("sess-preflight-empty", "午餐想吃清淡的");
        assertTrue(empty.displayBlocks().isEmpty());
        assertFalse(empty.actions().isEmpty(), "零候选走追加条件流");
        assertTrue(empty.actions().stream().anyMatch(action -> "APPEND_SLOT".equals(action.type())));
    }
}
