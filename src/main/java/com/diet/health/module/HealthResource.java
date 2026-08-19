package com.diet.health.module;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 类型化健康资源引用（餐食 / 动作 / 作息共用，作息 resourceType 统一为 ROUTINE）。
 * resourceId 为稳定字符串：正式模式餐食与动作为数据库主键、作息为冻结业务 ref_id；
 * fixture 模式为种子 ID（餐食 M1-M9、动作 9001-9008、作息 R1-R5），两种模式由 Provider 标识区分，不得混用。
 */
public record HealthResource(
        String resourceType,
        String resourceId,
        String name,
        String sourceType,
        String sourceName,
        String mediaUrl,
        boolean planReady,
        Map<String, List<String>> tags,
        List<String> ingredients,
        Nutrition nutrition
) {
    public HealthResource {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }

    public HealthResource(String resourceType, String resourceId, String name, String sourceType,
                          String sourceName, String mediaUrl, boolean planReady,
                          Map<String, List<String>> tags) {
        this(resourceType, resourceId, name, sourceType, sourceName, mediaUrl, planReady,
                tags, List.of(), null);
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

    public record Nutrition(BigDecimal caloriesKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbohydrateG, String basis, boolean estimated) {
    }
}
