package com.diet.health.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 独立餐食计划简报，不复用训练字段。
 * 简报只表达"当前整理出的条件"，没有独立的用户确认状态或确认版本；
 * 开始生成时由服务端重新读取并校验完整性（ADR-0016）。
 * <p>
 * 稳定 JSON 形状（简报补充回路规格 v3.2）：
 * {weekStart, mealTimes, healthGoal, cuisines, foodTypes, tastePreferences, convenience, unsupportedPreferences}；
 * 列表字段始终为数组，未填单值为 null；healthGoal 只承载健康目标（减脂/增肌/维持健康/均衡），
 * 菜系、餐食类型及口味/营养偏好均支持多选；convenience 为单选；
 * unsupportedPreferences 使用稳定键 "field:value" 并去重，是库中无标签偏好的诚实记录。
 * 本对象是计划简报的唯一来源，通用 slots 中的旧 cuisine/taste/convenience 只在旧会话
 * 且计划字段为空时一次性导入。
 */
public record MealPlanBrief(
        LocalDate weekStart,
        List<String> mealTimes,
        String healthGoal,
        List<String> cuisines,
        List<String> foodTypes,
        List<String> tastePreferences,
        String convenience,
        List<String> unsupportedPreferences
) {
    public MealPlanBrief {
        mealTimes = mealTimes == null ? List.of() : List.copyOf(new LinkedHashSet<>(mealTimes));
        cuisines = cuisines == null ? List.of() : List.copyOf(new LinkedHashSet<>(cuisines));
        foodTypes = foodTypes == null ? List.of() : List.copyOf(new LinkedHashSet<>(foodTypes));
        tastePreferences = tastePreferences == null ? List.of() : List.copyOf(new LinkedHashSet<>(tastePreferences));
        unsupportedPreferences = unsupportedPreferences == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(unsupportedPreferences));
    }

    /** 兼容旧 3 参形态：可选偏好与未支持集合为空。 */
    public MealPlanBrief(LocalDate weekStart, List<String> mealTimes, String healthGoal) {
        this(weekStart, mealTimes, healthGoal, List.of(), List.of(), List.of(), null, List.of());
    }

    /** 兼容旧单值 cuisine 形态。 */
    public MealPlanBrief(LocalDate weekStart, List<String> mealTimes, String healthGoal,
                         String cuisine, List<String> tastePreferences, String convenience,
                         List<String> unsupportedPreferences) {
        this(weekStart, mealTimes, healthGoal,
                cuisine == null || cuisine.isBlank() ? List.of() : List.of(cuisine),
                List.of(), tastePreferences, convenience, unsupportedPreferences);
    }

    public static MealPlanBrief empty() {
        return new MealPlanBrief(null, List.of(), null, List.of(), List.of(), List.of(), null, List.of());
    }

    @JsonIgnore
    public boolean isComplete() {
        // ADR-0018：weekStart 是生成时派生的不可见内部锚点，不是用户必填字段。
        return !mealTimes.isEmpty() && healthGoal != null && !healthGoal.isBlank();
    }

    /** 生成边界注入内部周锚点（旧会话已有锚点优先保持，调用方按派生规则决定）。 */
    public MealPlanBrief withWeekStart(LocalDate nextWeekStart) {
        return new MealPlanBrief(nextWeekStart == null ? weekStart : nextWeekStart, mealTimes, healthGoal,
                cuisines, foodTypes, tastePreferences, convenience, unsupportedPreferences);
    }

    public MealPlanBrief withValues(LocalDate nextWeekStart, List<String> nextMealTimes, String nextHealthGoal) {
        return new MealPlanBrief(nextWeekStart == null ? weekStart : nextWeekStart,
                nextMealTimes == null || nextMealTimes.isEmpty() ? mealTimes : nextMealTimes,
                nextHealthGoal == null || nextHealthGoal.isBlank() ? healthGoal : nextHealthGoal,
                cuisines, foodTypes, tastePreferences, convenience, unsupportedPreferences);
    }

    /** 旧调用兼容：返回第一个菜系；新代码应使用 cuisines()。 */
    @JsonIgnore
    public String cuisine() {
        return cuisines.isEmpty() ? null : cuisines.get(0);
    }

    /**
     * 合并可选偏好（旧调用兼容重载）：cuisine 单值按单元素列表替换，convenience 在新值非空时替换。
     * 列表字段在传入非 null 时整体替换（"换成/改为"清除重建由解析器负责构造新列表）。
     * unsupportedPreferences 追加去重，不因其他字段更新丢失。
     */
    public MealPlanBrief withOptional(String nextCuisine, List<String> nextTastePreferences,
                                      String nextConvenience, java.util.Collection<String> nextUnsupported) {
        List<String> nextCuisines = nextCuisine == null || nextCuisine.isBlank() ? cuisines : List.of(nextCuisine);
        return withOptional(nextCuisines, null, nextTastePreferences, nextConvenience, nextUnsupported);
    }

    public MealPlanBrief withOptional(List<String> nextCuisines, List<String> nextFoodTypes,
                                      List<String> nextTastePreferences, String nextConvenience,
                                      java.util.Collection<String> nextUnsupported) {
        List<String> mergedUnsupported = new ArrayList<>(unsupportedPreferences);
        if (nextUnsupported != null) {
            nextUnsupported.stream().filter(value -> value != null && !value.isBlank()).forEach(mergedUnsupported::add);
        }
        return new MealPlanBrief(weekStart, mealTimes, healthGoal,
                nextCuisines == null ? cuisines : nextCuisines,
                nextFoodTypes == null ? foodTypes : nextFoodTypes,
                nextTastePreferences == null ? tastePreferences : nextTastePreferences,
                nextConvenience == null || nextConvenience.isBlank() ? convenience : nextConvenience,
                mergedUnsupported);
    }

    /** 替换未支持偏好集合（去重保序）。 */
    public MealPlanBrief withUnsupportedPreferences(java.util.Collection<String> nextUnsupported) {
        return new MealPlanBrief(weekStart, mealTimes, healthGoal, cuisines, foodTypes, tastePreferences, convenience,
                nextUnsupported == null ? List.of() : new ArrayList<>(nextUnsupported));
    }
}
