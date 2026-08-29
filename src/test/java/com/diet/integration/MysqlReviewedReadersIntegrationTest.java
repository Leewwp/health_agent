package com.diet.integration;

import com.diet.health.browse.ExerciseBrowseService;
import com.diet.health.browse.MealBrowseService;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.HybridMealRetriever;
import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.rag.StructuredMealRetriever;
import com.diet.health.module.HealthResource;
import com.diet.health.reader.exercise.DbReviewedExerciseReader;
import com.diet.health.reader.exercise.ExerciseVocabulary;
import com.diet.health.reader.exercise.ReviewedExercise;
import com.diet.health.reader.meal.DbReviewedMealReader;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.vectorstore.InMemoryVectorStore;
import com.diet.health.vectorstore.VectorPoint;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #68/#69/#70 方案 B 读取边界：审核读取模块、浏览/检索与批处理数据面的真实 MySQL 门控验证。
 * <p>
 * 在独立测试库 diet_db_itest 上（V1-V24 迁移 + 资源种子导入，295 道 APPROVED 公共餐食、
 * 1324 个本地动作目录项，自动资格补全后全部具备计划资格），验证：
 * <ul>
 *   <li>#68 审核餐食/动作读取模块真库行为：仅 APPROVED + PUBLIC 返回（审核/来源过滤）、
 *       browse 分页计数、findByIds 批量回查去重、id 升序稳定排序；</li>
 *   <li>#69 reviewed 浏览服务（MealBrowseService/ExerciseBrowseService）真库分页走查；</li>
 *   <li>#69 Structured/Hybrid 二次校验：从真库召回后按 ID 回查确认一致——向量独有候选、
 *       过期索引命中、过期过敏原 payload 均以 MySQL 为事实源裁决。</li>
 *   <li>#70 审核快照的来源映射与 embedding 行按餐食 ID 一致，且不混入非审核数据。</li>
 * </ul>
 * 用例内自造的小批量行（source_name 前缀 itest-）在每例前后清理，不影响 295/1324 基线。
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
class MysqlReviewedReadersIntegrationTest {

    private static final int EXERCISE_CATALOG_BASELINE = 1324;

    /** 自造餐食行的来源标记（uk_meal_source 唯一键 + 用例清理锚点）。 */
    private static final String ITEST_MEAL_SOURCE = "itest-68";
    /** 自造动作行的来源标记（uk_exercise_source 唯一键 + 用例清理锚点）。 */
    private static final String ITEST_EXERCISE_SOURCE = "itest-64";
    private static final long ITEST_FAVORITE_USER = 9680001L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private DbReviewedMealReader mealReader;
    @Autowired
    private DbReviewedExerciseReader exerciseReader;
    @Autowired
    private MealBrowseService mealBrowseService;
    @Autowired
    private ExerciseBrowseService exerciseBrowseService;
    @Autowired
    private StructuredMealRetriever structuredRetriever;
    @Autowired
    private MealEmbeddingMapper embeddingMapper;
    @Autowired
    private HealthResourceProvider resourceProvider;

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanCustomRows() {
        jdbc = new JdbcTemplate(dataSource);
        // 清理上一次运行残留的自造行，保证 295/1324 基线断言确定
        jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
        jdbc.update("DELETE FROM exercise_item WHERE source_name = ?", ITEST_EXERCISE_SOURCE);
        jdbc.update("DELETE FROM health_resource_favorite WHERE user_id = ?", ITEST_FAVORITE_USER);
    }

    // ---------- 工具：自造数据与真库查询 ----------

    /** 插入一道自造餐食并返回主键（列口径与审核种子一致，facet 默认为空数组）。 */
    private long insertMeal(String sourceId, String reviewStatus, String sourceType, Long owner,
                            String mealTimeJson, String allergenJson) {
        return insertMeal(sourceId, reviewStatus, sourceType, owner, mealTimeJson, allergenJson,
                "[]", "[]", "itest 测试餐食");
    }

    /** 插入一道携带 facet 契约的自造餐食并返回主键（列口径与审核种子一致）。 */
    private long insertMeal(String sourceId, String reviewStatus, String sourceType, Long owner,
                            String mealTimeJson, String allergenJson,
                            String cuisineJson, String foodTypeJson, String name) {
        jdbc.update("INSERT INTO meal_item (source_type, owner_user_id, name, name_en, aliases,"
                        + " meal_time, mood, scene, health_goal, cuisine, food_type, taste, convenience,"
                        + " description, ingredients_json, serving_count, serving_size, serving_unit,"
                        + " calories_kcal, protein_g, fat_g, carbohydrate_g, nutrition_basis,"
                        + " nutrition_estimated, allergen_json, allergen_status, review_status,"
                        + " source_name, source_id, source_version, media_url, media_status,"
                        + " media_credit, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'itest meal', NULL, ?, '[]', '[]', '[]', ?,"
                        + " ?, '[]', '[]', '测试数据，用例后清理', '[\"测试食材\"]', 1, 1.00, '份',"
                        + " 100.00, 10.00, 5.00, 10.00, 'itest', 0, ?, 'REVIEWED', ?,"
                        + " ?, ?, 'v-itest', NULL, 'NONE', NULL, NOW(), NOW())",
                sourceType, owner, name, mealTimeJson, cuisineJson, foodTypeJson, allergenJson,
                reviewStatus, ITEST_MEAL_SOURCE, sourceId);
        return jdbc.queryForObject(
                "SELECT id FROM meal_item WHERE source_name = ? AND source_id = ?",
                Long.class, ITEST_MEAL_SOURCE, sourceId);
    }

