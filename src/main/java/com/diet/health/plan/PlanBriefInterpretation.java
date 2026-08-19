package com.diet.health.plan;

import java.util.List;
import java.util.Map;

/** 单轮计划简报解释结果；候选值尚未代表已经写入简报。 */
public record PlanBriefInterpretation(
        BriefInterpretationStatus status,
        PlanBrief parsed,
        Map<String, List<String>> candidateFields,
        String evidence,
        String guidance,
        boolean likelyCurrentField
) {
    public PlanBriefInterpretation {
        parsed = parsed == null ? PlanBrief.empty() : parsed;
        candidateFields = candidateFields == null ? Map.of() : Map.copyOf(candidateFields);
        evidence = evidence == null ? "" : evidence;
        guidance = guidance == null ? "" : guidance;
    }
}
