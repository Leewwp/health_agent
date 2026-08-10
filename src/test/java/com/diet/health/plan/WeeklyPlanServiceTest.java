package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.resource.DbReviewedResourceProvider;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.ExerciseMapper;
import com.diet.mapper.MealMapper;
import com.diet.mapper.RoutineFactMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.model.RoutineFactRow;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        HealthResourceProvider provider = new SeedResourceProvider();
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(provider, picker);
        service = new WeeklyPlanService(profileService, new HealthRiskRuleService(), composer,
                new PlanValidationService(), planMapper, provider,
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
    void 版本快照携带完整生成依据() {
        PlanView view = service.createDraft(1L, draftRequest());
        WeeklyPlanVersionRow version = planMapper.versions.get(0);
        assertEquals(PlanValidationService.RULES_VERSION, version.getRulesVersion(), "版本保存规则版本");
        assertEquals("sess-1", version.getSourceSessionId(), "版本保存来源会话");
        assertTrue(version.getProfileSnapshotJson() != null && !version.getProfileSnapshotJson().isBlank(),
                "版本保存档案快照");
        assertTrue(version.getValidationJson() != null && !version.getValidationJson().isBlank(),
                "版本保存校验结果");

        assertTrue(version.getFactSourcesJson() != null && version.getFactSourcesJson().contains("R1"),
                "版本保存作息事实来源（含事实 ID）");
        assertTrue(version.getFactSourcesJson().contains("sourceName"), "作息事实来源含来源引用");

        assertTrue(version.getResourceSnapshotJson() != null && version.getResourceSnapshotJson().contains("providerMode"),
                "版本保存资源快照 Provider 模式");
        assertTrue(version.getResourceSnapshotJson().contains("FIXTURE_SEED"), "fixture 模式标识明确");
        assertTrue(version.getResourceSnapshotJson().contains("resourceId"), "资源快照含类型化 ID");
        assertTrue(version.getResourceSnapshotJson().contains("planParams"), "资源快照含生成时计划参数");
        assertTrue(version.getResourceSnapshotJson().contains("sourceName"), "资源快照含来源");
        assertTrue(version.getResourceSnapshotJson().contains("sourceVersion"), "资源快照含来源版本");
        assertTrue(version.getResourceSnapshotJson().contains("reviewStatus"), "资源快照含审核状态");
    }

    @Test
    void 激活版本同样携带完整生成依据() {
        PlanView draft = service.createDraft(1L, draftRequest());
        service.activate(1L, draft.id());
        WeeklyPlanVersionRow version = planMapper.versions.get(1);
        assertEquals(2L, version.getVersionNo());
        assertEquals(PlanValidationService.RULES_VERSION, version.getRulesVersion());
        assertEquals("sess-1", version.getSourceSessionId());
        assertTrue(version.getFactSourcesJson().contains("R1"));
        assertTrue(version.getResourceSnapshotJson().contains("FIXTURE_SEED"));
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
    void 激活使用行锁重读与激活专用更新() {
        PlanView draft = service.createDraft(1L, draftRequest());
        PlanView active = service.activate(1L, draft.id());
        assertEquals(PlanStatus.ACTIVE, active.status());
        assertTrue(planMapper.findPlanByIdForUpdateCalls > 0, "激活必须用 FOR UPDATE 重读目标计划");
        assertEquals(0, planMapper.findPlanByIdCalls, "激活路径不得用非锁定查询");
        assertTrue(planMapper.findActiveByUserForUpdateCalls > 0, "激活必须锁定现有 ACTIVE");
        assertEquals(1, planMapper.activatePlanCalls);
        assertEquals(PlanStatus.ACTIVE.name(), planMapper.lastActivateArg.getStatus());
        assertEquals(2L, planMapper.lastActivateArg.getCurrentVersion(), "激活写回新版本号");
    }

    @Test
    void 激活专用更新携带档案依据与校验信息() {
        PlanView draft = service.createDraft(1L, draftRequest());
        service.activate(1L, draft.id());
        assertEquals(1L, planMapper.lastActivateArg.getProfileVersionNo(), "激活刷新档案版本");
        assertEquals(1200, planMapper.lastActivateArg.getCalorieLow());
        assertEquals(1800, planMapper.lastActivateArg.getCalorieHigh());
        assertEquals(PlanValidationService.RULES_VERSION, planMapper.lastActivateArg.getRulesVersion());
        assertEquals(PlanValidationLevel.OK.name(), planMapper.lastActivateArg.getValidationLevel());
        assertTrue(planMapper.lastActivateArg.getValidationJson() != null
                        && !planMapper.lastActivateArg.getValidationJson().isBlank(),
                "激活写回校验结果 JSON");
        assertEquals(PlanStatus.ACTIVE.name(), planMapper.lastActivateArg.getStatus());
    }

    @Test
    void 版本快照写入失败时activatePlan不被调用() {
        PlanView draft = service.createDraft(1L, draftRequest());
        planMapper.failInsertVersion = true;
        assertThrows(IllegalStateException.class, () -> service.activate(1L, draft.id()));
        assertEquals(0, planMapper.activatePlanCalls, "版本写入失败不得推进计划状态（回滚语义）");
        assertEquals(PlanStatus.DRAFT.name(), planMapper.plans.get(0).getStatus(), "计划保持 DRAFT");
    }

    @Test
    void 项目写入失败时草稿不落库() {
        planMapper.failInsertItem = true;
        assertThrows(IllegalStateException.class, () -> service.createDraft(1L, draftRequest()));
        // 内存假 Mapper 不模拟事务回滚；"数据库无半成品"由 @Transactional 在真实事务中保证
        // （真实 MySQL 迁移与事务验证在 38 号最终验收）。此处验证失败即中止、不再继续写入。
        assertEquals(0, planMapper.items.size(), "项目写入失败即中止，不再继续写入");
        assertEquals(1, planMapper.versions.size(), "计划与版本写入先于项目（调用顺序证据）");
    }

    @Test
    void 激活归档旧ACTIVE更新失败时状态不推进() {
        PlanView first = service.createDraft(1L, draftRequest());
        service.activate(1L, first.id());
        PlanView second = service.createDraft(1L, draftRequest());
        planMapper.failUpdatePlan = true;
        assertThrows(IllegalStateException.class, () -> service.activate(1L, second.id()));
        assertEquals(1, planMapper.activatePlanCalls, "归档更新失败不得执行新的激活更新（回滚语义）");
        assertEquals(PlanStatus.ACTIVE.name(), planMapper.plans.get(0).getStatus(), "旧 ACTIVE 保持 ACTIVE");
        assertEquals(PlanStatus.DRAFT.name(), planMapper.plans.get(1).getStatus(), "目标计划保持 DRAFT");
    }

    @Test
    void 激活状态已变化时activatePlan返回0抛CONFLICT() {
        PlanView draft = service.createDraft(1L, draftRequest());
        planMapper.onFindActiveForUpdate = () -> {
            WeeklyPlanRow row = planMapper.plans.get(0);
            row.setStatus(PlanStatus.ACTIVE.name());
        };
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.activate(1L, draft.id()));
        assertEquals(HealthApiException.CODE_CONFLICT, error.code());
        assertEquals("计划状态已变化，请刷新后重试", error.getMessage());
        assertEquals(0, planMapper.activatePlanAffectedRows, "冲突路径下激活更新影响 0 行");
    }

    @Test
    void 并发激活同一草稿只有一个成功() throws Exception {
        PlanView draft = service.createDraft(1L, draftRequest());
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    service.activate(1L, draft.id());
                    results.add("OK");
                } catch (HealthApiException e) {
                    results.add(e.code());
                }
                done.countDown();
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发激活应在限时内完成");
        assertEquals(threads, results.size());
        assertEquals(1, results.stream().filter("OK"::equals).count(), "同一草稿只能激活成功一次");
        assertEquals(1, results.stream().filter(HealthApiException.CODE_CONFLICT::equals).count(),
                "输掉竞争的请求得到冲突错误");
        assertEquals(PlanStatus.ACTIVE.name(), planMapper.plans.get(0).getStatus());
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

    @Test
    void 数据库模式草稿全部引用审核子集资源() {
        ExerciseMapper exerciseMapper = mock(ExerciseMapper.class);
        MealMapper mealMapper = mock(MealMapper.class);
        RoutineFactMapper factMapper = mock(RoutineFactMapper.class);
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(
                exerciseRow(101L, "chest", "[\"triceps\"]", "[\"triceps\", \"deltoids\", \"core\"]", "body weight", true),
                exerciseRow(102L, "upper legs", "[\"quadriceps\"]", "[\"quadriceps\", \"hamstrings\", \"calves\"]", "body weight", true),
                exerciseRow(103L, "back", "[\"biceps\"]", "[\"biceps\", \"forearms\"]", "band", false)
        ));
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of());
        when(factMapper.selectAll()).thenReturn(List.of(factRow("aasm-sleep-minimum", "睡眠时长下限"),
                factRow("nsf-sleep-duration-adult", "睡眠时长")));
        when(factMapper.selectByTopicLike(any())).thenAnswer(invocation -> List.of(factRow("aasm-sleep-minimum", "睡眠时长下限")));
        HealthResourceProvider provider = new DbReviewedResourceProvider(
                exerciseMapper, mealMapper, factMapper, new JsonService(objectMapper));
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(provider, picker);
        WeeklyPlanService dbService = new WeeklyPlanService(profileService, new HealthRiskRuleService(), composer,
                new PlanValidationService(), planMapper, provider,
                planAgent, new AgentTraceService(mock(AgentTraceMapper.class), objectMapper), objectMapper);

        PlanView view = dbService.createDraft(1L, draftRequest());

        Set<String> seedIds = Set.of("9001", "9002", "9003", "9004", "9005", "9006", "9007", "9008", "R1", "R2", "R3", "R4", "R5");
        assertTrue(view.items().stream().noneMatch(item -> seedIds.contains(item.resourceId())),
                "数据库模式草稿不得引用种子 ID（9001-9008/R1-R5）");
        PlanItemView sleep = view.items().stream().filter(item -> "ROUTINE".equals(item.resourceType()))
                .findFirst().orElseThrow();
        assertEquals("aasm-sleep-minimum", sleep.resourceId(), "睡眠项目使用数据库事实 ref_id");
        assertTrue(view.items().stream().filter(item -> "EXERCISE".equals(item.resourceType()))
                        .allMatch(item -> provider.planReadyExerciseIds().contains(item.resourceId())),
                "训练项目必须来自 plan_ready 审核动作");
        assertTrue(view.items().stream().filter(item -> "ROUTINE".equals(item.resourceType()))
                        .allMatch(item -> provider.allFactIds().contains(item.resourceId())),
                "作息项目必须来自审核事实 ref_id");
        assertEquals(PlanValidationLevel.OK, view.validationLevel());
    }

    private static ExerciseItemRow exerciseRow(Long id, String bodyPart, String target, String secondary,
                                               String equipment, boolean planReady) {
        ExerciseItemRow row = new ExerciseItemRow();
        row.setId(id);
        row.setName("动作" + id);
        row.setSourceName("gym-visual-exercises-dataset");
        row.setBodyPart(bodyPart);
        row.setTargetMuscles(target);
        row.setSecondaryMuscles(secondary);
        row.setEquipment(equipment);
        row.setDifficulty("入门");
        row.setMovementPattern("推");
        row.setPlanReady(planReady);
        return row;
    }

    private static RoutineFactRow factRow(String refId, String topic) {
        RoutineFactRow row = new RoutineFactRow();
        row.setRefId(refId);
        row.setTopic(topic);
        row.setFactZh("事实内容");
        row.setScope("成人 18+");
        row.setSource("来源机构");
        row.setSourceVersion("2015");
        return row;
    }

    /** 内存版 WeeklyPlanMapper（方法同步，支持并发激活测试；含激活路径计数与钩子）。 */
    private static final class FakeWeeklyPlanMapper implements WeeklyPlanMapper {
        final List<WeeklyPlanRow> plans = new ArrayList<>();
        final List<WeeklyPlanVersionRow> versions = new ArrayList<>();
        final List<WeeklyPlanItemRow> items = new ArrayList<>();
        int findPlanByIdCalls;
        int findPlanByIdForUpdateCalls;
        int findActiveByUserForUpdateCalls;
        int activatePlanCalls;
        int activatePlanAffectedRows;
        WeeklyPlanRow lastActivateArg;
        /** 测试钩子：insertVersion 抛异常（模拟 DB 故障）。 */
        volatile boolean failInsertVersion = false;
        /** 测试钩子：insertItem 抛异常（模拟 DB 故障，39 号票回滚语义）。 */
        volatile boolean failInsertItem = false;
        /** 测试钩子：updatePlan 抛异常（模拟 DB 故障，39 号票回滚语义）。 */
        volatile boolean failUpdatePlan = false;
        /** 测试钩子：在锁定现有 ACTIVE 后、activatePlan 前模拟其他事务已抢先提交。 */
        volatile Runnable onFindActiveForUpdate;

        @Override
        public synchronized WeeklyPlanRow findPlanById(Long id, Long userId) {
            findPlanByIdCalls++;
            return plans.stream().filter(row -> row.getId().equals(id) && row.getUserId().equals(userId))
                    .findFirst().map(WeeklyPlanServiceTest::copyPlan).orElse(null);
        }

        @Override
        public synchronized WeeklyPlanRow findPlanByIdForUpdate(Long id, Long userId) {
            findPlanByIdForUpdateCalls++;
            return plans.stream().filter(row -> row.getId().equals(id) && row.getUserId().equals(userId))
                    .findFirst().map(WeeklyPlanServiceTest::copyPlan).orElse(null);
        }

        @Override
        public synchronized WeeklyPlanRow findActiveByUser(Long userId) {
            return plans.stream().filter(row -> row.getUserId().equals(userId) && "ACTIVE".equals(row.getStatus()))
                    .findFirst().map(WeeklyPlanServiceTest::copyPlan).orElse(null);
        }

        @Override
        public synchronized WeeklyPlanRow findActiveByUserForUpdate(Long userId) {
            findActiveByUserForUpdateCalls++;
            if (onFindActiveForUpdate != null) {
                onFindActiveForUpdate.run();
            }
            return findActiveByUser(userId);
        }

        @Override
        public synchronized List<WeeklyPlanRow> listPlans(Long userId) {
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
        public synchronized int insertPlan(WeeklyPlanRow row) {
            row.setId((long) plans.size() + 1);
            plans.add(row);
            return 1;
        }

        @Override
        public synchronized int updatePlan(WeeklyPlanRow row) {
            if (failUpdatePlan) {
                throw new IllegalStateException("计划更新失败");
            }
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
            activatePlanCalls++;
            lastActivateArg = row;
            for (int i = 0; i < plans.size(); i++) {
                if (plans.get(i).getId().equals(row.getId())
                        && plans.get(i).getUserId().equals(row.getUserId())
                        && "DRAFT".equals(plans.get(i).getStatus())) {
                    plans.set(i, row);
                    activatePlanAffectedRows = 1;
                    return 1;
                }
            }
            activatePlanAffectedRows = 0;
            return 0;
        }

        @Override
        public synchronized int insertVersion(WeeklyPlanVersionRow row) {
            if (failInsertVersion) {
                throw new IllegalStateException("版本快照写入失败");
            }
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
            if (failInsertItem) {
                throw new IllegalStateException("项目写入失败");
            }
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

    /** 复制计划行：模拟"已提交的数据库状态"与调用方持有的对象分离（否则服务层内存修改会污染行锁判定）。 */
    private static WeeklyPlanRow copyPlan(WeeklyPlanRow row) {
        WeeklyPlanRow copy = new WeeklyPlanRow();
        copy.setId(row.getId());
        copy.setUserId(row.getUserId());
        copy.setStatus(row.getStatus());
        copy.setWeekStart(row.getWeekStart());
        copy.setTimezone(row.getTimezone());
        copy.setProfileVersionNo(row.getProfileVersionNo());
        copy.setCalorieLow(row.getCalorieLow());
        copy.setCalorieHigh(row.getCalorieHigh());
        copy.setRulesVersion(row.getRulesVersion());
        copy.setValidationLevel(row.getValidationLevel());
        copy.setValidationJson(row.getValidationJson());
        copy.setNote(row.getNote());
        copy.setSourceSessionId(row.getSourceSessionId());
        copy.setCurrentVersion(row.getCurrentVersion());
        copy.setCreatedAt(row.getCreatedAt());
        copy.setUpdatedAt(row.getUpdatedAt());
        return copy;
    }
}
