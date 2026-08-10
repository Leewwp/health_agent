package com.diet.health.enums;

/** 健康聊天响应的类型。 */
public enum HealthResponseType {
    /** 普通回答（含推荐结果）。 */
    ANSWER,
    /** 需要用户补充信息。 */
    CLARIFY,
    /** 风险拦截，返回固定提示。 */
    BLOCKED
}
