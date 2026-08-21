package com.diet.health.plan;

/** 计划状态写请求的幂等键和乐观版本。 */
public record PlanWriteRequest(String requestId, Long expectedVersion) {
}
