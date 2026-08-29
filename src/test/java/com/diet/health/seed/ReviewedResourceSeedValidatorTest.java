package com.diet.health.seed;

import com.diet.health.intent.HealthSlotDictionary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核资源种子数据校验（33 号票；精确数量契约见 59 号票；facet 加固见餐食标签加固规格）。
 * <p>
 * 校验对象是 ETL 生成的 {@code src/main/resources/db/seed/reviewed_resources.sql}：
 * 精确发布基线（295 道 APPROVED 公共餐食 / 30 个 APPROVED 且 plan_ready 动作 /
 * 15 条业务 refId 唯一的作息事实）、必填字段、槽位 JSON、营养估算标记、过敏原状态、
 * 来源版本、无外链媒体、plan_ready 元数据完整性。
 * <p>
 * facet 不变量（加固规格新增，独立挡住"种子本身是坏的"缺陷）：295 行菜系/餐食类型
 * 全部为非空 JSON 数组、取值全部落在 13/11 规范词表、无单字碎片、烧烤可达、
 * 三条人工补充餐食标签与规范稳定来源键规则一致；同时校验 ETL 报告的 facetSource
 * 溯源、规范词表/人工策展输入哈希与 current-corpus-v2 manifest 绑定。
 * 解析 INSERT 的列名取值，禁止依赖易漂移的列下标。
 * <p>
 * 295/30/15 是发布契约，禁止静默漂移：有意调整基线时必须同步 seed、ETL 报告
 * 与 README/AGENTS/规格证据，否则不允许上线。
 */
class ReviewedResourceSeedValidatorTest {

    private static final String SEED_PATH = "/db/seed/reviewed_resources.sql";
    private static final String MANUAL_MEALS_PATH = "scripts/meal_curation/manual_meals.json";
    private static final String CANONICAL_FACETS_PATH = "data/meal/facets.json";
    private static final String CLASSPATH_FACETS_PATH = "src/main/resources/db/seed/meal_facets.json";
    private static final String ETL_REPORT_PATH = "data/reports/resource_etl_report.json";
    private static final String MANIFEST_PATH = "data/manifests/current-corpus-v2.json";
    private static final Set<String> MANUAL_MEAL_IDS = Set.of("307525", "96740", "198328");

    // ---- 解析 ----

