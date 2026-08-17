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
        if (containsAny(text, "安排一周", "周计划", "一周计划", "一周健身计划", "一周训练计划", "一周的计划", "一周安排", "帮我安排一周")) {
            domain = containsAny(text, "训练", "健身") ? HealthDomain.EXERCISE : HealthDomain.MEAL;
            task = HealthTask.PLAN;
        } else if (containsAny(text, "推荐电影", "电影推荐", "你是 AI", "你是AI", "你是 ai", "你是ai")) {
            domain = HealthDomain.OTHER;
            task = HealthTask.CHAT;
        } else if (isRoutineFact(text)) {
            domain = HealthDomain.ROUTINE;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "吃什么", "早餐", "早饭", "午餐", "午饭", "中饭", "中午", "晚餐", "晚饭", "想吃", "饿")) {
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

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
