package com.diet.integration;

import com.diet.constants.DietConstants;
import com.diet.exception.DietException;
import com.diet.health.model.HealthFeedbackRequest;
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
import com.diet.health.session.HealthSessionState;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 事务回滚与行锁集成验证（39 号票剩余项，38 号总验收；62 号票补风险字段；#74 补 traceId 归因）：
 * 在独立测试库 diet_db_itest 上验证 V1-V9 迁移、saveProfile/createDraft/activate
 * 任一步写入失败时数据库无半成品、并发激活只产生一个有效 ACTIVE、
 * 激活后档案版本与能量区间与快照一致、档案版本号连续唯一、风险档案阻断后无残留。
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
    @Autowired
    private com.diet.mapper.FeedbackMapper realFeedbackMapper;
    @Autowired
    private com.diet.health.feedback.HealthFeedbackService feedbackService;
    @Autowired
    private com.diet.health.feedback.PreferenceService preferenceService;

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc = new JdbcTemplate(dataSource);
        // 顺序敏感：weekly_plan / health_profile 删除时级联清理其 version/item（V4 外键 ON DELETE CASCADE）
        jdbc.update("DELETE FROM weekly_plan WHERE user_id >= 880000");
        jdbc.update("DELETE FROM health_profile WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_sessions WHERE user_id >= 880000");
        jdbc.update("DELETE FROM diet_request_trace WHERE user_id >= 880000");
        jdbc.update("DELETE FROM recommend_feedback WHERE user_id >= 880000");
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
                com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai", null, null);
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
    void 干净库V1至V9迁移全部成功() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(9, rows.size(), "干净库应执行 V1-V9 共 9 条迁移");
        assertTrue(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))),
                "全部迁移必须标记成功");
        assertEquals("1", String.valueOf(rows.get(0).get("version")), "V1 旧库基线最先执行");
        assertEquals("9", String.valueOf(rows.get(8).get("version")), "V9 最后执行");
    }

    @Test
    void 风险档案createDraft被阻断时计划版本与项目均无残留() {
        profileService().saveProfile(USER, new HealthProfileService.HealthProfileInput(
                30, null, 175.0, 70.0,
                com.diet.health.enums.ActivityLevel.LIGHT,
                com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai",
                List.of(com.diet.health.enums.ProfileRiskCondition.CHRONIC_CONDITION), "高血压服药中"));
        String stored = jdbc.queryForObject(
                "SELECT risk_conditions_json FROM health_profile WHERE user_id = ?", String.class, USER);
        assertTrue(stored != null && stored.contains("CHRONIC_CONDITION"),
                "结构化风险条件必须落库，实际: " + stored);

        WeeklyPlanService service = planService(realPlanMapper);
        com.diet.exception.HealthApiException error = assertThrows(com.diet.exception.HealthApiException.class,
                () -> service.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null)),
                "风险档案创建计划必须被阻断");
        assertEquals("RISK_BLOCKED", error.code());
        assertEquals(0, count("weekly_plan", "user_id", USER), "计划不得残留半成品");
        assertEquals(0, countByPlan("weekly_plan_version", USER), "版本不得残留半成品");
        assertEquals(0, countByPlan("weekly_plan_item", USER), "项目不得残留半成品");
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
        List<String> riskColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'health_profile' AND column_name IN ('risk_conditions_json','risk_note')",
                String.class);
        assertEquals(2, riskColumns.size(), "V7 结构化风险列必须存在（risk_conditions_json/risk_note）");
        List<String> traceColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'recommend_feedback' AND column_name IN ('trace_id','item_id','resource_type')",
                String.class);
        assertEquals(3, traceColumns.size(), "V8 加 trace_id 且不破坏 item_id/resource_type 兼容列");
        Integer traceIndexes = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                        + " WHERE table_schema = 'diet_db_itest' AND table_name = 'recommend_feedback'"
                        + " AND index_name = 'idx_feedback_trace'",
                Integer.class);
        assertEquals(1, traceIndexes, "(user_id, trace_id) 普通索引必须存在（V8）");
        Integer traceColumnLen = jdbc.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'recommend_feedback' AND column_name = 'trace_id'",
                Integer.class);
        assertEquals(128, traceColumnLen, "trace_id 长度上限必须与 diet_request_trace.trace_id 一致（128）");
        List<String> evalColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'diet_request_trace'"
                        + " AND column_name IN ('evaluation_schema_version','expected_health_json')",
                String.class);
        assertEquals(2, evalColumns.size(), "V9 评估标注列必须存在（evaluation_schema_version/expected_health_json）");
        String evalColumnType = jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = 'diet_db_itest'"
                        + " AND table_name = 'diet_request_trace' AND column_name = 'expected_health_json'",
                String.class);
        assertEquals("json", evalColumnType, "expected_health_json 必须为 JSON 类型（V9）");
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
                        com.diet.health.enums.ProfileGoal.MAINTAIN, "Asia/Shanghai", null, null));
        assertEquals(1L, first.versionNo());
        assertEquals(2L, second.versionNo());
        assertEquals(2, count("health_profile_version", "user_id", USER), "两版快照全部落库");
        assertEquals(1, count("health_profile", "user_id", USER), "当前档案只有一份");
    }

    // ---------- 56 号票：并发首次创建默认会话幂等恢复 ----------

    @Test
    void 并发首次创建默认会话均成功且返回同一会话() throws Exception {
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<HealthSessionState> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    results.add(sessionService.loadOrCreate(null, USER));
                } catch (Exception error) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发首次创建应在限时内完成");
        assertEquals(0, failures.get(), "并发首次创建默认会话不得误报无权访问");
        assertEquals(2, results.size());
        assertEquals(1, results.stream().map(HealthSessionState::sessionId).distinct().count(),
                "双方必须返回同一默认会话");
        assertEquals(1, count("diet_sessions", "user_id", USER), "默认会话只能落库一条");
    }

    @Test
    void 显式sessionId被其他用户占用时仍拒绝() {
        long owner = 880002L;
        long other = 880003L;
        String sessionId = "sess_itest_cross_user";
        sessionService.loadOrCreate(sessionId, owner);
        DietException error = assertThrows(DietException.class,
                () -> sessionService.loadOrCreate(sessionId, other));
        assertEquals("会话不存在或无权访问", error.getMessage());
        assertEquals(1, count("diet_sessions", "user_id", owner), "占用者的会话不被破坏");
    }

    @Test
    void 并发首次创建周计划草稿时默认会话竞态自动恢复() throws Exception {
        saveProfile();
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<PlanView> plans = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            WeeklyPlanService instance = planService(realPlanMapper);
            new Thread(() -> {
                try {
                    start.await();
                    plans.add(instance.createDraft(USER, new DraftPlanRequest(null, MON, "Asia/Shanghai", null)));
                } catch (Exception error) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发建草稿应在限时内完成");
        assertEquals(0, failures.get(), "周计划并发首次创建不得因默认会话竞态失败");
        assertEquals(2, plans.size());
        assertEquals(2, countWhereStatus("weekly_plan", "user_id", USER, "DRAFT"), "两份草稿均落库");
        Integer distinctSources = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT source_session_id) FROM weekly_plan WHERE user_id = ?",
                Integer.class, USER);
        assertEquals(1, distinctSources, "两份草稿必须引用同一来源会话");
        assertEquals(1, count("diet_sessions", "user_id", USER), "默认会话只能落库一条");
    }

    // ---------- #65：反馈完整序列经真实 findRecent 折叠与 100 条窗口 ----------

    @Test
    void 反馈完整序列经真实findRecent折叠成两维状态() {
        // EXERCISE:9001：DISLIKE -> FAVORITE -> UNFAVORITE = 未收藏 + NEUTRAL
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-a", "EXERCISE", "9001", "DISLIKE", null, null, null, null, null));
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-b", "EXERCISE", "9001", "FAVORITE", null, null, null, null, null));
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-c", "EXERCISE", "9001", "UNFAVORITE", null, null, null, null, null));
        // MEAL:M1：DISLIKE -> FAVORITE -> LIKE -> UNFAVORITE = 未收藏 + POSITIVE
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-d", "MEAL", "M1", "DISLIKE", null, null, null, null, null));
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-e", "MEAL", "M1", "FAVORITE", null, null, null, null, null));
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-f", "MEAL", "M1", "LIKE", null, null, null, null, null));
        feedbackService.save(USER, new HealthFeedbackRequest("sess-65-g", "MEAL", "M1", "UNFAVORITE", null, null, null, null, null));

        List<com.diet.model.FeedbackRow> recent = realFeedbackMapper.findRecent(USER, 100);
        assertEquals(7, recent.size(), "UNFAVORITE 必须真实持久化");
        // findRecent 按 created_at DESC, id DESC：同秒插入时按 id DESC 保证最新在前；
        // 逆序展开必为严格时序（同秒内 id 逆序还原），避免跨秒边界导致的位置漂移
        assertEquals(List.of("DISLIKE", "FAVORITE", "UNFAVORITE", "DISLIKE", "FAVORITE", "LIKE", "UNFAVORITE"),
                recent.stream().map(com.diet.model.FeedbackRow::getAction).toList().reversed(),
                "逆序展开必须与提交序列完全一致（最新在前，同秒按 id DESC）");

        assertEquals(Set.of(), preferenceService.favoriteKeysFor(USER),
                "两个序列的最终收藏状态都必须为 false");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DietConstants.USER_ID_ATTRIBUTE, USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<com.diet.health.module.HealthResource> result = preferenceService.applyPreference(
                    List.of(
                            new com.diet.health.module.HealthResource("MEAL", "M1", "a", "PUBLIC", "s", null, false, java.util.Map.of()),
                            new com.diet.health.module.HealthResource("EXERCISE", "9001", "b", "PUBLIC", "s", null, true, java.util.Map.of()),
                            new com.diet.health.module.HealthResource("MEAL", "M3", "c", "PUBLIC", "s", null, false, java.util.Map.of())));
            assertEquals(List.of("MEAL:M1", "EXERCISE:9001", "MEAL:M3"), result.stream()
                            .map(item -> item.resourceType() + ":" + item.resourceId()).toList(),
                    "MEAL:M1 保持 POSITIVE 提升置前，EXERCISE:9001 为 NEUTRAL 不排除也不提升且顺序稳定");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void findRecent只返回最近100条且折叠不越窗() {
        // 105 条事件：第 101-105 条（最旧）不得参与偏好聚合
        for (int i = 0; i < 105; i++) {
            jdbc.update("INSERT INTO recommend_feedback (user_id, session_id, item_id, resource_type, resource_id,"
                            + " plan_id, plan_item_id, action, rating, reason, source, created_at)"
                            + " VALUES (?, ?, NULL, 'MEAL', ?, NULL, NULL, 'DISLIKE', NULL, NULL, 'HEALTH_CHAT', ?)",
                    USER, "sess-65-window-" + i, "M" + (1 + (i % 9)), java.sql.Timestamp.valueOf(LocalDateTime.now()));
        }
        List<com.diet.model.FeedbackRow> recent = realFeedbackMapper.findRecent(USER, 100);
        assertEquals(100, recent.size(), "全局读取窗口只能取最近 100 条");
        assertEquals(105, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommend_feedback WHERE user_id = ?", Integer.class, USER));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DietConstants.USER_ID_ATTRIBUTE, USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<com.diet.health.module.HealthResource> result = preferenceService.applyPreference(
                    List.of(new com.diet.health.module.HealthResource("MEAL", "M1", "a", "PUBLIC", "s", null, false, java.util.Map.of())));
            assertTrue(result.isEmpty(), "窗口内 100 条全部是 DISLIKE，M1 必须被排除");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // ---------- #74：反馈 traceId 精确归因在真实 MySQL 上落库与查询 ----------

    @Test
    void traceId反馈经真实服务落库并可按trace精确读取() throws Exception {
        String sessionId = "sess-74";
        String traceId = "trace_74_itest";
        // 先落一条归属该用户的真实 Trace（response_json 为健康聊天最终响应快照）。
        String responseJson = objectMapper.writeValueAsString(com.diet.health.model.HealthChatResponse.answer(
                sessionId, traceId, com.diet.health.enums.HealthDomain.EXERCISE,
                com.diet.health.enums.HealthTask.RECOMMEND, List.of(),
                com.diet.health.enums.HealthPhase.RESPOND, "推荐如下",
                List.of(new com.diet.health.model.HealthDisplayBlock(
                        "EXERCISE", "9001", "俯卧撑", "PUBLIC", "来源", null, true, null))));
        jdbc.update("INSERT INTO diet_request_trace (trace_id, request_id, session_id, user_id, status, event_count,"
                        + " trace_json, response_json, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'SUCCESS', 0, '{}', ?, NOW(), NOW())",
                traceId, traceId, sessionId, USER, responseJson);

        // 合法归因：资源在该 trace 推荐结果中，真实服务写入并携带 traceId。
        feedbackService.save(USER, new HealthFeedbackRequest(sessionId, "EXERCISE", "9001", "LIKE",
                null, null, 5, "练了很舒服", null, traceId));
        List<com.diet.model.FeedbackRow> byTrace = realFeedbackMapper.findByTraceIds(USER, List.of(traceId));
        assertEquals(1, byTrace.size(), "按 traceId 必须精确读到一条反馈");
        assertEquals(traceId, byTrace.get(0).getTraceId());
        assertEquals("EXERCISE", byTrace.get(0).getResourceType());
        assertEquals("9001", byTrace.get(0).getResourceId());
        assertEquals("HEALTH_CHAT", byTrace.get(0).getSource());

        // 精确归因后，session 回退查询不得再读到这条带 traceId 的新反馈。
        assertTrue(realFeedbackMapper.findBySessions(
                USER, List.of(sessionId), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)).isEmpty(),
                "带 trace_id 的反馈不得混入 session 回退归因");
    }

    @Test
    void traceId反馈跨用户与资源不匹配均被拒绝且不落库() throws Exception {
        String sessionId = "sess-74-b";
        String traceId = "trace_74_cross";
        jdbc.update("INSERT INTO diet_request_trace (trace_id, request_id, session_id, user_id, status, event_count,"
                        + " trace_json, response_json, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'SUCCESS', 0, '{}', ?, NOW(), NOW())",
                traceId, traceId, sessionId, USER,
                objectMapper.writeValueAsString(com.diet.health.model.HealthChatResponse.answer(
                        sessionId, traceId, com.diet.health.enums.HealthDomain.MEAL,
                        com.diet.health.enums.HealthTask.RECOMMEND, List.of(),
                        com.diet.health.enums.HealthPhase.RESPOND, "推荐如下",
                        List.of(new com.diet.health.model.HealthDisplayBlock(
                                "MEAL", "M1", "示例餐", "PUBLIC", "来源", null, false, null)))));

        // 跨用户：Trace 属于 USER，另一用户反馈必须 404 且不写入。
        com.diet.exception.HealthApiException crossUser = assertThrows(
                com.diet.exception.HealthApiException.class,
                () -> feedbackService.save(USER + 1, new HealthFeedbackRequest(
                        sessionId, "MEAL", "M1", "LIKE", null, null, null, null, null, traceId)));
        assertEquals("NOT_FOUND", crossUser.code());
        assertTrue(crossUser.getMessage().contains("无权访问"));

        // 资源不匹配：该 trace 只推荐 MEAL:M1，对 EXERCISE:9001 反馈必须 400 且不写入。
        com.diet.exception.HealthApiException wrongResource = assertThrows(
                com.diet.exception.HealthApiException.class,
                () -> feedbackService.save(USER, new HealthFeedbackRequest(
                        sessionId, "EXERCISE", "9001", "LIKE", null, null, null, null, null, traceId)));
        assertEquals("BAD_REQUEST", wrongResource.code());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommend_feedback WHERE user_id = ? AND session_id = ?",
                Integer.class, USER, sessionId), "两处非法归因都不得落库");
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
