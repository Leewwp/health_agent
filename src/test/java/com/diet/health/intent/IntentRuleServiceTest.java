package com.diet.health.intent;

import com.diet.health.TestSupport;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java 意图兜底规则：三品类路由、风险信号、调整与闲聊。 */
class IntentRuleServiceTest {

    private final IntentRuleService rules = new IntentRuleService(new HealthInputNormalizer());

    @Test
    void 健身关键词路由到EXERCISE推荐() {
        HealthIntentResult result = rules.fallback("我想练胸", Map.of(), "TIMEOUT");
        assertEquals(HealthDomain.EXERCISE, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
        assertTrue(result.degraded());
    }

    @Test
    void 作息关键词路由到ROUTINE() {
        HealthIntentResult result = rules.fallback("几点睡合适", Map.of(), null);
        assertEquals(HealthDomain.ROUTINE, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
    }

    @Test
    void 饮食关键词路由到MEAL推荐() {
        HealthIntentResult result = rules.fallback("中午想吃清淡的", Map.of(), null);
        assertEquals(HealthDomain.MEAL, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
    }

    @Test
    void 周计划关键词路由到PLAN意图() {
        HealthIntentResult result = rules.fallback("帮我安排一周的计划", Map.of(), null);
        assertEquals(HealthTask.PLAN, result.task());

        HealthIntentResult exercisePlan = rules.fallback("帮我安排一周的训练", Map.of(), null);
        assertEquals(HealthTask.PLAN, exercisePlan.task(), "训练与周计划关键词并存时按 PLAN 处理");

        HealthIntentResult currentWeekExercisePlan = rules.fallback("帮我安排一下这周的健身计划", Map.of(), null);
        assertEquals(HealthTask.PLAN, currentWeekExercisePlan.task(), "前端快捷问题的本周表达必须按 PLAN 处理");
    }

    @Test
    void 训练简报确认生成计划不得被训练时段误路由为作息() {
        String input = "训练偏好已整理：训练目标：增肌；部位：全身；器械：哑铃；难度：进阶；目标周：2026-08-24；训练日：周一、周二、周三、周四、周五；时间：18:00-19:00。请确认后生成计划。";
        HealthIntentResult result = rules.fastPath(input, Map.of());
        assertEquals(HealthDomain.EXERCISE, result.domain());
        assertEquals(HealthTask.PLAN, result.task());
    }

    @Test
    void 训练简报字段标签不应被识别为器械值() {
        HealthIntentResult result = rules.fallback("训练偏好已整理：器械：哑铃；难度：进阶", Map.of(), null);
        assertEquals(List.of("哑铃"), result.slots().get("equipment"));
    }

    @Test
    void 单餐请求不得被PLAN关键词误路由() {
        // 回归：#72 PLAN 兜底关键词必须足够精确，避免「帮我安排一下午餐」被误判为周计划。
        HealthIntentResult meal = rules.fallback("帮我安排一下午餐", Map.of(), null);
        assertEquals(HealthTask.RECOMMEND, meal.task(), "「帮我安排一下午餐」是单餐推荐，不是周计划");
    }

    @Test
    void 换一批路由到ADJUST且尊重已有健身槽位() {
        HealthIntentResult mealAdjust = rules.fallback("换一批", Map.of("mealTime", List.of("午餐")), null);
        assertEquals(HealthDomain.MEAL, mealAdjust.domain());
        assertEquals(HealthTask.ADJUST, mealAdjust.task());

        HealthIntentResult exerciseAdjust = rules.fallback("换一批", Map.of("bodyParts", List.of("胸")), null);
        assertEquals(HealthDomain.EXERCISE, exerciseAdjust.domain());
        assertEquals(HealthTask.ADJUST, exerciseAdjust.task());
    }

    @Test
    void 风险关键词产生风险flag() {
        HealthIntentResult result = rules.fallback("我怀孕了还能训练吗", Map.of(), null);
        assertTrue(result.riskFlags().contains("PREGNANCY"));
    }

    @Test
    void 无关键词进入OTHER闲聊() {
        HealthIntentResult result = rules.fallback("你好", Map.of(), "INVALID_JSON");
        assertEquals(HealthDomain.OTHER, result.domain());
        assertEquals(HealthTask.CHAT, result.task());
    }

    @Test
    void 健身别名与无关对话安全路由() {
        assertEquals(HealthDomain.EXERCISE, rules.fallback("胸肌", Map.of(), null).domain());
        assertEquals(List.of("腿"), rules.fallback("大腿", Map.of(), null).slots().get("bodyParts"));
        assertEquals(List.of("臀"), rules.fallback("臀大肌", Map.of(), null).slots().get("bodyParts"));
        assertEquals(HealthDomain.OTHER, rules.fallback("推荐电影", Map.of(), null).domain());
        assertEquals(HealthDomain.OTHER, rules.fallback("你是 AI 吗", Map.of(), null).domain());
    }

    @Test
    void 咖啡与训练时段路由到作息事实() {
        assertEquals(HealthDomain.ROUTINE, rules.fallback("晚上几点前停止喝咖啡", Map.of(), null).domain());
        assertEquals(HealthDomain.ROUTINE, rules.fallback("什么时候训练合适", Map.of(), null).domain());
        assertEquals(HealthDomain.EXERCISE, rules.fallback("适合新手的训练", Map.of(), null).domain());
    }

    @Test
    void 兜底提取饮食槽位使模板追问可继续会话() {
        HealthIntentResult result = rules.fallback("午餐想吃清淡的", Map.of(), "MISSING_CONFIG");
        assertEquals(List.of("午餐"), result.slots().get("mealTime"));
        assertEquals(List.of("清淡"), result.slots().get("healthGoal"));
    }

    @Test
    void 兜底提取健身槽位() {
        HealthIntentResult result = rules.fallback("想练胸", Map.of(), "TIMEOUT");
        assertEquals(List.of("胸"), result.slots().get("bodyParts"));
    }

    @Test
    void 作息结构化槽位无法关键词提取() {
        HealthIntentResult result = rules.fallback("几点睡合适", Map.of(), "TIMEOUT");
        assertTrue(result.slots().isEmpty());
    }

    @Test
    void 没有餐次的口语餐食请求仍路由到MEAL() {
        HealthIntentResult result = rules.fallback("想要尽快能吃上的食物", Map.of(), "TIMEOUT");
        assertEquals(HealthDomain.MEAL, result.domain());
        assertEquals(List.of("快速"), result.slots().get("convenience"));
    }

    @Test
    void 单独便捷表达也路由到餐食并保留快速槽位() {
        for (String input : List.of("尽快能吃上", "马上能吃", "赶时间", "快速")) {
            HealthIntentResult result = rules.fallback(input, Map.of(), "TIMEOUT");
            assertEquals(HealthDomain.MEAL, result.domain(), input);
            assertEquals(List.of("快速"), result.slots().get("convenience"), input);
        }
    }
}
