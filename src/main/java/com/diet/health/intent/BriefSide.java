package com.diet.health.intent;

/** 计划简报侧归属：MEAL/EXERCISE 为单侧，BOTH 表示综合简报两侧均完整且需要显式前缀，NONE 表示无活跃侧。 */
public enum BriefSide {
    MEAL,
    EXERCISE,
    BOTH,
    NONE
}
