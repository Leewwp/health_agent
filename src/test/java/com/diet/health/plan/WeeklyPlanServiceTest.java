package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanScope;
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
import com.diet.model.WeeklyPlanItemRow;
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

/** 计划范围写入 Guard：旧混合草稿入口已移除，范围和项目类型必须严格一致。 */
class WeeklyPlanServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private final WeeklyPlanMapper mapper = mock(WeeklyPlanMapper.class);
    private final HealthProfileService profileService = mock(HealthProfileService.class);
    private final HealthSessionService sessionService = mock(HealthSessionService.class);
    private WeeklyPlanService service;

    private static final HealthProfileService.HealthProfileView PROFILE = new HealthProfileService.HealthProfileView(
            1L, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 1200, 1800, true, 1L, "basis", List.of(), null);

    @BeforeEach
    void setUp() {
        SeedResourceProvider provider = new SeedResourceProvider();
        when(profileService.getProfile(1L)).thenReturn(PROFILE);
        when(sessionService.loadOrCreate(any(), any())).thenReturn(confirmedExerciseSession());
        when(mapper.findItems(any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            WeeklyPlanRow row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        }).when(mapper).insertPlan(any());
        service = new WeeklyPlanService(profileService, new HealthRiskRuleService(),
                mock(WeeklyPlanComposerService.class), new PlanValidationService(), mapper, provider,
                mock(HealthPlanResponseAgentService.class),
                new AgentTraceService(mock(AgentTraceMapper.class), new ObjectMapper()), sessionService,
                new ObjectMapper(), new PlanScopeGuard());
    }

    @Test
    void 训练范围只接受训练项目并持久化范围到根和版本() {
        HealthResource resource = new SeedResourceProvider().planReadyExercises().get(0);
        PlanItemDraft exercise = new PlanItemDraft("EXERCISE", resource.resourceId(), resource.name(),
                MONDAY, LocalTime.of(19, 0), LocalTime.of(20, 0), null,
                Map.of("bodyPart", resource.tags().getOrDefault("primaryBodyPart", List.of("胸")).get(0)));

        PlanView view = service.persistScopedGeneratedDraft(1L,
                new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null, PlanScope.EXERCISE),
                PlanScope.EXERCISE, List.of(exercise), "FALLBACK", Map.of("planScope", "EXERCISE"), "规则生成");

        assertEquals(PlanScope.EXERCISE, view.planScope());
        assertEquals("EXERCISE", capturePlan().getPlanScope());
        verify(mapper).insertVersion(any());
    }

    @Test
    void 范围与资源类型不一致时写入前拒绝且无半成品() {
        PlanItemDraft meal = new PlanItemDraft("MEAL", "M1", "餐食", MONDAY,
                LocalTime.of(12, 0), LocalTime.of(13, 0), null, Map.of("caloriesKcal", 500));

        HealthApiException error = assertThrows(HealthApiException.class, () -> service.persistScopedGeneratedDraft(
                1L, new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null, PlanScope.EXERCISE),
                PlanScope.EXERCISE, List.of(meal), "FALLBACK", Map.of(), ""));

        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        verify(mapper, never()).insertPlan(any());
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void 旧通用草稿入口直接阻断不得绕过简报() {
        HealthApiException error = assertThrows(HealthApiException.class,
                () -> service.createDraft(1L, new DraftPlanRequest("sess", MONDAY, "Asia/Shanghai", null)));
        assertEquals(HealthApiException.CODE_CONFLICT, error.code());
        assertTrue(error.getMessage().contains("简报"));
        verify(mapper, never()).insertPlan(any());
    }

    private HealthSessionState confirmedExerciseSession() {
        PlanBrief brief = new PlanBrief("保持健康", List.of("胸"), List.of("徒手"), "入门", MONDAY,
                List.of(DayOfWeek.MONDAY), new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                Map.of(), true, 1, null);
        return HealthSessionState.fresh("sess", 1L).withPlanBrief(brief);
    }

    private WeeklyPlanRow capturePlan() {
        org.mockito.ArgumentCaptor<WeeklyPlanRow> captor = org.mockito.ArgumentCaptor.forClass(WeeklyPlanRow.class);
        verify(mapper).insertPlan(captor.capture());
        return captor.getValue();
    }
}
