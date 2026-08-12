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
import com.diet.health.reader.exercise.DbReviewedExerciseReader;
import com.diet.health.reader.exercise.ExerciseVocabulary;
import com.diet.health.reader.exercise.ReviewedExercise;
import com.diet.health.reader.meal.DbReviewedMealReader;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.vectorstore.InMemoryVectorStore;
import com.diet.health.vectorstore.VectorPoint;
import com.diet.health.vectorstore.VectorStoreIdentity;
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
 * #68/#69 方案 B 读取边界：审核读取模块与浏览/检索链路的真实 MySQL 门控验证。
 * <p>
 * 在独立测试库 diet_db_itest 上（V1-V7 迁移 + 审核资源种子导入，295 道 APPROVED 公共餐食、
 * 30 个 APPROVED 动作），验证：
 * <ul>
 *   <li>#68 审核餐食/动作读取模块真库行为：仅 APPROVED + PUBLIC 返回（审核/来源过滤）、
 *       browse 分页计数、findByIds 批量回查去重、id 升序稳定排序；</li>
 *   <li>#69 reviewed 浏览服务（MealBrowseService/ExerciseBrowseService）真库分页走查；</li>
 *   <li>#69 Structured/Hybrid 二次校验：从真库召回后按 ID 回查确认一致——向量独有候选、
 *       过期索引命中、过期过敏原 payload 均以 MySQL 为事实源裁决。</li>
 * </ul>
 * 用例内自造的小批量行（source_name 前缀 itest-）在每例前后清理，不影响 295/30 基线。
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

    /** 自造餐食行的来源标记（uk_meal_source 唯一键 + 用例清理锚点）。 */
    private static final String ITEST_MEAL_SOURCE = "itest-68";
    /** 自造动作行的来源标记（uk_exercise_source 唯一键 + 用例清理锚点）。 */
    private static final String ITEST_EXERCISE_SOURCE = "itest-64";

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

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanCustomRows() {
        jdbc = new JdbcTemplate(dataSource);
        // 清理上一次运行残留的自造行，保证 295/30 基线断言确定
        jdbc.update("DELETE FROM meal_item WHERE source_name = ?", ITEST_MEAL_SOURCE);
        jdbc.update("DELETE FROM exercise_item WHERE source_name = ?", ITEST_EXERCISE_SOURCE);
    }

    // ---------- 工具：自造数据与真库查询 ----------

    /** 插入一道自造餐食并返回主键（列口径与审核种子一致）。 */
    private long insertMeal(String sourceId, String reviewStatus, String sourceType, Long owner,
                            String mealTimeJson, String allergenJson) {
        jdbc.update("INSERT INTO meal_item (source_type, owner_user_id, name, name_en, aliases,"
                        + " meal_time, mood, scene, health_goal, cuisine, taste, convenience,"
                        + " description, ingredients_json, serving_count, serving_size, serving_unit,"
                        + " calories_kcal, protein_g, fat_g, carbohydrate_g, nutrition_basis,"
                        + " nutrition_estimated, allergen_json, allergen_status, review_status,"
                        + " source_name, source_id, source_version, media_url, media_status,"
                        + " media_credit, created_at, updated_at)"
                        + " VALUES (?, ?, 'itest 测试餐食', 'itest meal', NULL, ?, '[]', '[]', '[]', '[]',"
                        + " '[]', '[]', '测试数据，用例后清理', '[\"测试食材\"]', 1, 1.00, '份',"
                        + " 100.00, 10.00, 5.00, 10.00, 'itest', 0, ?, 'REVIEWED', ?,"
                        + " ?, ?, 'v-itest', NULL, 'NONE', NULL, NOW(), NOW())",
                sourceType, owner, mealTimeJson, allergenJson, reviewStatus,
                ITEST_MEAL_SOURCE, sourceId);
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

    // ---------- #68/#64：DbReviewedExerciseReader 真库行为 ----------

    @Test
    void 动作读取仅返回APPROVED行审核过滤生效且词汇归一() {
        try {
            long approved = insertExercise("e1", "APPROVED");
            insertExercise("e2", "PENDING");
            insertExercise("e3", "REJECTED");

            assertEquals(31, exerciseReader.count(), "动作总数只统计 APPROVED 行");
            List<ReviewedExercise> all = exerciseReader.browse(0, 100);
            assertTrue(all.stream().anyMatch(e -> e.id().equals(approved)), "APPROVED 行必须可见");
            assertTrue(all.stream().noneMatch(e -> e.reviewStatus().equals("PENDING")
                            || e.reviewStatus().equals("REJECTED")),
                    "PENDING/REJECTED 行不得可见");

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
        assertEquals(30, exerciseReader.count(), "清理后必须恢复 30 基线");
    }

    @Test
    void 动作分页id升序稳定且计数与种子基线一致() {
        assertEquals(30, exerciseReader.count(), "审核动作发布基线 30 条");
        List<Long> firstPage = exerciseReader.browse(0, 20).stream().map(ReviewedExercise::id).toList();
        List<Long> secondPage = exerciseReader.browse(20, 20).stream().map(ReviewedExercise::id).toList();
        assertEquals(20, firstPage.size());
        assertEquals(10, secondPage.size(), "末页应为 30 - 20 = 10 条");
        assertTrue(isStrictlyAscending(firstPage), "首页必须 id 升序");
        assertTrue(isStrictlyAscending(secondPage), "末页必须 id 升序");
        List<Long> union = new ArrayList<>(firstPage);
        union.addAll(secondPage);
        assertEquals(union.stream().distinct().toList(), union, "翻页不得重复");
        assertTrue(exerciseReader.browse(0, 100).stream()
                        .allMatch(e -> "APPROVED".equals(e.reviewStatus())),
                "全部可见动作必须 APPROVED");
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
        assertEquals(30, page1.total(), "动作浏览总数必须与审核库基线一致");
        assertEquals(2, page1.totalPages());
        assertEquals(20, page1.items().size());
        PagedResponse<ExerciseBrowseItem> page2 = exerciseBrowseService.browse(2, 20);
        assertEquals(10, page2.items().size(), "末页应为 30 - 20 = 10 条");
        assertTrue(page1.items().stream().allMatch(i -> "APPROVED".equals(i.reviewStatus())),
                "浏览页不得出现非 APPROVED 动作");

        List<Long> allPageIds = new ArrayList<>();
        allPageIds.addAll(page1.items().stream().map(ExerciseBrowseItem::id).toList());
        allPageIds.addAll(page2.items().stream().map(ExerciseBrowseItem::id).toList());
        assertEquals(allPageIds.stream().distinct().toList(), allPageIds, "翻页不得重复");
        assertEquals(exerciseReader.browse(0, 100).stream().map(ReviewedExercise::id).toList(),
                allPageIds, "浏览服务逐页结果必须与读取模块一致");

        List<String> legalBodyParts = ExerciseVocabulary.legalFitnessValues().get("bodyParts");
        List<String> legalEquipment = ExerciseVocabulary.legalFitnessValues().get("equipment");
        List<ExerciseBrowseItem> allItems = new ArrayList<>(page1.items());
        allItems.addAll(page2.items());
        for (ExerciseBrowseItem item : allItems) {
            assertTrue(legalBodyParts.contains(item.bodyPart()),
                    "主部位必须是健身槽位中文词汇，不得透出英文: " + item.bodyPart());
            assertTrue(item.equipment() == null || item.equipment().isEmpty()
                            || legalEquipment.contains(item.equipment()),
                    "器材必须是健身槽位中文词汇: " + item.equipment());
        }
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
        long structuredHit = firstApprovedPublicMealWithBreakfastId();
        long vectorOnly = firstApprovedPublicMealWithoutBreakfastId();
        long staleIndexId = 99999991L;

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f, 0f, 0f}));
        // 向量独有候选 + 结构化候选 + 一个真库不存在的过期索引命中
        InMemoryVectorStore vectorStore = vectorStoreWith(List.of(
                new VectorPoint(structuredHit, new float[]{1f, 0f, 0f, 0f}, approvedPayload()),
                new VectorPoint(vectorOnly, new float[]{1f, 0f, 0f, 0f}, approvedPayload()),
                new VectorPoint(staleIndexId, new float[]{1f, 0f, 0f, 0f}, approvedPayload())));
        HybridMealRetriever hybrid = hybridWith(embeddingClient, vectorStore);

        RetrievalResult result = hybrid.retrieve(new MealRetrievalQuery(
                Map.of("mealTime", List.of("早餐")), List.of(), List.of(), "早餐"), 10);

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
}
