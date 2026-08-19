package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                        && item.endTime().equals(LocalTime.of(19, 45))));
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
        AgentTraceService traceService = new AgentTraceService(traceMapper, objectMapper);
        AgentContractModule contract = new AgentContractModule(invoker, new LlmJsonService(objectMapper), traceService);
        return new TrainingPlanGenerationService(sessionService, profileService, new HealthRiskRuleService(),
                new SeedResourceProvider(), new PlanValidationService(), mock(com.diet.health.plan.WeeklyPlanComposerService.class),
                weeklyPlanService, contract, new PromptLoader(), traceService, objectMapper, "fixture-model", 1000);
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
