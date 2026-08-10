package com.diet.health.plan;

/** 规则命中视图（Trace 与前端展示）。 */
public record RuleHitView(String ruleCode, String ruleVersion, String stage, String severity,
                          String decision, String copy, String detail) {
}