    /** 插入一条自造动作并返回主键（列口径与审核种子一致）。 */
    private long insertExercise(String sourceId, String reviewStatus) {
        jdbc.update("INSERT INTO exercise_item (source_name, source_id, source_version, name, name_en,"
                        + " aliases, category, body_part, target_muscles, secondary_muscles, equipment,"
                        + " difficulty, movement_pattern, risk_tags, alternative_group, review_status,"
                        + " plan_ready, instructions_zh, steps_json, media_state, media_credit,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'v-itest', 'itest 测试动作', 'itest exercise', NULL,"
                        + " 'chest', 'chest', '[\"triceps\"]', '[]', 'body weight', '中级', '推',"
                        + " '[]', NULL, ?, 1, NULL, NULL, 'NONE', NULL, NOW(), NOW())",
                ITEST_EXERCISE_SOURCE, sourceId, reviewStatus);
        return jdbc.queryForObject(
                "SELECT id FROM exercise_item WHERE source_name = ? AND source_id = ?",
                Long.class, ITEST_EXERCISE_SOURCE, sourceId);
    }

    /** 审核公共餐食谓词；extra 为附加条件（不得含 ORDER BY）。 */
    private long firstMealId(String extra) {
        return jdbc.queryForObject(
                "SELECT id FROM meal_item WHERE source_type = 'PUBLIC' AND owner_user_id IS NULL"
                        + " AND review_status = 'APPROVED'" + extra + " ORDER BY id LIMIT 1",
                Long.class);
    }

    private long firstApprovedPublicMealId() {
        return firstMealId("");
    }

    private long firstApprovedPublicMealWithBreakfastId() {
        return firstMealId(" AND JSON_CONTAINS(meal_time, '\"早餐\"')");
    }

    private long firstApprovedPublicMealWithoutBreakfastId() {
        return firstMealId(" AND NOT JSON_CONTAINS(meal_time, '\"早餐\"')");
    }

    private long firstApprovedPublicMealWithMilkId() {
        return firstMealId(" AND JSON_CONTAINS(allergen_json, '\"牛奶\"')");
    }

    private long firstApprovedPublicMealWithBreakfastAndMilkId() {
        return firstMealId(" AND JSON_CONTAINS(meal_time, '\"早餐\"')"
                + " AND JSON_CONTAINS(allergen_json, '\"牛奶\"')");
    }

    private long firstApprovedPublicMealWithBreakfastNoMilkId() {
        return firstMealId(" AND JSON_CONTAINS(meal_time, '\"早餐\"')"
                + " AND NOT JSON_CONTAINS(allergen_json, '\"牛奶\"')");
    }

    // ---------- #68：DbReviewedMealReader 真库行为 ----------

    @Test
    void 结构化召回在LIMIT前过滤未审核与非公共餐食() {
        long approvedId = insertMeal("limit-approved", "APPROVED", "PUBLIC", null,
                "[\"早餐\"]", "[]");
        long pendingId = insertMeal("limit-pending", "PENDING", "PUBLIC", null,
                "[\"早餐\"]", "[]");
        long personalId = insertMeal("limit-personal", "APPROVED", "PERSONAL", 1L,
                "[\"早餐\"]", "[]");
        jdbc.update("UPDATE meal_item SET updated_at = DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE id = ?", approvedId);
        jdbc.update("UPDATE meal_item SET updated_at = DATE_ADD(NOW(), INTERVAL 2 DAY) WHERE id IN (?, ?)", pendingId, personalId);

        List<ReviewedMeal> result = mealReader.recallStructured(Map.of("mealTime", List.of("早餐")), 1);

        assertEquals(List.of(approvedId), result.stream().map(ReviewedMeal::id).toList(),
                "PENDING 或个人餐食不得占用公共审核结果的 LIMIT 窗口");
        assertTrue(result.stream().allMatch(meal -> "APPROVED".equals(meal.reviewStatus())));
    }

