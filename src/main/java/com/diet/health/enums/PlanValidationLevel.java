package com.diet.health.enums;

/**
 * 计划校验结果分类（24 号契约）：
 * OK 可继续流程；WARNING 可保存 DRAFT 不可激活；HARD_ERROR 拒绝保存或激活；DEGRADED 确定性模板/信息不足。
 */
public enum PlanValidationLevel {
    OK,
    WARNING,
    HARD_ERROR,
    DEGRADED
}
