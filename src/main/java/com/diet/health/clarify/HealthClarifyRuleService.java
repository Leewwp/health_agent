package com.diet.health.clarify;

import com.diet.health.enums.HealthDomain;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 健康澄清规则：是否追问由 Java 决定，ClarifyAgent 只优化措辞。
 * 模板追问必须能独立继续会话（下一轮意图 Agent 可从回答中提取槽位）。
 */
@Service
public class HealthClarifyRuleService {

    /** 计算缺失的关键槽位，按领域最少追问原则每次只问最优先的一项。 */
    public List<String> missingSlots(HealthDomain domain, Map<String, List<String>> slots) {
        Map<String, List<String>> safe = slots == null ? Map.of() : slots;
        List<String> missing = new ArrayList<>();
        switch (domain) {
            case MEAL -> {
                if (isEmpty(safe.get("mealTime"))) {
                    missing.add("mealTime");
                } else if (isEmpty(safe.get("healthGoal")) && noStrongFoodPreference(safe)) {
                    missing.add("healthGoal");
                }
            }
            case EXERCISE -> {
                if (isEmpty(safe.get("trainingGoal"))) {
                    missing.add("trainingGoal");
                } else if (isEmpty(safe.get("bodyParts"))) {
                    missing.add("bodyParts");
                } else if (isEmpty(safe.get("equipment"))) {
                    missing.add("equipment");
                } else if (isEmpty(safe.get("difficulty"))) {
                    missing.add("difficulty");
                }
            }
            case ROUTINE -> {
                if (isEmpty(safe.get("wakeTime")) && isEmpty(safe.get("bedtime")) && isEmpty(safe.get("sleepDuration"))) {
                    missing.add("wakeTime");
                }
            }
            default -> {
            }
        }
        return missing;
    }

    /** 推荐前确认使用的最低条件；其余槽位仅作为可选补充，不阻断一次推荐。 */
    public List<String> minimumRecommendationSlots(HealthDomain domain, Map<String, List<String>> slots) {
        Map<String, List<String>> safe = slots == null ? Map.of() : slots;
        List<String> missing = new ArrayList<>();
        switch (domain) {
            case MEAL -> { if (isEmpty(safe.get("mealTime"))) missing.add("mealTime"); }
            case EXERCISE -> {
                if (isEmpty(safe.get("trainingGoal"))) missing.add("trainingGoal");
                else if (isEmpty(safe.get("bodyParts"))) missing.add("bodyParts");
            }
            case ROUTINE -> missing.addAll(missingSlots(domain, safe));
            default -> { }
        }
        return missing;
    }

    /** 未确认但可继续补充的槽位。 */
    public List<String> optionalRecommendationSlots(HealthDomain domain, Map<String, List<String>> slots) {
        List<String> all = switch (domain) {
            case MEAL -> List.of("mood", "healthGoal", "cuisine", "taste", "scene", "convenience");
            case EXERCISE -> List.of("equipment", "difficulty");
            default -> List.of();
        };
        return all.stream().filter(slot -> slots == null || slots.get(slot) == null || slots.get(slot).isEmpty()).toList();
    }

    private boolean noStrongFoodPreference(Map<String, List<String>> slots) {
        return isEmpty(slots.get("cuisine")) && isEmpty(slots.get("taste"))
                && isEmpty(slots.get("scene")) && isEmpty(slots.get("convenience"));
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    /** 模板追问文案，LLM 措辞失败时兜底。 */
    public String fallbackQuestion(HealthDomain domain, List<String> missingSlots) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "可以再多告诉我一点你的偏好，我再帮你安排。";
        }
        String first = missingSlots.get(0);
        return switch (domain) {
            case MEAL -> "mealTime".equals(first)
                    ? "这顿主要是早餐、午餐还是晚餐？"
                    : "这顿更想清淡点、顶饱点，还是按口味来？";
            case EXERCISE -> switch (first) {
                case "trainingGoal" -> "这次训练想侧重增肌、减脂还是耐力？";
                case "bodyParts" -> "你今天想练哪个部位？";
                case "equipment" -> "你现在可以使用徒手、哑铃还是其他器械？";
                case "difficulty" -> "你希望动作难度是入门、进阶还是挑战？";
                default -> "可以再具体说说这次训练需求吗？";
            };
            case ROUTINE -> "wakeTime".equals(first)
                    ? "你平时大概几点睡、几点起？"
                    : "你希望我帮你规划睡眠时长还是作息安排？";
            default -> "可以再具体说说你的需求吗？";
        };
    }
}
