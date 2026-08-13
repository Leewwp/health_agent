package com.diet.health.reader.exercise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作词汇归一共享模块（#64，方案 B）。
 * <p>
 * 无 I/O 纯规则：审核动作数据集（Gym Visual）的部位/器材/肌群为英文原始值，
 * 统一归一为健身槽位中文词汇（与 {@code HealthSlotDictionary} 对齐），
 * 未收录的原始值返回空串并过滤，不透出英文。供 {@code DbReviewedExerciseReader}
 * （浏览读取模型）与 {@code DbReviewedResourceProvider}（领域资源标签）共用，
 * 保证浏览、聊天推荐、周计划与详情抽屉使用同一套词汇语义；两者不得互相调用。
 */
public final class ExerciseVocabulary {

    /** 数据集器材英文值 → 健身槽位中文值。 */
    private static final Map<String, String> EQUIPMENT_ZH = Map.of(
            "body weight", "徒手",
            "dumbbell", "哑铃",
            "band", "弹力带"
    );

    /** 数据集部位英文值 → 健身槽位中文值。 */
    private static final Map<String, String> BODY_PART_ZH = Map.of(
            "chest", "胸",
            "waist", "核心",
            "back", "背",
            "upper legs", "腿",
            "lower legs", "腿",
            "upper arms", "手臂",
            "shoulders", "肩",
            "cardio", "全身"
    );

    /** 数据集肌群英文值 → 健身槽位中文值。 */
    private static final Map<String, String> MUSCLE_ZH = Map.ofEntries(
            Map.entry("chest", "胸"),
            Map.entry("triceps", "手臂"),
            Map.entry("biceps", "手臂"),
            Map.entry("forearms", "手臂"),
            Map.entry("shoulders", "肩"),
            Map.entry("deltoids", "肩"),
            Map.entry("traps", "背"),
            Map.entry("upper back", "背"),
            Map.entry("quadriceps", "腿"),
            Map.entry("hamstrings", "腿"),
            Map.entry("calves", "腿"),
            Map.entry("ankles", "腿"),
            Map.entry("feet", "腿"),
            Map.entry("core", "核心"),
            Map.entry("obliques", "核心"),
            Map.entry("hip flexors", "核心"),
            Map.entry("lower back", "核心"),
            Map.entry("glutes", "臀")
    );

    private ExerciseVocabulary() {
    }

    /**
     * 数据集英文部位/肌群值 → 健身槽位中文值：body_part、靶肌、次肌统一走同一套归一规则
     * （先查部位字典，再查肌群字典），保证同一原始值归一结果一致；未收录返回空串。
     */
    public static String partZh(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String zh = BODY_PART_ZH.get(value);
        return zh != null ? zh : MUSCLE_ZH.getOrDefault(value, "");
    }

    /** 数据集器材英文值 → 健身槽位中文值（未收录返回空串）。 */
    public static String equipmentZh(String value) {
        return value == null ? "" : EQUIPMENT_ZH.getOrDefault(value, "");
    }

    /**
     * 数据集难度 → 健身槽位合法难度：数据集只有「入门/中级/进阶」，槽位字典难度为
     * 「入门/进阶/挑战」，中级归一到进阶；未收录返回空串。
     */
    public static String difficultyZh(String value) {
        return switch (value == null ? "" : value) {
            case "入门" -> "入门";
            case "中级", "进阶" -> "进阶";
            case "挑战" -> "挑战";
            default -> "";
        };
    }

    /** 多个原始部位/肌群值 → 归一中文集合（去重、保持顺序、未收录过滤）。 */
    public static List<String> normalizeParts(List<String> rawValues) {
        Set<String> result = new LinkedHashSet<>();
        if (rawValues != null) {
            for (String raw : rawValues) {
                String zh = partZh(raw);
                if (!zh.isEmpty()) {
                    result.add(zh);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 归一后仍未被收录的原始部位/肌群值（供调用方记录可诊断信息，不透出英文）。 */
    public static List<String> unrepresentedParts(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        List<String> dropped = new ArrayList<>();
        for (String raw : rawValues) {
            if (raw != null && !raw.isBlank() && partZh(raw).isEmpty()) {
                dropped.add(raw);
            }
        }
        return dropped;
    }

}
