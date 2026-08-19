package com.diet.health.eval;

import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 评估内核（33 号票验收：#77 扩展到六层 60 条查询与 MRR/NDCG@3/Precision@3/延迟/降级分布）。
 * <p>
 * 指标口径（全部按 topK=3，位置从 1 开始）：
 * <ul>
 *   <li>Recall@3 = top3 命中真值数 / 真值总数（真值为空记 0）</li>
 *   <li>MRR = top3 内首个命中的 1/rank，无命中记 0</li>
 *   <li>NDCG@3 = DCG/IDCG；DCG = Σ rel_i / log2(i+1)，IDCG 按真值全部排前取理想增益</li>
 *   <li>Precision@3 = top3 命中真值数 / 3</li>
 * </ul>
 * 硬约束命中率 = 排除项/过敏原不出现于 top3 的查询占比；降级分布按 hybrid 检索结果的
 * degradationReason（vector_store_unavailable/embedding_unavailable/no_vector_hits），
 * 未降级记「无」。延迟在每次 retrieve 调用处计时，P95 按升序第 ceil(0.95*N) 位取值。
 * 嵌入文本来源（用户原话/槽位拼接）由 {@link EmbeddingTextMode} 注入，用于消融。
 */
public final class RecallEvaluationService {

    /** NDCG 理想增益表：log2(2)=1, log2(3), log2(4)。 */
    private static final double[] DCG_GAIN = {1.0, Math.log(3) / Math.log(2), Math.log(4) / Math.log(2)};

    private RecallEvaluationService() {
    }

    /** 兼容入口：默认用户原话嵌入。 */
    public static RecallEvaluation evaluate(MealRetriever structured,
                                            MealRetriever hybrid,
                                            List<LabeledMealQuery> queries,
                                            Map<Long, String> mealIdToSourceId,
                                            int topK) {
        return evaluate(structured, hybrid, queries, mealIdToSourceId, topK, EmbeddingTextMode.USER_TEXT);
    }

    /**
     * 对两个检索器在同一查询集上评估。
     * degradedCount 只对 hybrid 位置有意义（structured 恒为 STRUCTURED，不计数）。
     *
     * @param mealIdToSourceId 候选餐食 id → 来源 ID（来源 ID 是评估真值的稳定键）
     */
    public static RecallEvaluation evaluate(MealRetriever structured,
                                            MealRetriever hybrid,
                                            List<LabeledMealQuery> queries,
                                            Map<Long, String> mealIdToSourceId,
                                            int topK,
                                            EmbeddingTextMode textMode) {
        return new RecallEvaluation(
                evaluateRetriever(structured, queries, mealIdToSourceId, topK, false, textMode),
                evaluateRetriever(hybrid, queries, mealIdToSourceId, topK, true, textMode));
    }

