package com.diet.health.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 独立餐食计划简报，不复用训练字段。
 * 简报只表达"当前整理出的条件"，没有独立的用户确认状态或确认版本；
 * 开始生成时由服务端重新读取并校验完整性（ADR-0016）。
 */
public record MealPlanBrief(
        LocalDate weekStart,
        List<String> mealTimes,
        String healthGoal
) {
    public MealPlanBrief {
        mealTimes = mealTimes == null ? List.of() : List.copyOf(new LinkedHashSet<>(mealTimes));
    }

    public static MealPlanBrief empty() {
        return new MealPlanBrief(null, List.of(), null);
    }

    @JsonIgnore
    public boolean isComplete() {
        return weekStart != null && !mealTimes.isEmpty() && healthGoal != null && !healthGoal.isBlank();
    }

    public MealPlanBrief withValues(LocalDate nextWeekStart, List<String> nextMealTimes, String nextHealthGoal) {
        return new MealPlanBrief(nextWeekStart == null ? weekStart : nextWeekStart,
                nextMealTimes == null || nextMealTimes.isEmpty() ? mealTimes : nextMealTimes,
                nextHealthGoal == null || nextHealthGoal.isBlank() ? healthGoal : nextHealthGoal);
    }
}
