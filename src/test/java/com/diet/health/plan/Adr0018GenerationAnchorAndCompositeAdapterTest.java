package com.diet.health.plan;

import com.diet.health.intent.AmbiguityArbitrationAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0018 生成边界与综合适配接线（票据 03/04）：
 * 简报缺内部周锚点也能生成并派生“当天所在周的周一”；综合生成把餐食送入
 * 训练优先适配器并记录适配说明（metadata/解释/版本）。
 */
class Adr0018GenerationAnchorAndCompositeAdapterTest {

    private static final long USER = 1L;

    private static final com.diet.health.profile.HealthProfileService.HealthProfileView PROFILE =
            new com.diet.health.profile.HealthProfileService.HealthProfileView(
                    USER, 30, com.diet.health.enums.ProfileSex.MALE, 175.0, 70.0,
                    com.diet.health.enums.ActivityLevel.LIGHT, com.diet.health.enums.ProfileGoal.MAINTAIN,
                    "Asia/Shanghai", 1200, 1800, true, 1L, "basis", List.of(), null);

    @Test
    void 餐食生成简报缺锚点时派生当天所在周周一并持久化() {
        java.util.List<com.diet.model.WeeklyPlanRow> insertedPlans = new java.util.ArrayList<>();
        com.diet.mapper.WeeklyPlanMapper planMapper = mock(com.diet.mapper.WeeklyPlanMapper.class);
        com.diet.mapper.PlanWriteRequestMapper writeMapper = mock(com.diet.mapper.PlanWriteRequestMapper.class);
        com.diet.health.session.HealthSessionService sessionService = mock(com.diet.health.session.HealthSessionService.class);
        com.diet.health.profile.HealthProfileService profileService = mock(com.diet.health.profile.HealthProfileService.class);
        WeeklyPlanComposerService composer = mock(WeeklyPlanComposerService.class);
        when(profileService.getProfile(USER)).thenReturn(PROFILE);
        com.diet.health.session.HealthSessionState session = com.diet.health.session.HealthSessionState.fresh("s-anchor", USER)
                .withIntent(com.diet.health.enums.HealthDomain.MEAL, com.diet.health.enums.HealthTask.PLAN, List.of())
                .withMealPlanBrief(new MealPlanBrief(null, List.of("早餐", "午餐"), "减脂"));
        when(sessionService.loadOrCreate(anyString(), eq(USER))).thenReturn(session);
        LocalDate derived = WeekAnchorProvider.currentMonday(PROFILE.timezone());
        when(composer.composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any()))
                .thenAnswer(invocation -> {
                    LocalDate weekStart = invocation.getArgument(2);
                    assertTrue(weekStart.getDayOfWeek() == java.time.DayOfWeek.MONDAY, "派生锚点必须是周一");
                    assertEquals(derived, weekStart, "简报缺锚点时按生成边界统一派生");
                    return new WeeklyPlanComposerService.MealCompositionResult(List.of(
                            mealItem("M1", "燕麦牛奶粥", weekStart, 320, LocalTime.of(8, 0))), GenerationNotes.empty());
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            com.diet.model.WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(7L);
            insertedPlans.add(row);
            return 1;
        }).when(planMapper).insertPlan(any());
        when(planMapper.findItems(any(), any())).thenReturn(List.of());

