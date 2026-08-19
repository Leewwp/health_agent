package com.diet.health.plan;

import com.diet.health.enums.PlanScope;

/** 训练计划生成请求；简报不从客户端接收，服务端从 session 中重读。 */
public record GenerateTrainingPlanRequest(String sessionId, String requestId, PlanScope planScope) {
    public GenerateTrainingPlanRequest(String sessionId, String requestId) {
        this(sessionId, requestId, PlanScope.EXERCISE);
    }
}
