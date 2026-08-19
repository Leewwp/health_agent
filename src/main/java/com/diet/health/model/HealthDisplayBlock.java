package com.diet.health.model;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.module.HealthResource;

import java.util.List;
import java.util.Map;

/** 类型化推荐展示块（餐食 / 动作 / 作息事实共用）。 */
public record HealthDisplayBlock(
        String resourceType,
        String resourceId,
        String name,
        String sourceType,
        String sourceName,
        String mediaUrl,
        boolean planReady,
        String reason,
        Map<String, List<String>> tags,
        List<String> ingredients,
        HealthResource.Nutrition nutrition
) {
    public HealthDisplayBlock(String resourceType, String resourceId, String name, String sourceType,
                              String sourceName, String mediaUrl, boolean planReady, String reason) {
        this(resourceType, resourceId, name, sourceType, sourceName, mediaUrl, planReady, reason,
                Map.of(), List.of(), null);
    }
}
