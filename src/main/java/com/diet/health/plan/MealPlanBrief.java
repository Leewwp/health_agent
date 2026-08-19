package com.diet.health.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/** 独立餐食计划简报，不复用训练字段。 */
public record MealPlanBrief(
        LocalDate weekStart,
        List<String> mealTimes,
        String healthGoal,
        boolean confirmed,
        long confirmationVersion,
        LocalDateTime confirmedAt
) {
    public MealPlanBrief {
        mealTimes = mealTimes == null ? List.of() : List.copyOf(new LinkedHashSet<>(mealTimes));
        confirmationVersion = Math.max(0, confirmationVersion);
    }

    public static MealPlanBrief empty() {
        return new MealPlanBrief(null, List.of(), null, false, 0, null);
    }

    @JsonIgnore
    public boolean isComplete() {
        return weekStart != null && !mealTimes.isEmpty();
    }

    @JsonIgnore
    public boolean isConfirmedAndComplete() {
        return isComplete() && confirmed && confirmationVersion > 0;
    }

    public MealPlanBrief invalidate() {
        return new MealPlanBrief(weekStart, mealTimes, healthGoal, false,
                confirmationVersion, null);
    }

    public MealPlanBrief withValues(LocalDate nextWeekStart, List<String> nextMealTimes, String nextHealthGoal) {
        return new MealPlanBrief(nextWeekStart == null ? weekStart : nextWeekStart,
                nextMealTimes == null || nextMealTimes.isEmpty() ? mealTimes : nextMealTimes,
                nextHealthGoal == null || nextHealthGoal.isBlank() ? healthGoal : nextHealthGoal,
                false, confirmationVersion, null);
    }

    public MealPlanBrief confirm() {
        return new MealPlanBrief(weekStart, mealTimes, healthGoal, true,
                confirmationVersion + 1, LocalDateTime.now());
    }
}
