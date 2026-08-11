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
        return meal(id, name, kcal, null);
    }

    private MealItemRow meal(long id, String name, int kcal, String mealTime) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setName(name);
        row.setCaloriesKcal(BigDecimal.valueOf(kcal));
        if (mealTime != null) {
            row.setMealTime("[\"" + mealTime + "\"]");
        }
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
    void 午餐优先保证日总热量落入预算区间() {
        // 早餐/晚餐就近各取 30% 档后总和 1525；若午餐仍按 40% 就近选 995 则总和 2520 超上限
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(
                meal(1, "清淡早餐", 725, "早餐"),
                meal(2, "高热量午餐", 995, "午餐"),
                meal(3, "中等午餐", 900, "午餐"),
                meal(4, "低热量晚餐", 644, "晚餐"),
                meal(5, "标准晚餐", 800, "晚餐")
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(2400, 2500);
        assertEquals(3, picks.size());
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 2400 && sum <= 2500, "日总热量应落在预算区间，实际 " + sum);
        assertEquals("午餐", picks.get(1).mealTime());
    }

    @Test
    void 无达标候选时回退就近选择不返回空() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(
                meal(1, "早餐", 200, "早餐"),
                meal(2, "午餐", 300, "午餐"),
                meal(3, "晚餐", 400, "晚餐")
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800);
        assertEquals(3, picks.size(), "无达标候选也不得返回空");
    }

    @Test
    void 跨槽位调整组合使日总热量落入预算区间() {
        // 早餐档只有 725（早餐），午餐/晚餐均可选 995；725+995+995=2715 超上限，
        // 需把早餐换成 492 的高蛋白加餐（同样带早餐标签）组合成 995+995+492=2482
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(
                meal(1, "花生酱油炒饭", 725, "早餐"),
                meal(2, "高蛋白奶酪杯", 492, "早餐"),
                meal(3, "鹰嘴豆丸意面", 995, "午餐"),
                meal(4, "鸡肉丸意面", 995, "晚餐"),
                meal(5, "切达鸡肉砂锅", 644, "晚餐")
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(2400, 2500);
        assertEquals(3, picks.size());
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 2400 && sum <= 2500, "跨槽位调整后日总热量应落在预算区间，实际 " + sum);
        assertEquals(List.of("早餐", "午餐", "晚餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
        long distinctIds = picks.stream().map(MealPlanPicker.MealPick::resourceId).distinct().count();
        assertEquals(3, distinctIds, "三餐不得重复");
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
