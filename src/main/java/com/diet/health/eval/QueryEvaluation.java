package com.diet.health.eval;

import java.util.List;

/** 单条查询的评估结果。 */
public record QueryEvaluation(
        String queryId,
        double recallAt3,
        double hardConstraintHitRate,
        String mode,
        List<String> topKSourceIds
) {
}
