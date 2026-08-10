package com.diet.health.plan;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 餐食计划挑选器（34 号）：按预算中点 30/40/30 分档就近选餐，确定性且不重复；
 * 空库或全无热量时返回空，不阻塞结构化组合。
 */
class MealPlanPickerTest {

    private final MealMapper mealMapper = mock(MealMapper.class);
    private final MealPlanPicker picker = new MealPlanPicker(mealMapper);

    private MealItemRow meal(long id, String name, int kcal) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setName(name);
        row.setCaloriesKcal(BigDecimal.valueOf(kcal));
        return row;
    }

    @Test
    void 按预算中点就近挑选三餐不重复() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(
                meal(1, "清蒸鲈鱼", 300),
                meal(2, "鸡胸肉沙拉", 500),
                meal(3, "米饭套餐", 700),
                meal(4, "牛肉面", 900)
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800);
        assertEquals(3, picks.size());
        assertEquals(List.of("早餐", "午餐", "晚餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
        long distinctIds = picks.stream().map(MealPlanPicker.MealPick::resourceId).distinct().count();
        assertEquals(3, distinctIds, "三餐不得重复");
        assertTrue(picks.get(1).caloriesKcal() >= picks.get(0).caloriesKcal(), "午餐档热量不低于早餐档");
    }

    @Test
    void 空库返回空列表() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of());
        assertTrue(picker.pickForDay(1200, 1800).isEmpty());
    }

    @Test
    void 全部无热量时返回空列表() {
        MealItemRow row = new MealItemRow();
        row.setId(1L);
        row.setName("无热量口径");
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(row));
        assertTrue(picker.pickForDay(1200, 1800).isEmpty());
    }
}
