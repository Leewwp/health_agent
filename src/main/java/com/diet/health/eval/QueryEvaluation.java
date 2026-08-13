package com.diet.health.eval;

import java.util.List;

/**
 * 单条查询的评估结果（#77 扩展：MRR/NDCG@3/Precision@3）。
 *
 * @param queryId         查询 ID
 * @param stratum         评测分层
 * @param recallAt3       Recall@3（top3 命中真值 / 真值总数）
 * @param mrr             平均倒数排名（top3 内首个命中位置倒数，无命中为 0）
 * @param ndcgAt3         NDCG@3（DCG 对数底 2，位置从 1 开始，理想排序按真值全排前）
 * @param precisionAt3    Precision@3（top3 命中真值 / 3）
 * @param hardConstraintHitRate 硬约束命中率（排除项/过敏原不出现于 top3）
 * @param mode            实际检索模式（STRUCTURED/HYBRID）
 * @param topKSourceIds   top3 来源 ID（按检索排序）
 */
public record QueryEvaluation(
        String queryId,
        String stratum,
        double recallAt3,
        double mrr,
        double ndcgAt3,
        double precisionAt3,
        double hardConstraintHitRate,
        String mode,
        List<String> topKSourceIds
) {
}
