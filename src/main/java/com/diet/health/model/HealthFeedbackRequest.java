package com.diet.health.model;

/**
 * 健康类型化反馈请求（41 号票）。
 * resourceType/resourceId 同时提供时校验资源存在，可都省略（会话级反馈）；
 * planId/planItemId 提供时校验归属与版本；source 缺省为 HEALTH_CHAT。
 */
public record HealthFeedbackRequest(
        String sessionId,
        String resourceType,
        String resourceId,
        String action,
        Long planId,
        Long planItemId,
        Integer rating,
        String reason,
        String source
) {
}
