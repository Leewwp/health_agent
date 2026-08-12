package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.exception.HealthApiExceptionHandler;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.plan.HealthPlanResponseAgentService;
import com.diet.health.plan.MealPlanPicker;
import com.diet.health.plan.PlanValidationService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计划 API 直达绕过测试（62 号票）：
 * 不先调用聊天，直接 PUT 档案 + POST 计划草稿；风险档案必须得到 RISK_BLOCKED，
 * 且计划/版本/项目均不落库；正常档案仍成功；未知风险枚举在 HTTP 层被干净拒绝为 400。
 */
class HealthPlanRiskGuardControllerTest {

    private static final long USER = 100L;

    private final FakeProfileMapper profileMapper = new FakeProfileMapper();
    private final FakePlanMapper planMapper = new FakePlanMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HealthProfileService profileService;
    private MockMvc mockMvc;

    private static final String PROFILE_BODY = "{\"age\":30,\"sex\":\"FEMALE\",\"heightCm\":175,\"weightKg\":70,"
            + "\"activityLevel\":\"LIGHT\",\"goal\":\"MAINTAIN\",\"timezone\":\"Asia/Shanghai\"}";

    @BeforeEach
    void setUp() {
        profileService = new HealthProfileService(profileMapper, objectMapper);
        HealthResourceProvider provider = new SeedResourceProvider();
        MealPlanPicker picker = new MealPlanPicker(provider);
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(provider, picker);
        HealthPlanResponseAgentService planAgent = mock(HealthPlanResponseAgentService.class);
        when(planAgent.explain(any(), any())).thenReturn(
                new HealthPlanResponseAgentService.PlanExplanation("已生成周计划草稿。", List.of(), null));
        HealthSessionService sessionService = mock(HealthSessionService.class);
        when(sessionService.loadOrCreate(any(), any())).thenReturn(HealthSessionState.fresh("sess-1", USER));
        WeeklyPlanService planService = new WeeklyPlanService(
                profileService, new HealthRiskRuleService(), composer, new PlanValidationService(),
                planMapper, provider, planAgent, new AgentTraceService(mock(AgentTraceMapper.class), objectMapper),
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
                .andExpect(jsonPath("$.code").value("RISK_BLOCKED"))
                .andExpect(jsonPath("$.message").value(HealthRiskRuleService.BLOCK_PLAN_COPY));

        assertEquals(0, planMapper.plans.size(), "风险阻断不得持久化计划");
        assertEquals(0, planMapper.versions.size(), "风险阻断不得持久化版本");
        assertEquals(0, planMapper.items.size(), "风险阻断不得持久化项目");
    }

    @Test
    void 正常档案直接调用计划API成功() throws Exception {
        mockMvc.perform(put("/api/v1/health/profile")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskConditions").isEmpty());

        mockMvc.perform(post("/api/v1/health/plans/drafts")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals(1, planMapper.plans.size(), "正常档案草稿成功落库");
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
    }
}
