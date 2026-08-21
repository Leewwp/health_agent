package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.exception.HealthApiExceptionHandler;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanScope;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.plan.HealthPlanResponseAgentService;
import com.diet.health.plan.MealPlanPicker;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.PlanItemDraft;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.plan.TrainingTimeWindow;
import com.diet.health.plan.WeeklyPlanComposerService;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.HealthProfileMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.HealthProfileRow;
import com.diet.model.HealthProfileVersionRow;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计划 API Guard 测试：范围计划写入口必须依赖已确认简报，旧通用草稿入口拒绝；
 * 风险、时间和日期错误均不得留下计划半成品。
 */
class HealthPlanRiskGuardControllerTest {

    private static final long USER = 100L;

    private final FakeProfileMapper profileMapper = new FakeProfileMapper();
    private final FakePlanMapper planMapper = new FakePlanMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HealthResourceProvider resourceProvider;
    private HealthSessionService sessionService;
    private HealthSessionState sessionState;
    private WeeklyPlanService planService;
    private HealthProfileService profileService;
    private MockMvc mockMvc;

    private static final String PROFILE_BODY = "{\"age\":30,\"sex\":\"FEMALE\",\"heightCm\":175,\"weightKg\":70,"
            + "\"activityLevel\":\"LIGHT\",\"goal\":\"MAINTAIN\",\"timezone\":\"Asia/Shanghai\"}";

    @BeforeEach
    void setUp() {
        profileService = new HealthProfileService(profileMapper, objectMapper);
        resourceProvider = new SeedResourceProvider();
        MealPlanPicker picker = new MealPlanPicker(resourceProvider);
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(resourceProvider, picker);
        HealthPlanResponseAgentService planAgent = mock(HealthPlanResponseAgentService.class);
        when(planAgent.explain(any(), any())).thenReturn(
                new HealthPlanResponseAgentService.PlanExplanation("已生成周计划草稿。", List.of(), null));
        sessionService = mock(HealthSessionService.class);
        sessionState = HealthSessionState.fresh("sess-1", USER);
        when(sessionService.loadOrCreate(any(), any())).thenAnswer(invocation -> sessionState);
        planService = new WeeklyPlanService(
                profileService, new HealthRiskRuleService(), composer, new PlanValidationService(),
                planMapper, resourceProvider, planAgent, new AgentTraceService(mock(AgentTraceMapper.class), objectMapper),
                sessionService, objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthProfileController(profileService), new HealthPlanController(planService))
                .setControllerAdvice(new HealthApiExceptionHandler())
                .build();
    }

    @Test
    void 风险档案直接调用计划API被RISK_BLOCKED且不落库() throws Exception {
        String riskBody = "{\"age\":30,\"sex\":\"FEMALE\",\"heightCm\":175,\"weightKg\":70,"
                + "\"activityLevel\":\"LIGHT\",\"goal\":\"MAINTAIN\","
                + "\"riskConditions\":[\"PREGNANCY\"],\"riskNote\":\"孕晚期\"}";
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(riskBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskConditions[0]").value("PREGNANCY"))
                .andExpect(jsonPath("$.riskNote").value("孕晚期"));

        mockMvc.perform(post("/api/v1/health/plans/drafts")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("旧的通用草稿入口已移除，请从聊天简报进入范围生成"));

        assertEquals(0, planMapper.plans.size(), "风险阻断不得持久化计划");
        assertEquals(0, planMapper.versions.size(), "风险阻断不得持久化版本");
        assertEquals(0, planMapper.items.size(), "风险阻断不得持久化项目");
    }

    @Test
    void 正常档案直接调用旧计划API也被拒绝且不落库() throws Exception {
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskConditions").isEmpty());

        mockMvc.perform(post("/api/v1/health/plans/drafts")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertEquals(0, planMapper.plans.size(), "旧入口拒绝后不得落库");
    }

    @Test
    void 未知风险条件枚举在HTTP层被拒绝为400() throws Exception {
        String badBody = "{\"age\":30,\"sex\":\"FEMALE\",\"heightCm\":175,\"weightKg\":70,"
                + "\"activityLevel\":\"LIGHT\",\"goal\":\"MAINTAIN\",\"riskConditions\":[\"BOGUS_CONDITION\"]}";
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        assertEquals(0, profileMapper.profiles.size(), "未知枚举拒绝后档案不得落库");
    }

    @Test
    void 孕产条件与男性生理性别组合在HTTP层被拒绝为400() throws Exception {
        String badBody = "{\"age\":30,\"sex\":\"MALE\",\"heightCm\":175,\"weightKg\":70,"
                + "\"activityLevel\":\"LIGHT\",\"goal\":\"MAINTAIN\",\"riskConditions\":[\"PREGNANCY\"]}";
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        assertEquals(0, profileMapper.profiles.size(), "非法组合拒绝后档案不得落库");
    }

    // ---- 60 号票：PATCH 时间/日期不变量在 HTTP 层生效 ----

    @Test
    void PATCH项目到周范围外被HTTP400拒绝且不落库() throws Exception {
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isOk());
        PlanView plan = createConfirmedExerciseDraft();
        Long exerciseId = planMapper.items.stream()
                .filter(item -> "EXERCISE".equals(item.getResourceType()))
                .findFirst().orElseThrow().getId();
        Long planId = plan.id();

        mockMvc.perform(patch("/api/v1/health/plans/" + planId + "/items/" + exerciseId)
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localDate\":\"2026-08-24\",\"startTime\":\"20:00\",\"endTime\":\"21:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超出本周范围")));

