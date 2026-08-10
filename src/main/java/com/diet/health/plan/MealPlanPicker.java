package com.diet.health.plan;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 餐食计划挑选器（34 号）：为组合器按日能量预算确定性挑选三餐。
 * <p>
 * 从审核通过的公共餐食（有热量口径）中，按预算中点 30%/40%/30% 分早/午/晚三档目标，
 * 每档选取热量最接近目标且未重复的一餐；无候选或热量缺失时返回空，不阻塞结构化组合。
 */
@Service
public class MealPlanPicker {

    /** 三餐热量占比（早/午/晚）。 */
    private static final int[] SLOT_SHARE_PERCENT = {30, 40, 30};
    private static final String[] SLOT_NAMES = {"早餐", "午餐", "晚餐"};

    private final MealMapper mealMapper;

    public MealPlanPicker(MealMapper mealMapper) {
        this.mealMapper = mealMapper;
    }

    /** 挑选结果：资源标识 + 展示名 + 每份热量。 */
    public record MealPick(String resourceId, String name, int caloriesKcal, String mealTime) {
    }

    /** 为一天挑选最多三餐（早餐/午餐/晚餐），空库或全无热量时返回空列表。 */
    public List<MealPick> pickForDay(int budgetLow, int budgetHigh) {
        List<MealItemRow> pool = mealMapper.findApprovedPublicMeals().stream()
                .filter(row -> row.getCaloriesKcal() != null)
                .sorted(Comparator.comparing(MealItemRow::getId))
                .toList();
        if (pool.isEmpty()) {
            return List.of();
        }
        int mid = (budgetLow + budgetHigh) / 2;
        List<MealPick> picks = new ArrayList<>();
        List<MealItemRow> remaining = new ArrayList<>(pool);
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            int target = mid * SLOT_SHARE_PERCENT[i] / 100;
            MealItemRow best = nearestTo(remaining, target);
            if (best == null) {
                continue;
            }
            remaining.remove(best);
            picks.add(new MealPick(
                    String.valueOf(best.getId()),
                    best.getName(),
                    best.getCaloriesKcal().intValue(),
                    SLOT_NAMES[i]
            ));
        }
        return picks;
    }

    /** 热量最接近目标的一餐（并列取 id 较小者，保持确定性）。 */
    private MealItemRow nearestTo(List<MealItemRow> pool, int target) {
        return pool.stream()
                .min(Comparator
                        .comparingInt((MealItemRow row) -> Math.abs(row.getCaloriesKcal().intValue() - target))
                        .thenComparing(MealItemRow::getId))
                .orElse(null);
    }
}
