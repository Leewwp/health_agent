package com.diet.health.reader.meal;

import java.math.BigDecimal;
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

    /** 份量口径。 */
    public record Serving(int count, BigDecimal size, String unit) {
    }

    /** 营养信息（Food.com 原始值，均为估算）。 */
    public record Nutrition(BigDecimal caloriesKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbohydrateG, String basis, boolean estimated) {
    }
}
