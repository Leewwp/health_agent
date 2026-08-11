package com.diet.integration;

import com.diet.health.plan.DraftPlanRequest;
import com.diet.health.plan.HealthPlanResponseAgentService;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.plan.PlanView;
import com.diet.health.plan.WeeklyPlanComposerService;
import com.diet.health.plan.WeeklyPlanService;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.mapper.HealthProfileMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 事务回滚与行锁集成验证（39 号票剩余项，38 号总验收）：
 * 在独立测试库 diet_db_itest 上验证 V1-V6 迁移、saveProfile/createDraft/activate
 * 任一步写入失败时数据库无半成品、并发激活只产生一个有效 ACTIVE、
 * 激活后档案版本与能量区间与快照一致、档案版本号连续唯一。
 * <p>
 * 门控：-Ditest.mysql=true（CI 的 MySQL 服务容器与本地 MySQL 均为 root/123456）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/diet_db_itest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "diet.agent.mode=fixture",
        "diet.resource.mode=fixture",
        "diet.seed.reviewed-resources=false"
})
@EnabledIfSystemProperty(named = "itest.mysql", matches = "true")
class MysqlTransactionIntegrationTest {

    private static final long USER = 880001L;
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
        // 顺序敏感：weekly_plan / health_profile 删除时级联清理其 version/item（V4 外键 ON DELETE CASCADE）
        jdbc.update("DELETE FROM weekly_plan WHERE user_id >= 880000");
        jdbc.update("DELETE FROM health_profile WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_sessions WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_request_trace WHERE user_id >= 880000");
    }

    // ---------- 工具 ----------

    /** 用真实事务管理器包装手动装配的 service，等价于 Spring 代理（@Transactional 生效）。 */
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

    private WeeklyPlanService planService(WeeklyPlanMapper mapper) {
        return transactionalProxy(new WeeklyPlanService(
                realProfileService, riskRuleService, composer, validationService,
                mapper, resourceProvider, planAgent, traceService, sessionService, objectMapper));
    }

    private HealthProfileService profileService() {
        return transactionalProxy(new HealthProfileService(realProfileMapper, objectMapper));
    }

    private HealthProfileService.HealthProfileInput profileInput() {
        return new HealthProfileService.HealthProfileInput(
                30, null, 175.0, 70.0,
                com.diet.health.enums.ActivityLevel.LIGHT,
                com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai");
    }

    private void saveProfile() {
        profileService().saveProfile(USER, profileInput());
    }

    private int count(String table, String column, long userId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    /** weekly_plan_item / weekly_plan_version 无 user_id 列，按 plan_id 归属查询。 */
    private int countByPlan(String table, long userId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " i JOIN weekly_plan p ON p.id = i.plan_id"
                        + " WHERE p.user_id = ?",
                Integer.class, userId);
        return value == null ? 0 : value;
    }

    // ---------- 迁移 ----------

    @Test
    void 干净库V1至V6迁移全部成功() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(6, rows.size(), "干净库应执行 V1-V6 共 6 条迁移");
        assertTrue(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))),
                "全部迁移必须标记成功");
        assertEquals("1", String.valueOf(rows.get(0).get("version")), "V1 旧库基线最先执行");
        assertEquals("6", String.valueOf(rows.get(5).get("version")), "V6 最后执行");
    }

    @Test
    void 迁移后业务表结构与约束存在() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name IN ('weekly_plan','weekly_plan_version','weekly_plan_item',"
                        + "'health_profile','health_profile_version','recommend_feedback','meal_item_embedding')",
                String.class);
        assertEquals(7, tables.size(), "领域表必须全部存在");
        Integer actives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'weekly_plan' AND index_name = 'uk_plan_active_user_key'",
                Integer.class);
        assertEquals(1, actives, "ACTIVE 唯一约束必须存在（V6）");
    }

    // ---------- 39 号票验收点：任一步失败数据库无半成品 ----------

    @Test
    void saveProfile版本快照写入失败时档案表无残留() {
        FaultyHealthProfileMapper mapper = new FaultyHealthProfileMapper(realProfileMapper);
        mapper.failInsertVersion = true;
        HealthProfileService service = transactionalProxy(
                new HealthProfileService(mapper, objectMapper));
        assertThrows(IllegalStateException.class, () -> service.saveProfile(USER, profileInput()),
                "版本快照写入失败必须抛出异常");
        assertEquals(0, count("health_profile", "user_id", USER), "当前档案不得残留半成品");
        assertEquals(0, count("health_profile_version", "user_id", USER), "版本快照不得残留半成品");
    }

    @Test
    void createDraft项目写入失败时计划版本与项目均无残留() {
        saveProfile();
        FaultyWeeklyPlanMapper mapper = new FaultyWeeklyPlanMapper(realPlanMapper);
        mapper.failInsertItem = true;
        WeeklyPlanService service = planService(mapper);
        assertThrows(IllegalStateException.class,
                () -> service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null)),
                "项目写入失败必须抛出异常");
        assertEquals(0, count("weekly_plan", "user_id", USER), "计划不得残留半成品");
        assertEquals(0, countByPlan("weekly_plan_version", USER), "版本不得残留半成品");
        assertEquals(0, countByPlan("weekly_plan_item", USER), "项目不得残留半成品");
    }

    @Test
    void 激活版本快照写入失败时计划保持DRAFT且无ACTIVE() {
        saveProfile();
        WeeklyPlanService service = planService(realPlanMapper);
        PlanView draft = service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        FaultyWeeklyPlanMapper mapper = new FaultyWeeklyPlanMapper(realPlanMapper);
        mapper.failInsertVersion = true;
        WeeklyPlanService failing = planService(mapper);
        assertThrows(IllegalStateException.class, () -> failing.activate(USER, draft.id()),
                "激活时版本快照失败必须抛出异常");
        String status = jdbc.queryForObject(
                "SELECT status FROM weekly_plan WHERE id = ?", String.class, draft.id());
        assertEquals("DRAFT", status, "目标计划必须保持 DRAFT");
        assertEquals(0, countWhereStatus("weekly_plan", "user_id", USER, "ACTIVE"),
                "不得产生 ACTIVE 计划");
        assertEquals(1, countWhereStatus("weekly_plan", "user_id", USER, "DRAFT"), "仍只有原草稿");
    }

    @Test
    void 激活归档旧ACTIVE失败时旧计划保持ACTIVE() {
        saveProfile();
        WeeklyPlanService service = planService(realPlanMapper);
        PlanView first = service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        service.activate(USER, first.id());
        PlanView second = service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        FaultyWeeklyPlanMapper mapper = new FaultyWeeklyPlanMapper(realPlanMapper);
        mapper.failUpdatePlan = true;
        WeeklyPlanService failing = planService(mapper);
        assertThrows(IllegalStateException.class, () -> failing.activate(USER, second.id()),
                "归档更新失败必须抛出异常");
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM weekly_plan WHERE id = ?", String.class, first.id()),
                "旧 ACTIVE 必须保持 ACTIVE");
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM weekly_plan WHERE id = ?", String.class, second.id()),
                "新草稿必须保持 DRAFT");
    }

    // ---------- 39 号票验收点：并发激活与快照一致 ----------

    @Test
    void 并发激活同一草稿只有一个成功() throws Exception {
        saveProfile();
        WeeklyPlanService service = planService(realPlanMapper);
        PlanView draft = service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            WeeklyPlanService instance = planService(realPlanMapper);
            new Thread(() -> {
                try {
                    start.await();
                    instance.activate(USER, draft.id());
                    ok.incrementAndGet();
                } catch (Exception error) {
                    conflict.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发激活应在限时内完成");
        assertEquals(1, ok.get(), "同一草稿只能激活成功一次");
        assertEquals(1, conflict.get(), "输掉竞争的请求必须得到可解释结果");
        assertEquals(1, countWhereStatus("weekly_plan", "user_id", USER, "ACTIVE"),
                "数据库只能有一条 ACTIVE（行锁 + V6 唯一约束兜底）");
    }

    @Test
    void 激活后重新查询档案版本与能量区间与快照一致() {
        HealthProfileService.HealthProfileView profile =
                profileService().saveProfile(USER, profileInput());
        WeeklyPlanService service = planService(realPlanMapper);
        PlanView draft = service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null));
        PlanView active = service.activate(USER, draft.id());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT profile_version_no, calorie_low, calorie_high FROM weekly_plan WHERE id = ?",
                active.id());
        assertEquals(profile.versionNo(), ((Number) row.get("profile_version_no")).longValue(),
                "激活后档案版本与激活时快照一致");
        assertEquals(profile.calorieLow(), ((Number) row.get("calorie_low")).intValue(),
                "激活后能量下限与激活时快照一致");
        assertEquals(profile.calorieHigh(), ((Number) row.get("calorie_high")).intValue(),
                "激活后能量上限与激活时快照一致");
        assertEquals(2L, active.currentVersion(), "激活后版本号递增");
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM weekly_plan WHERE id = ?", String.class, active.id()));
    }

    @Test
    void 档案连续保存产生连续且唯一版本号() {
        HealthProfileService service = profileService();
        HealthProfileService.HealthProfileView first =
                service.saveProfile(USER, profileInput());
        HealthProfileService.HealthProfileView second =
                service.saveProfile(USER, new HealthProfileService.HealthProfileInput(
                        31, null, 176.0, 71.0,
                        com.diet.health.enums.ActivityLevel.LIGHT,
                        com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai"));
        assertEquals(1L, first.versionNo());
        assertEquals(2L, second.versionNo());
        assertEquals(2, count("health_profile_version", "user_id", USER), "两版快照全部落库");
        assertEquals(1, count("health_profile", "user_id", USER), "当前档案只有一份");
    }

    // ---------- 28 号矩阵：MySQL 不可用有固定结果 ----------

    @Test
    void MySQL不可用时连接快速失败且错误类型确定() {
        com.zaxxer.hikari.HikariDataSource deadDb = new com.zaxxer.hikari.HikariDataSource();
        deadDb.setJdbcUrl("jdbc:mysql://127.0.0.1:3307/nowhere?connectTimeout=500&socketTimeout=500");
        deadDb.setUsername("root");
        deadDb.setPassword("123456");
        deadDb.setMaximumPoolSize(1);
        deadDb.setConnectionTimeout(1000);
        deadDb.setInitializationFailTimeout(-1);
        try {
            long start = System.nanoTime();
            org.springframework.dao.DataAccessResourceFailureException error = assertThrows(
                    org.springframework.dao.DataAccessResourceFailureException.class,
                    () -> new JdbcTemplate(deadDb).queryForObject("SELECT 1", Integer.class),
                    "MySQL 不可用时查询必须快速失败且错误类型确定（不挂起、不吞错）");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5000, "连接失败必须在限时内返回，实际耗时 " + elapsedMs + "ms");
            Throwable cause = error.getCause();
            assertTrue(cause instanceof java.sql.SQLException
                            || String.valueOf(error).contains("Failed to obtain JDBC Connection"),
                    "根因应为连接建立失败，实际: " + error);
        } finally {
            deadDb.close();
        }
    }

    // ---------- 断言辅助 ----------

    private int countWhereStatus(String table, String column, long userId, String status) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ? AND status = ?",
                Integer.class, userId, status);
        return value == null ? 0 : value;
    }

    // ---------- 故障注入 Mapper ----------

    /** 委托真实 mapper，可对单个写入方法注入故障（模拟数据库故障，39 号票回滚语义）。 */
    static final class FaultyWeeklyPlanMapper implements WeeklyPlanMapper {
        private final WeeklyPlanMapper delegate;
        volatile boolean failInsertItem;
        volatile boolean failInsertVersion;
        volatile boolean failUpdatePlan;

        FaultyWeeklyPlanMapper(WeeklyPlanMapper delegate) {
            this.delegate = delegate;
        }

        @Override public WeeklyPlanRow findPlanById(Long id, Long userId) {
            return delegate.findPlanById(id, userId);
        }

        @Override public WeeklyPlanRow findPlanByIdForUpdate(Long id, Long userId) {
            return delegate.findPlanByIdForUpdate(id, userId);
        }

        @Override public WeeklyPlanRow findActiveByUser(Long userId) {
            return delegate.findActiveByUser(userId);
        }

        @Override public WeeklyPlanRow findActiveByUserForUpdate(Long userId) {
            return delegate.findActiveByUserForUpdate(userId);
        }

        @Override public List<WeeklyPlanRow> listPlans(Long userId) {
            return delegate.listPlans(userId);
        }

        @Override public int insertPlan(WeeklyPlanRow row) {
            return delegate.insertPlan(row);
        }

        @Override public int updatePlan(WeeklyPlanRow row) {
            if (failUpdatePlan) {
                throw new IllegalStateException("计划更新失败");
            }
            return delegate.updatePlan(row);
        }

        @Override public int activatePlan(WeeklyPlanRow row) {
            return delegate.activatePlan(row);
        }

        @Override public int insertVersion(WeeklyPlanVersionRow row) {
            if (failInsertVersion) {
                throw new IllegalStateException("版本快照写入失败");
            }
            return delegate.insertVersion(row);
        }

        @Override public List<WeeklyPlanItemRow> findItems(Long planId, Long versionNo) {
            return delegate.findItems(planId, versionNo);
        }

        @Override public WeeklyPlanItemRow findItemById(Long itemId) {
            return delegate.findItemById(itemId);
        }

        @Override public int insertItem(WeeklyPlanItemRow row) {
            if (failInsertItem) {
                throw new IllegalStateException("项目写入失败");
            }
            return delegate.insertItem(row);
        }

        @Override public int updateItemSchedule(WeeklyPlanItemRow row) {
            return delegate.updateItemSchedule(row);
        }
    }

    /** 委托真实 mapper，可对档案版本快照写入注入故障。 */
    static final class FaultyHealthProfileMapper implements HealthProfileMapper {
        private final HealthProfileMapper delegate;
        volatile boolean failInsertVersion;

        FaultyHealthProfileMapper(HealthProfileMapper delegate) {
            this.delegate = delegate;
        }

        @Override public com.diet.model.HealthProfileRow findByUserId(Long userId) {
            return delegate.findByUserId(userId);
        }

        @Override public com.diet.model.HealthProfileRow findByUserIdForUpdate(Long userId) {
            return delegate.findByUserIdForUpdate(userId);
        }

        @Override public int insert(com.diet.model.HealthProfileRow row) {
            return delegate.insert(row);
        }

        @Override public int update(com.diet.model.HealthProfileRow row) {
            return delegate.update(row);
        }

        @Override public int insertVersion(com.diet.model.HealthProfileVersionRow row) {
            if (failInsertVersion) {
                throw new IllegalStateException("档案版本快照写入失败");
            }
            return delegate.insertVersion(row);
        }
    }

}
