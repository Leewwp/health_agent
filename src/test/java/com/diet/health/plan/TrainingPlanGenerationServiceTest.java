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
            new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)), Map.of(), true, 1, null);
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

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        sessionService = mock(HealthSessionService.class);
        profileService = mock(HealthProfileService.class);
        weeklyPlanService = mock(WeeklyPlanService.class);
        persistedItems = new ArrayList<>();
        traceMapper = new FakeAgentTraceMapper(objectMapper);
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(BRIEF));
        when(profileService.getProfile(1L)).thenReturn(PROFILE);
        when(weeklyPlanService.persistGeneratedDraft(anyLong(), any(), any(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    persistedItems.clear();
                    persistedItems.addAll(invocation.getArgument(2));
                    String source = invocation.getArgument(3);
                    persistedMetadata = invocation.getArgument(4);
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
                true, 2, java.time.LocalDateTime.now());
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
    void 全身减脂入门在严格目标无候选时拒绝错误匹配() {
        PlanBrief wholeBodyFatLoss = new PlanBrief(
                "减脂", List.of("全身"), List.of("徒手"), "入门", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), true, 2, java.time.LocalDateTime.now());
        when(sessionService.loadOrCreate(anyString(), anyLong())).thenReturn(
                HealthSessionState.fresh("sess-training", 1L).withPlanBrief(wholeBodyFatLoss));

        assertThrows(com.diet.exception.HealthApiException.class, () -> createService("{\"schedule\":["
                + schedule("9001", MONDAY) + "]}").generate(1L,
                new GenerateTrainingPlanRequest("sess-training", "training-request-relaxed-goal")));
    }

    @Test
    void 精确难度无候选时拒绝错误匹配() {
        PlanBrief challenge = new PlanBrief(
                "增肌", List.of("胸"), List.of("徒手"), "挑战", BRIEF.weekStart(),
                BRIEF.trainingDays(), BRIEF.timeWindow(), Map.of(), true, 2, java.time.LocalDateTime.now());
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
                weeklyPlanService, contract, new PromptLoader(), traceService, objectMapper, "fixture-model", timeoutMs);
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