        WeeklyPlanItemRow row = planMapper.items.stream()
                .filter(item -> item.getId().equals(exerciseId)).findFirst().orElseThrow();
        assertEquals(java.time.LocalDate.of(2026, 8, 17), row.getLocalDate(), "越界 PATCH 不得落库");
        assertEquals(java.time.LocalTime.of(19, 30), row.getStartTime(), "越界 PATCH 不得落库");
    }

    @Test
    void PATCH零时长区间被HTTP409RISK_BLOCKED拒绝且不落库() throws Exception {
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isOk());
        PlanView plan = createConfirmedExerciseDraft();
        Long exerciseId = planMapper.items.stream()
                .filter(item -> "EXERCISE".equals(item.getResourceType()))
                .findFirst().orElseThrow().getId();
        Long planId = plan.id();

        mockMvc.perform(patch("/api/v1/health/plans/" + planId + "/items/" + exerciseId)
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"20:00\",\"endTime\":\"20:00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_TIME_CONFLICT"))
                .andExpect(jsonPath("$.message").value(PlanValidationService.INVALID_TIME_RANGE_COPY));

        WeeklyPlanItemRow row = planMapper.items.stream()
                .filter(item -> item.getId().equals(exerciseId)).findFirst().orElseThrow();
        assertEquals(java.time.LocalTime.of(19, 30), row.getStartTime(), "零时长修改不得落库");
        assertEquals(java.time.LocalTime.of(21, 0), row.getEndTime(), "零时长修改不得落库");
    }

    private PlanView createConfirmedExerciseDraft() {
        LocalDate weekStart = LocalDate.of(2026, 8, 17);
        var resource = resourceProvider.planReadyExercises().get(0);
        String bodyPart = resource.tags().getOrDefault("primaryBodyPart", List.of("全身")).get(0);
        PlanBrief brief = new PlanBrief(
                resource.tags().getOrDefault("trainingGoal", List.of("保持健康")).get(0),
                resource.tags().getOrDefault("bodyParts", List.of(bodyPart)).subList(0, 1),
                resource.tags().getOrDefault("equipment", List.of("徒手")).subList(0, 1),
                resource.tags().getOrDefault("difficulty", List.of("入门")).get(0),
                weekStart, List.of(DayOfWeek.MONDAY),
                new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                Map.of(), true, 1, LocalDateTime.now());
        sessionState = sessionState.withPlanBrief(brief);
        PlanItemDraft item = new PlanItemDraft(
                "EXERCISE", resource.resourceId(), resource.name(), weekStart,
                LocalTime.of(19, 30), LocalTime.of(21, 0), null,
                Map.of("bodyPart", bodyPart, "sets", 2, "reps", 10, "durationMinutes", 90));
        return planService.persistScopedGeneratedDraft(USER,
                new com.diet.health.plan.DraftPlanRequest(sessionState.sessionId(), weekStart,
                        "Asia/Shanghai", null, PlanScope.EXERCISE), PlanScope.EXERCISE,
                List.of(item), "FALLBACK", Map.of("planScope", PlanScope.EXERCISE.name()), "已生成训练计划草稿。");
    }

    // ---------- 内存版 Mapper ----------

    /** 内存版 HealthProfileMapper（保存/读取当前档案与版本快照）。 */
    private static final class FakeProfileMapper implements HealthProfileMapper {
        final List<HealthProfileRow> profiles = new ArrayList<>();
        final List<HealthProfileVersionRow> versions = new ArrayList<>();

        @Override
        public synchronized HealthProfileRow findByUserId(Long userId) {
            return profiles.stream().filter(row -> row.getUserId().equals(userId)).findFirst().orElse(null);
        }

        @Override
        public synchronized HealthProfileRow findByUserIdForUpdate(Long userId) {
            return findByUserId(userId);
        }

        @Override
        public synchronized int insert(HealthProfileRow row) {
            row.setId((long) profiles.size() + 1);
            profiles.add(row);
            return 1;
        }

        @Override
        public synchronized int update(HealthProfileRow row) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(row.getId())) {
                    profiles.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized int insertVersion(HealthProfileVersionRow row) {
            row.setId((long) versions.size() + 1);
            versions.add(row);
            return 1;
        }
    }

    /** 内存版 WeeklyPlanMapper（只保留本测试用到的写入/查询语义）。 */
    private static final class FakePlanMapper implements WeeklyPlanMapper {
        final List<WeeklyPlanRow> plans = new ArrayList<>();
        final List<WeeklyPlanVersionRow> versions = new ArrayList<>();
        final List<WeeklyPlanItemRow> items = new ArrayList<>();

        @Override
        public synchronized WeeklyPlanRow findPlanById(Long id, Long userId) {
            return plans.stream().filter(row -> row.getId().equals(id) && row.getUserId().equals(userId))
                    .findFirst().orElse(null);
        }

        @Override
        public synchronized WeeklyPlanRow findPlanByIdForUpdate(Long id, Long userId) {
            return findPlanById(id, userId);
        }

        @Override
        public synchronized WeeklyPlanRow findActiveByUser(Long userId) {
            return plans.stream().filter(row -> row.getUserId().equals(userId) && "ACTIVE".equals(row.getStatus()))
                    .findFirst().orElse(null);
        }

        @Override
        public synchronized WeeklyPlanRow findActiveByUserForUpdate(Long userId) {
            return findActiveByUser(userId);
        }

        @Override
        public synchronized WeeklyPlanRow findActiveByUserAndScopeForUpdate(Long userId, String planScope) {
            return plans.stream().filter(row -> row.getUserId().equals(userId)
                            && planScope.equals(row.getPlanScope()) && "ACTIVE".equals(row.getStatus()))
                    .findFirst().orElse(null);
        }

        @Override
        public synchronized List<WeeklyPlanRow> listPlans(Long userId) {
            return plans.stream().filter(row -> row.getUserId().equals(userId)).toList();
        }

        @Override
        public synchronized int insertPlan(WeeklyPlanRow row) {
            row.setId((long) plans.size() + 1);
            plans.add(row);
            return 1;
        }

        @Override
        public synchronized int updatePlan(WeeklyPlanRow row) {
            for (int i = 0; i < plans.size(); i++) {
                if (plans.get(i).getId().equals(row.getId())) {
                    plans.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized int activatePlan(WeeklyPlanRow row) {
            return updatePlan(row);
        }

        @Override
        public synchronized int insertVersion(WeeklyPlanVersionRow row) {
            row.setId((long) versions.size() + 1);
            versions.add(row);
            return 1;
        }

        @Override
        public synchronized List<WeeklyPlanItemRow> findItems(Long planId, Long versionNo) {
            return items.stream()
                    .filter(row -> row.getPlanId().equals(planId) && row.getVersionNo().equals(versionNo))
                    .toList();
        }

        @Override
        public synchronized WeeklyPlanItemRow findItemById(Long itemId) {
            return items.stream().filter(row -> row.getId().equals(itemId)).findFirst().orElse(null);
        }

        @Override
        public synchronized int insertItem(WeeklyPlanItemRow row) {
            row.setId((long) items.size() + 1);
            items.add(row);
            return 1;
        }

        @Override
        public synchronized int updateItemSchedule(WeeklyPlanItemRow row) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getId().equals(row.getId())) {
                    items.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override public synchronized int deleteItemsByPlanId(Long planId) { return 0; }
        @Override public synchronized int deleteVersionsByPlanId(Long planId) { return 0; }
        @Override public synchronized int deletePlan(Long planId, Long userId) { return 0; }
    }
}
