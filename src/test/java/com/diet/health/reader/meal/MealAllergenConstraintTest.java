package com.diet.health.reader.meal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 餐食过敏原硬约束单一实现契约（#68）：空集合、无交集、有交集与重复标签结果固定。 */
class MealAllergenConstraintTest {

    private ReviewedMeal meal(List<String> allergens) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", List.of());
        return new ReviewedMeal(1L, "测试餐", null, List.of(), tags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                allergens, "REVIEWED", "APPROVED", null, "NONE", null, "src", "s1", "v2", "PUBLIC");
    }

    @Test
    void 查询过敏原为空时不命中() {
        assertFalse(MealAllergenConstraint.intersects(meal(List.of("花生")), List.of()));
        assertFalse(MealAllergenConstraint.intersects(meal(List.of("花生")), null));
    }

    @Test
    void 餐食过敏原为空时不命中() {
        assertFalse(MealAllergenConstraint.intersects(meal(List.of()), List.of("花生")));
    }

    @Test
    void 有交集时命中() {
        assertTrue(MealAllergenConstraint.intersects(meal(List.of("牛奶", "鸡蛋")), List.of("鸡蛋")));
    }

    @Test
    void 重复标签不影响交集判断() {
        assertTrue(MealAllergenConstraint.intersects(meal(List.of("花生", "花生")), List.of("花生", "花生")));
        assertFalse(MealAllergenConstraint.intersects(meal(List.of("花生")), List.of("鱼", "虾")));
    }
}
