package com.diet.health.reader.meal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 餐食过敏原硬约束的单一实现（#68）。
 * <p>
 * 结构化与 Hybrid 检索、以及向量 payload 过滤共用同一语义：查询过敏原集合
 * 与餐食过敏原标签存在交集即命中（先于任何打分排除）。
 */
public final class MealAllergenConstraint {

    private MealAllergenConstraint() {
    }

    /** 餐食过敏原与查询过敏原是否有交集；查询为空返回 false。 */
    public static boolean intersects(ReviewedMeal meal, List<String> allergenTags) {
        if (allergenTags == null || allergenTags.isEmpty()) {
            return false;
        }
        Set<String> rowAllergens = new HashSet<>(meal.allergens());
        rowAllergens.retainAll(allergenTags);
        return !rowAllergens.isEmpty();
    }
}
