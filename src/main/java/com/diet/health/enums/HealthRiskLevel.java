package com.diet.health.enums;

/** 健康风险等级，多规则取最高。 */
public enum HealthRiskLevel {
    /** 无风险信号，正常流程。 */
    NORMAL,
    /** 建议提示，不阻止单次推荐（周计划场景可能阻止组合）。 */
    ADVISORY,
    /** 阻止生成具体计划，返回固定提示。 */
    BLOCK_PLAN
}
