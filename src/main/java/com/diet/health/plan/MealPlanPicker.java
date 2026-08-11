package com.diet.health.plan;

import com.diet.health.module.PlanMealCandidate;
import com.diet.health.resource.HealthResourceProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 餐食计划挑选器（34 号）：为组合器按日能量预算确定性挑选三餐。
 * <p>
 * 57 号票起候选统一来自健康资源 Provider（不直接依赖 MealMapper）：从 Provider 的
 * 计划餐食候选中按餐次标签分组，全局搜索使日总热量落在预算区间内的早/午/晚组合
 * （优先接近预算中点）；无达标组合时回退 30%/40%/30% 就近选择，空候选或热量缺失时
 * 返回空，不阻塞结构化组合。确定性排序以候选 sortKey 为准（正式模式为主键序，
 * fixture 模式为种子列表序），REVIEWED_DB 与改造前行为一致。
 * 38 号验收发现：单餐各自就近可能让总和掉出区间导致 WARNING 无法激活。
 */
@Service
public class MealPlanPicker {

    /** 三餐热量占比（早/午/晚），无达标组合时的回退目标。 */
    private static final int[] SLOT_SHARE_PERCENT = {30, 40, 30};
    private static final String[] SLOT_NAMES = {"早餐", "午餐", "晚餐"};

    private final HealthResourceProvider resourceProvider;

    public MealPlanPicker(HealthResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
    }

    /** 挑选结果：资源标识 + 展示名 + 每份热量。 */
    public record MealPick(String resourceId, String name, int caloriesKcal, String mealTime) {
    }

    /** 为一天挑选最多三餐（早餐/午餐/晚餐），空候选或全无热量时返回空列表。 */
    public List<MealPick> pickForDay(int budgetLow, int budgetHigh) {
        List<PlanMealCandidate> pool = resourceProvider.planMealCandidates().stream()
                .filter(candidate -> candidate.caloriesKcal() != null)
                .sorted(Comparator.comparingLong(PlanMealCandidate::sortKey))
                .toList();
        if (pool.isEmpty()) {
            return List.of();
        }
        int low = Math.min(budgetLow, budgetHigh);
        int high = Math.max(budgetLow, budgetHigh);
        int mid = (low + high) / 2;

        List<PlanMealCandidate> breakfastPool = candidates(pool, "早餐");
        List<PlanMealCandidate> lunchPool = candidates(pool, "午餐");
        List<PlanMealCandidate> dinnerPool = candidates(pool, "晚餐");
        if (breakfastPool.isEmpty() || lunchPool.isEmpty() || dinnerPool.isEmpty()) {
            return fallbackPick(pool, mid);
        }

        // 全局搜索：优先找总热量落入预算区间的组合，其次接近预算中点（并列取 sortKey 最小）
        for (PlanMealCandidate breakfast : breakfastPool) {
            for (PlanMealCandidate dinner : dinnerPool) {
                if (dinner.resourceId().equals(breakfast.resourceId())) {
                    continue;
                }
                int needLow = low - breakfast.caloriesKcal() - dinner.caloriesKcal();
                int needHigh = high - breakfast.caloriesKcal() - dinner.caloriesKcal();
                PlanMealCandidate lunch = nearestInRange(lunchPool, needLow, needHigh,
                        mid - breakfast.caloriesKcal() - dinner.caloriesKcal(),
                        Set.of(breakfast.resourceId(), dinner.resourceId()));
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
    private List<PlanMealCandidate> candidates(List<PlanMealCandidate> pool, String slotName) {
        return pool.stream()
                .filter(candidate -> mealTimeContains(candidate, slotName))
                .toList();
    }

    private boolean mealTimeContains(PlanMealCandidate candidate, String slotName) {
        return candidate.mealTimeTags().isEmpty() || candidate.mealTimeTags().contains(slotName);
    }

    /** 在 [needLow, needHigh] 热量区间内挑热量最接近理想值的一餐（未选过、sortKey 最小优先）。 */
    private PlanMealCandidate nearestInRange(List<PlanMealCandidate> pool, int needLow, int needHigh,
                                             int ideal, Set<String> usedIds) {
        return pool.stream()
                .filter(candidate -> !usedIds.contains(candidate.resourceId()))
                .filter(candidate -> candidate.caloriesKcal() >= needLow
                        && candidate.caloriesKcal() <= needHigh)
                .min(Comparator
                        .comparingInt((PlanMealCandidate candidate) -> Math.abs(candidate.caloriesKcal() - ideal))
                        .thenComparingLong(PlanMealCandidate::sortKey))
                .orElse(null);
    }

    /** 回退：按预算中点 30%/40%/30% 就近选三餐（不重复）。 */
    private List<MealPick> fallbackPick(List<PlanMealCandidate> pool, int mid) {
        List<PlanMealCandidate> remaining = new ArrayList<>(pool);
        List<MealPick> picks = new ArrayList<>();
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            int target = mid * SLOT_SHARE_PERCENT[i] / 100;
            PlanMealCandidate best = nearestTo(remaining, target);
            if (best == null) {
                continue;
            }
            remaining.remove(best);
            picks.add(toPick(best, SLOT_NAMES[i]));
        }
        return picks;
    }

    private MealPick toPick(PlanMealCandidate candidate, String slotName) {
        return new MealPick(
                candidate.resourceId(),
                candidate.name(),
                candidate.caloriesKcal(),
                slotName
        );
    }

    /** 热量最接近目标的一餐（并列取 sortKey 较小者，保持确定性）。 */
    private PlanMealCandidate nearestTo(List<PlanMealCandidate> pool, int target) {
        return pool.stream()
                .min(Comparator
                        .comparingInt((PlanMealCandidate candidate) -> Math.abs(candidate.caloriesKcal() - target))
                        .thenComparingLong(PlanMealCandidate::sortKey))
                .orElse(null);
    }
}
