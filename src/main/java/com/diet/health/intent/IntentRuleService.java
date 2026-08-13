package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.health.risk.RiskRuleCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** 槽位字典，兜底槽位提取以字典为唯一来源。 */
    private final HealthSlotDictionary slotDictionary;

    public IntentRuleService(HealthSlotDictionary slotDictionary) {
        this.slotDictionary = slotDictionary;
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
        HealthDomain domain = HealthDomain.MEAL;
        HealthTask task = HealthTask.CHAT;
        if (containsAny(text, "安排一周", "周计划", "一周的计划", "一周安排", "帮我安排一周")) {
            domain = HealthDomain.MEAL;
            task = HealthTask.PLAN;
        } else if (containsAny(text, "训练", "健身", "俯卧撑", "深蹲", "练")) {
            domain = HealthDomain.EXERCISE;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "生物钟")) {
            domain = HealthDomain.ROUTINE;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "吃什么", "推荐", "早餐", "午餐", "晚餐", "想吃", "饿")) {
            domain = HealthDomain.MEAL;
            task = HealthTask.RECOMMEND;
        } else if (containsAny(text, "换一批", "换换", "不要", "去掉")) {
            domain = knownExercise(knownSlots) ? HealthDomain.EXERCISE : HealthDomain.MEAL;
            task = HealthTask.ADJUST;
        } else if (containsAny(text, "你好", "你是谁", "谢谢", "再见")) {
            task = HealthTask.CHAT;
        }
        Map<String, List<String>> slots = extractSlots(text, domain);
        return HealthIntentResult.degraded(domain, task, riskFlags, slots, List.of(),
                fallbackReason == null ? "KEYWORD_FALLBACK" : fallbackReason);
    }

    /**
     * 从用户原文中按字典提取命中槽位（用于关键词降级时让模板追问可继续会话）。
     * 只提取当前领域的槽位；作息时间/时长等结构化槽位无法从关键词提取，保持追问。
     */
    private Map<String, List<String>> extractSlots(String text, HealthDomain domain) {
        Map<String, List<String>> slots = new LinkedHashMap<>();
        List<String> slotNames = switch (domain) {
            case MEAL -> HealthSlotDictionary.MEAL_SLOTS;
            case EXERCISE -> HealthSlotDictionary.FITNESS_SLOTS;
            default -> List.of();
        };
        Map<String, List<String>> legalValues = slotDictionary.legalValues();
        for (String slot : slotNames) {
            List<String> hits = legalValues.getOrDefault(slot, List.of()).stream()
                    .filter(text::contains)
                    .toList();
            if (!hits.isEmpty()) {
                slots.put(slot, hits);
            }
        }
        return slots;
    }

    private boolean knownExercise(Map<String, List<String>> knownSlots) {
        if (knownSlots == null) {
            return false;
        }
        return knownSlots.containsKey("bodyParts") || knownSlots.containsKey("trainingGoal");
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
