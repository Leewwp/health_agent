package com.diet.health.profile;

import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;

import java.util.List;

/**
 * 能量估算（24 号契约，确定性 Java 计算，不进入 Prompt）：
 * Mifflin-St Jeor 基础代谢 × 三档活动系数 × 目标调整区间，四舍五入到 50 kcal。
 * 生理性别缺失时取男/女公式并集形成更宽区间，不由 LLM 补值。
 */
public final class EnergyCalculator {

    /** 活动系数：只保留三档。 */
    public static final double FACTOR_SEDENTARY = 1.2;
    public static final double FACTOR_LIGHT = 1.375;
    public static final double FACTOR_MODERATE = 1.55;

    /** 能量区间（kcal，估算值）。 */
    public record EnergyRange(int lowKcal, int highKcal) {
    }

    private EnergyCalculator() {
    }

    /** 计算每日能量区间。 */
    public static EnergyRange dailyRange(int age, ProfileSex sex, double heightCm, double weightKg,
                                         ActivityLevel activity, ProfileGoal goal) {
        List<Double> bmrs = sex != null
                ? List.of(bmr(sex, age, heightCm, weightKg))
                : List.of(bmr(ProfileSex.MALE, age, heightCm, weightKg), bmr(ProfileSex.FEMALE, age, heightCm, weightKg));
        double factor = activityFactor(activity);
        double low = bmrs.stream().mapToDouble(bmr -> bmr * factor * goalFactor(goal).low()).min().orElse(0);
        double high = bmrs.stream().mapToDouble(bmr -> bmr * factor * goalFactor(goal).high()).max().orElse(0);
        return new EnergyRange(roundTo50(low), roundTo50(high));
    }

    /** Mifflin-St Jeor：男性 10W + 6.25H - 5A + 5；女性 -161。 */
    static double bmr(ProfileSex sex, int age, double heightCm, double weightKg) {
        double base = 10 * weightKg + 6.25 * heightCm - 5 * age;
        return switch (sex) {
            case MALE -> base + 5;
            case FEMALE -> base - 161;
        };
    }

    /** 活动系数查询（供计算依据展示）。 */
    public static double activityFactor(ActivityLevel activity) {
        return switch (activity) {
            case SEDENTARY -> FACTOR_SEDENTARY;
            case LIGHT -> FACTOR_LIGHT;
            case MODERATE -> FACTOR_MODERATE;
        };
    }

    /** 目标调整区间：维持 ±5%，减脂 -5%~-15%，增重 +5%~+10%。 */
    static GoalFactor goalFactor(ProfileGoal goal) {
        return switch (goal) {
            case MAINTAIN -> new GoalFactor(0.95, 1.05);
            case LOSE -> new GoalFactor(0.85, 0.95);
            case GAIN -> new GoalFactor(1.05, 1.10);
        };
    }

    private record GoalFactor(double low, double high) {
    }

    /** 四舍五入到最近的 50 kcal。 */
    static int roundTo50(double value) {
        return (int) (Math.round(value / 50.0) * 50);
    }
}
