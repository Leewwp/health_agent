package com.diet.health.intent;

import java.util.Map;

/** 健康槽位的用户可见中文文案，内部字段只用于协议和状态机。 */
public final class HealthSlotLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("domain", "推荐类型"),
            Map.entry("mealTime", "用餐时间"),
            Map.entry("mood", "今天的心情"),
            Map.entry("scene", "用餐场景"),
            Map.entry("healthGoal", "健康目标"),
            Map.entry("cuisine", "菜系"),
            Map.entry("foodType", "餐食类型"),
            Map.entry("taste", "口味"),
            Map.entry("convenience", "能接受的耗时和购买方式"),
            Map.entry("bodyPart", "训练部位"),
            Map.entry("bodyParts", "训练部位"),
            Map.entry("equipment", "器械"),
            Map.entry("trainingGoal", "训练目标"),
            Map.entry("difficulty", "难度"),
            Map.entry("trainingDays", "训练日"),
            Map.entry("timeWindow", "训练时段"),
            Map.entry("wakeTime", "起床时间"),
            Map.entry("bedtime", "入睡时间"),
            Map.entry("sleepDuration", "睡眠时长")
    );

    private HealthSlotLabels() {
    }

    /** 未知字段不直接回显，避免内部协议名进入用户文案。 */
    public static String label(String slot) {
        if (slot == null || slot.isBlank()) {
            return "其他偏好";
        }
        return LABELS.getOrDefault(slot, "其他偏好");
    }
}
