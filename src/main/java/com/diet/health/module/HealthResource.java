package com.diet.health.module;

import java.util.List;
import java.util.Map;

/**
 * 类型化健康资源引用（餐食 / 动作 / 作息事实共用）。
 * resourceId 为稳定字符串：餐食为数据库 ID，动作为种子 ID，事实为 R 前缀事实 ID。
 */
public record HealthResource(
        String resourceType,
        String resourceId,
        String name,
        String sourceType,
        String sourceName,
        String mediaUrl,
        boolean planReady,
        Map<String, List<String>> tags
) {
    public HealthResource {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** 供响应 Agent 与 Trace 使用的紧凑描述。 */
    public Map<String, Object> compact() {
        return Map.of(
                "resourceType", resourceType,
                "resourceId", resourceId,
                "name", name,
                "planReady", planReady
        );
    }
}
