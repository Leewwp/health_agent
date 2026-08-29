package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用户输入归一：最小别名、多值、幂等与否定安全。 */
class HealthInputNormalizerTest {

    private final HealthInputNormalizer normalizer = new HealthInputNormalizer();

    @Test
    void 常见部位别名归一为现有词汇() {
        assertSlot("胸肌 胸部 胸大肌", "bodyParts", List.of("胸"));
        assertSlot("大腿 小腿 腿部", "bodyParts", List.of("腿"));
        assertSlot("臀部 臀肌 臀大肌", "bodyParts", List.of("臀"));
        assertSlot("背部 肩膀 胳膊 腰腹", "bodyParts", List.of("背", "肩", "手臂", "核心"));
        assertSlot("臀腿一起练", "bodyParts", List.of("腿"));
        assertSlot("臀和腿一起练", "bodyParts", List.of("腿", "臀"));
    }

    @Test
    void 颈部别名归一为完整目录部位() {
        assertSlot("脖子和颈部", "bodyParts", List.of("颈部"));
    }

    @Test
    void 难度目标和器材别名归一() {
        var result = normalizer.normalize(HealthDomain.EXERCISE,
                "适合初学者的轻量自重减肥训练", Map.of());
        assertEquals(List.of("入门"), result.slots().get("difficulty"));
        assertEquals(List.of("减脂"), result.slots().get("trainingGoal"));
        assertEquals(List.of("徒手"), result.slots().get("equipment"));
        assertFalse(result.requiresClarification());
    }

    @Test
    void 无器械表达是徒手而不是正向器械约束() {
        for (String input : List.of("无器械训练", "不用器械", "不使用器械")) {
            var result = normalizer.normalize(HealthDomain.EXERCISE, input, Map.of());
            assertEquals(List.of("徒手"), result.slots().get("equipment"));
            assertFalse(result.slots().get("equipment").contains("器械"));
            assertFalse(result.requiresClarification());
        }
    }

    @Test
    void 否定部位不转成正向约束并要求澄清() {
        var result = normalizer.normalize(HealthDomain.EXERCISE, "不要练胸",
                Map.of("bodyParts", List.of("胸")));
        assertFalse(result.slots().containsKey("bodyParts"));
        assertTrue(result.requiresClarification());
    }

    @Test
    void 模型别名与规范值共用归一且保持幂等() {
        var aliases = normalizer.normalize(HealthDomain.EXERCISE, "",
                Map.of("bodyParts", List.of(" 胸肌 ", "胸"), "difficulty", List.of("新手")));
        assertEquals(List.of("胸"), aliases.slots().get("bodyParts"));
        assertEquals(List.of("入门"), aliases.slots().get("difficulty"));
        assertEquals(aliases.slots(), normalizer.normalize(HealthDomain.EXERCISE, "", aliases.slots()).slots());
    }

    @Test
    void 领域投影不携带其他领域槽位() {
        Map<String, List<String>> mixed = Map.of(
                "mealTime", List.of("午餐"),
                "bodyParts", List.of("腿"),
                "wakeTime", List.of("07:00"));
        assertEquals(Map.of("mealTime", List.of("午餐")), normalizer.project(HealthDomain.MEAL, mixed));
        assertEquals(Map.of("bodyParts", List.of("腿")), normalizer.project(HealthDomain.EXERCISE, mixed));
        assertTrue(normalizer.project(HealthDomain.OTHER, mixed).isEmpty());
    }

    @Test
    void 历史时间从句不污染当前餐次且否定被标记为临时约束() {
        var result = normalizer.normalize(HealthDomain.MEAL,
                "昨天没吃晚餐，今天想吃丰盛早餐", Map.of());

        assertEquals(List.of("早餐"), result.slots().get("mealTime"));
        assertTrue(result.negatedSlots().isEmpty(), "历史事实被剥离后不应成为当前否定槽位");
    }

    @Test
    void 明确不练腿不生成腿部正向槽位() {
        var result = normalizer.normalize(HealthDomain.EXERCISE, "今天不练腿", Map.of());

        assertFalse(result.slots().containsKey("bodyParts"));
        assertTrue(result.negatedSlots().contains("bodyParts:腿"));
    }

    @Test
    void 口语别名合并多个餐食槽位() {
        var result = normalizer.normalize(HealthDomain.MEAL,
                "今晚胃口不好，想吃素，想吃便利店能买的酸甜口味速食",
                Map.of("taste", List.of("酸甜")));

        assertEquals(List.of("晚餐"), result.slots().get("mealTime"));
        assertEquals(List.of("没胃口"), result.slots().get("mood"));
        assertEquals(List.of("素食"), result.slots().get("foodType"));
        assertEquals(List.of("酸甜"), result.slots().get("taste"));
        assertEquals(List.of("快速"), result.slots().get("convenience"));
    }

    @Test
    void 尽快便利店速食和外带归一为便捷需求() {
        for (String input : List.of("尽快能吃上", "马上能吃", "赶时间", "快速", "便利店", "速食")) {
            assertEquals(List.of("快速"), normalizer.normalize(HealthDomain.MEAL, input, Map.of())
                    .slots().get("convenience"), input);
        }
        assertEquals(List.of("外带方便"), normalizer.normalize(HealthDomain.MEAL, "想外带", Map.of())
                .slots().get("convenience"));
    }

    @Test
    void 否定想吃素不会生成素食正向槽位() {
        var result = normalizer.normalize(HealthDomain.MEAL, "不想吃素", Map.of());
        assertFalse(result.slots().containsKey("foodType"));
        assertTrue(result.negatedSlots().contains("foodType:素食"));
    }

    private void assertSlot(String input, String slot, List<String> expected) {
        assertEquals(expected, normalizer.normalize(HealthDomain.EXERCISE, input, Map.of()).slots().get(slot));
    }
}
