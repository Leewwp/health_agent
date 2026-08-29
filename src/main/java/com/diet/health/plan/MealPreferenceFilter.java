package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 餐食计划偏好过滤组（简报补充回路规格 v3.2）。
 * <p>
 * 过滤合同：同一候选字段内多值 OR，不同候选字段之间 AND；tastePreferences 逐值映射——
 * 口味规范值匹配候选 tasteTags，营养偏好规范值匹配候选 nutritionPreferenceTags；
 * 边界例 {清淡, 高蛋白} 要求同一候选同时命中两个候选字段。未支持偏好（unsupportedPreferences）
 * 不进入过滤，也不进入未满足记录。
 */
public final class MealPreferenceFilter {

    private final List<String> cuisine;
    private final List<String> foodType;
    private final List<String> taste;
    private final List<String> nutrition;
    private final List<String> convenience;
    /** 全部受支持活跃偏好的“字段:值”键（回退日按日记录未满足时使用）。 */
    private final List<String> requiredKeys;

    private MealPreferenceFilter(List<String> cuisine, List<String> foodType, List<String> taste, List<String> nutrition,
                                 List<String> convenience, List<String> requiredKeys) {
        this.cuisine = List.copyOf(cuisine);
        this.foodType = List.copyOf(foodType);
        this.taste = List.copyOf(taste);
        this.nutrition = List.copyOf(nutrition);
        this.convenience = List.copyOf(convenience);
        this.requiredKeys = List.copyOf(requiredKeys);
    }

    /** 从餐食简报构建过滤器；无任何受支持偏好时返回空过滤器（挑选行为与现状一致）。 */
    public static MealPreferenceFilter from(MealPlanBrief brief, HealthInputNormalizer normalizer) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        List<String> cuisine = new ArrayList<>();
        for (String cuisineValue : value.cuisines()) {
            String canonical = normalizer.canonicalValueOf("cuisine", cuisineValue);
            if (canonical != null) cuisine.add(canonical);
        }
        List<String> foodType = new ArrayList<>();
        for (String foodTypeValue : value.foodTypes()) {
            String canonical = normalizer.canonicalValueOf("foodType", foodTypeValue);
            if (canonical != null) foodType.add(canonical);
        }
        List<String> convenience = new ArrayList<>();
        if (value.convenience() != null && !value.convenience().isBlank()) {
            String canonical = normalizer.canonicalValueOf("convenience", value.convenience());
            if (canonical != null) {
                convenience.add(canonical);
            }
        }
        List<String> taste = new ArrayList<>();
        List<String> nutrition = new ArrayList<>();
        List<String> tasteCanonical = normalizer.canonicalValues("taste");
        List<String> nutritionCanonical = normalizer.canonicalValues("healthGoal").stream()
                .filter(v -> !List.of("减脂", "增肌", "维持健康", "均衡").contains(v))
                .toList();
        for (String preference : value.tastePreferences()) {
            // 逐值映射（规格 v3.2）：“清淡”属于口味值匹配 tasteTags（边界例 {清淡, 高蛋白} 跨字段 AND）；
            // 其余非热量目标的健康目标规范值（低油/低盐/高蛋白等）属于营养偏好匹配 nutritionPreferenceTags
            if (tasteCanonical.contains(preference) || "清淡".equals(preference)) {
                taste.add(preference);
            } else if (nutritionCanonical.contains(preference)) {
                nutrition.add(preference);
            }
            // 既非口味也非营养偏好的值不进入过滤（保守跳过，不记录未满足）
        }
        List<String> keys = new ArrayList<>();
        cuisine.forEach(v -> keys.add("cuisine:" + v));
        foodType.forEach(v -> keys.add("foodType:" + v));
        taste.forEach(v -> keys.add("taste:" + v));
        nutrition.forEach(v -> keys.add("nutrition:" + v));
        convenience.forEach(v -> keys.add("convenience:" + v));
        return new MealPreferenceFilter(cuisine, foodType, taste, nutrition, convenience, keys);
    }

    /** 是否没有任何受支持偏好（空过滤器 → 不参与挑选）。 */
    public boolean isEmpty() {
        return requiredKeys.isEmpty();
    }

    /** 回退日未满足记录：该日全部受支持活跃偏好的“字段:值”键。 */
    public List<String> requiredKeys() {
        return requiredKeys;
    }

    /** 候选是否满足全部偏好组：组内任一命中（OR），组间全部命中（AND）。 */
    public boolean matches(com.diet.health.module.PlanMealCandidate candidate) {
        return matchesGroup(cuisine, candidate.cuisineTags())
                && matchesGroup(foodType, candidate.foodTypeTags())
                && matchesGroup(taste, candidate.tasteTags())
                && matchesGroup(nutrition, candidate.nutritionPreferenceTags())
                && matchesGroup(convenience, candidate.convenienceTags());
    }

    private boolean matchesGroup(List<String> required, List<String> tags) {
        if (required.isEmpty()) {
            return true;
        }
        for (String value : required) {
            if (tags.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
