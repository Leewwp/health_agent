package com.diet.health.seed;

import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.plan.MealCuisineIntentParser;
import com.diet.health.plan.MealFoodTypeIntentParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规范词表漂移守卫（餐食标签加固规格，ADR-0017）。
 * <p>
 * canonical 文件 {@code data/meal/facets.json} 是菜系/餐食类型词汇的唯一事实源；
 * classpath 副本（Java 归一器与启动兜底消费）、前端筛选模块、提示词词表行、
 * 两个受限解析器的可选值列表全部由它生成或读取。本测试比较文件哈希、顺序与
 * 全部生成物——任何一处手改造成的漂移都会失败，不允许只比较集合。
 */
class MealFacetVocabularyDriftTest {

    private static final String CANONICAL = "data/meal/facets.json";
    private static final String CLASSPATH_COPY = "src/main/resources/db/seed/meal_facets.json";
    private static final String FRONTEND_MODULE = "frontend/assets/js/data/mealFacets.js";
    private static final String CLARIFY_PROMPT = "src/main/resources/diet/prompts/clarify.txt";

    @Test
    void canonical文件与classpath副本逐字节一致() throws Exception {
        assertEquals(sha256(Path.of(CANONICAL)), sha256(Path.of(CLASSPATH_COPY)),
                "classpath 词表副本必须由 ETL 从 canonical 逐字节生成，禁止手改");
    }

    @Test
    void Java词表与归一器与解析器按同一顺序消费同一词表() {
        MealFacetVocabulary vocabulary = MealFacetVocabulary.INSTANCE;
        List<String> cuisines = readCanonical("cuisines");
        List<String> foodTypes = readCanonical("foodTypes");

        assertEquals(cuisines, vocabulary.cuisines(), "MealFacetVocabulary 菜系必须与 canonical 同序");
        assertEquals(foodTypes, vocabulary.foodTypes(), "MealFacetVocabulary 餐食类型必须与 canonical 同序");

        HealthInputNormalizer normalizer = new HealthInputNormalizer();
        assertEquals(cuisines, normalizer.canonicalValues("cuisine"),
                "归一器菜系规范值必须与 canonical 同序（词表唯一事实源）");
        assertEquals(foodTypes, normalizer.canonicalValues("foodType"),
                "归一器餐食类型规范值必须与 canonical 同序（词表唯一事实源）");

        assertEquals(cuisines, new MealCuisineIntentParser(normalizer).supportedCuisines());
        assertEquals(foodTypes, new MealFoodTypeIntentParser(normalizer).supportedFoodTypes());
    }

    @Test
    void 前端筛选模块与提示词词表行同序镜像() throws Exception {
        List<String> cuisines = readCanonical("cuisines");
        List<String> foodTypes = readCanonical("foodTypes");

        String frontend = Files.readString(Path.of(FRONTEND_MODULE));
        assertTrue(frontend.contains("由 scripts/build_reviewed_resources.py 从 data/meal/facets.json 自动生成"),
                "前端词表模块必须标记为生成物");
        assertEquals("export const CUISINE_OPTIONS = " + toJson(cuisines) + ";",
                findLine(frontend, "export const CUISINE_OPTIONS"),
                "前端菜系词表必须与 canonical 同序");
        assertEquals("export const FOOD_TYPE_OPTIONS = " + toJson(foodTypes) + ";",
                findLine(frontend, "export const FOOD_TYPE_OPTIONS"),
                "前端餐食类型词表必须与 canonical 同序");

        String prompt = Files.readString(Path.of(CLARIFY_PROMPT));
        assertEquals("- cuisine：菜系偏好（" + String.join("/", cuisines) + "）",
                findLine(prompt, "- cuisine：菜系偏好（").trim(),
                "提示词菜系词表行必须由 canonical 生成");
        assertEquals("- foodType：餐食类型（" + String.join("/", foodTypes) + "）",
                findLine(prompt, "- foodType：餐食类型（").trim(),
                "提示词餐食类型词表行必须由 canonical 生成");
    }

    private List<String> readCanonical(String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(Path.of(CANONICAL)));
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            root.path(field).forEach(node -> values.add(node.asText()));
            return values;
        } catch (Exception e) {
            throw new IllegalStateException("canonical 词表读取失败: " + CANONICAL, e);
        }
    }

    private String findLine(String text, String prefix) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(prefix) + ".*$", Pattern.MULTILINE)
                .matcher(text);
        assertTrue(matcher.find(), "找不到生成物行: " + prefix);
        return matcher.group();
    }

    private String toJson(List<String> values) {
        return JsonServiceHolder.toJson(values);
    }

    /** 测试内嵌的紧凑 JSON 数组渲染（与生成脚本 ensure_ascii=False 输出一致）。 */
    private static final class JsonServiceHolder {
        private static String toJson(List<String> values) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append('"').append(values.get(i)).append('"');
            }
            return builder.append(']').toString();
        }
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder();
        for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
        return result.toString();
    }
}