    private Map<String, SeedSqlParser.ParsedTable> parseSeed() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SEED_PATH)) {
            assertNotNull(in, "种子 SQL 文件不存在: " + SEED_PATH + "（先运行 scripts/build_reviewed_resources.py）");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return SeedSqlParser.parse(sql);
        }
    }

    private SeedSqlParser.ParsedTable table(Map<String, SeedSqlParser.ParsedTable> parsed, String table) {
        SeedSqlParser.ParsedTable parsedTable = parsed.get(table);
        assertNotNull(parsedTable, "缺少 " + table + " 段");
        return parsedTable;
    }

    private String value(SeedSqlParser.ParsedTable parsed, List<String> row, String column) {
        return parsed.value(row, column);
    }

    // ---- 餐食 ----

    @Test
    void 餐食数量为精确发布基线295条() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        assertEquals(295, meals.rows().size(),
                "餐食发布基线为 295 条（发布契约，不允许静默漂移；有意调整须同步 seed、ETL 报告与规格证据），实际 "
                        + meals.rows().size());
    }

    @Test
    void 每行餐食均为APPROVED公共资源() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            assertEquals("PUBLIC", value(meals, row, "source_type"),
                    "公共餐食库要求 source_type=PUBLIC: " + row);
            assertEquals("NULL", value(meals, row, "owner_user_id"),
                    "公共餐食要求 owner_user_id 为 NULL（非用户私有）: " + row);
            assertEquals("APPROVED", value(meals, row, "review_status"), "审核状态必须 APPROVED: " + row);
        }
    }

    @Test
    void 每行餐食有中文名英文名和别名() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            assertFalse(value(meals, row, "name").isBlank(), "中文名缺失: " + row);
            assertFalse(value(meals, row, "name_en").isBlank(), "英文名缺失: " + row);
            assertFalse(value(meals, row, "aliases").isBlank(), "别名缺失: " + row);
        }
    }

    @Test
    void 每行餐食份量口径完整且营养为估算值() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            assertTrue(Integer.parseInt(value(meals, row, "serving_count")) > 0, "份数必须 > 0: " + row);
            assertTrue(Double.parseDouble(value(meals, row, "serving_size")) > 0, "每份量必须 > 0: " + row);
            assertEquals("份", value(meals, row, "serving_unit"), "每份单位必须是「份」: " + row);
            assertTrue(Double.parseDouble(value(meals, row, "calories_kcal")) >= 0, "热量缺失: " + row);
            assertTrue(Double.parseDouble(value(meals, row, "protein_g")) >= 0, "蛋白质缺失: " + row);
            assertTrue(Double.parseDouble(value(meals, row, "fat_g")) >= 0, "脂肪缺失: " + row);
            assertTrue(Double.parseDouble(value(meals, row, "carbohydrate_g")) >= 0, "碳水缺失: " + row);
            assertEquals("foodcom_source_value", value(meals, row, "nutrition_basis"), "营养口径错误: " + row);
            assertEquals("1", value(meals, row, "nutrition_estimated"), "Food.com 营养必须标记为估算值: " + row);
        }
    }

    @Test
    void 每行餐食过敏原状态已审核且JSON合法() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            assertTrue(value(meals, row, "allergen_json").startsWith("["), "过敏原必须是 JSON 数组: " + row);
            String status = value(meals, row, "allergen_status");
            assertTrue("REVIEWED".equals(status) || "PENDING".equals(status), "过敏原状态非法: " + status);
            assertEquals("APPROVED", value(meals, row, "review_status"), "审核状态必须 APPROVED: " + row);
        }
    }

    @Test
    void 每行餐食无外链媒体() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            assertEquals("NULL", value(meals, row, "media_url"),
                    "无媒体许可时必须清除外链（media_url 为 NULL）: " + row);
            assertEquals("NONE", value(meals, row, "media_status"), "媒体状态必须是 NONE: " + row);
        }
    }

    @Test
    void 每行餐食来源版本齐全且来源ID唯一() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        Set<String> seen = new HashSet<>();
        for (List<String> row : meals.rows()) {
            assertEquals("foodcom-recipes-and-reviews-v2", value(meals, row, "source_name"), "来源名错误: " + row);
            assertFalse(value(meals, row, "source_id").isBlank(), "来源 ID 缺失: " + row);
            assertFalse(value(meals, row, "source_version").isBlank(), "来源版本缺失: " + row);
            assertFalse(seen.contains(value(meals, row, "source_id")), "来源 ID 重复: " + row);
            seen.add(value(meals, row, "source_id"));
        }
    }

    @Test
    void 每行餐食槽位JSON合法且餐次非空() throws IOException {
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            List<String> mealTimes = readJsonArray(value(meals, row, "meal_time"));
            assertFalse(mealTimes.isEmpty(), "餐次不能为空: " + row);
            for (String column : List.of("mood", "scene", "health_goal", "cuisine", "food_type",
                    "taste", "convenience")) {
                assertTrue(readJsonArray(value(meals, row, column)) != null,
                        column + " 必须是 JSON 数组: " + row);
            }
        }
    }

    // ---- facet 不变量（加固规格）----

    @Test
    void 每行餐食facet为非空且全部落在规范词表内且无单字碎片() throws IOException {
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        for (List<String> row : meals.rows()) {
            List<String> source = List.of(value(meals, row, "source_name"), value(meals, row, "source_id"));
            List<String> cuisines = readJsonArray(value(meals, row, "cuisine"));
            List<String> foodTypes = readJsonArray(value(meals, row, "food_type"));
            assertFalse(cuisines.isEmpty(), "菜系 facet 不能为空（标签唯一事实源契约）: " + source);
            assertFalse(foodTypes.isEmpty(), "餐食类型 facet 不能为空（标签唯一事实源契约）: " + source);
            for (String tag : cuisines) {
                assertTrue(vocabulary.isLegal(MealFacetVocabulary.CUISINE, tag),
                        "菜系标签 " + tag + " 不在 13 值规范词表内: " + source);
                assertTrue(tag.length() >= 2, "菜系标签存在单字碎片: " + source);
            }
            for (String tag : foodTypes) {
                assertTrue(vocabulary.isLegal(MealFacetVocabulary.FOOD_TYPE, tag),
                        "餐食类型标签 " + tag + " 不在 11 值规范词表内: " + source);
                assertTrue(tag.length() >= 2, "餐食类型标签存在单字碎片: " + source);
            }
        }
    }

    @Test
    void 烧烤类型在词表可达且种子里真实出现() throws IOException {
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        assertTrue(vocabulary.isLegal(MealFacetVocabulary.FOOD_TYPE, "烧烤"),
                "文档化类型集合必须包含「烧烤」（演示可 exercising 契约）");
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        long rowsWithBbq = meals.rows().stream()
                .filter(row -> readJsonArray(value(meals, row, "food_type")).contains("烧烤"))
                .count();
        assertTrue(rowsWithBbq > 0, "「烧烤」必须是可达的餐食类型（种子里至少一行携带）");
    }

    @Test
    void 三条人工补充餐食标签与规范稳定来源键规则一致() throws IOException {
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        Map<String, List<String>> bySourceId = new LinkedHashMap<>();
        for (List<String> row : meals.rows()) {
            bySourceId.put(value(meals, row, "source_id"), row);
        }
        com.fasterxml.jackson.databind.JsonNode manual = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(Path.of(MANUAL_MEALS_PATH)));
        for (com.fasterxml.jackson.databind.JsonNode meal : manual.path("meals")) {
            String sourceId = meal.path("source_id").asText();
            assertTrue(MANUAL_MEAL_IDS.contains(sourceId), "人工策展输入必须是三条历史补充餐食: " + sourceId);
            List<String> row = bySourceId.get(sourceId);
            assertNotNull(row, "人工补充餐食必须进入种子: " + sourceId);
            String sourceName = meal.path("source_name").asText();
            assertEquals(List.of(vocabulary.stableKeyDemoLabel(MealFacetVocabulary.CUISINE, sourceName, sourceId)),
                    readJsonArray(value(meals, row, "cuisine")),
                    "人工餐食菜系必须与规范稳定来源键演示分类一致: " + sourceId);
            assertEquals(List.of(vocabulary.stableKeyDemoLabel(MealFacetVocabulary.FOOD_TYPE, sourceName, sourceId)),
                    readJsonArray(value(meals, row, "food_type")),
                    "人工餐食类型必须与规范稳定来源键演示分类一致: " + sourceId);
            assertEquals(meal.path("cuisine").path(0).asText(),
                    readJsonArray(value(meals, row, "cuisine")).get(0),
                    "manual_meals.json 与 seed 的菜系必须一致: " + sourceId);
            assertEquals(meal.path("food_type").path(0).asText(),
                    readJsonArray(value(meals, row, "food_type")).get(0),
                    "manual_meals.json 与 seed 的餐食类型必须一致: " + sourceId);
        }
    }

    @Test
    void ETL报告facetSource溯源与哈希绑定规范词表和人工输入() throws IOException {
        com.fasterxml.jackson.databind.JsonNode report = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(Path.of(ETL_REPORT_PATH)));
        assertEquals(16, report.path("meals").path("facetSource").path("cuisine").path("DATA").asInt());
        assertEquals(279, report.path("meals").path("facetSource").path("cuisine")
                .path("STABLE_KEY_DEMO").asInt());
        assertEquals(46, report.path("meals").path("facetSource").path("food_type").path("DATA").asInt());
        assertEquals(249, report.path("meals").path("facetSource").path("food_type")
                .path("STABLE_KEY_DEMO").asInt());
        assertEquals(sha256(Path.of(CANONICAL_FACETS_PATH)), report.path("facets").path("sha256").asText(),
                "ETL 报告必须绑定规范 facet 词表哈希");
        assertEquals(sha256(Path.of(MANUAL_MEALS_PATH)), report.path("meals").path("manual_input")
                .path("sha256").asText(), "ETL 报告必须绑定人工策展输入哈希");
        assertEquals(3, report.path("meals").path("manual_input").path("count").asInt());
        // 报告标注的演示分类行，其种子标签必须等于规范稳定键计算结果
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        SeedSqlParser.ParsedTable meals = table(parseSeed(), "meal_item");
        Map<String, List<String>> bySourceId = new LinkedHashMap<>();
        for (List<String> row : meals.rows()) {
            bySourceId.put(value(meals, row, "source_id"), row);
        }
        for (String dimension : List.of("cuisine", "food_type")) {
            for (com.fasterxml.jackson.databind.JsonNode idNode : report.path("meals")
                    .path("facetDemoRows").path(dimension)) {
                String sourceId = idNode.asText();
                List<String> row = bySourceId.get(sourceId);
                assertNotNull(row, "facetDemoRows 引用了不存在的行: " + sourceId);
                String expected = vocabulary.stableKeyDemoLabel(
                        "food_type".equals(dimension) ? MealFacetVocabulary.FOOD_TYPE : MealFacetVocabulary.CUISINE,
                        value(meals, row, "source_name"), sourceId);
                assertEquals(List.of(expected), readJsonArray(value(meals, row, dimension)),
                        "演示分类行的 " + dimension + " 必须等于规范稳定键计算结果: " + sourceId);
            }
        }
    }

    @Test
    void 规范词表生成物与manifest绑定一致() throws IOException {
        // canonical → classpath 副本逐字节一致（Java 归一器/启动兜底消费该副本）
        assertEquals(sha256(Path.of(CANONICAL_FACETS_PATH)), sha256(Path.of(CLASSPATH_FACETS_PATH)),
                "classpath 词表副本必须与 canonical 文件逐字节一致");
        com.fasterxml.jackson.databind.JsonNode manifest = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(Path.of(MANIFEST_PATH)));
        assertEquals("current-corpus-v2", manifest.path("corpusVersion").asText());
        assertEquals("reviewed-2026-08-29-v1", manifest.path("resourceVersion").asText());
        assertEquals(sha256(Path.of(CANONICAL_FACETS_PATH)), manifest.path("facets").path("sha256").asText(),
                "manifest 必须绑定规范词表哈希");
        assertEquals(sha256(Path.of(MANUAL_MEALS_PATH)), manifest.path("manualInput").path("sha256").asText(),
                "manifest 必须绑定人工策展输入哈希");
        assertEquals(sha256(Path.of("src/main/resources/db/seed/reviewed_resources.sql")),
                manifest.path("source").path("seedSha256").asText(), "manifest 必须绑定当前 seed 哈希");
    }

    private List<String> readJsonArray(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readerForListOf(String.class)
                    .readValue(json);
        } catch (IOException e) {
            throw new AssertionError("JSON 数组解析失败: " + json, e);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder result = new StringBuilder();
            for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (Exception e) {
            throw new IOException("sha256 计算失败: " + path, e);
        }
    }

    // ---- 动作 ----

    @Test
    void 动作数量为精确发布基线30条() throws IOException {
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        assertEquals(30, exercises.rows().size(),
                "动作发布基线为 30 个（发布契约，不允许静默漂移；有意调整须同步 seed、ETL 报告与规格证据），实际 "
                        + exercises.rows().size());
    }

    @Test
    void 每个动作有中英文名别名和完整元数据() throws IOException {
        List<String> difficulties = List.of("入门", "中级", "进阶");
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        for (List<String> row : exercises.rows()) {
            assertFalse(value(exercises, row, "name").isBlank(), "动作中文名缺失: " + row);
            assertFalse(value(exercises, row, "name_en").isBlank(), "动作英文名缺失: " + row);
            assertFalse(value(exercises, row, "aliases").isBlank(), "动作别名缺失: " + row);
            assertFalse(value(exercises, row, "body_part").isBlank(), "主训练部位缺失: " + row);
            assertFalse(value(exercises, row, "equipment").isBlank(), "器材缺失: " + row);
            assertTrue(difficulties.contains(value(exercises, row, "difficulty")),
                    "难度非法: " + value(exercises, row, "difficulty"));
            assertFalse(value(exercises, row, "movement_pattern").isBlank(), "动作模式缺失: " + row);
            assertTrue(value(exercises, row, "risk_tags").startsWith("["), "风险标签必须是 JSON 数组: " + row);
            assertFalse(value(exercises, row, "alternative_group").isBlank(), "替代动作组缺失: " + row);
        }
    }

    @Test
    void 每个动作已审核且plan_ready元数据完整() throws IOException {
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        for (List<String> row : exercises.rows()) {
            assertEquals("APPROVED", value(exercises, row, "review_status"), "动作审核状态必须 APPROVED: " + row);
            assertEquals("1", value(exercises, row, "plan_ready"), "审核动作必须 plan_ready=1: " + row);
            assertTrue(value(exercises, row, "instructions_zh").length() > 20, "中文步骤说明缺失: " + row);
            assertTrue(value(exercises, row, "steps_json").startsWith("["), "分步 JSON 必须是数组: " + row);
        }
    }

    @Test
    void 每个动作用户槽位字段属于健身槽位合法中文集合() throws IOException {
        // 64 号票：真实审核 seed 驱动的 API 与页面不得显示未允许英文槽位
        List<String> allowedBodyParts = HealthSlotDictionary
                .FITNESS_OPTIONS.get("bodyParts");
        List<String> allowedEquipment = HealthSlotDictionary
                .FITNESS_OPTIONS.get("equipment");
        List<String> allowedDifficulty = HealthSlotDictionary
                .FITNESS_OPTIONS.get("difficulty");
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        for (List<String> row : exercises.rows()) {
            assertEquals("APPROVED", value(exercises, row, "review_status"), "动作审核状态必须 APPROVED: " + row);
            String bodyPart = com.diet.health.reader.exercise.ExerciseVocabulary
                    .partZh(value(exercises, row, "body_part"));
            assertTrue(allowedBodyParts.contains(bodyPart), "主部位必须归一为合法中文值: " + row);
            String category = com.diet.health.reader.exercise.ExerciseVocabulary
                    .partZh(value(exercises, row, "category"));
            assertTrue(allowedBodyParts.contains(category), "类别必须归一为合法中文值: " + row);
            for (String column : List.of("target_muscles", "secondary_muscles")) {
                for (String muscle : readJsonArray(value(exercises, row, column))) {
                    String zh = com.diet.health.reader.exercise.ExerciseVocabulary.partZh(muscle);
                    assertTrue(allowedBodyParts.contains(zh),
                            "肌群 " + muscle + " 必须归一为合法中文值: " + row);
                }
            }
            String equipment = com.diet.health.reader.exercise.ExerciseVocabulary
                    .equipmentZh(value(exercises, row, "equipment"));
            assertTrue(allowedEquipment.contains(equipment), "器材必须归一为合法中文值: " + row);
            String difficulty = com.diet.health.reader.exercise.ExerciseVocabulary
                    .difficultyZh(value(exercises, row, "difficulty"));
            assertTrue(allowedDifficulty.contains(difficulty), "难度必须归一为合法中文值: " + row);
        }
    }

    @Test
    void 每个动作无媒体且保留GymVisual署名() throws IOException {
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        for (List<String> row : exercises.rows()) {
            assertEquals("NONE", value(exercises, row, "media_state"), "动作媒体状态必须是 NONE: " + row);
            assertTrue(value(exercises, row, "media_credit").contains("Gym visual"),
                    "必须保留 Gym visual 署名: " + row);
        }
    }

    @Test
    void 动作来源版本齐全且来源ID唯一() throws IOException {
        SeedSqlParser.ParsedTable exercises = table(parseSeed(), "exercise_item");
        Set<String> seen = new HashSet<>();
        for (List<String> row : exercises.rows()) {
            assertEquals("gym-visual-exercises-dataset", value(exercises, row, "source_name"), "来源名错误: " + row);
            assertFalse(value(exercises, row, "source_id").isBlank(), "来源 ID 缺失: " + row);
            assertFalse(value(exercises, row, "source_version").isBlank(), "来源版本缺失: " + row);
            assertFalse(seen.contains(value(exercises, row, "source_id")), "动作来源 ID 重复: " + row);
            seen.add(value(exercises, row, "source_id"));
        }
    }

    // ---- 作息事实 ----

    @Test
    void 作息事实数量为精确发布基线15条且字段齐全refId唯一() throws IOException {
        SeedSqlParser.ParsedTable facts = table(parseSeed(), "routine_fact");
        assertEquals(15, facts.rows().size(),
                "作息事实发布基线为 15 条（发布契约，不允许静默漂移；有意调整须同步 seed、ETL 报告与规格证据），实际 "
                        + facts.rows().size());
        List<String> refIds = new ArrayList<>();
        for (List<String> row : facts.rows()) {
            for (String column : List.of("topic", "fact_zh", "scope", "source", "source_version", "ref_id")) {
                assertFalse(value(facts, row, column).isBlank(), "字段 " + column + " 缺失: " + row);
            }
            refIds.add(value(facts, row, "ref_id"));
        }
        assertEquals(facts.rows().size(), refIds.stream().distinct().count(), "ref_id 必须唯一");
    }
}
