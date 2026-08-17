package com.diet.health.reader.meal;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 餐食领域映射单一实现契约（#68）：ReviewedMeal → MealItem 七维槽位与来源口径固定。 */
class MealDomainMapperTest {

    @Test
    void 领域视图与浏览索引视图同口径() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", List.of("午餐"));
        tags.put("mood", List.of());
        tags.put("scene", List.of());
        tags.put("healthGoal", List.of("增肌"));
        tags.put("cuisine", List.of());
        tags.put("taste", List.of("鲜"));
        tags.put("convenience", List.of());
        ReviewedMeal meal = new ReviewedMeal(
                5L, "鸡胸肉糙米饭", null, List.of(), tags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                List.of(), "REVIEWED", "APPROVED", null, "NONE", null, "src", "s5", "v2", "PUBLIC");

        MealItem item = MealDomainMapper.toMealItem(meal);

        assertEquals(5L, item.id());
        assertEquals(SourceMode.PUBLIC, item.sourceType());
        assertNull(item.ownerUserId());
        assertEquals("鸡胸肉糙米饭", item.name());
        assertEquals(List.of("午餐"), item.slots().mealTime());
        assertEquals(List.of("增肌"), item.slots().healthGoal());
        assertEquals(List.of("鲜"), item.slots().taste());
        assertEquals(0, item.matchScore(), "matchScore 由重排器覆盖，映射保持 0");
    }
}
