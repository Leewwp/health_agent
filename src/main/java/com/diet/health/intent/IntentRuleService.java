package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.health.risk.RiskRuleCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 意图 Java 兜底规则：LLM 失败/输出非法时的关键词路由与轻量槽位提取，保证三品类主流程可继续。
 * 固定置信度 0.2 并标记 degraded，不伪装成真实理解。
 */
@Service
public class IntentRuleService {

    /** 风险关键词 → flag 映射（44 号票：来自 RiskRuleCatalog 唯一事实来源，不得复制规则语义）。 */
    private static final Map<String, String> RISK_KEYWORDS = RiskRuleCatalog.keywordToFlag();

    /** 模型与兜底路径共用的输入归一器。 */
    private final HealthInputNormalizer normalizer;

    public IntentRuleService(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 意图降级后的确定性结果（含轻量槽位提取）。 */
    public HealthIntentResult fallback(String userInput, Map<String, List<String>> knownSlots, String fallbackReason) {
        String text = userInput == null ? "" : userInput;
        List<String> riskFlags = new ArrayList<>();
        for (Map.Entry<String, String> entry : RISK_KEYWORDS.entrySet()) {
            if (text.contains(entry.getKey()) && !riskFlags.contains(entry.getValue())) {
                riskFlags.add(entry.getValue());
            }
        }
        HealthDomain domain = HealthDomain.OTHER;
        HealthTask task = HealthTask.CHAT;
        if (HealthPlanIntentMatcher.matches(text)) {
            domain = HealthPlanIntentMatcher.matchesComposite(text) ? HealthDomain.COMPOSITE
                    : containsAny(text, "训练", "健身") ? HealthDomain.EXERCISE : HealthDomain.MEAL;
            task = HealthTask.PLAN;
        } else if (containsAny(text, "推荐电影", "电影推荐", "你是 AI", "你是AI", "你是 ai", "你是ai")) {
            domain = HealthDomain.OTHER;
            task = HealthTask.CHAT;
        } else if (isRoutineFact(text)) {
            domain = HealthDomain.ROUTINE;
            task = HealthTask.RECOMMEND;
        } else if (mealSignal(text)) {
            domain = HealthDomain.MEAL;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "训练", "健身", "动作", "俯卧撑", "深蹲", "练")
                || !normalizer.normalize(HealthDomain.EXERCISE, text, Map.of()).slots().isEmpty()) {
            domain = HealthDomain.EXERCISE;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "午休", "生物钟", "咖啡")) {
            domain = HealthDomain.ROUTINE;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "换一批", "换换", "不要", "去掉")) {
            domain = knownExercise(knownSlots) ? HealthDomain.EXERCISE : HealthDomain.MEAL;
            task = HealthTask.ADJUST;
        }
        Map<String, List<String>> slots = normalizer.normalize(domain, text, Map.of()).slots();
        return HealthIntentResult.degraded(domain, task, riskFlags, slots, List.of(),
                fallbackReason == null ? "KEYWORD_FALLBACK" : fallbackReason);
    }

    /**
     * 明确短语的确定性快路径。无法唯一确定领域时返回 null，交给唯一一次结构化理解调用。
     */
    public HealthIntentResult fastPath(String userInput, Map<String, List<String>> knownSlots) {
        String text = userInput == null ? "" : userInput;
        if (text.isBlank()) {
            return null;
        }
        String compact = text.replaceAll("[，。！？,.!?\\s]", "");
        if (List.of("帮我推荐", "帮我推荐一下", "推荐一下", "给我推荐一下").contains(compact)) {
            return HealthIntentResult.parsed(HealthDomain.COMPOSITE, HealthTask.RECOMMEND,
                    List.of(), Map.of(), List.of(), 1.0);
        }
        // 计划简报常带训练日与时间等词，先判定 PLAN，避免被“训练时段”规则误路由为作息。
        if (HealthPlanIntentMatcher.matches(text)) {
            HealthIntentResult plan = fallback(text, knownSlots, null);
            return HealthIntentResult.parsed(plan.domain(), plan.task(), plan.riskFlags(), plan.slots(),
                    plan.preferenceSignals(), 1.0);
        }
        // 复合诉求必须保留给一次结构化理解，不能被单品类关键词抢先路由。
        if (containsAny(text, "综合", "同时", "一起", "兼顾")) {
            return null;
        }
        boolean meal = mealSignal(text);
        boolean exercise = containsAny(text, "训练", "健身", "动作", "俯卧撑", "深蹲", "练");
        // 本票快路径聚焦餐食、动作和计划；作息结构化理解仍沿用原有契约，避免改变事实槽位解析。
        boolean routine = isRoutineFact(text);
        int domains = (meal ? 1 : 0) + (exercise ? 1 : 0) + (routine ? 1 : 0);
        if (domains > 1) {
            return null;
        }
        if (domains == 0 && knownSlots != null && !knownSlots.isEmpty()) {
            Map<String, List<String>> mealSlots = normalizer.normalize(HealthDomain.MEAL, text, Map.of()).slots();
            Map<String, List<String>> exerciseSlots = normalizer.normalize(HealthDomain.EXERCISE, text, Map.of()).slots();
            Map<String, List<String>> routineSlots = normalizer.normalize(HealthDomain.ROUTINE, text, Map.of()).slots();
            meal = knownSlots.keySet().stream().anyMatch(HealthSlotDictionary.MEAL_SLOTS::contains) && !mealSlots.isEmpty();
            exercise = knownSlots.keySet().stream().anyMatch(HealthSlotDictionary.FITNESS_SLOTS::contains) && !exerciseSlots.isEmpty();
            routine = knownSlots.keySet().stream().anyMatch(HealthSlotDictionary.ROUTINE_SLOTS::contains) && !routineSlots.isEmpty();
            domains = (meal ? 1 : 0) + (exercise ? 1 : 0) + (routine ? 1 : 0);
            if (domains != 1) {
                return null;
            }
        }
        if (domains != 1) {
            return null;
        }
        HealthIntentResult result = fallback(text, knownSlots, null);
        return HealthIntentResult.parsed(result.domain(), result.task(), result.riskFlags(), result.slots(), result.preferenceSignals(), 1.0);
    }

    private boolean knownExercise(Map<String, List<String>> knownSlots) {
        if (knownSlots == null) {
            return false;
        }
        return knownSlots.containsKey("bodyParts") || knownSlots.containsKey("trainingGoal");
    }

    private boolean isRoutineFact(String text) {
        return containsAny(text, "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "午休", "生物钟", "咖啡")
                || (containsAny(text, "训练", "运动") && containsAny(text, "什么时候", "几点", "时段", "时间"));
    }

    /** 识别没有明确餐次但仍在表达餐食需求的口语。 */
    private boolean mealSignal(String text) {
        return containsAny(text, "吃什么", "早餐", "早饭", "午餐", "午饭", "中饭", "中午", "晚餐", "晚饭", "想吃", "饿",
                "食物", "尽快能吃上", "尽快吃上", "马上能吃", "赶时间", "快速", "便利店", "速食", "外带", "胃口", "没食欲", "素食", "吃素", "酸甜口味", "酸甜口")
                || (!normalizer.normalize(HealthDomain.MEAL, text, Map.of()).slots().isEmpty()
                && !containsAny(text, "训练", "健身", "动作", "俯卧撑", "深蹲", "练"));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
