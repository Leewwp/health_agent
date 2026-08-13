package com.diet.health.reader.meal;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核公共餐食不可变读取模型（#68）：浏览、领域召回与索引/评估快照共用同一行映射口径。
 * <p>
 * tags 为 7 维槽位标签（mealTime/mood/scene/healthGoal/cuisine/taste/convenience）；
 * allergens 为过敏原标签；来源/审核/媒体字段原样携带，由调用场景决定透出与否。
 */
public record ReviewedMeal(
        Long id,
        String name,
        String nameEn,
        List<String> aliases,
        Map<String, List<String>> tags,
        String description,
        List<String> ingredients,
        Serving serving,
        Nutrition nutrition,
        List<String> allergens,
        String allergenStatus,
        String reviewStatus,
        String mediaStatus,
        String mediaCredit,
        String sourceName,
        String sourceId,
        String sourceVersion,
        String sourceType
) {

    /** 在读取模块边界完成深拷贝，避免调用方修改共享快照。 */
    public ReviewedMeal {
        aliases = immutableList(aliases);
        tags = immutableTags(tags);
        ingredients = immutableList(ingredients);
        allergens = immutableList(allergens);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, List<String>> immutableTags(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, immutableList(value)));
        return Collections.unmodifiableMap(copy);
    }

    /** 份量口径。 */
    public record Serving(int count, BigDecimal size, String unit) {
    }

    /** 营养信息（Food.com 原始值，均为估算）。 */
    public record Nutrition(BigDecimal caloriesKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbohydrateG, String basis, boolean estimated) {
    }
}
