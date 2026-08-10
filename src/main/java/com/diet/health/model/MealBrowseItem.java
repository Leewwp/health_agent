package com.diet.health.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 餐食浏览条目（规格 6.2，GET /api/v1/health/meals）。 */
public record MealBrowseItem(
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
        String sourceVersion
) {

    /** 份量口径。 */
    public record Serving(int count, BigDecimal size, String unit) {
    }

    /** 营养信息（Food.com 原始值，均为估算）。 */
    public record Nutrition(BigDecimal caloriesKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbohydrateG, String basis, boolean estimated) {
    }
}