    @Test
    void 餐食读取仅返回APPROVED公共行审核与来源过滤生效() {
        try {
            long approvedPublic = insertMeal("x1", "APPROVED", "PUBLIC", null, "[\"早餐\"]", "[]");
            long pendingPublic = insertMeal("x2", "PENDING", "PUBLIC", null, "[\"早餐\"]", "[]");
            long rejectedPublic = insertMeal("x3", "REJECTED", "PUBLIC", null, "[\"早餐\"]", "[]");
            long ownedPublic = insertMeal("x4", "APPROVED", "PUBLIC", 880098L, "[\"早餐\"]", "[]");
            long personal = insertMeal("x5", "APPROVED", "PERSONAL", 880098L, "[\"早餐\"]", "[]");

            assertEquals(296, mealReader.countPublic(), "总数只统计 APPROVED + PUBLIC 行");
            Set<Long> snapshotIds = mealReader.snapshotAll().stream()
                    .map(ReviewedMeal::id).collect(Collectors.toSet());
            assertTrue(snapshotIds.contains(approvedPublic), "APPROVED + PUBLIC 行必须出现在快照中");
            assertTrue(!snapshotIds.contains(pendingPublic) && !snapshotIds.contains(rejectedPublic)
                            && !snapshotIds.contains(ownedPublic) && !snapshotIds.contains(personal),
                    "PENDING/REJECTED/带归属人/PERSONAL 行不得出现在快照中");

            List<Long> recheck = mealReader.findByIds(List.of(
                    approvedPublic, approvedPublic, 99999998L)).stream().map(ReviewedMeal::id).toList();
            assertEquals(List.of(approvedPublic), recheck, "批量回查只返回有效且去重后的审核公共行");
            assertTrue(mealReader.findByIds(null).isEmpty() && mealReader.findByIds(List.of()).isEmpty(),
                    "空 ID 集合不触发 SQL 并返回空");
        } finally {
            jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
        }
        assertEquals(295, mealReader.countPublic(), "清理后必须恢复 295 基线");
    }

    @Test
    void 餐食与动作名称搜索支持中英文并保持审核边界() {
        // #105：名称搜索 SQL name/name_en LIKE 在真库验证中英文命中，且只返回 APPROVED + PUBLIC 行。
        try {
            long approvedMeal = insertMeal("q-zh", "APPROVED", "PUBLIC", null, "[\"早餐\"]", "[]");
            jdbc.update("UPDATE meal_item SET name = '宫保鸡丁', name_en = 'Kung Pao Chicken' WHERE id = ?", approvedMeal);
            long pendingMeal = insertMeal("q-pending", "PENDING", "PUBLIC", null, "[\"早餐\"]", "[]");
            jdbc.update("UPDATE meal_item SET name = '宫保鸡丁副本', name_en = 'Kung Pao Copy' WHERE id = ?", pendingMeal);
            long exerciseId = insertExercise("q-exercise", "APPROVED");
            jdbc.update("UPDATE exercise_item SET name = 'itest 俯卧撑变式', name_en = 'Itest Push Up Variant' WHERE id = ?", exerciseId);

            assertEquals(List.of(approvedMeal),
                    mealReader.browse(0, 20, null, false, "宫保鸡丁", Map.of()).stream().map(ReviewedMeal::id).toList(),
                    "中文名必须命中，且 PENDING 同名行不得返回");
            assertEquals(List.of(approvedMeal),
                    mealReader.browse(0, 20, null, false, "Kung Pao", Map.of()).stream().map(ReviewedMeal::id).toList(),
                    "英文名（name_en）必须命中");
            assertEquals(1, mealReader.countPublic(null, false, "Kung Pao", Map.of()),
                    "计数与列表必须同口径");
            assertTrue(mealReader.browse(0, 20, null, false, "完全不存在的菜名组合", Map.of()).isEmpty(),
                    "无关搜索词必须返回空");

            List<Long> exerciseHits = exerciseReader.browse(0, 50, null, false, "Push Up Variant", Map.of())
                    .stream().map(ReviewedExercise::id).toList();
            assertTrue(exerciseHits.contains(exerciseId), "动作英文名搜索必须命中自造行");
        } finally {
            jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
            jdbc.update("DELETE FROM exercise_item WHERE source_name = ?", ITEST_EXERCISE_SOURCE);
        }
        assertEquals(295, mealReader.countPublic(), "清理后必须恢复 295 基线");
    }

    @Test
    void 餐食分页id升序稳定且翻页不重不漏() {
        List<ReviewedMeal> snapshot = mealReader.snapshotAll();
        assertEquals(295, snapshot.size(), "审核餐食发布基线 295 条");
        assertTrue(isStrictlyAscending(snapshot.stream().map(ReviewedMeal::id).toList()),
                "快照必须 id 升序");

        List<Long> pageIds = new ArrayList<>();
        int offset = 0;
        while (offset < 295) {
            List<Long> page = mealReader.browse(offset, 50).stream().map(ReviewedMeal::id).toList();
            assertFalse(page.isEmpty(), "offset " + offset + " 页不得为空");
            pageIds.addAll(page);
            offset += 50;
        }
        assertEquals(snapshot.stream().map(ReviewedMeal::id).toList(), pageIds,
                "逐页浏览与快照必须完全一致（id 升序、不重不漏）");
        assertEquals(45, mealReader.browse(250, 50).size(), "末页应为 295 - 5*50 = 45 条");
    }

    @Test
    void 餐食批量回查丢弃非审核非公共与重复ID且按id升序() {
        try {
            long first = insertMeal("y1", "APPROVED", "PUBLIC", null, "[\"早餐\"]", "[]");
            insertMeal("y2", "PENDING", "PUBLIC", null, "[\"早餐\"]", "[]");
            long third = insertMeal("y3", "APPROVED", "PUBLIC", null, "[\"早餐\"]", "[]");

            List<Long> found = mealReader.findByIds(List.of(third, first, third, 99999997L))
                    .stream().map(ReviewedMeal::id).toList();
            assertEquals(List.of(first, third), found, "回查必须去重、丢弃无效行并按 id 升序");
        } finally {
            jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
        }
    }

