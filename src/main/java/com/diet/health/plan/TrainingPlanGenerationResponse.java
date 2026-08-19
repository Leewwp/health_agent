package com.diet.health.plan;

/** 训练计划生成高层操作响应。 */
public record TrainingPlanGenerationResponse(
        Long planId,
        String traceId,
        String generationSource,
        String status,
        String message,
        PlanView plan
) {
}
