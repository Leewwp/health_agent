package com.diet.integration;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.plan.DraftPlanRequest;
import com.diet.health.plan.HealthPlanResponseAgentService;
import com.diet.health.plan.PatchItemRequest;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.WeeklyPlanComposerService;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.mapper.HealthProfileMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.service.trace.AgentTraceService;
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

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 57 号票：REVIEWED_DB 模式下周计划餐食候选与浏览/推荐/反馈同源的真实 MySQL 验证。
 * <p>
 * 在独立测试库 diet_db_itest 上（V1-V7 迁移 + 审核资源种子导入，295 道 APPROVED 公共餐食），
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

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc = new JdbcTemplate(dataSource);
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

    // ---------- 57 号票验收：餐食候选与浏览/推荐/反馈同源 ----------

    @Test
    void 审核模式下计划餐食全部来自审核候选且与浏览可见集合一致() {
        saveProfile();
        assertEquals(ResourceMode.REVIEWED_DB, resourceProvider.providerMode());
        List<PlanMealCandidate> candidates = resourceProvider.planMealCandidates();
        assertFalse(candidates.isEmpty(), "审核子集必须提供计划餐食候选");

        PlanView plan = planService().createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));

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
        PlanView plan = planService().createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));

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
        PlanView plan = planService().createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
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
        PlanView plan = planService().createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        com.diet.health.plan.PlanItemView exercise = plan.items().stream()
                .filter(item -> "EXERCISE".equals(item.resourceType()))
                .filter(item -> item.localDate().equals(MON))
                .findFirst().orElseThrow();

        // 1) 日期越界：稳定参数错误，回滚且不落库
        HealthApiException outOfRange = assertThrows(HealthApiException.class, () -> planService().patchItem(USER,
                plan.id(), exercise.id(),
                new PatchItemRequest(MON.plusDays(7), LocalTime.of(20, 0), LocalTime.of(21, 0), "越界到次周一")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, outOfRange.code());

        // 2) 跨午夜冲突（挪到周二早晨与前一晚跨午夜睡眠重叠）：HARD_ERROR，回滚且不落库
        HealthApiException overlap = assertThrows(HealthApiException.class, () -> planService().patchItem(USER,
                plan.id(), exercise.id(),
                new PatchItemRequest(MON.plusDays(1), LocalTime.of(6, 0), LocalTime.of(7, 0), "早起训练")));
        assertEquals(HealthApiException.CODE_RISK_BLOCKED, overlap.code());

        // 3) 数据库无半成品：项目保持原日期/时间，版本数与计划状态不变
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DATE_FORMAT(local_date, '%Y-%m-%d') AS d, TIME_FORMAT(start_time, '%H:%i') AS s,"
                        + " TIME_FORMAT(end_time, '%H:%i') AS e FROM weekly_plan_item WHERE id = ?",
                exercise.id());
        assertEquals(MON.toString(), String.valueOf(row.get("d")), "失败 PATCH 不得改动项目日期");
        assertEquals("19:30", String.valueOf(row.get("s")), "失败 PATCH 不得改动开始时间");
        assertEquals("21:00", String.valueOf(row.get("e")), "失败 PATCH 不得改动结束时间");
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weekly_plan_version v JOIN weekly_plan p ON p.id = v.plan_id"
                        + " WHERE p.user_id = ?",
                Integer.class, USER);
        assertEquals(1, versionCount, "失败路径不得新增版本快照");
        String status = jdbc.queryForObject("SELECT status FROM weekly_plan WHERE user_id = ?", String.class, USER);
        assertEquals("DRAFT", status, "计划保持 DRAFT 未激活");
    }
}
