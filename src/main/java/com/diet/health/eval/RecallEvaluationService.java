package com.diet.health.eval;

import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recall@K 评估（33 号票验收：固定查询集对比 structured-only 与 hybrid）。
 * <p>
 * 指标：Recall@3（top3 中命中真值 / 真值总数）、硬约束命中率（排除项不出现于 topK）、
 * 降级次数（hybrid 检索器回落到 STRUCTURED 模式）。hybrid 没有可复现提升时只记录结论。
 */
public final class RecallEvaluationService {

    private RecallEvaluationService() {
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
                                            int topK) {
        return new RecallEvaluation(
                evaluateRetriever(structured, queries, mealIdToSourceId, topK, false),
                evaluateRetriever(hybrid, queries, mealIdToSourceId, topK, true));
    }

    private static RetrieverEvaluation evaluateRetriever(MealRetriever retriever,
                                                         List<LabeledMealQuery> queries,
                                                         Map<Long, String> mealIdToSourceId,
                                                         int topK,
                                                         boolean countDegradation) {
        List<QueryEvaluation> perQuery = new ArrayList<>();
        double recallSum = 0;
        double constraintSum = 0;
        int degraded = 0;
        Map<String, Long> idBySource = mealIdToSourceId.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        for (LabeledMealQuery query : queries) {
            List<Long> excludeIds = query.excludeSourceIds().stream()
                    .map(idBySource::get)
                    .filter(id -> id != null)
                    .toList();
            RetrievalResult result = retriever.retrieve(
                    new MealRetrievalQuery(query.slots(), excludeIds, query.allergens(), query.text()),
                    topK);
            List<String> topKSourceIds = result.items().stream()
                    .map(RetrievalItem::meal)
                    .map(item -> mealIdToSourceId.get(item.id()))
                    .filter(id -> id != null)
                    .toList();

            double recall = recallAtK(topKSourceIds, query.expectedSourceIds());
            double constraint = constraintHitRate(topKSourceIds, query.excludeSourceIds());
            recallSum += recall;
            constraintSum += constraint;
            if (countDegradation && result.mode() == RetrievalMode.STRUCTURED) {
                degraded++;
            }
            perQuery.add(new QueryEvaluation(query.id(), recall, constraint, result.mode().name(), topKSourceIds));
        }
        int size = queries.size();
        return new RetrieverEvaluation(
                perQuery,
                size == 0 ? 0 : recallSum / size,
                size == 0 ? 1.0 : constraintSum / size,
                degraded);
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

    /** 排除项不出现在 topK 时为 1，否则为 0。 */
    private static double constraintHitRate(List<String> topKSourceIds, List<String> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return 1.0;
        }
        Set<String> topK = new HashSet<>(topKSourceIds);
        boolean violated = exclude.stream().anyMatch(topK::contains);
        return violated ? 0.0 : 1.0;
    }
}
