package com.diet.health.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核资源种子数据校验（33 号票）。
 * <p>
 * 校验对象是 ETL 生成的 {@code src/main/resources/db/seed/reviewed_resources.sql}：
 * 数量区间、必填字段、槽位 JSON、营养估算标记、过敏原状态、来源版本、
 * 无外链媒体（media_url 必须为 NULL）与 plan_ready 元数据完整性。
 * 数据不满足本测试时，说明 ETL 或人工审核数据有缺口，不允许上线。
 */
class ReviewedResourceSeedValidatorTest {

    private static final String SEED_PATH = "/db/seed/reviewed_resources.sql";

    // ---- 解析 ----

    private Map<String, List<List<String>>> parseSeed() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SEED_PATH)) {
            assertNotNull(in, "种子 SQL 文件不存在: " + SEED_PATH + "（先运行 scripts/build_reviewed_resources.py）");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return SeedSqlParser.parse(sql);
        }
    }

    private List<List<String>> table(Map<String, List<List<String>>> parsed, String table) {
        return parsed.get(table);
    }

    private String cell(List<String> row, int index) {
        return row.get(index);
    }

    // ---- 餐食 ----

    @Test
    void 餐食数量在100到300之间() throws IOException {
        List<List<String>> meals = table(parseSeed(), "meal_item");
        assertNotNull(meals, "缺少 meal_item 段");
        assertTrue(meals.size() >= 100, "餐食数量 < 100，实际 " + meals.size());
        assertTrue(meals.size() <= 300, "餐食数量 > 300，实际 " + meals.size());
    }

    @Test
    void 每行餐食有中文名英文名和别名() throws IOException {
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertFalse(cell(row, 2).isBlank(), "中文名缺失: " + row);
            assertFalse(cell(row, 3).isBlank(), "英文名缺失: " + row);
            assertFalse(cell(row, 4).isBlank(), "别名缺失: " + row);
        }
    }

    @Test
    void 每行餐食份量口径完整且营养为估算值() throws IOException {
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertTrue(Integer.parseInt(cell(row, 14)) > 0, "份数必须 > 0: " + row);
            assertTrue(Double.parseDouble(cell(row, 15)) > 0, "每份量必须 > 0: " + row);
            assertEquals("份", cell(row, 16), "每份单位必须是「份」: " + row);
            assertTrue(Double.parseDouble(cell(row, 17)) >= 0, "热量缺失: " + row);
            assertTrue(Double.parseDouble(cell(row, 18)) >= 0, "蛋白质缺失: " + row);
            assertTrue(Double.parseDouble(cell(row, 19)) >= 0, "脂肪缺失: " + row);
            assertTrue(Double.parseDouble(cell(row, 20)) >= 0, "碳水缺失: " + row);
            assertEquals("foodcom_source_value", cell(row, 21), "营养口径错误: " + row);
            assertEquals("1", cell(row, 22), "Food.com 营养必须标记为估算值: " + row);
        }
    }

    @Test
    void 每行餐食过敏原状态已审核且JSON合法() throws IOException {
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertTrue(cell(row, 23).startsWith("["), "过敏原必须是 JSON 数组: " + row);
            String status = cell(row, 24);
            assertTrue("REVIEWED".equals(status) || "PENDING".equals(status), "过敏原状态非法: " + status);
            assertEquals("APPROVED", cell(row, 25), "审核状态必须 APPROVED: " + row);
        }
    }

    @Test
    void 每行餐食无外链媒体() throws IOException {
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertEquals("NULL", cell(row, 29), "无媒体许可时必须清除外链（media_url 为 NULL）: " + row);
            assertEquals("NONE", cell(row, 30), "媒体状态必须是 NONE: " + row);
        }
    }

    @Test
    void 每行餐食来源版本齐全且来源ID唯一() throws IOException {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertEquals("foodcom-recipes-and-reviews-v2", cell(row, 26), "来源名错误: " + row);
            assertFalse(cell(row, 27).isBlank(), "来源 ID 缺失: " + row);
            assertFalse(cell(row, 28).isBlank(), "来源版本缺失: " + row);
            assertFalse(seen.containsKey(cell(row, 27)), "来源 ID 重复: " + cell(row, 27));
            seen.put(cell(row, 27), row);
        }
    }

    @Test
    void 每行餐食槽位JSON合法且餐次非空() throws IOException {
        for (List<String> row : table(parseSeed(), "meal_item")) {
            assertTrue(cell(row, 5).startsWith("["), "meal_time 必须是 JSON 数组: " + row);
            assertFalse(trimBrackets(cell(row, 5)).isEmpty(), "餐次不能为空: " + row);
            for (int i = 6; i <= 11; i++) {
                assertTrue(cell(row, i).startsWith("["), "槽位 " + i + " 必须是 JSON 数组: " + row);
            }
        }
    }

    private String trimBrackets(String jsonArray) {
        return jsonArray.substring(1, jsonArray.length() - 1);
    }

    // ---- 动作 ----

    @Test
    void 动作数量在20到40之间() throws IOException {
        List<List<String>> exercises = table(parseSeed(), "exercise_item");
        assertNotNull(exercises, "缺少 exercise_item 段");
        assertTrue(exercises.size() >= 20, "动作数量 < 20，实际 " + exercises.size());
        assertTrue(exercises.size() <= 40, "动作数量 > 40，实际 " + exercises.size());
    }

    @Test
    void 每个动作有中英文名别名和完整元数据() throws IOException {
        List<String> difficulties = List.of("入门", "中级", "进阶");
        for (List<String> row : table(parseSeed(), "exercise_item")) {
            assertFalse(cell(row, 3).isBlank(), "动作中文名缺失: " + row);
            assertFalse(cell(row, 4).isBlank(), "动作英文名缺失: " + row);
            assertFalse(cell(row, 5).isBlank(), "动作别名缺失: " + row);
            assertFalse(cell(row, 7).isBlank(), "主训练部位缺失: " + row);
            assertFalse(cell(row, 10).isBlank(), "器材缺失: " + row);
            assertTrue(difficulties.contains(cell(row, 11)), "难度非法: " + cell(row, 11));
            assertFalse(cell(row, 12).isBlank(), "动作模式缺失: " + row);
            assertTrue(cell(row, 13).startsWith("["), "风险标签必须是 JSON 数组: " + row);
            assertFalse(cell(row, 14).isBlank(), "替代动作组缺失: " + row);
        }
    }

    @Test
    void 每个动作已审核且plan_ready元数据完整() throws IOException {
        for (List<String> row : table(parseSeed(), "exercise_item")) {
            assertEquals("APPROVED", cell(row, 15), "动作审核状态必须 APPROVED: " + row);
            assertEquals("1", cell(row, 16), "审核动作必须 plan_ready=1: " + row);
            assertTrue(cell(row, 17).length() > 20, "中文步骤说明缺失: " + row);
            assertTrue(cell(row, 18).startsWith("["), "分步 JSON 必须是数组: " + row);
        }
    }

    @Test
    void 每个动作无媒体且保留GymVisual署名() throws IOException {
        for (List<String> row : table(parseSeed(), "exercise_item")) {
            assertEquals("NONE", cell(row, 19), "动作媒体状态必须是 NONE: " + row);
            assertTrue(cell(row, 20).contains("Gym visual"), "必须保留 Gym visual 署名: " + row);
        }
    }

    @Test
    void 动作来源版本齐全且来源ID唯一() throws IOException {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        for (List<String> row : table(parseSeed(), "exercise_item")) {
            assertEquals("gym-visual-exercises-dataset", cell(row, 0), "来源名错误: " + row);
            assertFalse(cell(row, 1).isBlank(), "来源 ID 缺失: " + row);
            assertFalse(cell(row, 2).isBlank(), "来源版本缺失: " + row);
            assertFalse(seen.containsKey(cell(row, 1)), "动作来源 ID 重复: " + cell(row, 1));
            seen.put(cell(row, 1), row);
        }
    }

    // ---- 作息事实 ----

    @Test
    void 作息事实数量在10到20之间且字段齐全() throws IOException {
        List<List<String>> facts = table(parseSeed(), "routine_fact");
        assertNotNull(facts, "缺少 routine_fact 段");
        assertTrue(facts.size() >= 10, "事实数量 < 10，实际 " + facts.size());
        assertTrue(facts.size() <= 20, "事实数量 > 20，实际 " + facts.size());
        List<String> refIds = new ArrayList<>();
        for (List<String> row : facts) {
            for (int i = 0; i <= 5; i++) {
                assertFalse(cell(row, i).isBlank(), "字段 " + i + " 缺失: " + row);
            }
            refIds.add(cell(row, 5));
        }
        assertEquals(facts.size(), refIds.stream().distinct().count(), "ref_id 必须唯一");
    }
}
