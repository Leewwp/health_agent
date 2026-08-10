package com.diet.health.eval;

import java.util.List;

/** 单个检索器在整个查询集上的汇总。 */
public record RetrieverEvaluation(
        List<QueryEvaluation> queries,
        double avgRecallAt3,
        double hardConstraintHitRate,
        int degradedCount
) {
}
