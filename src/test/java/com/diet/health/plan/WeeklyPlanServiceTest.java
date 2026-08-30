package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanScope;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.module.HealthResource;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一综合周计划的生命周期、归属、版本和时间校验契约。 */
class WeeklyPlanServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final long USER = 1L;

    private final WeeklyPlanMapper mapper = mock(WeeklyPlanMapper.class);
    private final HealthProfileService profileService = mock(HealthProfileService.class);
    private final HealthSessionService sessionService = mock(HealthSessionService.class);
    private final SeedResourceProvider provider = new SeedResourceProvider();
    private WeeklyPlanService service;

    private static final HealthProfileService.HealthProfileView PROFILE = new HealthProfileService.HealthProfileView(
            1L, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 1200, 1800, true, 1L, "basis", List.of(), null);

    @BeforeEach
    void setUp() {
        when(profileService.getProfile(USER)).thenReturn(PROFILE);
        when(sessionService.loadOrCreate(any(), any())).thenReturn(confirmedExerciseSession());
        when(mapper.findItems(any(), any())).thenReturn(List.of());
        when(mapper.updatePlan(any())).thenReturn(1);
        when(mapper.activatePlan(any())).thenReturn(1);
        doAnswer(invocation -> {
            WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        }).when(mapper).insertPlan(any());
        service = new WeeklyPlanService(profileService, new HealthRiskRuleService(),
                mock(WeeklyPlanComposerService.class), new PlanValidationService(), mapper, provider,
                mock(HealthPlanResponseAgentService.class),
                new AgentTraceService(mock(AgentTraceMapper.class), new ObjectMapper()), sessionService,
                new ObjectMapper());
    }

    @Test
    void 餐食或训练生成都保存为综合计划() {
        HealthResource resource = provider.planReadyExercises().get(0);
        PlanItemDraft exercise = exercise(resource, MONDAY, LocalTime.of(19, 0), LocalTime.of(20, 0));

        PlanView view = service.persistScopedGeneratedDraft(USER,
                new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null, PlanScope.EXERCISE),
                PlanScope.EXERCISE, List.of(exercise), "FALLBACK", Map.of(), "规则生成");

        assertEquals(PlanScope.COMPOSITE, view.planScope());
        assertEquals(PlanStatus.DRAFT, view.status());
        org.mockito.ArgumentCaptor<WeeklyPlanRow> captor = org.mockito.ArgumentCaptor.forClass(WeeklyPlanRow.class);
        verify(mapper).insertPlan(captor.capture());
        assertEquals("COMPOSITE", captor.getValue().getPlanScope());
    }

    @Test
    void 草稿确认后可以启用停用并转历史() {
        WeeklyPlanRow plan = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(plan);

        PlanView confirmed = service.confirm(USER, 42L, new PlanWriteRequest("confirm-1", 1L));
        assertEquals(PlanStatus.UNENABLED, confirmed.status());

        plan.setStatus("UNENABLED");
        PlanView enabled = service.enable(USER, 42L, new PlanWriteRequest("enable-1", 1L));
        assertEquals(PlanStatus.ENABLED, enabled.status());

        plan.setStatus("ENABLED");
        PlanView disabled = service.disable(USER, 42L, new PlanWriteRequest("disable-1", 2L));
        assertEquals(PlanStatus.UNENABLED, disabled.status());

        plan.setStatus("UNENABLED");
        PlanView history = service.archive(USER, 42L, new PlanWriteRequest("archive-1", 2L));
        assertEquals(PlanStatus.HISTORY, history.status());
    }

    @Test
    void 启用新计划只把旧计划变为未启用而不是历史() {
        WeeklyPlanRow target = plan(42L, "UNENABLED");
        WeeklyPlanRow old = plan(41L, "ENABLED");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(target);
        when(mapper.findActiveByUserForUpdate(USER)).thenReturn(old);

        PlanView result = service.enable(USER, 42L, new PlanWriteRequest("enable-switch", 1L));

        assertEquals(PlanStatus.ENABLED, result.status());
        assertEquals("UNENABLED", old.getStatus());
        verify(mapper, never()).deletePlan(any(), any());
    }

    @Test
    void 餐食与训练重叠被拒绝且非半小时也被拒绝() {
        WeeklyPlanRow draft = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(draft);
        HealthResource exercise = provider.planReadyExercises().get(0);
        HealthResource meal = provider.mealById(provider.planMealCandidates().get(0).resourceId()).orElseThrow();

        PlanItemsWriteRequest overlap = new PlanItemsWriteRequest("items-overlap", 1L, List.of(
                new PlanItemWrite(null, "MEAL", meal.resourceId(), meal.name(), MONDAY,
                        LocalTime.of(12, 0), LocalTime.of(13, 0), null, Map.of("caloriesKcal", 500)),
                new PlanItemWrite(null, "EXERCISE", exercise.resourceId(), exercise.name(), MONDAY,
                        LocalTime.of(12, 30), LocalTime.of(13, 30), null, Map.of("bodyPart", "胸"))));
        HealthApiException overlapError = assertThrows(HealthApiException.class,
                () -> service.updateItems(USER, 42L, overlap));
        assertEquals(HealthApiException.CODE_PLAN_TIME_CONFLICT, overlapError.code());

        PlanItemsWriteRequest granularity = new PlanItemsWriteRequest("items-granularity", 1L, List.of(
                new PlanItemWrite(null, "MEAL", meal.resourceId(), meal.name(), MONDAY,
                        LocalTime.of(12, 0), LocalTime.of(12, 30), null, Map.of("caloriesKcal", 500)),
                new PlanItemWrite(null, "EXERCISE", exercise.resourceId(), exercise.name(), MONDAY,
                        LocalTime.of(13, 15), LocalTime.of(14, 15), null, Map.of("bodyPart", "胸"))));
        HealthApiException granularityError = assertThrows(HealthApiException.class,
                () -> service.updateItems(USER, 42L, granularity));
        assertEquals(HealthApiException.CODE_PLAN_TIME_CONFLICT, granularityError.code());
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void 批量保存按Provider重读资源事实不接受客户端伪造名称和热量() {
        WeeklyPlanRow draft = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(draft);
        HealthResource meal = provider.planMealCandidates().get(0) == null ? null
                : provider.mealById(provider.planMealCandidates().get(0).resourceId()).orElseThrow();
        int canonicalCalories = meal.nutrition() == null || meal.nutrition().caloriesKcal() == null
                ? 0 : meal.nutrition().caloriesKcal().intValue();

        service.updateItems(USER, 42L, new PlanItemsWriteRequest("canonical-1", 1L, List.of(
                new PlanItemWrite(null, "MEAL", meal.resourceId(), "客户端伪造名称", MONDAY,
                        LocalTime.of(12, 0), LocalTime.of(12, 30), null,
                        Map.of("caloriesKcal", 99999, "mealTime", "伪造餐次")))));

        org.mockito.ArgumentCaptor<com.diet.model.WeeklyPlanItemRow> captor =
                org.mockito.ArgumentCaptor.forClass(com.diet.model.WeeklyPlanItemRow.class);
        verify(mapper).insertItem(captor.capture());
        assertEquals(meal.name(), captor.getValue().getName());
        assertTrue(captor.getValue().getPlanParamsJson().contains(String.valueOf(canonicalCalories))
                || canonicalCalories == 0);
        org.junit.jupiter.api.Assertions.assertFalse(captor.getValue().getPlanParamsJson().contains("99999"));
    }

    @Test
    void 替换动作时同次批量保存仍保留合法处方() {
        WeeklyPlanRow draft = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(draft);
        HealthResource previous = provider.planReadyExercises().get(0);
        HealthResource replacement = provider.planReadyExercises().get(1);
        com.diet.model.WeeklyPlanItemRow current = new com.diet.model.WeeklyPlanItemRow();
        current.setId(7L);
        current.setPlanId(42L);
        current.setVersionNo(1L);
        current.setResourceType("EXERCISE");
        current.setResourceId(previous.resourceId());
        current.setPlanParamsJson("{\"durationMinutes\":30,\"sets\":3,\"reps\":10}");
        when(mapper.findItems(any(), any())).thenReturn(List.of(current));

        service.updateItems(USER, 42L, new PlanItemsWriteRequest("replace-with-prescription", 1L, List.of(
                new PlanItemWrite(7L, "EXERCISE", replacement.resourceId(), "客户端名称", MONDAY,
                        LocalTime.of(19, 0), LocalTime.of(20, 0), "同次编辑",
                        Map.of("durationMinutes", 45, "sets", 3, "reps", 12)))));

        org.mockito.ArgumentCaptor<com.diet.model.WeeklyPlanItemRow> captor =
                org.mockito.ArgumentCaptor.forClass(com.diet.model.WeeklyPlanItemRow.class);
        verify(mapper).insertItem(captor.capture());
        com.diet.model.WeeklyPlanItemRow saved = captor.getValue();
        assertEquals(replacement.name(), saved.getName());
        assertTrue(saved.getPlanParamsJson().contains("45"));
        assertTrue(saved.getPlanParamsJson().contains("12"));
    }

    @Test
    void 越权访问和版本冲突都在写入前拒绝() {
        when(mapper.findPlanByIdForUpdate(99L, USER)).thenReturn(null);
        HealthApiException notFound = assertThrows(HealthApiException.class,
                () -> service.confirm(USER, 99L, new PlanWriteRequest("missing", 1L)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, notFound.code());

        WeeklyPlanRow plan = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(plan);
        HealthApiException stale = assertThrows(HealthApiException.class,
                () -> service.confirm(USER, 42L, new PlanWriteRequest("stale", 2L)));
        assertEquals(HealthApiException.CODE_PLAN_VERSION_CONFLICT, stale.code());
        verify(mapper, never()).updatePlan(any());
    }

    @Test
    void 同一用户的requestId不能跨计划重放() {
        WeeklyPlanRow first = plan(41L, "DRAFT");
        WeeklyPlanRow second = plan(42L, "DRAFT");
        when(mapper.findPlanByIdForUpdate(41L, USER)).thenReturn(first);
        when(mapper.findPlanByIdForUpdate(42L, USER)).thenReturn(second);

        WeeklyPlanService firstService = serviceWithWriteRequest((userId, requestId) -> {
            com.diet.model.PlanWriteRequestRow row = new com.diet.model.PlanWriteRequestRow();
            row.setUserId(userId);
            row.setRequestId(requestId);
            row.setPlanId(41L);
            row.setOperation("CONFIRM");
            row.setResponseJson("{}");
            return row;
        });
        HealthApiException error = assertThrows(HealthApiException.class,
                () -> firstService.confirm(USER, 42L, new PlanWriteRequest("same-request", 1L)));
        assertEquals(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT, error.code());
        verify(mapper, never()).updatePlan(any());
    }

    @Test
    void 旧通用草稿入口不能绕过已确认简报() {
        HealthApiException error = assertThrows(HealthApiException.class,
                () -> service.createDraft(USER, new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null)));
        assertEquals(HealthApiException.CODE_CONFLICT, error.code());
        assertTrue(error.getMessage().contains("简报"));
        verify(mapper, never()).insertPlan(any());
    }

    @Test
    void generationNotes从生成metadata透传到计划详情() {
        HealthResource resource = provider.planReadyExercises().get(0);
        PlanItemDraft exercise = exercise(resource, MONDAY, LocalTime.of(19, 0), LocalTime.of(20, 0));
        Map<String, Object> notes = new java.util.LinkedHashMap<>();
        notes.put("unsupportedPreferences", List.of("cuisine:中餐"));
        notes.put("fallbacks", List.of(java.util.Map.of(
                "date", MONDAY.toString(),
                "mealTimes", List.of("早餐"),
                "unmetPreferences", List.of("cuisine:川菜"))));
        notes.put("candidateScarcity", List.of("符合全部条件的候选动作只有 1 个，已按指定训练日复用候选。"));
        Map<String, Object> metadata = Map.of(GenerationNotes.METADATA_KEY, notes);

        PlanView view = service.persistScopedGeneratedDraft(USER,
                new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null, PlanScope.EXERCISE),
                PlanScope.EXERCISE, List.of(exercise), "FALLBACK", metadata, "规则生成");

        org.junit.jupiter.api.Assertions.assertNotNull(view.generationNotes());
        assertEquals(List.of("cuisine:中餐"), view.generationNotes().unsupportedPreferences());
        assertEquals(1, view.generationNotes().fallbacks().size());
        assertEquals(MONDAY.toString(), view.generationNotes().fallbacks().get(0).date());
        assertEquals(List.of("cuisine:川菜"), view.generationNotes().fallbacks().get(0).unmetPreferences());
        assertEquals(List.of("符合全部条件的候选动作只有 1 个，已按指定训练日复用候选。"),
                view.generationNotes().candidateScarcity());
    }

    @Test
    void 旧计划缺metadata时generationNotes返回非null空对象() {
        HealthResource resource = provider.planReadyExercises().get(0);
        PlanItemDraft exercise = exercise(resource, MONDAY, LocalTime.of(19, 0), LocalTime.of(20, 0));
        PlanView view = service.persistScopedGeneratedDraft(USER,
                new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null, PlanScope.EXERCISE),
                PlanScope.EXERCISE, List.of(exercise), "FALLBACK", null, "规则生成");
        assertEquals(GenerationNotes.empty(), view.generationNotes());
        org.junit.jupiter.api.Assertions.assertNotNull(view.generationNotes());
    }

    @Test
    void 版本详情返回对应版本PlanView且generationNotes来自版本快照() {
        WeeklyPlanRow plan = plan(42L, "DRAFT");
        when(mapper.findPlanById(42L, USER)).thenReturn(plan);
        com.diet.model.WeeklyPlanVersionRow version = new com.diet.model.WeeklyPlanVersionRow();
        version.setPlanId(42L);
        version.setVersionNo(2L);
        version.setProfileVersionNo(1L);
        version.setRulesVersion(PlanValidationService.RULES_VERSION);
        version.setValidationJson("[]");
        version.setResourceSnapshotJson("{\"generation\":{\"generationNotes\":{"
                + "\"unsupportedPreferences\":[\"cuisine:中餐\"],"
                + "\"fallbacks\":[{\"date\":\"" + MONDAY + "\",\"mealTimes\":[\"早餐\"],"
                + "\"unmetPreferences\":[\"cuisine:川菜\"]}],"
                + "\"candidateScarcity\":[\"符合全部条件的候选动作只有 1 个，已按指定训练日复用候选。\"]}}}");
        when(mapper.findVersion(42L, 2L)).thenReturn(version);
        when(mapper.findVersion(42L, 9L)).thenReturn(null);

        PlanView view = service.getPlanVersion(USER, 42L, 2L);
        assertEquals(2L, view.currentVersion());
        assertEquals(List.of("cuisine:中餐"), view.generationNotes().unsupportedPreferences());
        assertEquals(List.of("cuisine:川菜"), view.generationNotes().fallbacks().get(0).unmetPreferences());
        assertEquals(List.of("符合全部条件的候选动作只有 1 个，已按指定训练日复用候选。"),
                view.generationNotes().candidateScarcity(), "版本详情必须与当前计划快照一致");

        HealthApiException missing = assertThrows(HealthApiException.class,
                () -> service.getPlanVersion(USER, 42L, 9L));
        assertEquals(HealthApiException.CODE_NOT_FOUND, missing.code());
    }

    private PlanItemDraft exercise(HealthResource resource, LocalDate date, LocalTime start, LocalTime end) {
        String bodyPart = resource.tags().getOrDefault("primaryBodyPart", List.of("全身")).get(0);
        return new PlanItemDraft("EXERCISE", resource.resourceId(), resource.name(), date, start, end, null,
                Map.of("bodyPart", bodyPart, "sets", 2, "reps", 10, "durationMinutes", 60));
    }

    private HealthSessionState confirmedExerciseSession() {
        PlanBrief brief = new PlanBrief("保持健康", List.of("胸"), List.of("徒手"), "入门", MONDAY,
                List.of(DayOfWeek.MONDAY), new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                Map.of(), null, 0, null);
        return HealthSessionState.fresh("sess", USER).withPlanBrief(brief);
    }

    private WeeklyPlanRow plan(Long id, String status) {
        WeeklyPlanRow row = new WeeklyPlanRow();
        row.setId(id);
        row.setUserId(USER);
        row.setPlanScope("COMPOSITE");
        row.setName("演示计划");
        row.setStatus(status);
        row.setWeekStart(MONDAY);
        row.setTimezone("Asia/Shanghai");
        row.setProfileVersionNo(1L);
        row.setCalorieLow(1200);
        row.setCalorieHigh(1800);
        row.setRulesVersion(PlanValidationService.RULES_VERSION);
        row.setValidationLevel("OK");
        row.setCurrentVersion(1L);
        return row;
    }

    private WeeklyPlanService serviceWithWriteRequest(
            java.util.function.BiFunction<Long, String, com.diet.model.PlanWriteRequestRow> lookup) {
        com.diet.mapper.PlanWriteRequestMapper writeMapper = mock(com.diet.mapper.PlanWriteRequestMapper.class);
        when(writeMapper.find(any(), any())).thenAnswer(invocation -> lookup.apply(
                invocation.getArgument(0), invocation.getArgument(1)));
        return new WeeklyPlanService(profileService, new HealthRiskRuleService(),
                mock(WeeklyPlanComposerService.class), new PlanValidationService(), mapper, provider,
                mock(HealthPlanResponseAgentService.class),
                new AgentTraceService(mock(AgentTraceMapper.class), new ObjectMapper()), sessionService,
                new ObjectMapper(), new PlanScopeGuard(), writeMapper);
    }
}
