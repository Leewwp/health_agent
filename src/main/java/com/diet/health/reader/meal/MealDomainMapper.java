package com.diet.health.reader.meal;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;

import java.util.List;

/**
 * 审核餐食 → 旧链路领域模型 MealItem 的共享映射（#68）。
 * <p>
 * 结构化与 Hybrid 检索器共用同一实现，消除两个检索器各自维护的重复映射；
 * 7 维槽位来自 {@link ReviewedMeal#tags()}，不重新解析 JSON。
 */
public final class MealDomainMapper {

    private MealDomainMapper() {
    }

    /** 审核餐食视图 → MealItem（sourceType 固定 PUBLIC，无归属人，matchScore 0 由重排器覆盖）。 */
    public static MealItem toMealItem(ReviewedMeal meal) {
        return new MealItem(
                meal.id(),
                SourceMode.PUBLIC,
                null,
                meal.name(),
                SlotBundle.fromHealthSlots(meal.tags()),
                0
        );
    }
}
