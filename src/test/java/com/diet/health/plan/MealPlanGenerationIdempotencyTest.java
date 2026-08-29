package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanScope;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.PlanWriteRequestMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.PlanWriteRequestRow;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成写入的耐久幂等与失败恢复（简报补充回路规格 v3.2）：
 * 相同 requestId 只生成一份草稿；跨 session/scope 冲突；生命周期回写失败返回 5xx，
 * 重试经 GENERATE_MEAL 写记录补偿回写并恢复原响应。
 */
class MealPlanGenerationIdempotencyTest {

    private static final long USER = 1L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FakePlanWriteRequestMapper writeRequestMapper = new FakePlanWriteRequestMapper();
    private final WeeklyPlanMapper planMapper = mock(WeeklyPlanMapper.class);
    private final HealthProfileService profileService = mock(HealthProfileService.class);
    private final HealthSessionService sessionService = mock(HealthSessionService.class);
    private final WeeklyPlanComposerService composer = mock(WeeklyPlanComposerService.class);

    private MealPlanGenerationService service;

    private static final HealthProfileService.HealthProfileView PROFILE = new HealthProfileService.HealthProfileView(
            USER, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 1200, 1800, true, 1L, "basis", List.of(), null);

    @BeforeEach
    void setUp() {
        when(profileService.getProfile(USER)).thenReturn(PROFILE);
        when(sessionService.loadOrCreate(any(), eq(USER))).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            return completeMealSession(sessionId == null ? "sess-default" : sessionId);
        });
        when(composer.composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any()))
                .thenReturn(new WeeklyPlanComposerService.MealCompositionResult(
                        List.of(mealItem("M1", "燕麦牛奶粥", MONDAY, 320, LocalTime.of(8, 0)),
                                mealItem("M4", "鸡胸肉糙米饭", MONDAY.plusDays(1), 750, LocalTime.of(12, 0))),
                        new GenerationNotes(List.of("cuisine:中餐"), List.of(
                                new GenerationNotes.FallbackDay(MONDAY.toString(), List.of("早餐", "午餐"),
                                        List.of("cuisine:家常"))))));
        doAnswer(invocation -> {
            WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        }).when(planMapper).insertPlan(any());
        when(planMapper.findItems(any(), any())).thenReturn(List.of());
        when(planMapper.updatePlan(any())).thenReturn(1);

        WeeklyPlanService weeklyPlanService = new WeeklyPlanService(profileService,
                new HealthRiskRuleService(), composer, new PlanValidationService(), planMapper,
                new SeedResourceProvider(), mock(HealthPlanResponseAgentService.class),
                new AgentTraceService(mock(AgentTraceMapper.class), objectMapper), sessionService,
                objectMapper, new PlanScopeGuard(), writeRequestMapper);
        GenerationIdempotencyService idempotencyService =
                new GenerationIdempotencyService(writeRequestMapper, sessionService, objectMapper);
        service = new MealPlanGenerationService(sessionService, profileService, composer, weeklyPlanService,
                idempotencyService, new AgentTraceService(mock(AgentTraceMapper.class), objectMapper), objectMapper);
        ReflectionTestUtils.setField(idempotencyService, "writeRequestMapper", writeRequestMapper);
    }

    @Test
    void 相同requestId只生成一份草稿并补偿生命周期() {
        TrainingPlanGenerationResponse first = service.generate(USER,
                new GenerateTrainingPlanRequest("sess-meal", "gen-idem-1", PlanScope.MEAL));
        assertEquals(1, writeRequestMapper.rows.size(), "生成写记录只落一条");
        assertEquals(GenerationIdempotencyService.scopesFor("GENERATE_MEAL"), List.of("MEAL"));
        verify(sessionService).markBriefGenerated(eq(USER), eq("sess-meal"), eq(List.of("MEAL")));

        TrainingPlanGenerationResponse second = service.generate(USER,
                new GenerateTrainingPlanRequest("sess-meal", "gen-idem-1", PlanScope.MEAL));
        assertEquals(first.planId(), second.planId(), "幂等重放返回已有计划，不重新生成");
        assertEquals(1, writeRequestMapper.rows.size(), "不新增写记录");
        verify(composer, times(1)).composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any());
        // 重放路径再次补偿生命周期回写（幂等）
        verify(sessionService, times(2)).markBriefGenerated(eq(USER), eq("sess-meal"), eq(List.of("MEAL")));
        // 生成说明进入响应中的 PlanView（metadata 透传）
        assertNotNull(second.plan().generationNotes());
        assertEquals(List.of("cuisine:中餐"), second.plan().generationNotes().unsupportedPreferences());
    }

    @Test
    void 同一requestId跨范围使用返回幂等冲突() {
        PlanWriteRequestRow existing = new PlanWriteRequestRow();
        existing.setUserId(USER);
        existing.setRequestId("gen-cross-scope");
        existing.setPlanId(7L);
        existing.setOperation("GENERATE_EXERCISE");
        existing.setResponseJson("{}");
        writeRequestMapper.rows.put("gen-cross-scope", existing);

        HealthApiException error = assertThrows(HealthApiException.class, () -> service.generate(USER,
                new GenerateTrainingPlanRequest("sess-meal", "gen-cross-scope", PlanScope.MEAL)));
        assertEquals(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT, error.code());
    }

    @Test
    void 同一requestId跨会话使用返回幂等冲突() {
        service.generate(USER, new GenerateTrainingPlanRequest("sess-meal", "gen-cross-session", PlanScope.MEAL));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.generate(USER,
                new GenerateTrainingPlanRequest("sess-other", "gen-cross-session", PlanScope.MEAL)));
        assertEquals(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT, error.code());
    }

    @Test
    void 生命周期回写失败返回5xx且重试经写记录补偿成功() {
        // 第一次：计划已提交（写记录已存在），会话回写失败 → 请求 5xx
        doThrow(new com.diet.exception.DietException("会话回写失败"))
                .doNothing()
                .when(sessionService)
                .markBriefGenerated(anyLong(), anyString(), anyList());

        assertThrows(RuntimeException.class, () -> service.generate(USER,
                new GenerateTrainingPlanRequest("sess-meal", "gen-recover-1", PlanScope.MEAL)));
        assertEquals(1, writeRequestMapper.rows.size(), "计划与幂等记录已提交，可恢复");

        // 重试：命中生成写记录 → 补偿式生命周期回写 → 恢复原响应，不重新生成
        TrainingPlanGenerationResponse replayed = service.generate(USER,
                new GenerateTrainingPlanRequest("sess-meal", "gen-recover-1", PlanScope.MEAL));
        assertEquals(42L, replayed.planId());
        verify(composer, times(1)).composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any());
        assertTrue(writeRequestMapper.rows.containsKey("gen-recover-1"));
    }

    private HealthSessionState completeMealSession(String sessionId) {
        MealPlanBrief brief = new MealPlanBrief(MONDAY, List.of("早餐", "午餐"), "减脂");
        return HealthSessionState.fresh(sessionId, USER).withMealPlanBrief(brief);
    }

    private PlanItemDraft mealItem(String id, String name, LocalDate date, int kcal, LocalTime start) {
        return new PlanItemDraft("MEAL", id, name, date, start, start.plusMinutes(30), null,
                Map.of("mealTime", "早餐", "caloriesKcal", kcal));
    }

    /** 内存版写请求 Mapper：模拟 (user_id, request_id) 主键唯一约束。 */
    private static final class FakePlanWriteRequestMapper implements PlanWriteRequestMapper {
        private final Map<String, PlanWriteRequestRow> rows = new ConcurrentHashMap<>();

        @Override
        public PlanWriteRequestRow find(Long userId, String requestId) {
            return rows.get(requestId);
        }

        @Override
        public int insert(PlanWriteRequestRow row) {
            if (rows.containsKey(row.getRequestId())) {
                throw new org.springframework.dao.DuplicateKeyException("duplicate");
            }
            rows.put(row.getRequestId(), row);
            return 1;
        }
    }
}
