package com.diet.health.eval;

/**
 * 评测分层汇总（#77：六层各 10 条，逐层报告 Recall@3/MRR/NDCG@3/Precision@3）。
 *
 * @param stratum         分层名
 * @param queryCount      该层查询数
 * @param avgRecallAt3    该层平均 Recall@3
 * @param avgMrr          该层平均 MRR
 * @param avgNdcgAt3      该层平均 NDCG@3
 * @param avgPrecisionAt3 该层平均 Precision@3
 */
public record StratumSummary(
        String stratum,
        int queryCount,
        double avgRecallAt3,
        double avgMrr,
        double avgNdcgAt3,
        double avgPrecisionAt3
) {
}
