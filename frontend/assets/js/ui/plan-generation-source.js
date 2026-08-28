const SOURCE_LABELS = {
    AGENT: "Agent 生成",
    FALLBACK: "规则降级",
    RULE_COMPOSER: "规则组合",
    RULE_MEAL_COMPOSER: "餐食规则组合",
    COMPOSITE_RULE_MERGE: "综合规则合并"
};

/** 将后端计划生成来源转换为用户可见文案。 */
export function planGenerationSourceLabel(source) {
    return SOURCE_LABELS[source] || source || "未知来源";
}
