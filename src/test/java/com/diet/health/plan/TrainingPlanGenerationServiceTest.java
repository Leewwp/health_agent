package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.AgentTimeoutException;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.module.HealthResource;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 受约束训练计划生成：候选白名单、Guard、fallback、来源和 requestId 幂等。 */
class TrainingPlanGenerationServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final PlanBrief BRIEF = new PlanBrief(
            "保持健康", List.of("胸"), List.of("徒手"), "入门", MONDAY,
            List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY),
            new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)), Map.of(), null, 0, null);
    private static final HealthProfileService.HealthProfileView PROFILE = new HealthProfileService.HealthProfileView(
            1L, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 1200, 1800, true, 1L, "basis", List.of(), null);

    private ObjectMapper objectMapper;
    private HealthSessionService sessionService;
    private HealthProfileService profileService;
    private WeeklyPlanService weeklyPlanService;
    private FakeAgentTraceMapper traceMapper;
    private TrainingPlanGenerationService service;
    private List<PlanItemDraft> persistedItems;
    private Map<String, Object> persistedMetadata;
    private String persistedExplanation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        sessionService = mock(HealthSessionService.class);
        profileService = mock(HealthProfileService.class);
        weeklyPlanService = mock(WeeklyPlanService.class);
        persistedItems = new ArrayList<>();
        persistedExplanation = null;
        traceMapper = new FakeAgentTraceMapper(objectMapper);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(BRIEF));
        when(profileService.getProfile(1L)).thenReturn(PROFILE);
        when(weeklyPlanService.persistGeneratedDraft(anyLong(), any(), any(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    persistedItems.clear();
                    persistedItems.addAll(invocation.getArgument(2));
                    String source = invocation.getArgument(3);
                    persistedMetadata = invocation.getArgument(4);
                    persistedExplanation = invocation.getArgument(5);
                    return new PlanView(42L, PlanStatus.DRAFT, MONDAY, "Asia/Shanghai", 1L,
                            1200, 1800, PlanValidationService.RULES_VERSION, PlanValidationLevel.OK,
                            List.of(), null, 1L, List.of(), false, "已生成", source, null);
                });
    }

    @Test
    void 合法Agent输出只写入候选动作且剂量由规则生成() {
        TrainingPlanGenerationService service = createService("{\"schedule\":["
                + schedule("9001", MONDAY) + "," + schedule("9006", MONDAY.plusDays(2)) + ","
                + schedule("9001", MONDAY.plusDays(4)) + "]}");

        TrainingPlanGenerationResponse response = service.generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-1"));

        assertEquals("AGENT", response.generationSource());
        assertEquals(3, persistedItems.stream().filter(PlanItemDraft::isExercise).count());
        assertTrue(persistedItems.stream().filter(PlanItemDraft::isExercise)
                .allMatch(item -> item.planParams().containsKey("sets") && item.planParams().containsKey("reps")));
        assertTrue(persistedItems.stream().filter(PlanItemDraft::isExercise)
                .allMatch(item -> item.startTime().equals(LocalTime.of(19, 0))));
        assertEquals("AGENT", persistedMetadata.get("generationSource"));
        assertEquals("fixture-model", persistedMetadata.get("actualModel"));
    }

    @Test
    void 非法Agent输出立即使用同一简报的fallback且不回退固定日期() {
        TrainingPlanGenerationService service = createService("{\"schedule\":["
                + schedule("unknown-exercise", MONDAY) + "]}");

        TrainingPlanGenerationResponse response = service.generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-fallback"));

        assertEquals("FALLBACK", response.generationSource());
        List<PlanItemDraft> exercises = persistedItems.stream().filter(PlanItemDraft::isExercise).toList();
        assertEquals(List.of(MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(4)),
                exercises.stream().map(PlanItemDraft::localDate).toList());
        assertTrue(exercises.stream().allMatch(item -> item.localDate().isAfter(LocalDate.of(2026, 8, 1))));
    }

    @Test
    void 跨越时间窗口末端的Agent排期必须降级() {
        TrainingPlanGenerationService service = createService("{\"schedule\":["
                + "{\"exerciseId\":\"9001\",\"localDate\":\"" + MONDAY
                + "\",\"startTime\":\"19:30\",\"durationMinutes\":60}]}");

        TrainingPlanGenerationResponse response = service.generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-window"));

        assertEquals("FALLBACK", response.generationSource());
        assertTrue(persistedItems.stream().filter(PlanItemDraft::isExercise)
                .allMatch(item -> item.startTime().equals(LocalTime.of(19, 0))
                        && item.endTime().equals(LocalTime.of(20, 0))));
    }

    @Test
    void 同requestId复用已保存响应而不再次写入() {
        TrainingPlanGenerationService service = createService("{\"schedule\":["
                + schedule("9001", MONDAY) + "," + schedule("9006", MONDAY.plusDays(2)) + ","
                + schedule("9001", MONDAY.plusDays(4)) + "]}");
        GenerateTrainingPlanRequest request = new GenerateTrainingPlanRequest("sess-training", "training-request-idempotent");

        TrainingPlanGenerationResponse first = service.generate(1L, request);
        int persistedCount = traceMapper.rows.size();
        TrainingPlanGenerationResponse second = service.generate(1L, request);

        assertEquals(first.traceId(), second.traceId());
        assertEquals(first.planId(), second.planId());
        assertEquals(persistedCount, traceMapper.rows.size());
    }

    @Test
    void 应用截止时间耗尽后拒绝持久化计划() {
        AtomicReference<java.time.Duration> modelBudget = new AtomicReference<>();
        AgentInvoker slowInvoker = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                modelBudget.set(invocation.timeout());
                try {
                    Thread.sleep(650);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return new AgentInvocationResult("{\"schedule\":[" + schedule("9001", MONDAY) + "]}",
                        "resolved-model", 650L);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        TrainingPlanGenerationService service = createService(slowInvoker, 500);

        com.diet.exception.HealthApiException error = assertThrows(com.diet.exception.HealthApiException.class,
                () -> service.generate(1L, new GenerateTrainingPlanRequest("sess-training", "training-request-deadline")));

        assertEquals(com.diet.exception.HealthApiException.CODE_TIMEOUT, error.code());
        assertTrue(modelBudget.get().toMillis() < 500, "模型预算必须小于应用截止时间");
        org.mockito.Mockito.verifyNoInteractions(weeklyPlanService);
    }

    @Test
    void 模型未配置时元数据不伪造实际调用模型() {
        AgentInvoker missingConfig = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                throw new AssertionError("缺少配置时不应调用模型");
            }

            @Override
            public boolean configured() {
                return false;
            }
        };

        TrainingPlanGenerationResponse response = createService(missingConfig, 1000).generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-missing-config"));

        assertEquals("FALLBACK", response.generationSource());
        assertEquals("fixture-model", persistedMetadata.get("requestedModel"));
        assertEquals("", persistedMetadata.get("actualModel"));
    }

    @Test
    void 非法Json不重试并使用同一简报降级() {
        TrainingPlanGenerationResponse response = createService("这不是 JSON").generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-invalid-json"));

        assertEquals("FALLBACK", response.generationSource());
        assertTrue(String.valueOf(persistedMetadata.get("fallbackReason")).contains("INVALID_JSON"));
        assertEquals(List.of(MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(4)),
                persistedItems.stream().filter(PlanItemDraft::isExercise).map(PlanItemDraft::localDate).toList());
    }

    @Test
    void 模型在预算内超时时返回可用fallback() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentInvoker timedOut = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                calls.incrementAndGet();
                throw new AgentTimeoutException("timeout", null);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };

        TrainingPlanGenerationResponse response = createService(timedOut, 1000).generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-model-timeout"));

        assertEquals("FALLBACK", response.generationSource());
        assertEquals(1, calls.get(), "模型失败不得重试");
        assertTrue(String.valueOf(persistedMetadata.get("fallbackReason")).contains("TIMEOUT"));
    }

    @Test
    void 档案风险阻断时不调用模型也不持久化() {
        when(profileService.getProfile(1L)).thenReturn(new HealthProfileService.HealthProfileView(
                1L, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
                "Asia/Shanghai", 1200, 1800, true, 1L, "basis",
                List.of(com.diet.health.enums.ProfileRiskCondition.CURRENT_INJURY), "肩部受伤"));
        AgentInvoker invoker = mock(AgentInvoker.class);
        when(invoker.configured()).thenReturn(true);

        com.diet.exception.HealthApiException error = assertThrows(com.diet.exception.HealthApiException.class,
                () -> createService(invoker, 1000).generate(1L,
                        new GenerateTrainingPlanRequest("sess-training", "training-request-risk")));

        assertEquals(com.diet.exception.HealthApiException.CODE_RISK_BLOCKED, error.code());
        org.mockito.Mockito.verifyNoInteractions(weeklyPlanService);
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.never()).invoke(any());
    }

    @Test
    void 排除动作不会进入Agent候选或fallback结果() {
        PlanBrief excluded = new PlanBrief(
                BRIEF.trainingGoal(), BRIEF.bodyParts(), BRIEF.equipment(), BRIEF.difficulty(), BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of("excludeExercises", List.of("9001")),
                null, 0, null);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(excluded));

        TrainingPlanGenerationResponse response = createService("{\"schedule\":[" + schedule("9001", MONDAY) + "]}")
                .generate(1L, new GenerateTrainingPlanRequest("sess-training", "training-request-excluded"));

        assertEquals("FALLBACK", response.generationSource());
        assertTrue(persistedItems.stream().filter(PlanItemDraft::isExercise)
                .noneMatch(item -> "9001".equals(item.resourceId())));
        assertTrue(((List<?>) persistedMetadata.get("candidateIds")).stream().noneMatch("9001"::equals));
    }

    @Test
    void 全身简报零候选时不自动放宽并说明约束维度() {
        // 全身 + 哑铃在审核库中没有候选：旧实现会把任意单部位哑铃动作当作全身候选，新实现必须拒绝。
        PlanBrief wholeBodyDumbbell = new PlanBrief(
                "保持健康", List.of("全身"), List.of("哑铃"), "入门", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), null, 0, null);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(wholeBodyDumbbell));

        com.diet.exception.HealthApiException error = assertThrows(com.diet.exception.HealthApiException.class,
                () -> createService("{\"schedule\":[" + schedule("9007", MONDAY) + "]}").generate(1L,
                        new GenerateTrainingPlanRequest("sess-training", "training-request-relaxed-goal")));
        assertEquals(com.diet.exception.HealthApiException.CODE_CONFLICT, error.code());
        assertTrue(error.getMessage().contains("部位"), error.getMessage());
        assertTrue(error.getMessage().contains("难度"), error.getMessage());
        org.mockito.Mockito.verify(weeklyPlanService, org.mockito.Mockito.never())
                .persistGeneratedDraft(anyLong(), any(), any(), anyString(), any(), anyString(),
                        anyString(), anyString(), anyString());
    }

    @Test
    void 全身简报只命中明确全身标签且单部位动作被拒绝() {
        // Agent 输出胸部/腿部动作：不在全身候选白名单内，必须降级到唯一全身候选 9009 并复用。
        PlanBrief wholeBody = new PlanBrief(
                "保持健康", List.of("全身"), List.of("徒手"), "入门", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), null, 0, null);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(wholeBody));

        TrainingPlanGenerationResponse response = createService("{\"schedule\":["
                + schedule("9001", MONDAY) + "," + schedule("9002", MONDAY.plusDays(2)) + ","
                + schedule("9004", MONDAY.plusDays(4)) + "]}")
                .generate(1L, new GenerateTrainingPlanRequest("sess-training", "training-request-whole-body"));

        assertEquals("FALLBACK", response.generationSource(), "单部位动作不得进入全身计划");
        List<PlanItemDraft> exercises = persistedItems.stream().filter(PlanItemDraft::isExercise).toList();
        assertEquals(List.of(MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(4)),
                exercises.stream().map(PlanItemDraft::localDate).toList(), "所有指定训练日都必须有安排");
        assertTrue(exercises.stream().allMatch(item -> "9009".equals(item.resourceId())),
                "全身简报只能使用明确归一为全身的候选");
        assertTrue(persistedExplanation.contains("候选不足") || persistedExplanation.contains("复用"),
                "候选不足必须明确说明：" + persistedExplanation);
    }

    @Test
    void 唯一候选按全部指定训练日复用并明确提示候选不足() {
        // 背 + 弹力带 + 力量 只命中 9007：候选数少于训练日数，允许复用且必须说明候选不足。
        PlanBrief backBand = new PlanBrief(
                "力量", List.of("背"), List.of("弹力带"), "入门", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), null, 0, null);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(backBand));

        TrainingPlanGenerationResponse response = createService("{\"schedule\":["
                + schedule("9007", MONDAY) + "," + schedule("9007", MONDAY.plusDays(2)) + ","
                + schedule("9007", MONDAY.plusDays(4)) + "]}")
                .generate(1L, new GenerateTrainingPlanRequest("sess-training", "training-request-unique-candidate"));

        assertEquals("AGENT", response.generationSource());
        List<PlanItemDraft> exercises = persistedItems.stream().filter(PlanItemDraft::isExercise).toList();
        assertEquals(List.of(MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(4)),
                exercises.stream().map(PlanItemDraft::localDate).toList());
        assertTrue(exercises.stream().allMatch(item -> "9007".equals(item.resourceId())),
                "唯一候选允许覆盖全部指定训练日");
        assertTrue(persistedExplanation.contains("候选不足") || persistedExplanation.contains("复用"),
                "唯一候选复用必须说明候选不足：" + persistedExplanation);
    }

    @Test
    void 精确难度无候选时拒绝错误匹配() {
        PlanBrief challenge = new PlanBrief(
                "增肌", List.of("胸"), List.of("徒手"), "挑战", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), null, 0, null);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(challenge));

        assertThrows(com.diet.exception.HealthApiException.class, () -> createService("{\"schedule\":["
                + schedule("9001", MONDAY) + "]}").generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-relaxed-difficulty")));
    }

    private TrainingPlanGenerationService createService(String output) {
        AgentInvoker invoker = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult(output, invocation.modelName(), 4L);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        return createService(invoker, 1000);
    }

    private TrainingPlanGenerationService createService(AgentInvoker invoker, long timeoutMs) {
        AgentTraceService traceService = new AgentTraceService(traceMapper, objectMapper);
        AgentContractModule contract = new AgentContractModule(invoker, new LlmJsonService(objectMapper), traceService);
        return new TrainingPlanGenerationService(sessionService, profileService, new HealthRiskRuleService(),
                new SeedResourceProvider(), new PlanValidationService(), mock(com.diet.health.plan.WeeklyPlanComposerService.class),
                weeklyPlanService, new GenerationIdempotencyService(null, sessionService, objectMapper),
                contract, new PromptLoader(), traceService, objectMapper, "fixture-model", timeoutMs);
    }

    private String schedule(String exerciseId, LocalDate date) {
        return "{\"exerciseId\":\"" + exerciseId + "\",\"localDate\":\"" + date
                + "\",\"startTime\":\"19:00\",\"durationMinutes\":45}";
    }

    private static final class FakeAgentTraceMapper implements AgentTraceMapper {
        private final ObjectMapper objectMapper;
        private final List<RequestTraceRow> rows = new ArrayList<>();

        private FakeAgentTraceMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public int insert(RequestTraceRow row) {
            row.setId((long) rows.size() + 1);
            rows.add(row);
            return 1;
        }

        @Override
        public RequestTraceRow findByRequestId(Long userId, String sessionId, String requestId) {
            return rows.stream().filter(row -> userId.equals(row.getUserId())
                    && requestId.equals(row.getRequestId()) && sessionId.equals(row.getSessionId())
                    && row.getResponseJson() != null).findFirst().orElse(null);
        }

        @Override public RequestTraceRow findByTraceId(Long userId, String traceId) { return null; }
        @Override public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, int limit) { return List.of(); }
        @Override public List<RequestTraceRow> findByTimeRange(Long userId, java.time.LocalDateTime startAt,
                                                                java.time.LocalDateTime endAt, boolean onlyUnlabeled, int limit) { return List.of(); }
        @Override public int updateLabel(Long userId, String traceId, String expectedIntent, String expectedSlots,
                                         String expectedClarifyAction, Long labeledBy, String labelNote,
                                         String evaluationSchemaVersion, String expectedHealthJson) { return 0; }
    }
}
