package com.diet.health.enums;

/** 健康意图的任务维度（正交模型）。 */
public enum HealthTask {
    /** 一般对话（未明确具体任务）。 */
    CHAT,
    /** 浏览资源。 */
    BROWSE,
    /** 单次推荐。 */
    RECOMMEND,
    /** 周计划。 */
    PLAN,
    /** 调整既有推荐。 */
    ADJUST
}
