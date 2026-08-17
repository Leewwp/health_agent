package com.diet.health.enums;

/** 健康意图的领域维度（正交模型）。 */
public enum HealthDomain {
    /** 饮食。 */
    MEAL,
    /** 健身。 */
    EXERCISE,
    /** 作息。 */
    ROUTINE,
    /** 健康范围外的一般对话，不触发健康资源检索。 */
    OTHER,
    /** 综合（多品类组合诉求）。 */
    COMPOSITE
}
