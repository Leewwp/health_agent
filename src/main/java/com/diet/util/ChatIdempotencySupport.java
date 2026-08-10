package com.diet.util;

import com.diet.exception.DietException;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 聊天编排共用的幂等快照助手：重复 requestId 时从 diet_request_trace 恢复已保存响应。
 * 旧饮食与健康两个编排器共用，避免重复实现。
 */
public final class ChatIdempotencySupport {

    private ChatIdempotencySupport() {
    }

    /** 是否存在可复用的响应快照。 */
    public static boolean hasSnapshot(RequestTraceRow row) {
        return row != null && row.getResponseJson() != null && !row.getResponseJson().isBlank();
    }

    /** 从响应快照 JSON 恢复类型化响应。 */
    public static <T> T restore(ObjectMapper objectMapper, String responseJson, Class<T> type) {
        try {
            return objectMapper.readValue(responseJson, type);
        } catch (Exception error) {
            throw new DietException("幂等响应恢复失败", error);
        }
    }

    /** 纳秒时间戳转毫秒耗时。 */
    public static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
