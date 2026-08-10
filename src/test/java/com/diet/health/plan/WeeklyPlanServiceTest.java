package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 周计划生命周期（34 号，规格 6.3/8.2）：
 * DRAFT 生成持久化、未校验不持久化、WARNING 可存不可激活、激活归档旧 ACTIVE 并快照版本、
 * ACTIVE 编辑复制为新 DRAFT、PATCH 只改日期/时间/备注且硬错误拒绝。
 */
class WeeklyPlanServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 17);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeWeeklyPlanMapper planMapper = new FakeWeeklyPlanMapper();
    private final MealPlanPicker picker = mock(MealPlanPicker.class);
    private final HealthProfileService profileService = mock(HealthProfileService.class);
    private final HealthPlanResponseAgentService planAgent = mock(HealthPlanResponseAgentService.class);
    private WeeklyPlanService service;

    private static final HealthProfileService.HealthProfileView PROFILE = new HealthProfileService.HealthProfileView(
            1L, 30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 1200, 1800, true, 1L, "basis");

    @BeforeEach
    void setUp() {
        when(profileService.getProfile(1L)).thenReturn(PROFILE);
        when(planAgent.explain(any(), any())).thenReturn(
                new HealthPlanResponseAgentService.PlanExplanation("已生成周计划草稿。", List.of(), null));
        when(picker.pickForDay(any(Integer.class), any(Integer.class))).thenReturn(threeMeals());
        AgentTraceService trace = new AgentTraceService(mock(AgentTraceMapper.class), objectMapper);
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(new ExerciseModule(), picker);
        service = new WeeklyPlanService(profileService, new HealthRiskRuleService(), composer,
                new PlanValidationService(), planMapper, new ExerciseModule(), new RoutineModule(),
                planAgent, trace, objectMapper);
    }

    private static List<MealPlanPicker.MealPick> threeMeals() {
        return List.of(
                new MealPlanPicker.MealPick("5", "清蒸鲈鱼", 400, "早餐"),
                new MealPlanPicker.MealPick("7", "鸡胸肉沙拉", 600, "午餐"),
                new MealPlanPicker.MealPick("9", "白灼西兰花", 400, "晚餐")
        );
    }

    private DraftPlanRequest draftRequest() {
        return new DraftPlanRequest("sess-1", MON, "Asia/Shanghai", null);
    }

    @Test
    void 创建草稿持久化计划版本与项目() {
        PlanView view = service.createDraft(1L, draftRequest());
        assertEquals(PlanStatus.DRAFT, view.status());
        assertEquals(PlanValidationLevel.OK, view.validationLevel());
        assertEquals(1L, view.currentVersion());
        assertEquals(31, view.items().size());
        assertTrue(view.explanation().contains("周计划草稿"));
        assertEquals(1, planMapper.plans.size());
        assertEquals(1, planMapper.versions.size());
        assertEquals(31, planMapper.items.size());
        assertEquals(1L, planMapper.versions.get(0).getVersionNo());
    }

    @Test
    void 无档案时创建计划NOT_FOUND() {
        when(profileService.getProfile(2L)).thenThrow(
                new HealthApiException(HealthApiException.CODE_NOT_FOUND, "健康档案不存在，请先完善健康档案"));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.createDraft(2L, draftRequest()));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
        assertEquals(0, planMapper.plans.size(), "无档案不得持久化计划");
    }

    @Test
    void 高龄档案生成计划被RISK_BLOCKED且不持久化() {
        HealthProfileService.HealthProfileView senior = new HealthProfileService.HealthProfileView(
                1L, 70, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
                "Asia/Shanghai", 1200, 1800, true, 1L, "basis");
        when(profileService.getProfile(1L)).thenReturn(senior);
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.createDraft(1L, draftRequest()));
        assertEquals(HealthApiException.CODE_RISK_BLOCKED, error.code());
        assertEquals(HealthRiskRuleService.SENIOR_TRAINING_COPY, error.getMessage());
        assertEquals(0, planMapper.plans.size());
    }

    @Test
    void 能量警告草稿可保存但不可激活() {
        when(picker.pickForDay(any(Integer.class), any(Integer.class))).thenReturn(List.of(
                new MealPlanPicker.MealPick("5", "甲", 1000, "早餐"),
                new MealPlanPicker.MealPick("7", "乙", 1000, "午餐"),
                new MealPlanPicker.MealPick("9", "丙", 1000, "晚餐")
        ));
        PlanView draft = service.createDraft(1L, draftRequest());
        assertEquals(PlanValidationLevel.WARNING, draft.validationLevel());
        assertTrue(draft.validationHits().stream().anyMatch(hit -> "ENERGY_OUT_OF_RANGE".equals(hit.ruleCode())));

        HealthApiException error = assertThrows(HealthApiException.class, () -> service.activate(1L, draft.id()));
        assertEquals(HealthApiException.CODE_RISK_BLOCKED, error.code());
        assertEquals(PlanValidationService.ENERGY_OUT_OF_RANGE_COPY, error.getMessage());
        assertEquals(PlanStatus.DRAFT.name(), planMapper.plans.get(0).getStatus(), "警告计划不可激活");
    }

    @Test
    void 激活草稿归档旧ACTIVE并快照新版本() {
        PlanView first = service.createDraft(1L, draftRequest());
        PlanView active = service.activate(1L, first.id());
        assertEquals(PlanStatus.ACTIVE, active.status());
        assertEquals(2L, active.currentVersion(), "激活生成新版本");
        assertEquals(2, planMapper.versions.size());
        assertEquals(31, planMapper.items.stream().filter(item -> item.getVersionNo() == 2).count(),
                "项目快照到新版本");

        PlanView second = service.createDraft(1L, draftRequest());
        PlanView active2 = service.activate(1L, second.id());
        assertEquals(PlanStatus.ACTIVE, active2.status());
        assertEquals(PlanStatus.ARCHIVED.name(), planMapper.plans.stream()
                .filter(row -> row.getId().equals(first.id())).findFirst().orElseThrow().getStatus(),
                "旧 ACTIVE 归档");
    }

    @Test
    void 激活非草稿计划为CONFLICT() {
        PlanView draft = service.createDraft(1L, draftRequest());
        service.activate(1L, draft.id());
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.activate(1L, draft.id()));
        assertEquals(HealthApiException.CODE_CONFLICT, error.code());
    }

    @Test
    void 编辑ACTIVE复制为新草稿() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanView active = service.activate(1L, draft.id());
        PlanView edited = service.edit(1L, active.id());
        assertNotEquals(active.id(), edited.id(), "ACTIVE 编辑生成新计划");
        assertEquals(PlanStatus.DRAFT, edited.status());
        assertEquals(31, edited.items().size(), "项目复制到新草稿");
        assertEquals(edited.items().get(0).name(), active.items().get(0).name());
        assertEquals(1L, edited.currentVersion());
    }

    @Test
    void 编辑草稿直接返回原草稿() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanView edited = service.edit(1L, draft.id());
        assertEquals(draft.id(), edited.id());
    }

    @Test
    void PATCH移动项目日期时间和备注() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanItemView exercise = draft.items().stream()
                .filter(item -> "EXERCISE".equals(item.resourceType()))
                .findFirst().orElseThrow();
        PlanView updated = service.patchItem(1L, draft.id(), exercise.id(),
                new PatchItemRequest(MON.plusDays(1), LocalTime.of(20, 0), LocalTime.of(21, 30), "移到周二晚"));
        PlanItemView moved = updated.items().stream().filter(item -> item.id().equals(exercise.id()))
                .findFirst().orElseThrow();
        assertEquals(MON.plusDays(1), moved.localDate());
        assertEquals(LocalTime.of(20, 0), moved.startTime());
        assertEquals("移到周二晚", moved.note());
        assertEquals(PlanValidationLevel.OK, updated.validationLevel());
    }

    @Test
    void PATCH造成作息冲突被拒绝且不持久化() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanItemView exercise = draft.items().stream()
                .filter(item -> "EXERCISE".equals(item.resourceType()))
                .findFirst().orElseThrow();
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.patchItem(1L, draft.id(),
                exercise.id(), new PatchItemRequest(MON, LocalTime.of(23, 0), LocalTime.of(23, 30), "睡前训练")));
        assertEquals(HealthApiException.CODE_RISK_BLOCKED, error.code());
        assertEquals(PlanValidationService.SCHEDULE_OVERLAP_COPY, error.getMessage());
        WeeklyPlanItemRow row = planMapper.items.stream()
                .filter(item -> item.getId().equals(exercise.id())).findFirst().orElseThrow();
        assertEquals(LocalTime.of(19, 30), row.getStartTime(), "冲突修改不得落库");
    }

    @Test
    void PATCH不可修改ACTIVE计划() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanView active = service.activate(1L, draft.id());
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.patchItem(1L, active.id(),
                active.items().get(0).id(), new PatchItemRequest(MON, LocalTime.of(9, 0), LocalTime.of(10, 0), "改")));
        assertEquals(HealthApiException.CODE_CONFLICT, error.code());
    }

    @Test
    void 他人计划查询与编辑为NOT_FOUND() {
        PlanView draft = service.createDraft(1L, draftRequest());
        HealthApiException getError = assertThrows(HealthApiException.class, () -> service.getPlan(2L, draft.id()));
        assertEquals(HealthApiException.CODE_NOT_FOUND, getError.code());
        HealthApiException patchError = assertThrows(HealthApiException.class, () -> service.patchItem(2L, draft.id(),
                draft.items().get(0).id(), new PatchItemRequest(MON, null, null, "x")));
        assertEquals(HealthApiException.CODE_NOT_FOUND, patchError.code());
    }

    @Test
    void 列表ACTIVE优先排序() {
        PlanView first = service.createDraft(1L, draftRequest());
        service.activate(1L, first.id());
        PlanView second = service.createDraft(1L, draftRequest());
        service.activate(1L, second.id());
        List<PlanSummaryView> plans = service.listPlans(1L);
        assertEquals(2, plans.size());
        assertEquals(PlanStatus.ACTIVE, plans.get(0).status());
        assertEquals(PlanStatus.ARCHIVED, plans.get(1).status());
    }

    @Test
    void 非周一weekStart被拒绝() {
        DraftPlanRequest request = new DraftPlanRequest(null, MON.plusDays(1), "Asia/Shanghai", null);
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.createDraft(1L, request));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("周一"));
        assertEquals(0, planMapper.plans.size());
    }

    @Test
    void 档案更新后ACTIVE计划标记为较旧() {
        PlanView draft = service.createDraft(1L, draftRequest());
        service.activate(1L, draft.id());
        HealthProfileService.HealthProfileView newProfile = new HealthProfileService.HealthProfileView(
                1L, 31, ProfileSex.MALE, 176.0, 71.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
                "Asia/Shanghai", 2200, 2600, true, 2L, "basis");
        when(profileService.getProfile(1L)).thenReturn(newProfile);
        PlanView viewed = service.getPlan(1L, draft.id());
        assertTrue(viewed.profileStale(), "档案版本变新后 ACTIVE 计划应标记较旧");
        assertEquals(1L, viewed.profileVersionNo(), "已激活计划保留生成时档案版本");
    }

    @Test
    void 激活刷新计划档案依据到当前档案() {
        PlanView draft = service.createDraft(1L, draftRequest());
        service.activate(1L, draft.id());
        HealthProfileService.HealthProfileView newProfile = new HealthProfileService.HealthProfileView(
                1L, 31, ProfileSex.MALE, 176.0, 71.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
                "Asia/Shanghai", 1200, 1800, true, 2L, "basis");
        when(profileService.getProfile(1L)).thenReturn(newProfile);
        PlanView second = service.createDraft(1L, draftRequest());
        PlanView active = service.activate(1L, second.id());
        assertEquals(2L, active.profileVersionNo());
        assertEquals(1200, active.calorieLow());
    }

    @Test
    void PATCH空字符串备注清空备注() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanItemView meal = draft.items().stream().filter(item -> "MEAL".equals(item.resourceType()))
                .findFirst().orElseThrow();
        service.patchItem(1L, draft.id(), meal.id(), new PatchItemRequest(null, null, null, "临时备注"));
        PlanView withNote = service.getPlan(1L, draft.id());
        assertEquals("临时备注", withNote.items().stream().filter(item -> item.id().equals(meal.id()))
                .findFirst().orElseThrow().note());
        PlanView cleared = service.patchItem(1L, draft.id(), meal.id(), new PatchItemRequest(null, null, null, ""));
        assertTrue(cleared.items().stream().filter(item -> item.id().equals(meal.id()))
                .findFirst().orElseThrow().note() == null, "空字符串应清空备注");
    }

    @Test
    void 获取计划包含校验命中详情() {
        service.createDraft(1L, draftRequest());
        PlanView view = service.getPlan(1L, 1L);
        assertEquals(1L, view.profileVersionNo());
        assertEquals(1200, view.calorieLow());
        assertEquals(1800, view.calorieHigh());
        assertEquals(PlanValidationLevel.OK, view.validationLevel());
    }

    /** 内存版 WeeklyPlanMapper。 */
    private static final class FakeWeeklyPlanMapper implements WeeklyPlanMapper {
        final List<WeeklyPlanRow> plans = new ArrayList<>();
        final List<WeeklyPlanVersionRow> versions = new ArrayList<>();
        final List<WeeklyPlanItemRow> items = new ArrayList<>();

        @Override
        public WeeklyPlanRow findPlanById(Long id, Long userId) {
            return plans.stream().filter(row -> row.getId().equals(id) && row.getUserId().equals(userId))
                    .findFirst().orElse(null);
        }

        @Override
        public WeeklyPlanRow findActiveByUser(Long userId) {
            return plans.stream().filter(row -> row.getUserId().equals(userId) && "ACTIVE".equals(row.getStatus()))
                    .findFirst().orElse(null);
        }

        @Override
        public List<WeeklyPlanRow> listPlans(Long userId) {
            return plans.stream().filter(row -> row.getUserId().equals(userId))
                    .sorted((a, b) -> {
                        int orderA = switch (a.getStatus()) {
                            case "ACTIVE" -> 0;
                            case "DRAFT" -> 1;
                            default -> 2;
                        };
                        int orderB = switch (b.getStatus()) {
                            case "ACTIVE" -> 0;
                            case "DRAFT" -> 1;
                            default -> 2;
                        };
                        return Integer.compare(orderA, orderB);
                    }).toList();
        }

        @Override
        public int insertPlan(WeeklyPlanRow row) {
            row.setId((long) plans.size() + 1);
            plans.add(row);
            return 1;
        }

        @Override
        public int updatePlan(WeeklyPlanRow row) {
            for (int i = 0; i < plans.size(); i++) {
                if (plans.get(i).getId().equals(row.getId())) {
                    plans.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int insertVersion(WeeklyPlanVersionRow row) {
            row.setId((long) versions.size() + 1);
            versions.add(row);
            return 1;
        }

        @Override
        public List<WeeklyPlanItemRow> findItems(Long planId, Long versionNo) {
            return items.stream()
                    .filter(row -> row.getPlanId().equals(planId) && row.getVersionNo().equals(versionNo))
                    .toList();
        }

        @Override
        public WeeklyPlanItemRow findItemById(Long itemId) {
            return items.stream().filter(row -> row.getId().equals(itemId)).findFirst().orElse(null);
        }

        @Override
        public int insertItem(WeeklyPlanItemRow row) {
            row.setId((long) items.size() + 1);
            items.add(row);
            return 1;
        }

        @Override
        public int updateItemSchedule(WeeklyPlanItemRow row) {
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