    // ---------- #70：审核快照来源映射与 embedding 真库一致性 ----------

    @Test
    void 审核快照来源映射与Embedding行按餐食ID一致且不混入非审核数据() {
        long approved = insertMeal("embedding-approved", "APPROVED", "PUBLIC", null, "[\"早餐\"]", "[]");
        long pending = insertMeal("embedding-pending", "PENDING", "PUBLIC", null, "[\"早餐\"]", "[]");
        try {
            insertEmbedding(approved, "itest-model", "itest-v1");
            insertEmbedding(pending, "itest-model", "itest-v1");

            Map<Long, String> sourceById = mealReader.snapshotAll().stream()
                    .collect(Collectors.toMap(ReviewedMeal::id, ReviewedMeal::sourceId));
            assertEquals("embedding-approved", sourceById.get(approved));
            assertFalse(sourceById.containsKey(pending), "非审核餐食不得进入评估来源映射");

            List<MealEmbeddingRow> rows = embeddingMapper.findByMealIds(
                    sourceById.keySet().stream().sorted().toList(), "itest-model", "itest-v1");
            assertEquals(List.of(approved), rows.stream().map(MealEmbeddingRow::getMealId).toList(),
                    "按审核快照 ID 读取 embedding 时不得混入非审核餐食向量");
            assertEquals("embedding-approved", sourceById.get(rows.getFirst().getMealId()),
                    "embedding 行必须能映射回同一审核快照来源 ID");
            assertEquals(4, rows.getFirst().getDimension());
        } finally {
            jdbc.update("DELETE FROM meal_item_embedding WHERE model = ? AND model_version = ?",
                    "itest-model", "itest-v1");
            jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
        }
        assertEquals(295, mealReader.countPublic(), "清理后必须恢复 295 基线");
    }

    private void insertEmbedding(long mealId, String model, String modelVersion) {
        jdbc.update("INSERT INTO meal_item_embedding"
                        + " (meal_id, model, model_version, dimension, vector, created_at)"
                        + " VALUES (?, ?, ?, 4, '[1.0,0.0,0.0,0.0]', NOW())",
                mealId, model, modelVersion);
    }

    // ---------- #68/#64：DbReviewedExerciseReader 真库行为 ----------

    @Test
    void 动作目录完整展示且词汇归一与自动计划资格一致() {
        try {
            long approved = insertExercise("e1", "APPROVED");
            long pending = insertExercise("e2", "PENDING");
            long rejected = insertExercise("e3", "REJECTED");

            assertEquals(EXERCISE_CATALOG_BASELINE + 3, exerciseReader.count(),
                    "动作浏览目录应包含审核状态不同的完整本地资料");
            List<ReviewedExercise> all = exerciseReader.browse(0, EXERCISE_CATALOG_BASELINE + 3);
            assertTrue(all.stream().anyMatch(e -> e.id().equals(approved)), "APPROVED 行必须可见");
            assertTrue(all.stream().anyMatch(e -> e.id().equals(pending)), "PENDING 目录项必须可浏览");
            assertTrue(all.stream().anyMatch(e -> e.id().equals(rejected)), "REJECTED 目录项必须可浏览");

            ReviewedExercise viewed = all.stream().filter(e -> e.id().equals(approved))
                    .findFirst().orElseThrow();
            assertEquals("胸", viewed.category(), "chest 必须归一为「胸」");
            assertEquals("胸", viewed.bodyPart(), "chest 主部位必须归一为「胸」");
            assertEquals(List.of("手臂"), viewed.targetMuscles(), "triceps 必须归一为「手臂」");
            assertEquals("徒手", viewed.equipment(), "body weight 必须归一为「徒手」");
            assertEquals("进阶", viewed.difficulty(), "中级必须归一到「进阶」");
        } finally {
            jdbc.update("DELETE FROM exercise_item WHERE source_name = ?", ITEST_EXERCISE_SOURCE);
        }
        assertEquals(EXERCISE_CATALOG_BASELINE, exerciseReader.count(), "清理后必须恢复 1324 条目录基线");
    }

    @Test
    void 动作目录分页稳定且计数与本地媒体基线一致() {
        assertEquals(EXERCISE_CATALOG_BASELINE, exerciseReader.count(), "动作目录发布基线 1324 条");
        List<Long> allIds = new ArrayList<>();
        for (int offset = 0; offset < EXERCISE_CATALOG_BASELINE; offset += 50) {
            allIds.addAll(exerciseReader.browse(offset, 50).stream().map(ReviewedExercise::id).toList());
        }
        assertEquals(EXERCISE_CATALOG_BASELINE, allIds.size(), "分页应覆盖完整动作目录");
        assertEquals(allIds.stream().distinct().toList(), allIds, "翻页不得重复");
        assertEquals(24, exerciseReader.browse(1300, 50).size(), "末页应为 1324 - 26*50 = 24 条");
        assertEquals(exerciseReader.browse(0, EXERCISE_CATALOG_BASELINE).stream()
                        .map(ReviewedExercise::id).toList(), allIds,
                "逐页浏览与完整目录顺序必须一致");
    }

