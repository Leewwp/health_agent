package com.diet.health.eval;

import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recall@3 评估服务测试（33 号票验收：结构化与 hybrid 对比、硬约束命中率、降级计数）。
 * 接缝：RecallEvaluationService + 固定返回的 stub 检索器；聚合数值均为手工算出的字面量。
 */
class RecallEvaluationServiceTest {

    private static final Map<Long, String> SOURCE_BY_ID = Map.of(
            1L, "meal-1", 2L, "meal-2", 3L, "meal-3", 4L, "meal-4", 5L, "meal-5", 9L, "meal-9");

    @Test
    void 计算每条查询的RecallAt3() {
        // q1：期望 meal-1/meal-2。结构化 top3 只命中 meal-1 → 0.5；hybrid 命中两者 → 1.0
        // q2：期望 meal-4。结构化 top3 命中 → 1.0；hybrid 命中 → 1.0
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-3", "meal-1", "meal-5"),
                "查询 q2", result("meal-5", "meal-4", "meal-1")));
        MealRetriever hybrid = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "meal-3", "meal-2"),
                "查询 q2", result("meal-4", "meal-5", "meal-2")));

        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, hybrid, List.of(
                        query("q1", List.of("meal-1", "meal-2")),
                        query("q2", List.of("meal-4"))),
                SOURCE_BY_ID, 3);

        assertEquals(0.5, evaluation.structured().queries().get(0).recallAt3(), 1e-9);
        assertEquals(1.0, evaluation.structured().queries().get(1).recallAt3(), 1e-9);
        assertEquals(0.75, evaluation.structured().avgRecallAt3(), 1e-9);

        assertEquals(1.0, evaluation.hybrid().queries().get(0).recallAt3(), 1e-9);
        assertEquals(1.0, evaluation.hybrid().avgRecallAt3(), 1e-9);
    }

    @Test
    void 未命中时RecallAt3为0() {
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-4", "meal-5", "meal-3")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, structured, List.of(query("q1", List.of("meal-1", "meal-2"))),
                SOURCE_BY_ID, 3);
        assertEquals(0.0, evaluation.structured().queries().get(0).recallAt3(), 1e-9);
        assertEquals(0.0, evaluation.structured().avgRecallAt3(), 1e-9);
    }

    @Test
    void 硬约束命中率统计排除项不出现() {
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "meal-9", "meal-2")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, structured,
                List.of(query("q1", List.of("meal-1"), List.of("meal-9"))),
                SOURCE_BY_ID, 3);
        // meal-9 是排除项且出现在 top3 → 命中率 0
        assertEquals(0.0, evaluation.structured().hardConstraintHitRate(), 1e-9);
    }

    @Test
    void 排除项不出现在结果时硬约束命中率为1() {
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "meal-2", "meal-3")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, structured,
                List.of(query("q1", List.of("meal-1"), List.of("meal-9"))),
                SOURCE_BY_ID, 3);
        assertEquals(1.0, evaluation.structured().hardConstraintHitRate(), 1e-9);
    }

    @Test
    void hybrid降级次数被统计() {
        MealRetriever structured = stubRetriever(Map.of("查询 q1", result("meal-1")));
        MealRetriever degradedHybrid = stubRetrieverDegraded(Map.of("查询 q1", result("meal-1")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, degradedHybrid, List.of(query("q1", List.of("meal-1"))),
                SOURCE_BY_ID, 3);
        assertEquals(0, evaluation.structured().degradedCount());
        assertEquals(1, evaluation.hybrid().degradedCount(), "hybrid 降级为结构化必须被计数");
        assertEquals(1.0, evaluation.hybrid().avgRecallAt3(), 1e-9, "降级时使用结构化结果计算");
    }

    @Test
    void 结果中无法映射来源ID的条目跳过() {
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "unknown-source", "meal-2")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, structured, List.of(query("q1", List.of("meal-1", "meal-2"))),
                SOURCE_BY_ID, 3);
        assertEquals(1.0, evaluation.structured().queries().get(0).recallAt3(), 1e-9);
    }

    private LabeledMealQuery query(String id, List<String> expected) {
        return query(id, expected, List.of());
    }

    private LabeledMealQuery query(String id, List<String> expected, List<String> exclude) {
        return new LabeledMealQuery(id, "查询 " + id, Map.of(), List.of(), exclude, expected);
    }

    private MealRetriever stubRetriever(Map<String, List<String>> top3ByQuery) {
        MealRetriever mock = mock(MealRetriever.class);
        when(mock.retrieve(any(MealRetrievalQuery.class), anyInt())).thenAnswer(invocation -> {
            MealRetrievalQuery query = invocation.getArgument(0);
            return new RetrievalResult(
                    top3ByQuery.get(query.text()).stream().map(s -> item(s)).toList(),
                    RetrievalMode.HYBRID, null);
        });
        return mock;
    }

    private MealRetriever stubRetrieverDegraded(Map<String, List<String>> top3ByQuery) {
        MealRetriever mock = mock(MealRetriever.class);
        when(mock.retrieve(any(MealRetrievalQuery.class), anyInt())).thenAnswer(invocation -> {
            MealRetrievalQuery query = invocation.getArgument(0);
            return new RetrievalResult(
                    top3ByQuery.get(query.text()).stream().map(s -> item(s)).toList(),
                    RetrievalMode.STRUCTURED, "embedding_unavailable");
        });
        return mock;
    }

    private RetrievalItem item(String sourceId) {
        long id = SOURCE_BY_ID.entrySet().stream()
                .filter(e -> e.getValue().equals(sourceId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(999L);
        MealItem meal = new MealItem(id, SourceMode.PUBLIC, null, sourceId, SlotBundle.empty(), 1.0);
        return new RetrievalItem(meal, 1.0, null, 1.0);
    }

    private List<String> result(String... sourceIds) {
        return List.of(sourceIds);
    }
}
