package com.diet.health.model;

import java.util.List;
import java.util.Map;

/** 健康聊天请求。requestId 必填，用于同一会话内幂等去重。 */
public record HealthChatRequest(String sessionId, String requestId, String message, Map<String, Object> context,
                                AlternativeRequest alternative) {

    /** 兼容普通聊天请求。 */
    public HealthChatRequest(String sessionId, String requestId, String message, Map<String, Object> context) {
        this(sessionId, requestId, message, context, null);
    }

    /** 替代推荐动作：沿用健康聊天幂等通道，排除只作用于当前会话和当前资源类型。 */
    public record AlternativeRequest(String resourceType, String baseTraceId, List<String> addedExclusions,
                                     boolean allowRepeat, boolean relaxConstraints) {
        public AlternativeRequest {
            addedExclusions = addedExclusions == null ? List.of() : List.copyOf(addedExclusions);
        }

        public AlternativeRequest(String resourceType, String baseTraceId, List<String> addedExclusions,
                                  boolean allowRepeat) {
            this(resourceType, baseTraceId, addedExclusions, allowRepeat, false);
        }
    }
}
