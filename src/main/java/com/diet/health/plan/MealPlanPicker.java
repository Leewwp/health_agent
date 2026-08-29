package com.diet.health.plan;

import com.diet.health.module.PlanMealCandidate;
import com.diet.health.resource.HealthResourceProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;

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

    private final HealthResourceProvider resourceProvider;

    public MealPlanPicker(HealthResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
    }

    /** 挑选结果：资源标识 + 展示名 + 每份热量。 */
    public record MealPick(String resourceId, String name, int caloriesKcal, String mealTime) {
    }

    /**
     * 带偏好过滤的挑选结果：fallback 表示该日发生了“绕过偏好的整天回退”，
     * unmetPreferences 为该日未满足的受支持偏好“字段:值”键；无偏好过滤时恒为 (picks, false, [])。
     */
    public record PreferencePickResult(List<MealPick> picks, boolean fallback, List<String> unmetPreferences) {
        public PreferencePickResult {
            picks = picks == null ? List.of() : List.copyOf(picks);
            unmetPreferences = unmetPreferences == null ? List.of() : List.copyOf(unmetPreferences);
        }
    }

    /** 兼容旧调用：默认生成三餐。 */
    public List<MealPick> pickForDay(int budgetLow, int budgetHigh) {
        return pickForDay(budgetLow, budgetHigh, List.of("早餐", "午餐", "晚餐"), Map.of());
    }

    /** 按用户确认的餐次挑选一天的餐食；usage 用于跨日软多样性。 */
    public List<MealPick> pickForDay(int budgetLow, int budgetHigh, List<String> requestedMealTimes,
                                     Map<String, Integer> usage) {
        List<String> slots = canonicalSlots(requestedMealTimes);
        if (slots.isEmpty()) return List.of();
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

        List<List<PlanMealCandidate>> pools = slots.stream().map(slot -> candidates(pool, slot)).toList();
        if (pools.stream().anyMatch(List::isEmpty)) return fallbackPick(pool, slots, mid, usage);
        List<PlanMealCandidate> best = new ArrayList<>();
        int[] bestDistance = {Integer.MAX_VALUE};
        search(pools, slots, 0, new ArrayList<>(), new HashSet<>(), low, high, mid, usage, best, bestDistance);
        if (best.isEmpty()) return fallbackPick(pool, slots, mid, usage);
        List<MealPick> picks = new ArrayList<>();
        for (int i = 0; i < best.size(); i++) picks.add(toPick(best.get(i), slots.get(i)));
        return picks;
    }

    /**
     * 带偏好过滤的一天挑选（简报补充回路规格 v3.2）：
     * 先按所选餐次分桶再应用偏好过滤；某日任一餐次的偏好池为空、或偏好约束下无法形成
     * 完整的所选餐次组合时，该日整天绕过偏好回退到全量候选池（仍保留餐次、唯一性、热量
     * 和跨日多样性约束，不做单餐半回退），并记录该日未满足的受支持偏好；其余日期正常偏好过滤。
     * 纯热量回退（全量池内的 fallbackPick）不额外记录偏好未满足。
     * 过滤器为空时行为与 {@link #pickForDay} 完全一致（无偏好生成不变）。
     */
    public PreferencePickResult pickForDayWithPreferences(int budgetLow, int budgetHigh,
                                                          List<String> requestedMealTimes,
                                                          Map<String, Integer> usage,
                                                          MealPreferenceFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return new PreferencePickResult(pickForDay(budgetLow, budgetHigh, requestedMealTimes, usage),
                    false, List.of());
        }
        List<String> slots = canonicalSlots(requestedMealTimes);
        if (slots.isEmpty()) return new PreferencePickResult(List.of(), false, List.of());
        List<PlanMealCandidate> pool = resourceProvider.planMealCandidates().stream()
                .filter(candidate -> candidate.caloriesKcal() != null)
                .sorted(Comparator.comparingLong(PlanMealCandidate::sortKey))
                .toList();
        if (pool.isEmpty()) {
            return new PreferencePickResult(List.of(), false, List.of());
        }
        int low = Math.min(budgetLow, budgetHigh);
        int high = Math.max(budgetLow, budgetHigh);
        int mid = (low + high) / 2;

        // 先分桶，再做偏好过滤
        List<List<PlanMealCandidate>> pools = slots.stream()
                .map(slot -> candidates(pool.stream().filter(filter::matches).toList(), slot))
                .toList();
        List<PlanMealCandidate> best = new ArrayList<>();
        if (!pools.stream().anyMatch(List::isEmpty)) {
            int[] bestDistance = {Integer.MAX_VALUE};
            search(pools, slots, 0, new ArrayList<>(), new HashSet<>(), low, high, mid, usage, best, bestDistance);
        }
        if (!best.isEmpty()) {
            List<MealPick> picks = new ArrayList<>();
            for (int i = 0; i < best.size(); i++) picks.add(toPick(best.get(i), slots.get(i)));
            return new PreferencePickResult(picks, false, List.of());
        }
        // 整天回退：绕过偏好、保留餐次/唯一性/热量/多样性约束的全量池
        List<MealPick> fallbackPicks = fallbackPick(pool, slots, mid, usage);
        return new PreferencePickResult(fallbackPicks, true, filter.requiredKeys());
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

    private List<MealPick> fallbackPick(List<PlanMealCandidate> pool, List<String> slots, int mid,
                                        Map<String, Integer> usage) {
        List<PlanMealCandidate> remaining = new ArrayList<>(pool);
        List<MealPick> picks = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            int target = mid * shareFor(slots.get(i), slots) / 100;
            PlanMealCandidate best = nearestTo(candidates(remaining, slots.get(i)), target, usage);
            if (best == null) {
                continue;
            }
            remaining.remove(best);
            picks.add(toPick(best, slots.get(i)));
        }
        return picks;
    }

    private void search(List<List<PlanMealCandidate>> pools, List<String> slots, int index,
                        List<PlanMealCandidate> selected, Set<String> used, int low, int high, int mid,
                        Map<String, Integer> usage, List<PlanMealCandidate> best, int[] bestDistance) {
        if (index == pools.size()) {
            int sum = selected.stream().mapToInt(PlanMealCandidate::caloriesKcal).sum();
            int distance = sum >= low && sum <= high
                    ? Math.abs(sum - mid)
                    : 100_000 + (sum < low ? low - sum : sum - high);
            int repeated = selected.stream().mapToInt(candidate -> usage.getOrDefault(candidate.resourceId(), 0)).sum();
            distance += repeated * 1_000;
            if (distance < bestDistance[0]) { bestDistance[0] = distance; best.clear(); best.addAll(selected); }
            return;
        }
        int target = mid * shareFor(slots.get(index), slots) / 100;
        List<PlanMealCandidate> choices = pools.get(index).stream()
                .filter(candidate -> !used.contains(candidate.resourceId()))
                .sorted(Comparator.comparingInt((PlanMealCandidate c) -> usage.getOrDefault(c.resourceId(), 0))
                        .thenComparingInt(c -> Math.abs(c.caloriesKcal() - target))
                        .thenComparingLong(PlanMealCandidate::sortKey))
                .limit(80)
                .toList();
        for (PlanMealCandidate candidate : choices) {
            selected.add(candidate); used.add(candidate.resourceId());
            search(pools, slots, index + 1, selected, used, low, high, mid, usage, best, bestDistance);
            used.remove(candidate.resourceId()); selected.remove(selected.size() - 1);
            if (bestDistance[0] == 0) return;
        }
    }

    private List<String> canonicalSlots(List<String> requested) {
        List<String> value = requested == null ? List.of() : requested;
        return List.of("早餐", "午餐", "晚餐").stream().filter(value::contains).toList();
    }

    private int shareFor(String slot, List<String> slots) {
        int total = 0;
        for (String selected : slots) total += baseShare(selected);
        return total == 0 ? 0 : baseShare(slot) * 100 / total;
    }

    private int baseShare(String slot) {
        return switch (slot) { case "早餐" -> 30; case "午餐" -> 40; case "晚餐" -> 30; default -> 0; };
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
    private PlanMealCandidate nearestTo(List<PlanMealCandidate> pool, int target, Map<String, Integer> usage) {
        return pool.stream()
                .min(Comparator
                        .comparingInt((PlanMealCandidate candidate) -> usage.getOrDefault(candidate.resourceId(), 0))
                        .thenComparingInt(candidate -> Math.abs(candidate.caloriesKcal() - target))
                        .thenComparingLong(PlanMealCandidate::sortKey))
                .orElse(null);
    }
}
