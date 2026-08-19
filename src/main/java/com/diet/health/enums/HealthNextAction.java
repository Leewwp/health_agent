package com.diet.health.enums;

/** 健康聊天响应的下一步动作。 */
public enum HealthNextAction {
    /** 等待用户下一句输入。 */
    WAIT_USER,
    /** 需要用户回答问题。 */
    ASK_CLARIFY
    ,CONFIRM_PLAN_BRIEF
    ,COMPLETE_PROFILE
    ,GENERATE_PLAN
    ,VIEW_TRACE
}
