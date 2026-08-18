package com.diet.health.intent;

import java.util.List;

/**
 * 周计划表达匹配器：供模型结果修正与确定性兜底共用，避免两条路径的关键词口径漂移。
 */
final class HealthPlanIntentMatcher {

    private static final List<String> PLAN_PHRASES = List.of(
            "安排一周", "周计划", "一周计划", "一周健身计划", "一周训练计划", "一周的计划", "一周安排",
            "这周计划", "这周的计划", "这周健身计划", "这周的健身计划", "这周训练计划", "这周的训练计划",
            "本周计划", "本周健身计划", "本周训练计划"
    );

    private HealthPlanIntentMatcher() {
    }

    static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PLAN_PHRASES.stream().anyMatch(text::contains);
    }
}