    /** 单个检索器的完整评估（消融：任一检索器变体都可单独评估）。 */
    public static RetrieverEvaluation evaluateRetriever(MealRetriever retriever,
                                                        List<LabeledMealQuery> queries,
                                                        Map<Long, String> mealIdToSourceId,
                                                        int topK,
                                                        boolean countDegradation,
                                                        EmbeddingTextMode textMode) {
        Map<String, Long> idBySource = mealIdToSourceId.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        List<QueryEvaluation> perQuery = new ArrayList<>();
        Map<String, List<QueryEvaluation>> byStratum = new LinkedHashMap<>();
        Map<String, Integer> degradation = new LinkedHashMap<>();
        List<Double> latenciesMs = new ArrayList<>();
        double recallSum = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        double precisionSum = 0;
        double constraintSum = 0;
        int degraded = 0;
        for (LabeledMealQuery query : queries) {
            List<Long> excludeIds = query.excludeSourceIds().stream()
                    .map(idBySource::get)
                    .filter(id -> id != null)
                    .toList();
            long startNanos = System.nanoTime();
            RetrievalResult result = retriever.retrieve(
                    new MealRetrievalQuery(query.slots(), excludeIds, query.allergens(),
                            textMode == EmbeddingTextMode.SLOT_CONCAT ? "" : query.text()),
                    topK);
            double totalLatencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            latenciesMs.add(totalLatencyMs);
            List<String> topKSourceIds = result.items().stream()
                    .map(RetrievalItem::meal)
                    .map(item -> mealIdToSourceId.get(item.id()))
                    .filter(id -> id != null)
                    .toList();

            double recall = recallAtK(topKSourceIds, query.expectedSourceIds());
            double mrr = reciprocalRank(topKSourceIds, query.expectedSourceIds());
            double ndcg = ndcgAtK(topKSourceIds, query.expectedSourceIds());
            double precision = precisionAtK(topKSourceIds, query.expectedSourceIds());
            double constraint = constraintHitRate(topKSourceIds, query.excludeSourceIds());
            recallSum += recall;
            mrrSum += mrr;
            ndcgSum += ndcg;
            precisionSum += precision;
            constraintSum += constraint;
            boolean isDegraded = countDegradation && result.mode() == RetrievalMode.STRUCTURED;
            if (isDegraded) {
                degraded++;
            }
            String reason = isDegraded && result.degradationReason() != null
                    ? result.degradationReason() : "无";
            degradation.merge(reason, 1, Integer::sum);
            String stratum = query.stratum() == null ? "unknown" : query.stratum();
            QueryEvaluation qe = new QueryEvaluation(
                    query.id(), stratum, recall, mrr, ndcg, precision, constraint,
                    result.mode().name(), topKSourceIds,
                    result.evidence().structuredCandidateCount(),
                    result.evidence().vectorCandidateCount(),
                    result.evidence().fusedCandidateCount(),
                    result.evidence().vectorStatus().name(),
                    result.evidence().vectorLatencyMs(), totalLatencyMs);
            perQuery.add(qe);
            byStratum.computeIfAbsent(stratum, k -> new ArrayList<>()).add(qe);
        }
        int size = queries.size();
        Map<String, StratumSummary> stratumSummaries = new LinkedHashMap<>();
        byStratum.forEach((stratum, list) -> {
            int n = list.size();
            stratumSummaries.put(stratum, new StratumSummary(
                    stratum, n,
                    list.stream().mapToDouble(QueryEvaluation::recallAt3).average().orElse(0),
                    list.stream().mapToDouble(QueryEvaluation::mrr).average().orElse(0),
                    list.stream().mapToDouble(QueryEvaluation::ndcgAt3).average().orElse(0),
                    list.stream().mapToDouble(QueryEvaluation::precisionAt3).average().orElse(0)));
        });
        return new RetrieverEvaluation(
                perQuery,
                size == 0 ? 0 : recallSum / size,
                size == 0 ? 0 : mrrSum / size,
                size == 0 ? 0 : ndcgSum / size,
                size == 0 ? 0 : precisionSum / size,
                size == 0 ? 1.0 : constraintSum / size,
                degraded,
                p95(latenciesMs),
                degradation,
                stratumSummaries);
    }

    /** topK 命中真值来源数 / 真值总数；真值为空时该查询记 0。 */
    private static double recallAtK(List<String> topKSourceIds, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        Set<String> expectedSet = new HashSet<>(expected);
        long hits = topKSourceIds.stream().filter(expectedSet::contains).count();
        return (double) hits / expected.size();
    }

    /** MRR：topK 内首个命中的 1/rank，无命中记 0。 */
    private static double reciprocalRank(List<String> topKSourceIds, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        Set<String> expectedSet = new HashSet<>(expected);
        for (int i = 0; i < topKSourceIds.size(); i++) {
            if (expectedSet.contains(topKSourceIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /** NDCG@3：DCG 对数底 2、位置从 1 开始，IDCG 按真值全部排前。真值为空记 0。 */
    private static double ndcgAtK(List<String> topKSourceIds, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        Set<String> expectedSet = new HashSet<>(expected);
        double dcg = 0;
        for (int i = 0; i < Math.min(topKSourceIds.size(), 3); i++) {
            if (expectedSet.contains(topKSourceIds.get(i))) {
                dcg += 1.0 / DCG_GAIN[i];
            }
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(expected.size(), 3); i++) {
            idcg += 1.0 / DCG_GAIN[i];
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    /** Precision@3：top3 命中真值数 / 3。 */
    private static double precisionAtK(List<String> topKSourceIds, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        Set<String> expectedSet = new HashSet<>(expected);
        long hits = topKSourceIds.stream().filter(expectedSet::contains).count();
        return (double) hits / 3;
    }

    /** 排除项不出现在 topK 时为 1，否则为 0。 */
    private static double constraintHitRate(List<String> topKSourceIds, List<String> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return 1.0;
        }
        Set<String> topK = new HashSet<>(topKSourceIds);
        boolean violated = exclude.stream().anyMatch(topK::contains);
        return violated ? 0.0 : 1.0;
    }

    /** P95：按升序取第 ceil(0.95*N) 位；空集合记 0。 */
    public static double p95(List<Double> latenciesMs) {
        if (latenciesMs == null || latenciesMs.isEmpty()) {
            return 0;
        }
        List<Double> sorted = latenciesMs.stream().sorted().toList();
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
