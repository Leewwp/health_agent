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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 评估内核测试（33 号票验收；#77 扩展 MRR/NDCG@3/Precision@3/降级分布/分层汇总/P95）。
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

    @Test
    void mrr按首个命中位置倒数计算() {
        // q1：期望 meal-5，top3 第 3 位命中 → MRR=1/3
        // q2：期望 meal-1，top3 第 1 位命中 → MRR=1.0
        // q3：期望 meal-9，top3 无命中 → MRR=0
        MealRetriever retriever = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "meal-2", "meal-5"),
                "查询 q2", result("meal-1", "meal-3", "meal-4"),
                "查询 q3", result("meal-2", "meal-3", "meal-4")));
        RetrieverEvaluation evaluation = RecallEvaluationService.evaluateRetriever(
                retriever, List.of(
                        query("q1", List.of("meal-5")),
                        query("q2", List.of("meal-1")),
                        query("q3", List.of("meal-9"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(1.0 / 3, evaluation.queries().get(0).mrr(), 1e-9);
        assertEquals(1.0, evaluation.queries().get(1).mrr(), 1e-9);
        assertEquals(0.0, evaluation.queries().get(2).mrr(), 1e-9);
        assertEquals((1.0 / 3 + 1.0) / 3, evaluation.avgMrr(), 1e-9);
    }

    @Test
    void ndcgAt3按理想排序归一且空真值记0() {
        // 理想排序（期望 meal-1/meal-2 全排前）：IDCG = 1 + 1/log2(3)
        // 命中位 2/3：DCG = 1/log2(3) + 1/log2(4) → NDCG=0.6934...
        MealRetriever position23 = stubRetriever(Map.of(
                "查询 q", result("meal-3", "meal-1", "meal-2")));
        RetrieverEvaluation eval23 = RecallEvaluationService.evaluateRetriever(
                position23, List.of(query("q", List.of("meal-1", "meal-2"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(0.6934264036172708, eval23.queries().get(0).ndcgAt3(), 1e-9);

        // 完美排序（第 1 位即命中首个真值，且真值顺序任意）：NDCG=1.0
        MealRetriever perfect = stubRetriever(Map.of(
                "查询 q", result("meal-2", "meal-1", "meal-3")));
        RetrieverEvaluation evalPerfect = RecallEvaluationService.evaluateRetriever(
                perfect, List.of(query("q", List.of("meal-1", "meal-2"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(1.0, evalPerfect.queries().get(0).ndcgAt3(), 1e-9);

        // 只有第 1 位命中：DCG=1 → NDCG=1/IDCG=0.6131...
        MealRetriever pos1 = stubRetriever(Map.of(
                "查询 q", result("meal-2", "meal-3", "meal-5")));
        RetrieverEvaluation eval1 = RecallEvaluationService.evaluateRetriever(
                pos1, List.of(query("q", List.of("meal-1", "meal-2"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(0.6131471927654584, eval1.queries().get(0).ndcgAt3(), 1e-9);

        // 空真值：NDCG=0
        MealRetriever any = stubRetriever(Map.of("查询 q", result("meal-1", "meal-2", "meal-3")));
        RetrieverEvaluation evalEmpty = RecallEvaluationService.evaluateRetriever(
                any, List.of(query("q", List.of())),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(0.0, evalEmpty.queries().get(0).ndcgAt3(), 1e-9);
        assertEquals(0.0, evalEmpty.avgNdcgAt3(), 1e-9);
    }

    @Test
    void precisionAt3按top3命中数除以3() {
        // q1：命中 2 个 → 2/3；q2：命中 0 个 → 0；q3：命中 3 个 → 1.0
        MealRetriever retriever = stubRetriever(Map.of(
                "查询 q1", result("meal-1", "meal-2", "meal-5"),
                "查询 q2", result("meal-3", "meal-4", "meal-5"),
                "查询 q3", result("meal-1", "meal-2", "meal-3")));
        RetrieverEvaluation evaluation = RecallEvaluationService.evaluateRetriever(
                retriever, List.of(
                        query("q1", List.of("meal-1", "meal-2")),
                        query("q2", List.of("meal-9")),
                        query("q3", List.of("meal-1", "meal-2", "meal-3"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(2.0 / 3, evaluation.queries().get(0).precisionAt3(), 1e-9);
        assertEquals(0.0, evaluation.queries().get(1).precisionAt3(), 1e-9);
        assertEquals(1.0, evaluation.queries().get(2).precisionAt3(), 1e-9);
        assertEquals((2.0 / 3 + 0 + 1.0) / 3, evaluation.avgPrecisionAt3(), 1e-9);
    }

    @Test
    void 降级分布按原因统计且未降级记无() {
        MealRetriever structured = stubRetriever(Map.of(
                "查询 q1", result("meal-1"),
                "查询 q2", result("meal-2")));
        MealRetriever hybrid = stubRetrieverDegraded(Map.of(
                "查询 q1", result("meal-1"),
                "查询 q2", result("meal-2")));
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structured, hybrid,
                List.of(query("q1", List.of("meal-1")), query("q2", List.of("meal-2"))),
                SOURCE_BY_ID, 3);
        // structured 不计数：全部「无」
        assertEquals(Map.of("无", 2), evaluation.structured().degradationDistribution());
        // hybrid 全部降级为 embedding_unavailable
        assertEquals(Map.of("embedding_unavailable", 2), evaluation.hybrid().degradationDistribution());
    }

    @Test
    void 分层汇总按stratum聚合() {
        MealRetriever retriever = stubRetriever(Map.of(
                "查询 a1", result("meal-1", "meal-2", "meal-3"),
                "查询 a2", result("meal-3", "meal-1", "meal-5"),
                "查询 b1", result("meal-5", "meal-4", "meal-1")));
        RetrieverEvaluation evaluation = RecallEvaluationService.evaluateRetriever(
                retriever, List.of(
                        query("a1", "exact_label", List.of("meal-1", "meal-2")),
                        query("a2", "exact_label", List.of("meal-1")),
                        query("b1", "synonym", List.of("meal-1", "meal-2", "meal-3", "meal-5"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.USER_TEXT);
        assertEquals(2, evaluation.stratumSummaries().size());
        StratumSummary exact = evaluation.stratumSummaries().get("exact_label");
        assertEquals(2, exact.queryCount());
        // a1 recall=1.0, a2 recall=1.0 → 1.0；a1 MRR=1.0（meal-1 第 1 位）, a2 MRR=1/2（meal-1 第 2 位）→ 0.75
        assertEquals(1.0, exact.avgRecallAt3(), 1e-9);
        assertEquals(0.75, exact.avgMrr(), 1e-9);
        StratumSummary synonym = evaluation.stratumSummaries().get("synonym");
        assertEquals(1, synonym.queryCount());
        // b1 top3 = meal-5, meal-4, meal-1：命中 meal-5/meal-1 两个 → recall=2/4=0.5；MRR=1.0（meal-5 第 1 位）
        assertEquals(0.5, synonym.avgRecallAt3(), 1e-9);
        assertEquals(1.0, synonym.avgMrr(), 1e-9);
    }

    @Test
    void p95按升序第95百分位取值() {
        assertEquals(5.0, RecallEvaluationService.p95(List.of(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-9);
        assertEquals(3.0, RecallEvaluationService.p95(List.of(1.0, 2.0, 3.0)), 1e-9);
        assertEquals(0.0, RecallEvaluationService.p95(List.of()), 1e-9);
    }

    @Test
    void 槽位拼接模式将嵌入文本置空() {
        MealRetriever retriever = mock(MealRetriever.class);
        when(retriever.retrieve(any(MealRetrievalQuery.class), anyInt()))
                .thenAnswer(invocation -> {
                    MealRetrievalQuery q = invocation.getArgument(0);
                    if (!"".equals(q.text())) {
                        throw new AssertionError("SLOT_CONCAT 模式必须传空文本，实际: " + q.text());
                    }
                    return new RetrievalResult(List.of(), RetrievalMode.HYBRID, null);
                });
        RetrieverEvaluation evaluation = RecallEvaluationService.evaluateRetriever(
                retriever, List.of(query("q1", List.of("meal-1"))),
                SOURCE_BY_ID, 3, false, EmbeddingTextMode.SLOT_CONCAT);
        assertTrue(evaluation.queries().get(0).topKSourceIds().isEmpty());
    }

    private LabeledMealQuery query(String id, List<String> expected) {
        return query(id, expected, List.of());
    }

    private LabeledMealQuery query(String id, List<String> expected, List<String> exclude) {
        return new LabeledMealQuery(id, "查询 " + id, Map.of(), List.of(), exclude, expected);
    }

    private LabeledMealQuery query(String id, String stratum, List<String> expected) {
        return new LabeledMealQuery(id, stratum, "查询 " + id, Map.of(), List.of(), List.of(), expected);
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
