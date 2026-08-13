package com.diet.health.eval;

import java.util.List;
import java.util.Map;

/**
 * 单个检索器在整个查询集上的汇总（#77 扩展：MRR/NDCG@3/Precision@3/延迟/降级分布/分层汇总）。
 *
 * @param queries                 逐查询明细
 * @param avgRecallAt3            平均 Recall@3
 * @param avgMrr                  平均 MRR
 * @param avgNdcgAt3              平均 NDCG@3
 * @param avgPrecisionAt3         平均 Precision@3
 * @param hardConstraintHitRate   硬约束命中率（排除项/过敏原不出现于 top3 的查询占比）
 * @param degradedCount           降级查询数（hybrid 回落为 STRUCTURED；structured 恒为 0）
 * @param p95LatencyMs            P95 检索延迟（毫秒，按单次 retrieve 计时）
 * @param degradationDistribution 降级原因分布（vector_store_unavailable/embedding_unavailable/no_vector_hits/无）
 * @param stratumSummaries        按评测分层（六层）的指标汇总
 */
public record RetrieverEvaluation(
        List<QueryEvaluation> queries,
        double avgRecallAt3,
        double avgMrr,
        double avgNdcgAt3,
        double avgPrecisionAt3,
        double hardConstraintHitRate,
        int degradedCount,
        double p95LatencyMs,
        Map<String, Integer> degradationDistribution,
        Map<String, StratumSummary> stratumSummaries
) {
}
