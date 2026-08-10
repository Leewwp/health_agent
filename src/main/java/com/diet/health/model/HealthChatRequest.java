package com.diet.health.model;

import java.util.List;
import java.util.Map;

/** 健康聊天请求。requestId 必填，用于同一会话内幂等去重。 */
public record HealthChatRequest(String sessionId, String requestId, String message, Map<String, Object> context) {
}
