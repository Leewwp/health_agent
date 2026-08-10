package com.diet.health.enums;

/** 活动水平，只保留三档系数（24 号契约）。 */
public enum ActivityLevel {
    /** 久坐为主，系数 1.2。 */
    SEDENTARY,
    /** 轻度活动，系数 1.375。 */
    LIGHT,
    /** 中等活动，系数 1.55。 */
    MODERATE
}