    @Test
    void 全量动作进入计划候选且常见训练与三餐均有资源() {
        List<HealthResource> planReady = resourceProvider.planReadyExercises();

        assertEquals(EXERCISE_CATALOG_BASELINE, planReady.size(),
                "本地完整目录中具备确定性计划属性的动作都应进入 plan_ready");
        assertTrue(planReady.stream().anyMatch(item -> item.tags().getOrDefault("bodyParts", List.of()).contains("胸")
                        && item.tags().getOrDefault("equipment", List.of()).contains("哑铃")),
                "增肌/胸/哑铃等常见训练简报必须能命中动作");
        assertTrue(resourceProvider.planMealCandidates().stream()
                        .anyMatch(item -> item.mealTimeTags().contains("早餐")),
                "餐食计划必须有早餐候选");
        assertTrue(resourceProvider.planMealCandidates().stream()
                        .anyMatch(item -> item.mealTimeTags().contains("午餐")),
                "餐食计划必须有午餐候选");
        assertTrue(resourceProvider.planMealCandidates().stream()
                        .anyMatch(item -> item.mealTimeTags().contains("晚餐")),
                "餐食计划必须有晚餐候选");
    }

    @Test
    void 动作目录详情可读取待审核条目但不改变正式推荐边界() {
        long pending = insertExercise("detail-pending", "PENDING");
        jdbc.update("UPDATE exercise_item SET plan_ready = 0 WHERE id = ?", pending);
        try {
            ReviewedExercise viewed = exerciseReader.findById(pending).orElseThrow();
            assertEquals(pending, viewed.id());
            assertEquals("PENDING", viewed.reviewStatus());
            assertFalse(viewed.planReady());
            assertTrue(exerciseReader.browse(0, EXERCISE_CATALOG_BASELINE + 1).stream()
                    .anyMatch(item -> item.id().equals(pending)), "待审核动作详情与目录分页必须同口径");
        } finally {
            jdbc.update("DELETE FROM exercise_item WHERE source_name = ?", ITEST_EXERCISE_SOURCE);
        }
        assertEquals(EXERCISE_CATALOG_BASELINE, exerciseReader.count(), "清理后必须恢复 1324 条目录基线");
    }

    // ---------- #69：reviewed 浏览服务真库走查 ----------

    @Test
    void 餐食浏览服务真库分页走查总数与页序正确() {
        PagedResponse<MealBrowseItem> page1 = mealBrowseService.browse(1, 20);
        assertEquals(295, page1.total(), "餐食浏览总数必须与审核库基线一致");
        assertEquals(15, page1.totalPages(), "295 / 20 应向上取整为 15 页");
        assertEquals(20, page1.items().size());
        assertTrue(page1.items().stream().allMatch(i -> "APPROVED".equals(i.reviewStatus())),
                "浏览页不得出现非 APPROVED 餐食");

        List<Long> allPageIds = new ArrayList<>();
        for (int page = 1; page <= 15; page++) {
            List<Long> pageIds = mealBrowseService.browse(page, 20).items().stream()
                    .map(MealBrowseItem::id).toList();
            allPageIds.addAll(pageIds);
        }
        assertEquals(295, allPageIds.size(), "15 页合计应覆盖全部 295 条");
        assertEquals(mealReader.snapshotAll().stream().map(ReviewedMeal::id).toList(), allPageIds,
                "浏览服务逐页结果必须与读取模块快照完全一致（id 升序、不重不漏）");
        assertEquals(15, mealBrowseService.browse(15, 20).items().size(), "末页应为 15 条");
    }

    @Test
    void 动作浏览服务真库分页走查总数与页序正确() {
        PagedResponse<ExerciseBrowseItem> page1 = exerciseBrowseService.browse(1, 20);
        assertEquals(EXERCISE_CATALOG_BASELINE, page1.total(), "动作浏览总数必须与本地目录基线一致");
        assertEquals(67, page1.totalPages());
        assertEquals(20, page1.items().size());
        PagedResponse<ExerciseBrowseItem> lastPage = exerciseBrowseService.browse(67, 20);
        assertEquals(4, lastPage.items().size(), "末页应为 1324 - 66*20 = 4 条");

        List<Long> allPageIds = new ArrayList<>();
        List<ExerciseBrowseItem> allItems = new ArrayList<>();
        for (int page = 1; page <= 67; page++) {
            List<ExerciseBrowseItem> items = exerciseBrowseService.browse(page, 20).items();
            allItems.addAll(items);
            allPageIds.addAll(items.stream().map(ExerciseBrowseItem::id).toList());
        }
        assertEquals(allPageIds.stream().distinct().toList(), allPageIds, "翻页不得重复");
        assertEquals(exerciseReader.browse(0, EXERCISE_CATALOG_BASELINE).stream()
                        .map(ReviewedExercise::id).toList(),
                allPageIds, "浏览服务逐页结果必须与读取模块一致");

        for (ExerciseBrowseItem item : allItems) {
            assertFalse(containsAsciiLetter(item.bodyPart()),
                    "主部位不得透出英文原始词汇: " + item.bodyPart());
            assertFalse(containsAsciiLetter(item.equipment()),
                    "器材不得透出英文原始词汇: " + item.equipment());
        }
    }

