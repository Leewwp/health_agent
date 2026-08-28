package com.diet.health.plan;

import com.diet.health.module.PlanMealCandidate;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 餐食计划挑选器（34 号，57 号票候选来源改为 Provider）：
 * 候选来自健康资源 Provider（不再直接依赖 MealMapper），按预算中点 30/40/30 分档就近选餐，
 * 确定性且不重复；空候选或全无热量时返回空，不阻塞结构化组合；
 * REVIEWED_DB 与 FIXTURE_SEED 两种模式候选均可驱动挑选。
 */
class MealPlanPickerTest {

    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private final MealPlanPicker picker = new MealPlanPicker(provider);

    private PlanMealCandidate meal(long sortKey, String id, String name, int kcal) {
        return meal(sortKey, id, name, kcal, List.of());
    }

    private PlanMealCandidate meal(long sortKey, String id, String name, int kcal, List<String> mealTime) {
        return new PlanMealCandidate("MEAL", id, name, mealTime, kcal, sortKey);
    }

    @Test
    void 按预算中点就近挑选三餐不重复() {
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "1", "清蒸鲈鱼", 300),
                meal(2, "2", "鸡胸肉沙拉", 500),
                meal(3, "3", "米饭套餐", 700),
                meal(4, "4", "牛肉面", 900)
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800);
        assertEquals(3, picks.size());
        assertEquals(List.of("早餐", "午餐", "晚餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
        long distinctIds = picks.stream().map(MealPlanPicker.MealPick::resourceId).distinct().count();
        assertEquals(3, distinctIds, "三餐不得重复");
        assertTrue(picks.get(1).caloriesKcal() >= picks.get(0).caloriesKcal(), "午餐档热量不低于早餐档");
    }

    @Test
    void 空候选返回空列表() {
        when(provider.planMealCandidates()).thenReturn(List.of());
        assertTrue(picker.pickForDay(1200, 1800).isEmpty());
    }

    @Test
    void 午餐优先保证日总热量落入预算区间() {
        // 早餐/晚餐就近各取 30% 档后总和 1525；若午餐仍按 40% 就近选 995 则总和 2520 超上限
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "1", "清淡早餐", 725, List.of("早餐")),
                meal(2, "2", "高热量午餐", 995, List.of("午餐")),
                meal(3, "3", "中等午餐", 900, List.of("午餐")),
                meal(4, "4", "低热量晚餐", 644, List.of("晚餐")),
                meal(5, "5", "标准晚餐", 800, List.of("晚餐"))
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(2400, 2500);
        assertEquals(3, picks.size());
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 2400 && sum <= 2500, "日总热量应落在预算区间，实际 " + sum);
        assertEquals("午餐", picks.get(1).mealTime());
    }

    @Test
    void 无达标候选时回退就近选择不返回空() {
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "1", "早餐", 200, List.of("早餐")),
                meal(2, "2", "午餐", 300, List.of("午餐")),
                meal(3, "3", "晚餐", 400, List.of("晚餐"))
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800);
        assertEquals(3, picks.size(), "无达标候选也不得返回空");
    }

    @Test
    void 跨槽位调整组合使日总热量落入预算区间() {
        // 早餐档只有 725（早餐），午餐/晚餐均可选 995；725+995+995=2715 超上限，
        // 需把早餐换成 492 的高蛋白加餐（同样带早餐标签）组合成 995+995+492=2482
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "1", "花生酱油炒饭", 725, List.of("早餐")),
                meal(2, "2", "高蛋白奶酪杯", 492, List.of("早餐")),
                meal(3, "3", "鹰嘴豆丸意面", 995, List.of("午餐")),
                meal(4, "4", "鸡肉丸意面", 995, List.of("晚餐")),
                meal(5, "5", "切达鸡肉砂锅", 644, List.of("晚餐"))
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
        PlanMealCandidate noCalories = new PlanMealCandidate("MEAL", "1", "无热量口径", List.of(), null, 1);
        when(provider.planMealCandidates()).thenReturn(List.of(noCalories));
        assertTrue(picker.pickForDay(1200, 1800).isEmpty());
    }

    @Test
    void 未打餐次标签视为全时段可用() {
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "1", "全时段粥", 350),
                meal(2, "2", "全时段饭", 600),
                meal(3, "3", "全时段面", 750)
        ));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800);
        assertEquals(3, picks.size(), "未打餐次标签的候选应可被任一时段选中");
        assertEquals(List.of("早餐", "午餐", "晚餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 1200 && sum <= 1800, "全时段候选组合应落入预算区间，实际 " + sum);
    }

    @Test
    void 种子Provider候选驱动挑选确定且覆盖三餐() {
        MealPlanPicker seedPicker = new MealPlanPicker(new SeedResourceProvider());
        List<MealPlanPicker.MealPick> picks = seedPicker.pickForDay(1200, 1800);
        assertEquals(3, picks.size());
        assertEquals(List.of("早餐", "午餐", "晚餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
        assertTrue(picks.stream().allMatch(pick -> pick.resourceId().matches("M[1-9]")),
                "fixture 模式餐食必须来自种子 M1-M9，实际 " + picks);
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 1200 && sum <= 1800, "种子候选日总热量应落在预算区间，实际 " + sum);
        long distinctIds = picks.stream().map(MealPlanPicker.MealPick::resourceId).distinct().count();
        assertEquals(3, distinctIds, "三餐不得重复");
    }

    @Test
    void 种子Provider高预算日总热量同样落入区间() {
        MealPlanPicker seedPicker = new MealPlanPicker(new SeedResourceProvider());
        List<MealPlanPicker.MealPick> picks = seedPicker.pickForDay(2150, 2400);
        assertEquals(3, picks.size());
        int sum = picks.stream().mapToInt(MealPlanPicker.MealPick::caloriesKcal).sum();
        assertTrue(sum >= 2150 && sum <= 2400, "高预算日总热量应落在预算区间，实际 " + sum);
    }
    @Test
    void 只生成确认的餐次() {
        when(provider.planMealCandidates()).thenReturn(List.of(
                meal(1, "b", "早餐", 600, List.of("早餐")),
                meal(2, "l", "午餐", 800, List.of("午餐")),
                meal(3, "d", "晚餐", 700, List.of("晚餐"))));
        List<MealPlanPicker.MealPick> picks = picker.pickForDay(1200, 1800,
                List.of("早餐", "午餐"), new java.util.HashMap<>());
        assertEquals(List.of("早餐", "午餐"), picks.stream().map(MealPlanPicker.MealPick::mealTime).toList());
    }
}