        WeeklyPlanService weeklyPlanService = new WeeklyPlanService(profileService,
                new com.diet.health.risk.HealthRiskRuleService(), composer, new PlanValidationService(),
                planMapper, new com.diet.health.resource.SeedResourceProvider(),
                mock(HealthPlanResponseAgentService.class),
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), new PlanScopeGuard(),
                writeMapper);
        GenerationIdempotencyService idempotencyService =
                new GenerationIdempotencyService(writeMapper, sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        MealPlanGenerationService service = new MealPlanGenerationService(sessionService, profileService, composer,
                weeklyPlanService, idempotencyService,
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        TrainingPlanGenerationResponse response = service.generate(USER,
                new GenerateTrainingPlanRequest("s-anchor", "anchor-gen-1", com.diet.health.enums.PlanScope.MEAL));
        assertEquals("SUCCESS", response.status());
        assertEquals(1, insertedPlans.size());
        assertEquals(derived, insertedPlans.get(0).getWeekStart(), "持久化使用派生内部锚点");
    }

    @Test
    void 综合生成把冲突晚餐自动移入训练结束后并写入说明与元数据() {
        com.diet.mapper.WeeklyPlanMapper planMapper = mock(com.diet.mapper.WeeklyPlanMapper.class);
        com.diet.mapper.PlanWriteRequestMapper writeMapper = mock(com.diet.mapper.PlanWriteRequestMapper.class);
        com.diet.health.session.HealthSessionService sessionService = mock(com.diet.health.session.HealthSessionService.class);
        com.diet.health.profile.HealthProfileService profileService = mock(com.diet.health.profile.HealthProfileService.class);
        WeeklyPlanComposerService composer = mock(WeeklyPlanComposerService.class);
        TrainingPlanGenerationService trainingGeneration = mock(TrainingPlanGenerationService.class);
        when(profileService.getProfile(USER)).thenReturn(PROFILE);
        LocalDate weekStart = WeekAnchorProvider.currentMonday(PROFILE.timezone());
        com.diet.health.session.HealthSessionState session = com.diet.health.session.HealthSessionState.fresh("s-comp", USER)
                .withIntent(com.diet.health.enums.HealthDomain.COMPOSITE, com.diet.health.enums.HealthTask.PLAN, List.of())
                .withPlanBrief(completeTrainingBrief(weekStart))
                .withMealPlanBrief(new MealPlanBrief(null, List.of("早餐", "午餐", "晚餐"), "减脂"));
        when(sessionService.loadOrCreate(anyString(), eq(USER))).thenReturn(session);
        // 训练项目：周一 18:00-19:00（与晚餐默认窗口完全冲突）
        when(trainingGeneration.generateExerciseItemsForComposite(eq(USER), any(PlanBrief.class))).thenReturn(List.of(
                exerciseItem("9001", "杠铃卧推", weekStart, LocalTime.of(18, 0), LocalTime.of(19, 0))));
        when(composer.composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any()))
                .thenReturn(new WeeklyPlanComposerService.MealCompositionResult(List.of(
                        mealItem("M1", "燕麦牛奶粥", weekStart, 320, LocalTime.of(8, 0)),
                        mealItem("M4", "鸡胸肉糙米饭", weekStart, 750, LocalTime.of(12, 0)),
                        mealItem("M9", "清蒸鲈鱼", weekStart, 720, LocalTime.of(18, 0))), GenerationNotes.empty()));
        java.util.List<com.diet.model.WeeklyPlanItemRow> insertedItems = new java.util.ArrayList<>();
        java.util.List<com.diet.model.WeeklyPlanRow> insertedPlans = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            com.diet.model.WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(9L);
            insertedPlans.add(row);
            return 1;
        }).when(planMapper).insertPlan(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, com.diet.model.WeeklyPlanVersionRow.class);
            return 1;
        }).when(planMapper).insertVersion(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            insertedItems.add(invocation.getArgument(0));
            return 1;
        }).when(planMapper).insertItem(any());
        when(planMapper.findItems(any(), any())).thenReturn(List.of());
        when(planMapper.findVersion(any(), any())).thenReturn(null);
        when(planMapper.listPlans(any())).thenReturn(List.of());
        when(planMapper.findPlanById(any(), any())).thenReturn(null);
        when(planMapper.findPlanByIdForUpdate(any(), any())).thenReturn(null);

        WeeklyPlanService weeklyPlanService = new WeeklyPlanService(profileService,
                new com.diet.health.risk.HealthRiskRuleService(), composer, new PlanValidationService(),
                planMapper, new com.diet.health.resource.SeedResourceProvider(),
                mock(HealthPlanResponseAgentService.class),
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), new PlanScopeGuard(),
                writeMapper);
        GenerationIdempotencyService idempotencyService =
                new GenerationIdempotencyService(writeMapper, sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        CompositePlanGenerationService composite = new CompositePlanGenerationService(sessionService, profileService,
                trainingGeneration, composer, weeklyPlanService, idempotencyService,
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        TrainingPlanGenerationResponse response = composite.generate(USER,
                new GenerateTrainingPlanRequest("s-comp", "comp-adapt-1", com.diet.health.enums.PlanScope.COMPOSITE));
        assertEquals("SUCCESS", response.status());
        // 晚餐自动移动到训练结束后 19:00-20:00（训练时间保持用户输入 18:00-19:00）
        com.diet.model.WeeklyPlanItemRow dinner = insertedItems.stream()
                .filter(item -> "清蒸鲈鱼".equals(item.getName()))
                .findFirst().orElseThrow();
        assertEquals(LocalTime.of(19, 0), dinner.getStartTime());
        assertEquals(LocalTime.of(20, 0), dinner.getEndTime());
        java.util.Map<String, Object> params = java.util.Map.of();
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parsed = dinner.getPlanParamsJson() == null
                    ? java.util.Map.of()
                    : new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .readValue(dinner.getPlanParamsJson(), java.util.Map.class);
            params = parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // 参数 JSON 异常时保持空集合，断言会在下方失败
        }
        assertEquals(MealTrainingScheduleAdapter.ADAPTED_SOURCE, params.get("mealTimeSource"),
                "适配餐食携带 mealTimeSource=ADAPTED 结构化标记");
        // 生成说明包含适配结果（用户可见）
        assertTrue(response.plan().explanation() != null
                && (response.plan().explanation().contains("训练后")
                || response.plan().explanation().contains("自动适配")), response.plan().explanation());
        // 版本快照与 metadata 含适配记录
        assertTrue(insertedPlans.get(0).getGenerationMetadataJson().contains("mealTimeAdaptations"),
                insertedPlans.get(0).getGenerationMetadataJson());
        assertTrue(insertedPlans.get(0).getGenerationMetadataJson().contains("generationNotes"),
                "适配记录进入 generationNotes 结构化合同");
    }

    @Test
    void 综合生成无解时整体失败不落库() {
        com.diet.mapper.WeeklyPlanMapper planMapper = mock(com.diet.mapper.WeeklyPlanMapper.class);
        com.diet.mapper.PlanWriteRequestMapper writeMapper = mock(com.diet.mapper.PlanWriteRequestMapper.class);
        com.diet.health.session.HealthSessionService sessionService = mock(com.diet.health.session.HealthSessionService.class);
        com.diet.health.profile.HealthProfileService profileService = mock(com.diet.health.profile.HealthProfileService.class);
        WeeklyPlanComposerService composer = mock(WeeklyPlanComposerService.class);
        TrainingPlanGenerationService trainingGeneration = mock(TrainingPlanGenerationService.class);
        when(profileService.getProfile(USER)).thenReturn(PROFILE);
        LocalDate weekStart = WeekAnchorProvider.currentMonday(PROFILE.timezone());
        com.diet.health.session.HealthSessionState session = com.diet.health.session.HealthSessionState.fresh("s-fail", USER)
                .withIntent(com.diet.health.enums.HealthDomain.COMPOSITE, com.diet.health.enums.HealthTask.PLAN, List.of())
                .withPlanBrief(completeTrainingBrief(weekStart))
                .withMealPlanBrief(new MealPlanBrief(null, List.of("午餐"), "减脂"));
        when(sessionService.loadOrCreate(anyString(), eq(USER))).thenReturn(session);
        // 训练 05:00-23:59 覆盖整天，午餐无可行时段
        when(trainingGeneration.generateExerciseItemsForComposite(eq(USER), any(PlanBrief.class))).thenReturn(List.of(
                exerciseItem("9001", "全天训练", weekStart, LocalTime.of(5, 0), LocalTime.of(23, 59))));
        when(composer.composeMealsWithPreferences(anyInt(), anyInt(), any(), anyList(), any()))
                .thenReturn(new WeeklyPlanComposerService.MealCompositionResult(List.of(
                        mealItem("M4", "鸡胸肉糙米饭", weekStart, 750, LocalTime.of(12, 0))), GenerationNotes.empty()));
        org.mockito.Mockito.doAnswer(invocation -> {
            com.diet.model.WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        }).when(planMapper).insertPlan(any());
        when(planMapper.findItems(any(), any())).thenReturn(List.of());
        WeeklyPlanService weeklyPlanService = new WeeklyPlanService(profileService,
                new com.diet.health.risk.HealthRiskRuleService(), composer, new PlanValidationService(),
                planMapper, new com.diet.health.resource.SeedResourceProvider(),
                mock(HealthPlanResponseAgentService.class),
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), new PlanScopeGuard(),
                writeMapper);
        CompositePlanGenerationService composite = new CompositePlanGenerationService(sessionService, profileService,
                trainingGeneration, composer, weeklyPlanService,
                new GenerationIdempotencyService(writeMapper, sessionService, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new com.diet.service.trace.AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        com.diet.exception.HealthApiException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.diet.exception.HealthApiException.class, () -> composite.generate(USER,
                        new GenerateTrainingPlanRequest("s-fail", "comp-fail-1", com.diet.health.enums.PlanScope.COMPOSITE)));
        assertEquals(MealTrainingScheduleAdapter.NO_FEASIBLE_SLOT_COPY, error.getMessage());
        org.mockito.Mockito.verify(planMapper, org.mockito.Mockito.never()).insertPlan(any());
    }

    private PlanBrief completeTrainingBrief(LocalDate weekStart) {
        return new PlanBrief("减脂", List.of("胸"), List.of("徒手"), "入门", weekStart,
                List.of(java.time.DayOfWeek.MONDAY),
                new TrainingTimeWindow(LocalTime.of(18, 0), LocalTime.of(19, 0)),
                Map.of(), null, 0, null);
    }

    private PlanItemDraft mealItem(String id, String name, LocalDate date, int calories, LocalTime start) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("mealTime", start.equals(LocalTime.of(8, 0)) ? "早餐"
                : start.equals(LocalTime.of(12, 0)) ? "午餐" : "晚餐");
        params.put("caloriesKcal", calories);
        return new PlanItemDraft("MEAL", id, name, date, start, start.plusMinutes(
                start.equals(LocalTime.of(8, 0)) ? 30 : 60), null, params);
    }

    private PlanItemDraft exerciseItem(String id, String name, LocalDate date, LocalTime start, LocalTime end) {
        return new PlanItemDraft("EXERCISE", id, name, date, start, end, null,
                Map.of("bodyPart", "胸", "sets", 3, "reps", 10));
    }
}