    @Test
    void 资源查询服务端完成中文筛选搜索与仅收藏分页() {
        Map<String, String> chestFilters = Map.of("bodyPart", "胸");
        PagedResponse<ExerciseBrowseItem> chest = exerciseBrowseService.browse(
                1, 2, ITEST_FAVORITE_USER, false, null, chestFilters);
        assertTrue(chest.total() > 0, "中文部位必须映射到真实动作字段");
        assertEquals(2, chest.items().size());
        assertTrue(chest.items().stream().allMatch(item -> "胸".equals(item.bodyPart())));

        Map<String, String> bodyweightFilters = Map.of("equipment", "徒手");
        PagedResponse<ExerciseBrowseItem> bodyweight = exerciseBrowseService.browse(
                1, 2, ITEST_FAVORITE_USER, false, null, bodyweightFilters);
        assertTrue(bodyweight.total() > 0, "中文器材必须映射到 body weight");
        assertTrue(bodyweight.items().stream().allMatch(item -> "徒手".equals(item.equipment())));

        PagedResponse<MealBrowseItem> search = mealBrowseService.browse(
                1, 2, ITEST_FAVORITE_USER, false, "鸡", Map.of("mealTime", "午餐"));
        assertTrue(search.total() > 0, "名称搜索与餐次筛选必须在服务端生效");
        assertTrue(search.items().stream().allMatch(item -> item.name().contains("鸡")
                || item.nameEn().toLowerCase().contains("chicken")));

        long favoriteMealId = search.items().get(0).id();
        long favoriteExerciseId = chest.items().get(0).id();
        jdbc.update("INSERT INTO health_resource_favorite"
                        + " (user_id, resource_type, resource_id, created_at, updated_at)"
                        + " VALUES (?, 'MEAL', ?, NOW(), NOW()), (?, 'EXERCISE', ?, NOW(), NOW())",
                ITEST_FAVORITE_USER, String.valueOf(favoriteMealId),
                ITEST_FAVORITE_USER, String.valueOf(favoriteExerciseId));

        PagedResponse<MealBrowseItem> favorites = mealBrowseService.browse(
                1, 1, ITEST_FAVORITE_USER, true, null, Map.of());
        assertEquals(1, favorites.total());
        assertEquals(List.of(favoriteMealId), favorites.items().stream().map(MealBrowseItem::id).toList());
        PagedResponse<ExerciseBrowseItem> exerciseFavorites = exerciseBrowseService.browse(
                1, 1, ITEST_FAVORITE_USER, true, null, Map.of());
        assertEquals(1, exerciseFavorites.total());
        assertEquals(List.of(favoriteExerciseId), exerciseFavorites.items().stream()
                .map(ExerciseBrowseItem::id).toList());
    }

    // ---------- #69：Structured 二次校验（真库召回 → 按 ID 回查一致性） ----------

    @Test
    void 结构化检索真库召回后按ID回查一致且硬约束生效() {
        long excludedMeal = firstApprovedPublicMealId();
        long milkMeal = firstApprovedPublicMealWithMilkId();
        MealRetrievalQuery query = new MealRetrievalQuery(
                Map.of("mealTime", List.of("早餐")), List.of(excludedMeal),
                List.of("牛奶"), "早餐");

        RetrievalResult result = structuredRetriever.retrieve(query, 10);

        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertNull(result.degradationReason(), "纯结构化检索不得降级");
        assertFalse(result.items().isEmpty(), "真库必须召回到早餐餐食");
        assertTrue(result.items().size() <= 10);

        List<Long> resultIds = result.items().stream().map(item -> item.meal().id()).toList();
        assertFalse(resultIds.contains(excludedMeal), "排除 ID 硬约束必须先于打分生效");
        assertFalse(resultIds.contains(milkMeal), "过敏原硬约束必须先于打分生效");

        // 二次校验：按 ID 回查真库，集合一致且事实（名称）与召回结果一致
        Map<Long, ReviewedMeal> recheck = mealReader.findByIds(resultIds).stream()
                .collect(Collectors.toMap(ReviewedMeal::id, m -> m));
        assertEquals(resultIds.stream().collect(Collectors.toSet()), recheck.keySet(),
                "召回结果必须全部能被真库按 ID 回查到");
        for (var item : result.items()) {
            ReviewedMeal db = recheck.get(item.meal().id());
            assertEquals(db.name(), item.meal().name(), "召回名称必须与真库行一致");
            assertTrue(db.allergens().stream().noneMatch("牛奶"::equals),
                    "回查行的过敏原不得命中查询过敏原");
        }
    }

    // ---------- #69：Hybrid 二次校验（向量命中经真库回查裁决） ----------

    /** 装配 Hybrid 检索器：真实结构化检索器 + 真实 DbReviewedMealReader + 替身向量路径。 */
    private HybridMealRetriever hybridWith(EmbeddingClient embeddingClient, InMemoryVectorStore vectorStore) {
        return new HybridMealRetriever(structuredRetriever, embeddingClient, vectorStore, mealReader);
    }

