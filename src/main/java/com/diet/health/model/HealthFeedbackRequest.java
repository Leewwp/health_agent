package com.diet.health.model;

/**
 * 健康类型化反馈请求（41 号票 + #74）。
 * resourceType/resourceId 同时提供时校验资源存在，可都省略（会话级反馈）；
 * planId/planItemId 提供时校验归属与版本；source 缺省为 HEALTH_CHAT。
 * traceId 非空时进入 #74 精确归因校验：必须归属当前用户且 sessionId 匹配，
 * 且 resourceType/resourceId 必须出现在该 trace 响应的 displayBlocks 中。
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
        String source,
        String traceId
) {

    /** 兼容构造：未指定 traceId（旧调用/会话级反馈）时等价于 traceId 为 null。 */
    public HealthFeedbackRequest(String sessionId, String resourceType, String resourceId, String action,
                                 Long planId, Long planItemId, Integer rating, String reason, String source) {
        this(sessionId, resourceType, resourceId, action, planId, planItemId, rating, reason, source, null);
    }
}
