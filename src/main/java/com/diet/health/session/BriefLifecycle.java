package com.diet.health.session;

/**
 * 计划简报会话生命周期（简报补充回路规格 v3.2）。
 * 按MEAL/EXERCISE 侧独立记录并随会话 JSON 持久化：
 * OPEN 简报收集中；PAUSED 显式切换其他领域（暂停不等于关闭，显式计划词恢复 OPEN）；
 * GENERATED 对应范围计划生成成功（简报保留展示，briefActive=false）。
 * 已 GENERATED 不得被旧请求回写为 OPEN/PAUSED，仅显式计划词可重新打开。
 */
public enum BriefLifecycle {
    OPEN,
    PAUSED,
    GENERATED
}
