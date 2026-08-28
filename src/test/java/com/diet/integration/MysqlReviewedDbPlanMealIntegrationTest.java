package com.diet.integration;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanScope;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.plan.HealthPlanResponseAgentService;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.MealPlanGenerationService;
import com.diet.health.plan.PatchItemRequest;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.GenerateTrainingPlanRequest;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.TrainingPlanGenerationResponse;
import com.diet.health.plan.TrainingPlanGenerationService;
import com.diet.health.plan.TrainingTimeWindow;
import com.diet.health.plan.WeeklyPlanComposerService;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.HealthResource;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.mapper.HealthProfileMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 57 号票：REVIEWED_DB 模式下周计划餐食候选与浏览/推荐/反馈同源的真实 MySQL 验证。
 * <p>
 * 在独立测试库 diet_db_itest 上（V1-V9 迁移 + 审核资源种子导入，295 道 APPROVED 公共餐食），
 * 生成周计划并核对：餐食项目资源 ID 全部来自 Provider 计划餐食候选；候选集合与浏览/推荐
 * 可见的 APPROVED 公共餐食集合一致；每道计划餐食可被 Provider 按 ID 解析（反馈校验同源）；
 * 快照来源字段同源不混入 fixture 资源。
 * <p>
 * 门控：-Ditest.mysql=true（CI 的 MySQL 服务容器与本地 MySQL 均为 root/123456）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/diet_db_itest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "diet.agent.mode=fixture",
        "diet.resource.mode=reviewed"
})
@EnabledIfSystemProperty(named = "itest.mysql", matches = "true")
class MysqlReviewedDbPlanMealIntegrationTest {

