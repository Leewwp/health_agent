package com.diet.health.intent;

import java.util.List;

/**
 * 周计划表达匹配器：供模型结果修正与确定性兜底共用，避免两条路径的关键词口径漂移。
 */
public final class HealthPlanIntentMatcher {

    private static final List<String> PLAN_PHRASES = List.of(
            "安排一周", "周计划", "一周计划", "一周健身计划", "一周训练计划", "一周餐食计划", "一周饮食计划",
            "本周餐食计划", "本周饮食计划", "安排一周餐食", "安排一周饮食", "一周的计划", "一周安排",
            "这周计划", "这周的计划", "这周健身计划", "这周的健身计划", "这周训练计划", "这周的训练计划",
            "本周计划", "本周健身计划", "本周训练计划", "训练计划", "健身计划", "餐食计划", "饮食计划",
            "回到训练计划", "返回训练计划", "继续训练计划", "回到餐食计划", "返回餐食计划",
            "生成计划", "确认后生成计划", "按这个生成"
    );

    private HealthPlanIntentMatcher() {
    }

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PLAN_PHRASES.stream().anyMatch(text::contains);
    }

    public static boolean matchesComposite(String text) {
        if (text == null) return false;
        boolean planPhrase = matches(text) || text.contains("综合计划")
                || (text.contains("计划") && containsAny(text, "训练和餐食", "训练+餐食", "训练和饮食", "训练+饮食",
                "健身和餐食", "健身+餐食", "健身和饮食", "健身+饮食"));
        return planPhrase && containsAny(text, "训练和餐食", "训练+餐食", "训练和饮食", "训练+饮食",
                "健身和餐食", "健身+餐食", "健身和饮食", "健身+饮食", "综合计划", "训练、餐食");
    }

    public static boolean matchesMeal(String text) {
        return text != null && matches(text)
                && containsAny(text, "餐食", "饮食", "早餐", "午餐", "晚餐", "吃饭");
    }

    public static boolean matchesExercise(String text) {
        return text != null && matches(text) && containsAny(text, "训练", "健身", "动作");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
