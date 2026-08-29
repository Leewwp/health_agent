package com.diet.integration;

import com.diet.health.seed.MealFacetVocabulary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diet.health.seed.ReviewedResourceSeeder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 全新库 facet 投影比对（餐食标签加固规格，发布阻塞项）。
 * <p>
 * 三条路径共用同一份规范稳定来源键算法（{@link MealFacetVocabulary}，与 ETL/V24 逐字节对齐）：
 * <ol>
 *   <li>全新库：V1–V24 全部迁移 → 启动种子导入（餐食 ODKU 同步 facet）→ 295 行全覆盖、
 *       无 INSERT IGNORE/ODKU 静默缺行、行级 facet 不变量（非空、词表内、非碎片）；</li>
 *   <li>旧库路径：V1–V23 → 模拟迁移期状态（V22 自增 id 轮换、三条人工餐食单字碎片、
 *       空 facet 与词表外残留）→ V24 纠正迁移（修空/非法，保数据派生）→ 启动导种
 *       （ODKU 同步收敛 V22 轮换出的合法标签）→ 与全新库投影按复合 source key 逐行比对；</li>
 *   <li>守卫：V1 基线形态的无 source_id 行保留合法标签；出现非法 facet 时 V24 迁移失败并报告。</li>
 * </ol>
 * 分层契约：V24 修复空/非法 facet（迁移期即可验证）；V22 轮换出的"合法但非规范"标签
 * 不由迁移猜测改写，由种子 ODKU 同步收敛——标签唯一事实源是 ETL，最终投影逐行一致。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlMealFacetFreshSchemaIntegrationTest {

    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;

    private static final String FRESH_SCHEMA = "diet_db_facet_fresh";
    private static final String LEGACY_SCHEMA = "diet_db_facet_legacy";
    private static final String GUARD_SCHEMA = "diet_db_facet_guard";
    private static final String GUARD_FAIL_SCHEMA = "diet_db_facet_guard_fail";

    private static final List<String> CUISINES = List.of("粤菜", "川菜", "湘菜", "江浙菜", "东北菜", "鲁菜",
            "闽南菜", "云南菜", "新疆菜", "西餐", "日料", "韩餐", "东南亚菜");
    private static final List<String> FOOD_TYPES = List.of("素食", "海鲜", "家常", "轻食", "粉面", "粥汤",
            "快餐", "甜品", "火锅", "小吃", "烧烤");
    private static final List<String> MANUAL_MEAL_IDS = List.of("307525", "96740", "198328");

    private static JdbcTemplate admin;

    @BeforeAll
    static void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(serverUrl(), DB_USER, DB_PASS));
    }

    @AfterAll
    static void cleanUp() {
        for (String schema : List.of(FRESH_SCHEMA, LEGACY_SCHEMA, GUARD_SCHEMA, GUARD_FAIL_SCHEMA)) {
            try {
                admin.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            } catch (Exception ignored) {
                // 清理失败不影响测试结论（CI 容器一次性环境）
            }
        }
    }

    // ---- 工具 ----

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static void recreateSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl(), DB_USER, DB_PASS);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static JdbcTemplate jdbcFor(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(url(schema), DB_USER, DB_PASS));
    }

    /** 干净库迁移到指定版本（空库不触发 baseline，V1 起全部执行）。 */
    private static void migrate(String schema, String target) {
        Flyway.configure()
                .dataSource(url(schema), DB_USER, DB_PASS)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(target)
                .load()
                .migrate();
    }

    private static void importSeed(String schema) {
        JdbcTemplate jdbc = jdbcFor(schema);
        try {
            new ReviewedResourceSeeder(jdbc, null, true).importReviewedResources(jdbc);
        } catch (Exception e) {
            throw new IllegalStateException("种子导入失败: " + schema, e);
        }
    }

    /** facet 投影快照：复合 source key → (cuisine, food_type)。 */
    private static Map<String, String[]> projection(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_name, source_id, cuisine, food_type FROM meal_item "
                        + "WHERE source_type='PUBLIC' AND owner_user_id IS NULL "
                        + "AND review_status='APPROVED' AND source_id IS NOT NULL ORDER BY id");
        Map<String, String[]> projection = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            projection.put(row.get("source_name") + "\u0000" + row.get("source_id"),
                    new String[]{String.valueOf(row.get("cuisine")), String.valueOf(row.get("food_type"))});
        }
        return projection;
    }

    private static Set<String> seedSourceIds() throws Exception {
        Set<String> ids = new HashSet<>();
        String sql = new String(MysqlMealFacetFreshSchemaIntegrationTest.class.getResourceAsStream(
                "/db/seed/reviewed_resources.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String line : sql.split("\n")) {
            Matcher matcher = Pattern.compile("'foodcom-recipes-and-reviews-v2', '([0-9A-Za-z-]+)', 'v2'")
                    .matcher(line);
            if (matcher.find()) {
                assertTrue(ids.add(matcher.group(1)), "seed source_id 重复: " + matcher.group(1));
            }
        }
        return ids;
    }

    /** 投影逐行比较（复合 source key → cuisine/food_type 值列表）。 */
    private static List<String> compareProjections(Map<String, String[]> expected, Map<String, String[]> actual) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : expected.entrySet()) {
            String[] actualValue = actual.get(entry.getKey());
            if (actualValue == null || !Arrays.equals(entry.getValue(), actualValue)) {
                mismatches.add(entry.getKey() + " expected=" + Arrays.toString(entry.getValue())
                        + " actual=" + Arrays.toString(actualValue));
            }
        }
        return mismatches;
    }

    /** facet 值是否合法：非空 JSON 数组且全部取值都在规范词表内。 */
    private static boolean isLegalFacet(String facet) {
        List<String> values = parseFacet(facet);
        return !values.isEmpty() && values.stream().allMatch(value ->
                CUISINES.contains(value) || FOOD_TYPES.contains(value));
    }

    private static List<String> parseFacet(String facet) {
        if (facet == null || !facet.startsWith("[")) {
            return List.of();
        }
        try {
            return new ObjectMapper().readerForListOf(String.class).readValue(facet);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void assertFacetInvariants(JdbcTemplate jdbc, String stage) {
        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(1) FROM meal_item "
                        + "WHERE source_type='PUBLIC' AND owner_user_id IS NULL AND review_status='APPROVED' "
                        + "AND source_id IS NOT NULL AND ("
                        + " cuisine IS NULL OR JSON_TYPE(cuisine) <> 'ARRAY' OR JSON_LENGTH(cuisine) = 0"
                        + " OR food_type IS NULL OR JSON_TYPE(food_type) <> 'ARRAY' OR JSON_LENGTH(food_type) = 0"
                        + " OR EXISTS (SELECT 1 FROM JSON_TABLE(cuisine, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x"
                        + "   WHERE x.v NOT IN ('粤菜','川菜','湘菜','江浙菜','东北菜','鲁菜','闽南菜','云南菜','新疆菜','西餐','日料','韩餐','东南亚菜'))"
                        + " OR EXISTS (SELECT 1 FROM JSON_TABLE(food_type, '$[*]' COLUMNS(v VARCHAR(64) PATH '$')) x"
                        + "   WHERE x.v NOT IN ('素食','海鲜','家常','轻食','粉面','粥汤','快餐','甜品','火锅','小吃','烧烤')))",
                Integer.class);
        assertEquals(0, bad, stage + "：演示语料行 facet 必须非空且全部落在规范词表内");
    }

    // ---- 测试 ----

    @Test
    @Order(1)
    void 全新库V1至V24迁移导种后295行全覆盖且facet不变量成立() throws Exception {
        recreateSchema(FRESH_SCHEMA);
        MigrateResult result = Flyway.configure()
                .dataSource(url(FRESH_SCHEMA), DB_USER, DB_PASS)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertEquals(24, result.migrationsExecuted, "全新库必须执行 V1–V24 全部迁移");
        JdbcTemplate jdbc = jdbcFor(FRESH_SCHEMA);
        assertEquals(24, jdbc.queryForObject(
                "SELECT COUNT(1) FROM flyway_schema_history WHERE success = 1", Integer.class));
        importSeed(FRESH_SCHEMA);

        // 覆盖率 295/295：seed 中每个 (source_name, source_id) 必须都在库中，无静默缺行
        Set<String> seedIds = seedSourceIds();
        assertEquals(295, seedIds.size());
        Set<String> dbIds = new HashSet<>(jdbc.queryForList(
                "SELECT source_id FROM meal_item WHERE source_type='PUBLIC' "
                        + "AND owner_user_id IS NULL AND review_status='APPROVED' AND source_id IS NOT NULL",
                String.class));
        assertEquals(seedIds, dbIds, "演示语料 source_id 集合必须与 seed 完全一致（无静默缺行/多行）");
        assertFacetInvariants(jdbc, "全新库导种后");
    }

    @Test
    @Order(2)
    void 旧库路径经V24与启动导种后与全新库投影逐行收敛() throws Exception {
        recreateSchema(LEGACY_SCHEMA);
        migrate(LEGACY_SCHEMA, "23");
        JdbcTemplate jdbc = jdbcFor(LEGACY_SCHEMA);
        importSeed(LEGACY_SCHEMA);

        // 模拟迁移期旧库状态（按行分类还原真实历史）：
        // ① seed facet == 稳定键演示分类的行（原为空）→ V22 按 MOD(id, n) 自增轮换；
        // ② 数据可推导行 → 保留原值；③ 三条人工餐食 → 单字碎片种子；④ 演示行上的空 facet 与词表外残留。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, source_name, source_id, cuisine, food_type FROM meal_item "
                        + "WHERE source_type='PUBLIC' AND owner_user_id IS NULL AND review_status='APPROVED' "
                        + "AND source_id IS NOT NULL");
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        List<Long> rotateCuisine = new ArrayList<>();
        List<Long> rotateFoodType = new ArrayList<>();
        Long demoCuisineVictim = null;
        Long demoFoodTypeVictim = null;
        Long dataDerivedSample = null;
        Long dataDerivedFoodTypeSample = null;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sourceName = String.valueOf(row.get("source_name"));
            String sourceId = String.valueOf(row.get("source_id"));
            boolean demoCuisine = String.valueOf(row.get("cuisine")).equals("[\""
                    + vocabulary.stableKeyDemoLabel(MealFacetVocabulary.CUISINE, sourceName, sourceId) + "\"]");
            boolean demoFoodType = String.valueOf(row.get("food_type")).equals("[\""
                    + vocabulary.stableKeyDemoLabel(MealFacetVocabulary.FOOD_TYPE, sourceName, sourceId) + "\"]");
            if (demoCuisine) {
                rotateCuisine.add(id);
                if (demoCuisineVictim == null && !MANUAL_MEAL_IDS.contains(sourceId)) {
                    demoCuisineVictim = id;
                }
            }
            if (!demoCuisine && dataDerivedSample == null && !MANUAL_MEAL_IDS.contains(sourceId)) {
                dataDerivedSample = id;
            }
            if (!demoFoodType && dataDerivedFoodTypeSample == null && !MANUAL_MEAL_IDS.contains(sourceId)) {
                dataDerivedFoodTypeSample = id;
            }
            if (demoFoodType) {
                rotateFoodType.add(id);
                if (demoFoodTypeVictim == null && !MANUAL_MEAL_IDS.contains(sourceId)
                        && !sourceId.equals(String.valueOf(
                                jdbc.queryForObject("SELECT source_id FROM meal_item WHERE id = ?",
                                        String.class, demoCuisineVictim == null ? -1L : demoCuisineVictim)))) {
                    demoFoodTypeVictim = id;
                }
            }
        }
        rotate(LEGACY_SCHEMA, "cuisine", CUISINES.size(), rotateCuisine);
        rotate(LEGACY_SCHEMA, "food_type", FOOD_TYPES.size(), rotateFoodType);
        jdbc.update("UPDATE meal_item SET cuisine = JSON_ARRAY('粤','菜'), food_type = JSON_ARRAY('小','吃') "
                + "WHERE source_id = '307525'");
        jdbc.update("UPDATE meal_item SET cuisine = JSON_ARRAY('云','南','菜'), food_type = JSON_ARRAY('快','餐') "
                + "WHERE source_id = '96740'");
        jdbc.update("UPDATE meal_item SET cuisine = JSON_ARRAY('粤','菜'), food_type = JSON_ARRAY('小','吃') "
                + "WHERE source_id = '198328'");
        jdbc.update("UPDATE meal_item SET cuisine = JSON_ARRAY(), food_type = JSON_ARRAY('中餐') WHERE id = ?",
                demoCuisineVictim);
        jdbc.update("UPDATE meal_item SET food_type = JSON_ARRAY('轻食','随便') WHERE id = ?",
                demoFoodTypeVictim);

        // V24 纠正迁移语义（全行断言）：合法既有标签原样保留（含数据派生与 V22 轮换出的
        // 合法标签——后者不由迁移猜测改写，交给启动导种收敛）；空/非法 facet 由规范稳定键回填
        Map<String, String[]> beforeV24 = projection(jdbc);
        migrate(LEGACY_SCHEMA, "24");
        assertFacetInvariants(jdbc, "旧库 V24 修复后");
        Map<String, String[]> afterV24 = projection(jdbc);
        assertEquals(beforeV24.keySet(), afterV24.keySet());
        for (Map.Entry<String, String[]> entry : beforeV24.entrySet()) {
            String[] keyParts = entry.getKey().split("\u0000", -1);
            String[] before = entry.getValue();
            String[] after = afterV24.get(entry.getKey());
            for (int dim = 0; dim < 2; dim++) {
                String dimension = dim == 0 ? MealFacetVocabulary.CUISINE : MealFacetVocabulary.FOOD_TYPE;
                List<String> beforeValues = parseFacet(before[dim]);
                Set<String> expected;
                if (isLegalFacet(before[dim])) {
                    expected = new HashSet<>(beforeValues);
                } else {
                    // V24 语义：清理非法值保留合法值；合法值为空时按规范稳定键回填单个演示分类
                    List<String> legal = beforeValues.stream()
                            .filter(value -> CUISINES.contains(value) || FOOD_TYPES.contains(value))
                            .toList();
                    expected = legal.isEmpty()
                            ? Set.of(vocabulary.stableKeyDemoLabel(dimension, keyParts[0], keyParts[1]))
                            : new HashSet<>(legal);
                }
                assertEquals(expected, new HashSet<>(parseFacet(after[dim])),
                        "V24 修复语义不符（" + dimension + "）: " + entry.getKey()
                                + " before=" + before[dim] + " after=" + after[dim]);
            }
        }
        // 三条人工餐食的碎片必须被修复为规范稳定键演示分类（307525: 日料/小吃）
        assertEquals("[\"日料\"]", jdbc.queryForObject(
                "SELECT cuisine FROM meal_item WHERE source_id='307525'", String.class),
                "碎片必须由 V24 修复为规范稳定键演示分类");
        assertEquals("[\"小吃\"]", jdbc.queryForObject(
                "SELECT food_type FROM meal_item WHERE source_id='307525'", String.class));

        // 启动导种（ODKU 同步 facet）：收敛 V22 轮换出的合法但非规范标签 → 与全新库逐行收敛
        importSeed(LEGACY_SCHEMA);
        Map<String, String[]> legacyFinal = projection(jdbc);
        Map<String, String[]> freshProjection = projection(jdbcFor(FRESH_SCHEMA));
        assertEquals(295, freshProjection.size());
        assertEquals(freshProjection.keySet(), legacyFinal.keySet(), "复合 source key 集合必须一致");
        List<String> mismatches = compareProjections(freshProjection, legacyFinal);
        assertTrue(mismatches.isEmpty(), "fresh/legacy 投影必须逐行收敛，差异：" + mismatches);

        // 同步幂等：再次导种不得改变任何 facet
        importSeed(LEGACY_SCHEMA);
        List<String> idempotencyDrift = compareProjections(legacyFinal, projection(jdbc));
        assertTrue(idempotencyDrift.isEmpty(), "启动导种的 facet 同步必须幂等，差异：" + idempotencyDrift);
    }

    @Test
    @Order(3)
    void 无来源身份行保留合法标签且非法facet使V24迁移失败并报告() throws Exception {
        // 成功路径：V1 基线形态的无 source_id 行保留合法既有标签（空 facet 容忍，不猜测）
        recreateSchema(GUARD_SCHEMA);
        migrate(GUARD_SCHEMA, "23");
        JdbcTemplate jdbc = jdbcFor(GUARD_SCHEMA);
        jdbc.update("INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene, "
                + "health_goal, cuisine, food_type, taste, convenience, review_status, created_at, updated_at) "
                + "VALUES ('PUBLIC', NULL, '基线演示面', '[\"午餐\"]', '[]', '[]', '[]', '[\"粤菜\"]', "
                + "'[\"家常\"]', '[]', '[]', 'PENDING', NOW(), NOW())");
        migrate(GUARD_SCHEMA, "24");
        Map<String, Object> kept = jdbc.queryForMap(
                "SELECT cuisine, food_type FROM meal_item WHERE name = '基线演示面'");
        assertEquals("[\"粤菜\"]", String.valueOf(kept.get("cuisine")), "合法既有标签必须保留");
        assertEquals("[\"家常\"]", String.valueOf(kept.get("food_type")), "合法既有标签必须保留");

        // 失败路径：无来源身份行出现非法 facet → V24 SIGNAL 失败并给出行级报告
        recreateSchema(GUARD_FAIL_SCHEMA);
        migrate(GUARD_FAIL_SCHEMA, "23");
        JdbcTemplate failJdbc = jdbcFor(GUARD_FAIL_SCHEMA);
        failJdbc.update("INSERT INTO meal_item (source_type, owner_user_id, name, meal_time, mood, scene, "
                + "health_goal, cuisine, food_type, taste, convenience, review_status, created_at, updated_at) "
                + "VALUES ('PUBLIC', NULL, '基线碎片段', '[\"午餐\"]', '[]', '[]', '[]', '[\"粤\",\"菜\"]', "
                + "'[]', '[]', '[]', 'PENDING', NOW(), NOW())");
        try {
            migrate(GUARD_FAIL_SCHEMA, "24");
            fail("无来源身份行的非法 facet 必须使 V24 迁移失败");
        } catch (Exception expected) {
            assertTrue(String.valueOf(expected).contains("V24"),
                    "迁移失败必须携带 V24 行级报告: " + expected);
        }
    }

    /** 复刻 V22 的自增 id 轮换（MOD(id, n)），n 为该维度词表长度。 */
    private static void rotate(String schema, String column, int size, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> vocabulary = "cuisine".equals(column) ? CUISINES : FOOD_TYPES;
        StringBuilder cases = new StringBuilder("CASE MOD(id, ").append(size).append(") ");
        for (int i = 0; i < vocabulary.size(); i++) {
            cases.append(" WHEN ").append(i).append(" THEN '").append(vocabulary.get(i)).append("'");
        }
        cases.append(" END");
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        jdbcFor(schema).update("UPDATE meal_item SET " + column + " = JSON_ARRAY(" + cases + ") "
                + "WHERE source_type='PUBLIC' AND owner_user_id IS NULL AND review_status='APPROVED' "
                + "AND id IN (" + inList + ")");
    }
}
