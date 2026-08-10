package com.diet.health.profile;

import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 能量估算（24 号契约）：
 * Mifflin-St Jeor 男/女公式、三档活动系数、目标调整区间、四舍五入到 50 kcal、生理性别缺失取并集。
 */
class EnergyCalculatorTest {

    @Test
    void 男性维持区间按公式计算并四舍五入() {
        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
        assertEquals(2150, range.lowKcal());
        assertEquals(2400, range.highKcal());
    }

    @Test
    void 女性减脂区间使用减161公式() {
        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                30, ProfileSex.FEMALE, 165, 55, ActivityLevel.SEDENTARY, ProfileGoal.LOSE);
        assertEquals(1300, range.lowKcal());
        assertEquals(1450, range.highKcal());
    }

    @Test
    void 生理性别缺失时取男女性公式并集形成更宽区间() {
        EnergyCalculator.EnergyRange male = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
        EnergyCalculator.EnergyRange female = EnergyCalculator.dailyRange(
                30, ProfileSex.FEMALE, 175, 70, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
        EnergyCalculator.EnergyRange both = EnergyCalculator.dailyRange(
                30, null, 175, 70, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
        assertEquals(Math.min(male.lowKcal(), female.lowKcal()), both.lowKcal());
        assertEquals(Math.max(male.highKcal(), female.highKcal()), both.highKcal());
        assertEquals(1950, both.lowKcal());
        assertEquals(2400, both.highKcal());
    }

    @Test
    void 增重目标使用正百分之五到十() {
        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.LIGHT, ProfileGoal.GAIN);
        assertEquals(2400, range.lowKcal());
        assertEquals(2500, range.highKcal());
    }

    @Test
    void 中等活动系数155() {
        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.MODERATE, ProfileGoal.MAINTAIN);
        assertEquals(2450, range.lowKcal());
        assertEquals(2700, range.highKcal());
    }

    @Test
    void 久坐系数12低于轻度活动() {
        EnergyCalculator.EnergyRange sedentary = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.SEDENTARY, ProfileGoal.MAINTAIN);
        EnergyCalculator.EnergyRange light = EnergyCalculator.dailyRange(
                30, ProfileSex.MALE, 175, 70, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
        assertEquals(true, sedentary.lowKcal() < light.lowKcal());
    }

    @Test
    void 活动系数暴露给计算依据展示() {
        assertEquals(1.375, EnergyCalculator.activityFactor(ActivityLevel.LIGHT), 0.0001);
        assertEquals(1.2, EnergyCalculator.activityFactor(ActivityLevel.SEDENTARY), 0.0001);
        assertEquals(1.55, EnergyCalculator.activityFactor(ActivityLevel.MODERATE), 0.0001);
    }
}
