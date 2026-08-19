package com.diet.health.plan;

/** 训练计划生成请求；简报不从客户端接收，服务端从 session 中重读。 */
public record GenerateTrainingPlanRequest(String sessionId, String requestId) {
}