    private static final long USER = 880011L;
    private static final LocalDate MON = LocalDate.of(2026, 8, 17);

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private WeeklyPlanMapper realPlanMapper;
    @Autowired
    private HealthProfileMapper realProfileMapper;
    @Autowired
    private HealthProfileService realProfileService;
    @Autowired
    private HealthRiskRuleService riskRuleService;
    @Autowired
    private WeeklyPlanComposerService composer;
    @Autowired
    private PlanValidationService validationService;
    @Autowired
    private HealthResourceProvider resourceProvider;
    @Autowired
    private HealthPlanResponseAgentService planAgent;
    @Autowired
    private AgentTraceService traceService;
    @Autowired
    private HealthSessionService sessionService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WeeklyPlanService weeklyPlanService;
    @Autowired
    private TrainingPlanGenerationService trainingPlanGenerationService;
    @Autowired
    private MealPlanGenerationService mealPlanGenerationService;

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM weekly_plan_item WHERE plan_id IN (SELECT id FROM weekly_plan WHERE user_id >= 880000)");
        jdbc.update("DELETE FROM weekly_plan_version WHERE plan_id IN (SELECT id FROM weekly_plan WHERE user_id >= 880000)");
        jdbc.update("DELETE FROM weekly_plan WHERE user_id >= 880000");
        jdbc.update("DELETE FROM health_profile WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_sessions WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_request_trace WHERE user_id >= 880000");
    }

    // ---------- 工具 ----------

    private <T> T transactionalProxy(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.addAdvice(interceptor);
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    private WeeklyPlanService planService() {
        return transactionalProxy(new WeeklyPlanService(
                realProfileService, riskRuleService, composer, validationService,
                realPlanMapper, resourceProvider, planAgent, traceService, sessionService, objectMapper));
    }

    private HealthProfileService profileService() {
        return transactionalProxy(new HealthProfileService(realProfileMapper, objectMapper));
    }

    private void saveProfile() {
        profileService().saveProfile(USER, new HealthProfileService.HealthProfileInput(
                30, null, 175.0, 70.0,
                com.diet.health.enums.ActivityLevel.LIGHT,
                com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai", null, null));
    }

    private PlanBrief saveConfirmedBrief(String sessionId) {
        HealthResource candidate = resourceProvider.planReadyExercises().stream()
                .filter(item -> !item.tags().getOrDefault("trainingGoal", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("bodyParts", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("equipment", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("difficulty", List.of()).isEmpty())
                .findFirst().orElseThrow();
        PlanBrief brief = new PlanBrief(
                candidate.tags().get("trainingGoal").get(0),
                List.of(candidate.tags().get("bodyParts").get(0)),
                List.of(candidate.tags().get("equipment").get(0)),
                candidate.tags().get("difficulty").get(0),
                MON,
                List.of(DayOfWeek.MONDAY),
                new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                Map.of(), null, 0, null);
        sessionService.save(com.diet.health.session.HealthSessionState.fresh(sessionId, USER).withPlanBrief(brief));
        return brief;
    }

    private MealPlanBrief saveConfirmedMealBrief(String sessionId) {
        MealPlanBrief brief = new MealPlanBrief(MON, List.of("早餐", "午餐", "晚餐"), "保持健康");
        sessionService.save(com.diet.health.session.HealthSessionState.fresh(sessionId, USER)
                .withMealPlanBrief(brief));
        return brief;
    }

    private PlanView generateMealDraft(String sessionId, String requestId) {
        return mealPlanGenerationService.generate(USER,
                        new GenerateTrainingPlanRequest(sessionId, requestId, PlanScope.MEAL))
                .plan();
    }

    private TrainingPlanGenerationService generationService(AgentInvoker invoker) {
        AgentContractModule contract = new AgentContractModule(invoker, new LlmJsonService(objectMapper), traceService);
        return new TrainingPlanGenerationService(sessionService, realProfileService, riskRuleService,
                resourceProvider, validationService, composer, weeklyPlanService, contract, new PromptLoader(),
                traceService, objectMapper, "mysql-integration-model", 1000);
    }

    // ---------- #86 真实 MySQL：生成事务、并发幂等与慢模型事务边界 ----------

    @Test
    void 同一生成requestId并发请求只落一份草稿() throws Exception {
        saveProfile();
        String sessionId = "sess-plan-concurrent";
        saveConfirmedBrief(sessionId);
        GenerateTrainingPlanRequest request = new GenerateTrainingPlanRequest(sessionId, "mysql-plan-concurrent",
                PlanScope.EXERCISE);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<TrainingPlanGenerationResponse> responses = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    responses.add(trainingPlanGenerationService.generate(USER, request));
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "并发生成不应失败: " + failures);
        assertEquals(2, responses.size());
        assertEquals(responses.get(0).planId(), responses.get(1).planId());
        assertEquals(responses.get(0).traceId(), responses.get(1).traceId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plan WHERE user_id = ?", Integer.class, USER));
    }

    @Test
    void 慢模型调用发生在数据库事务之外() {
        saveProfile();
        String sessionId = "sess-plan-slow-agent";
        PlanBrief brief = saveConfirmedBrief(sessionId);
        HealthResource candidate = resourceProvider.planReadyExercises().stream()
                .filter(item -> item.tags().getOrDefault("trainingGoal", List.of()).contains(brief.trainingGoal()))
                .filter(item -> item.tags().getOrDefault("bodyParts", List.of()).contains(brief.bodyParts().get(0)))
                .filter(item -> item.tags().getOrDefault("equipment", List.of()).contains(brief.equipment().get(0)))
                .filter(item -> item.tags().getOrDefault("difficulty", List.of()).contains(brief.difficulty()))
                .findFirst().orElseThrow();
        AtomicBoolean transactionActiveDuringModel = new AtomicBoolean(true);
        AgentInvoker slowInvoker = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                transactionActiveDuringModel.set(TransactionSynchronizationManager.isActualTransactionActive());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                String output = "{\"schedule\":[{\"exerciseId\":\"" + candidate.resourceId()
                        + "\",\"localDate\":\"" + MON
                        + "\",\"startTime\":\"19:00\",\"durationMinutes\":45}]}";
                return new AgentInvocationResult(output, invocation.modelName(), 50);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };

        TrainingPlanGenerationResponse response = generationService(slowInvoker).generate(USER,
                new GenerateTrainingPlanRequest(sessionId, "mysql-plan-slow-agent", PlanScope.EXERCISE));

        assertFalse(transactionActiveDuringModel.get(), "外部模型等待期间不得持有数据库事务");
        assertEquals("AGENT", response.generationSource());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plan WHERE user_id = ?", Integer.class, USER));
    }

    @Test
    void 生成项目写入失败时计划版本和项目全部回滚() {
        saveProfile();
        String sessionId = "sess-plan-rollback";
        saveConfirmedBrief(sessionId);
        String trigger = "itest_fail_weekly_plan_item";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON weekly_plan_item FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'integration forced item failure'");
        try {
            assertThrows(RuntimeException.class, () -> trainingPlanGenerationService.generate(USER,
                    new GenerateTrainingPlanRequest(sessionId, "mysql-plan-rollback", PlanScope.EXERCISE)));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plan WHERE user_id = ?", Integer.class, USER));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plan_version v JOIN weekly_plan p ON p.id = v.plan_id WHERE p.user_id = ?", Integer.class, USER));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM weekly_plan_item i JOIN weekly_plan p ON p.id = i.plan_id WHERE p.user_id = ?", Integer.class, USER));
    }

    // ---------- 57 号票验收：餐食候选与浏览/推荐/反馈同源 ----------

    @Test
    void 审核模式下计划餐食全部来自审核候选且与浏览可见集合一致() {
        saveProfile();
        assertEquals(ResourceMode.REVIEWED_DB, resourceProvider.providerMode());
        List<PlanMealCandidate> candidates = resourceProvider.planMealCandidates();
        assertFalse(candidates.isEmpty(), "审核子集必须提供计划餐食候选");

        String sessionId = "sess-meal-candidates";
        saveConfirmedMealBrief(sessionId);
        PlanView plan = generateMealDraft(sessionId, "mysql-meal-candidates");

        Set<String> candidateIds = candidates.stream().map(PlanMealCandidate::resourceId).collect(Collectors.toSet());
        List<String> mealIds = plan.items().stream()
                .filter(item -> "MEAL".equals(item.resourceType()))
                .map(com.diet.health.plan.PlanItemView::resourceId)
                .toList();
        assertEquals(21, mealIds.size(), "一周应含 21 个餐食项目");
        assertTrue(candidateIds.containsAll(mealIds), "计划餐食必须全部来自审核候选: " + mealIds);
        assertTrue(mealIds.stream().noneMatch(id -> id.matches("M[1-9]|900[1-8]|R[1-5]")),
                "审核模式计划不得引用 fixture 资源 ID: " + mealIds);

        // 候选集合与浏览/推荐可见的 APPROVED 公共餐食一致（同一查询语义）
        List<String> browseIds = jdbc.queryForList(
                "SELECT id FROM meal_item WHERE source_type = 'PUBLIC' AND owner_user_id IS NULL"
                        + " AND review_status = 'APPROVED' ORDER BY id",
                Long.class).stream().map(String::valueOf).toList();
        assertEquals(browseIds, candidates.stream().map(PlanMealCandidate::resourceId).toList(),
                "Provider 餐食候选必须与浏览/推荐可见集合完全一致（数据库主键序）");
        assertEquals(295, browseIds.size(), "审核餐食发布基线 295 条");

        // 每道计划餐食可被 Provider 按 ID 解析（反馈校验与快照来源同源）
        for (String mealId : mealIds) {
            assertTrue(resourceProvider.mealById(mealId).isPresent(),
                    "计划餐食 ID 必须可被 Provider 解析: " + mealId);
        }
        assertTrue(PlanValidationLevel.OK == plan.validationLevel()
                        || PlanValidationLevel.WARNING == plan.validationLevel(),
                "审核模式草稿应可生成（允许能量 WARNING 但不得 HARD_ERROR）: " + plan.validationLevel());
    }

    @Test
    void 审核模式计划快照与项目来源字段同源() {
        saveProfile();
        String sessionId = "sess-meal-snapshot";
        saveConfirmedMealBrief(sessionId);
        PlanView plan = generateMealDraft(sessionId, "mysql-meal-snapshot");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.resource_type, i.resource_id, i.name FROM weekly_plan_item i"
                        + " JOIN weekly_plan p ON p.id = i.plan_id WHERE p.user_id = ?",
                USER);
        assertFalse(rows.isEmpty());
        Set<String> candidateIds = resourceProvider.planMealCandidates().stream()
                .map(PlanMealCandidate::resourceId).collect(Collectors.toSet());
        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.get("resource_type"));
            String id = String.valueOf(row.get("resource_id"));
            if ("MEAL".equals(type)) {
                assertTrue(candidateIds.contains(id), "餐食项目资源 ID 必须属于审核候选: " + id);
                assertTrue(resourceProvider.mealById(id).isPresent(), "餐食项目必须可解析来源: " + id);
            } else if ("EXERCISE".equals(type)) {
                assertTrue(resourceProvider.planReadyExerciseIds().contains(id), "训练项目必须 plan_ready: " + id);
            } else if ("ROUTINE".equals(type)) {
                assertTrue(resourceProvider.allFactIds().contains(id), "作息项目必须属于审核事实: " + id);
            }
        }

        Map<String, Object> version = jdbc.queryForMap(
                "SELECT v.resource_snapshot_json FROM weekly_plan_version v"
                        + " JOIN weekly_plan p ON p.id = v.plan_id WHERE p.user_id = ?"
                        + " ORDER BY v.version_no DESC LIMIT 1",
                USER);
        String snapshot = String.valueOf(version.get("resource_snapshot_json"));
        assertTrue(snapshot.contains("REVIEWED_DB"), "快照必须标识 Provider 模式 REVIEWED_DB");
        assertTrue(snapshot.contains("reviewed-2026-08-10-v1"), "快照必须标识审核子集资源版本");
        assertTrue(snapshot.contains("公共餐食库"), "餐食来源名必须为公共餐食库");
        assertTrue(snapshot.contains("sourceType"), "快照必须含来源类型字段");
        assertFalse(snapshot.contains("M1") || snapshot.contains("9001") || snapshot.contains("R1"),
                "快照不得混入 fixture 资源身份");
    }

    @Test
    void 审核模式计划餐食ID在反馈与浏览之间可互认() {
        saveProfile();
        String sessionId = "sess-meal-feedback";
        saveConfirmedMealBrief(sessionId);
        PlanView plan = generateMealDraft(sessionId, "mysql-meal-feedback");
        String mealId = plan.items().stream()
                .filter(item -> "MEAL".equals(item.resourceType()))
                .findFirst().orElseThrow().resourceId();
        // 反馈校验同源：Provider.mealById 能解析的计划餐食 ID 与浏览集合一致
        assertTrue(resourceProvider.mealById(mealId).isPresent(), "反馈校验必须能解析计划餐食: " + mealId);
        Long dbId = jdbc.queryForObject("SELECT id FROM meal_item WHERE id = ?", Long.class, Long.parseLong(mealId));
        assertEquals(Long.parseLong(mealId), dbId, "计划餐食 ID 必须直接对应 meal_item 主键（浏览/推荐同库）");
    }

    // ---- 60 号票：时间/日期不变量失败不产生半成品 ----

    @Test
    void 时间不变量失败不产生新版本或半成品项目() {
        saveProfile();
        String sessionId = "sess-exercise-patch";
        saveConfirmedBrief(sessionId);
        PlanView plan = trainingPlanGenerationService.generate(USER,
                        new GenerateTrainingPlanRequest(sessionId, "mysql-exercise-patch", PlanScope.EXERCISE))
                .plan();
        com.diet.health.plan.PlanItemView exercise = plan.items().stream()
                .filter(item -> "EXERCISE".equals(item.resourceType()))
                .filter(item -> item.localDate().equals(MON))
                .findFirst().orElseThrow();

        // 1) 日期越界：稳定参数错误，回滚且不落库
        HealthApiException outOfRange = assertThrows(HealthApiException.class, () -> planService().patchItem(USER,
                plan.id(), exercise.id(),
                new PatchItemRequest(MON.plusDays(7), LocalTime.of(20, 0), LocalTime.of(21, 0), "越界到次周一")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, outOfRange.code());

        // 2) 零时长区间：新范围计划不再隐式附带 ROUTINE，改由确定性时间 Guard 拒绝。
        HealthApiException overlap = assertThrows(HealthApiException.class, () -> planService().patchItem(USER,
                plan.id(), exercise.id(),
                new PatchItemRequest(null, LocalTime.of(19, 30), LocalTime.of(19, 30), "零时长训练")));
        assertEquals(HealthApiException.CODE_PLAN_TIME_CONFLICT, overlap.code());

        // 3) 数据库无半成品：项目保持原日期/时间，版本数与计划状态不变
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DATE_FORMAT(local_date, '%Y-%m-%d') AS d, TIME_FORMAT(start_time, '%H:%i') AS s,"
                        + " TIME_FORMAT(end_time, '%H:%i') AS e FROM weekly_plan_item WHERE id = ?",
                exercise.id());
        assertEquals(MON.toString(), String.valueOf(row.get("d")), "失败 PATCH 不得改动项目日期");
        assertEquals("19:00", String.valueOf(row.get("s")), "失败 PATCH 不得改动开始时间");
        assertEquals("20:00", String.valueOf(row.get("e")), "失败 PATCH 不得改动结束时间");
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weekly_plan_version v JOIN weekly_plan p ON p.id = v.plan_id"
                        + " WHERE p.user_id = ?",
                Integer.class, USER);
        assertEquals(1, versionCount, "失败路径不得新增版本快照");
        String status = jdbc.queryForObject("SELECT status FROM weekly_plan WHERE user_id = ?", String.class, USER);
        assertEquals("DRAFT", status, "计划保持 DRAFT 未激活");
    }
}
