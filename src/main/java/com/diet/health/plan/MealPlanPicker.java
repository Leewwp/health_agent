package com.diet.health.plan;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 餐食计划挑选器（34 号）：为组合器按日能量预算确定性挑选三餐。
 * <p>
 * 从审核通过的公共餐食（有热量口径）中按餐次标签分组，全局搜索使日总热量
 * 落在预算区间内的早/午/晚组合（优先接近预算中点）；无达标组合时回退
 * 30%/40%/30% 就近选择，空库或热量缺失时返回空，不阻塞结构化组合。
 * 38 号验收发现：单餐各自就近可能让总和掉出区间导致 WARNING 无法激活。
 */
@Service
public class MealPlanPicker {

    /** 三餐热量占比（早/午/晚），无达标组合时的回退目标。 */
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
        int low = Math.min(budgetLow, budgetHigh);
        int high = Math.max(budgetLow, budgetHigh);
        int mid = (low + high) / 2;

        List<MealItemRow> breakfastPool = candidates(pool, "早餐");
        List<MealItemRow> lunchPool = candidates(pool, "午餐");
        List<MealItemRow> dinnerPool = candidates(pool, "晚餐");
        if (breakfastPool.isEmpty() || lunchPool.isEmpty() || dinnerPool.isEmpty()) {
            return fallbackPick(pool, mid);
        }

        // 全局搜索：优先找总热量落入预算区间的组合，其次接近预算中点（并列取 id 最小）
        for (MealItemRow breakfast : breakfastPool) {
            for (MealItemRow dinner : dinnerPool) {
                if (dinner.getId().equals(breakfast.getId())) {
                    continue;
                }
                int needLow = low - breakfast.getCaloriesKcal().intValue() - dinner.getCaloriesKcal().intValue();
                int needHigh = high - breakfast.getCaloriesKcal().intValue() - dinner.getCaloriesKcal().intValue();
                MealItemRow lunch = nearestInRange(lunchPool, needLow, needHigh,
                        mid - breakfast.getCaloriesKcal().intValue() - dinner.getCaloriesKcal().intValue(),
                        Set.of(breakfast.getId(), dinner.getId()));
                if (lunch != null) {
                    List<MealPick> picks = new ArrayList<>();
                    picks.add(toPick(breakfast, SLOT_NAMES[0]));
                    picks.add(toPick(lunch, SLOT_NAMES[1]));
                    picks.add(toPick(dinner, SLOT_NAMES[2]));
                    return picks;
                }
            }
        }
        // 无达标组合时不阻塞结构化组合，回退就近选择
        return fallbackPick(pool, mid);
    }

    /** 餐次候选：标签含指定餐次，或未打餐次标签（视为全时段可用）。 */
    private List<MealItemRow> candidates(List<MealItemRow> pool, String slotName) {
        return pool.stream()
                .filter(row -> mealTimeContains(row, slotName))
                .toList();
    }

    private boolean mealTimeContains(MealItemRow row, String slotName) {
        String raw = row.getMealTime();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return raw.contains("\"" + slotName + "\"");
    }

    /** 在 [needLow, needHigh] 热量区间内挑热量最接近理想值的一餐（未选过、id 最小优先）。 */
    private MealItemRow nearestInRange(List<MealItemRow> pool, int needLow, int needHigh,
                                       int ideal, Set<Long> usedIds) {
        return pool.stream()
                .filter(row -> !usedIds.contains(row.getId()))
                .filter(row -> row.getCaloriesKcal().intValue() >= needLow
                        && row.getCaloriesKcal().intValue() <= needHigh)
                .min(Comparator
                        .comparingInt((MealItemRow row) -> Math.abs(row.getCaloriesKcal().intValue() - ideal))
                        .thenComparing(MealItemRow::getId))
                .orElse(null);
    }

    /** 回退：按预算中点 30%/40%/30% 就近选三餐（不重复）。 */
    private List<MealPick> fallbackPick(List<MealItemRow> pool, int mid) {
        List<MealItemRow> remaining = new ArrayList<>(pool);
        List<MealPick> picks = new ArrayList<>();
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            int target = mid * SLOT_SHARE_PERCENT[i] / 100;
            MealItemRow best = nearestTo(remaining, target);
            if (best == null) {
                continue;
            }
            remaining.remove(best);
            picks.add(toPick(best, SLOT_NAMES[i]));
        }
        return picks;
    }

    private MealPick toPick(MealItemRow row, String slotName) {
        return new MealPick(
                String.valueOf(row.getId()),
                row.getName(),
                row.getCaloriesKcal().intValue(),
                slotName
        );
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
