package com.diet.health.clarify;

import com.diet.health.enums.HealthDomain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 澄清规则：是否追问由 Java 决定。 */
class HealthClarifyRuleServiceTest {

    private final HealthClarifyRuleService rules = new HealthClarifyRuleService();

    @Test
    void 饮食缺少餐次必须追问() {
        List<String> missing = rules.missingSlots(HealthDomain.MEAL, Map.of());
        assertEquals(List.of("mealTime"), missing);
    }

    @Test
    void 饮食有餐次但缺健康诉求且无强偏好时追问() {
        List<String> missing = rules.missingSlots(HealthDomain.MEAL, Map.of("mealTime", List.of("午餐")));
        assertEquals(List.of("healthGoal"), missing);
    }

    @Test
    void 饮食有强口味偏好时无需健康诉求() {
        List<String> missing = rules.missingSlots(HealthDomain.MEAL,
                Map.of("mealTime", List.of("午餐"), "cuisine", List.of("川菜")));
        assertTrue(missing.isEmpty());
    }

    @Test
    void 健身按目标部位器械难度依次确认() {
        assertEquals(List.of("trainingGoal"), rules.missingSlots(HealthDomain.EXERCISE, Map.of()));
        assertEquals(List.of("bodyParts"), rules.missingSlots(HealthDomain.EXERCISE,
                Map.of("trainingGoal", List.of("减脂"))));
        assertEquals(List.of("equipment"), rules.missingSlots(HealthDomain.EXERCISE,
                Map.of("trainingGoal", List.of("减脂"), "bodyParts", List.of("胸"))));
        assertEquals(List.of("difficulty"), rules.missingSlots(HealthDomain.EXERCISE,
                Map.of("trainingGoal", List.of("减脂"), "bodyParts", List.of("胸"), "equipment", List.of("徒手"))));
        assertTrue(rules.missingSlots(HealthDomain.EXERCISE,
                Map.of("trainingGoal", List.of("减脂"), "bodyParts", List.of("胸"),
                        "equipment", List.of("徒手"), "difficulty", List.of("入门"))).isEmpty());
    }

    @Test
    void 作息无时间信息时追问() {
        assertEquals(List.of("wakeTime"), rules.missingSlots(HealthDomain.ROUTINE, Map.of()));
        assertTrue(rules.missingSlots(HealthDomain.ROUTINE, Map.of("wakeTime", List.of("07:00"))).isEmpty());
    }

    @Test
    void 模板追问可独立继续会话() {
        assertEquals("这顿主要是早餐、午餐还是晚餐？",
                rules.fallbackQuestion(HealthDomain.MEAL, List.of("mealTime")));
        assertEquals("你今天想练哪个部位？",
                rules.fallbackQuestion(HealthDomain.EXERCISE, List.of("bodyParts")));
        assertEquals("你平时大概几点睡、几点起？",
                rules.fallbackQuestion(HealthDomain.ROUTINE, List.of("wakeTime")));
    }
}