    private InMemoryVectorStore vectorStoreWith(List<VectorPoint> points) {
        InMemoryVectorStore store = new InMemoryVectorStore(
                new VectorStoreIdentity("itest", "text-embedding-v3", 4, "v3-4"));
        store.upsert(points);
        return store;
    }

    /** 向量点 payload：审核状态与来源（与 VectorIndexingRunner 写入侧同键）。 */
    private Map<String, List<String>> approvedPayload() {
        Map<String, List<String>> payload = new LinkedHashMap<>();
        payload.put("review_status", List.of("APPROVED"));
        payload.put("source_type", List.of("PUBLIC"));
        payload.put("allergens", List.of());
        return payload;
    }

    @Test
    void Hybrid融合后向量独有候选经真库回查一致且过期索引丢弃() {
        MealRetrievalQuery query = new MealRetrievalQuery(
                Map.of("mealTime", List.of("早餐")), List.of(), List.of(), "早餐");
        List<Long> structuredIds = structuredRetriever.retrieve(query, 10).items().stream()
                .map(item -> item.meal().id()).toList();
        long structuredHit = structuredIds.get(0);
        long vectorOnly = mealReader.snapshotAll().stream()
                .filter(meal -> meal.tags().getOrDefault("mealTime", List.of()).contains("早餐"))
                .map(ReviewedMeal::id)
                .filter(id -> !structuredIds.contains(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("测试库需要至少 11 个早餐候选"));
        long staleIndexId = 99999991L;

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f, 0f, 0f}));
        // 向量独有候选 + 结构化候选 + 一个真库不存在的过期索引命中
        InMemoryVectorStore vectorStore = vectorStoreWith(List.of(
                new VectorPoint(structuredHit, new float[]{1f, 0f, 0f, 0f}, approvedPayload()),
                new VectorPoint(vectorOnly, new float[]{1f, 0f, 0f, 0f}, approvedPayload()),
                new VectorPoint(staleIndexId, new float[]{1f, 0f, 0f, 0f}, approvedPayload())));
        HybridMealRetriever hybrid = hybridWith(embeddingClient, vectorStore);

        RetrievalResult result = hybrid.retrieve(query, 10);

        assertEquals(RetrievalMode.HYBRID, result.mode());
        assertNull(result.degradationReason(), "向量路径可用不得降级");
        List<Long> resultIds = result.items().stream().map(item -> item.meal().id()).toList();
        assertTrue(resultIds.contains(vectorOnly), "向量独有候选必须经真库回查后进入融合结果");
        assertFalse(resultIds.contains(staleIndexId), "真库不存在的过期索引命中必须被丢弃");

        // 二次校验：全部结果按 ID 回查真库，事实（名称）一致
        Map<Long, ReviewedMeal> recheck = mealReader.findByIds(resultIds).stream()
                .collect(Collectors.toMap(ReviewedMeal::id, m -> m));
        assertEquals(resultIds.stream().collect(Collectors.toSet()), recheck.keySet(),
                "融合结果必须全部能被真库按 ID 回查到");
        for (var item : result.items()) {
            assertEquals(recheck.get(item.meal().id()).name(), item.meal().name(),
                    "融合候选名称必须来自真库回查");
        }
        var vectorOnlyItem = result.items().stream()
                .filter(item -> item.meal().id() == vectorOnly).findFirst().orElseThrow();
        assertEquals(0.5, vectorOnlyItem.mergedScore(), 1e-9,
                "仅向量候选：0.5*0 结构分 + 0.5*1.0 语义分");
    }

    @Test
    void Hybrid向量命中过期过敏原经真库二次校验丢弃() {
        long allergenMeal = firstApprovedPublicMealWithBreakfastAndMilkId();
        long controlMeal = firstApprovedPublicMealWithBreakfastNoMilkId();

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f, 0f, 0f}));
        // 过期索引：过敏原行在向量 payload 中缺失（payload 无 allergen），向量过滤放行
        Map<String, List<String>> stalePayload = approvedPayload();
        stalePayload.put("allergens", List.of());
        InMemoryVectorStore vectorStore = vectorStoreWith(List.of(
                new VectorPoint(allergenMeal, new float[]{1f, 0f, 0f, 0f}, stalePayload),
                new VectorPoint(controlMeal, new float[]{1f, 0f, 0f, 0f}, approvedPayload())));
        HybridMealRetriever hybrid = hybridWith(embeddingClient, vectorStore);

        RetrievalResult result = hybrid.retrieve(new MealRetrievalQuery(
                Map.of("mealTime", List.of("早餐")), List.of(), List.of("牛奶"), "早餐"), 10);

        assertEquals(RetrievalMode.HYBRID, result.mode());
        assertFalse(result.items().isEmpty(), "控制餐食必须保留");
        List<Long> resultIds = result.items().stream().map(item -> item.meal().id()).toList();
        assertFalse(resultIds.contains(allergenMeal),
                "向量过滤放行的过敏原命中必须被真库二次校验丢弃");
        assertTrue(resultIds.contains(controlMeal), "无过敏原控制餐食必须保留");
        for (ReviewedMeal db : mealReader.findByIds(resultIds)) {
            assertTrue(db.allergens().stream().noneMatch("牛奶"::equals),
                    "融合结果回查真库后不得含牛奶过敏原: " + db.id());
        }
    }

    // ---------- 断言辅助 ----------

    private boolean isStrictlyAscending(List<Long> ids) {
        for (int i = 1; i < ids.size(); i++) {
            if (ids.get(i) <= ids.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAsciiLetter(String value) {
        return value != null && value.matches(".*[A-Za-z].*");
    }

    // ---------- 合并语句族与 facet 筛选（餐食标签加固规格） ----------

    @Test
    void 合并语句族经Reader路径覆盖facet筛选与分页一致性() {
        long cantoneseVeg = insertMeal("facet-a", "APPROVED", "PUBLIC", null,
                "[\"三餐\"]", "[]", "[\"粤菜\"]", "[\"素食\"]", "itest 素食粤菜");
        long sichuanVeg = insertMeal("facet-b", "APPROVED", "PUBLIC", null,
                "[\"午餐\"]", "[]", "[\"川菜\"]", "[\"素食\"]", "itest 素食川菜");
        long cantoneseBbq = insertMeal("facet-c", "APPROVED", "PUBLIC", null,
                "[\"晚餐\"]", "[]", "[\"粤菜\"]", "[\"烧烤\"]", "itest 烧烤粤菜");
        try {
            // 不带 foodType：名称搜索圈定自造行（foodTypeJson 空即不过滤，各过滤参数空串归 null）
            List<ReviewedMeal> all = mealReader.browse(0, 50, null, false, "itest meal", Map.of());
            assertTrue(all.stream().map(ReviewedMeal::id).collect(Collectors.toSet())
                    .containsAll(List.of(cantoneseVeg, sichuanVeg, cantoneseBbq)),
                    "不带 foodType 的合并浏览语句必须返回全部自造行");

            // foodType=素食：同维度跨菜系 OR（审核语料含既有素食行，断言用包含式）
            List<ReviewedMeal> vegetarian = mealReader.browse(0, 50, null, false, null,
                    Map.of("foodType", "素食"));
            Set<Long> vegetarianIds = vegetarian.stream().map(ReviewedMeal::id).collect(Collectors.toSet());
            assertTrue(vegetarianIds.containsAll(List.of(cantoneseVeg, sichuanVeg)),
                    "同维度 foodType 筛选必须命中两道自造素食行");
            assertFalse(vegetarianIds.contains(cantoneseBbq), "烧烤行不得命中素食筛选");
            assertEquals(mealReader.countPublic(null, false, null, Map.of("foodType", "素食")),
                    mealReader.browse(0, Integer.MAX_VALUE, null, false, null, Map.of("foodType", "素食")).size(),
                    "分页计数必须与行数一致");

            // cuisine+foodType 组合：跨维度 AND（粤菜 AND 素食）
            List<ReviewedMeal> combined = mealReader.browse(0, 50, null, false, null,
                    Map.of("cuisine", "粤菜", "foodType", "素食"));
            assertTrue(combined.stream().map(ReviewedMeal::id).anyMatch(id -> id == cantoneseVeg));
            assertFalse(combined.stream().map(ReviewedMeal::id).anyMatch(id -> id == sichuanVeg),
                    "川菜行不得命中粤菜+素食组合筛选");

            // 餐次兼容表达式：晚餐筛选命中三餐标注行（名称搜索圈定自造行避免分页漂移）
            assertTrue(mealReader.browse(0, 50, null, false, "itest meal", Map.of("mealTime", "晚餐"))
                    .stream().map(ReviewedMeal::id).anyMatch(id -> id == cantoneseVeg),
                    "三餐标注行必须被晚餐筛选命中");
            assertTrue(mealReader.browse(0, 50, null, false, "素食粤菜", Map.of())
                    .stream().map(ReviewedMeal::id).allMatch(id -> id == cantoneseVeg));

            // Structured 检索（search 语句）：foodType 过滤与三餐兼容
            List<ReviewedMeal> recalled = mealReader.recallStructured(
                    Map.of("foodType", List.of("素食"), "mealTime", List.of("早餐")), 50);
            assertTrue(recalled.stream().map(ReviewedMeal::id).anyMatch(id -> id == cantoneseVeg),
                    "三餐行必须被早餐筛选命中");
            assertFalse(recalled.stream().map(ReviewedMeal::id).anyMatch(id -> id == cantoneseBbq),
                    "foodType 硬过滤必须排除非素食行");
        } finally {
            jdbc.update("DELETE FROM meal_item WHERE source_name = ? AND source_id IN (?, ?, ?)",
                    ITEST_MEAL_SOURCE, "facet-a", "facet-b", "facet-c");
        }
    }

    @Test
    void 旧WithFoodType方法族已删除且合并语句恒定声明foodType参数() {
        // 加固规格：删除所有成对的 WithFoodType 变体，检索/浏览/计数各保留一条参数完整的语句
        for (java.lang.reflect.Method method : com.diet.mapper.MealMapper.class.getMethods()) {
            assertFalse(method.getName().endsWith("WithFoodType"),
                    "不得残留 WithFoodType 方法族: " + method.getName());
        }
    }
}