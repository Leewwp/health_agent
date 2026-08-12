package com.diet.health.rag;

import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.service.meal.MealRankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构化检索器测试（33 号票 RAG seam；#69 迁移到审核读取模块替身，不断言 Mapper 内部交互）。
 * 验证：硬约束（排除 ID、过敏原）先于打分过滤；结构化排序；
 * 结果模式标记为 STRUCTURED；APPROVED 过滤由读取模块契约承担。
 */
class StructuredMealRetrieverTest {

    private ReviewedMealReader reviewedMealReader;
    private StructuredMealRetriever retriever;

    @BeforeEach
    void setUp() {
        reviewedMealReader = mock(ReviewedMealReader.class);
        retriever = new StructuredMealRetriever(reviewedMealReader, new MealRankService());
    }

    @Test
    void 排除ID在检索后过滤() {
        when(reviewedMealReader.recallStructured(anyMap(), anyInt()))
                .thenReturn(List.of(meal(1L, "A", List.of()), meal(2L, "B", List.of())));
        RetrievalResult result = retriever.retrieve(query(List.of(2L), List.of()), 10);
        assertEquals(1, result.items().size());
        assertEquals(1L, result.items().get(0).meal().id());
    }

    @Test
    void 过敏原硬约束先过滤再排序() {
        when(reviewedMealReader.recallStructured(anyMap(), anyInt()))
                .thenReturn(List.of(
                        meal(1L, "花生鸡丁", List.of("花生")),
                        meal(2L, "清蒸鱼", List.of("鱼")),
                        meal(3L, "番茄蛋汤", List.of())));
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of("花生")), 10);
        assertEquals(List.of(2L, 3L), result.items().stream().map(r -> r.meal().id()).toList(),
                "花生过敏的餐食必须被过滤");
    }

    @Test
    void 结构化标签命中越多排越前() {
        when(reviewedMealReader.recallStructured(anyMap(), anyInt()))
                .thenReturn(List.of(
                        meal(1L, "普通米饭", Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡")), List.of()),
                        meal(2L, "高蛋白鸡胸", Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡"),
                                "taste", List.of("鲜")), List.of())));
        RetrievalResult result = retriever.retrieve(
                new MealRetrievalQuery(Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡"), "taste", List.of("鲜")),
                        List.of(), List.of(), "鲜味晚餐"),
                10);
        assertEquals(2L, result.items().get(0).meal().id(), "标签命中多的必须排前面");
    }

    @Test
    void 空候选返回空结果() {
        when(reviewedMealReader.recallStructured(anyMap(), anyInt())).thenReturn(List.of());
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of()), 10);
        assertTrue(result.items().isEmpty());
        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertNull(result.degradationReason());
    }

    @Test
    void 结果模式为STRUCTURED且语义分为空() {
        when(reviewedMealReader.recallStructured(anyMap(), anyInt())).thenReturn(List.of(meal(1L, "A", List.of())));
        RetrievalResult result = retriever.retrieve(query(List.of(), List.of()), 10);
        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertNull(result.items().get(0).semanticScore());
        assertTrue(result.items().get(0).structuredScore() >= 0);
    }

    private MealRetrievalQuery query(List<Long> excludeIds, List<String> allergens) {
        return new MealRetrievalQuery(
                Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡")),
                excludeIds, allergens, "高蛋白晚餐");
    }

    private ReviewedMeal meal(Long id, String name, List<String> allergens) {
        return meal(id, name, Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("清淡")), allergens);
    }

    private ReviewedMeal meal(Long id, String name, Map<String, List<String>> tags, List<String> allergens) {
        Map<String, List<String>> allTags = new LinkedHashMap<>();
        allTags.put("mealTime", tags.getOrDefault("mealTime", List.of()));
        allTags.put("mood", tags.getOrDefault("mood", List.of()));
        allTags.put("scene", tags.getOrDefault("scene", List.of()));
        allTags.put("healthGoal", tags.getOrDefault("healthGoal", List.of()));
        allTags.put("cuisine", tags.getOrDefault("cuisine", List.of()));
        allTags.put("taste", tags.getOrDefault("taste", List.of()));
        allTags.put("convenience", tags.getOrDefault("convenience", List.of()));
        return new ReviewedMeal(
                id, name, null, List.of(), allTags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                allergens, "REVIEWED", "APPROVED", "NONE", null,
                "foodcom-recipes-and-reviews-v2", "src-" + id, "v2", "PUBLIC"
        );
    }
}
