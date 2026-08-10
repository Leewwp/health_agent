package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 健康风险规则服务（版本化小规则集，25 号契约落地）。
 * <p>
 * 三档等级 NORMAL / ADVISORY / BLOCK_PLAN，多规则取最高；
 * 所有文案固定在 Java 模板中。本票实现前置（候选前）校验，组合时与输出后校验由 34 号补齐。
 */
@Service
public class HealthRiskRuleService {

    /** 规则集版本。 */
    public static final String RULES_VERSION = "2026-08-10-v1";

    /** BLOCK_PLAN 固定文案。 */
    public static final String BLOCK_PLAN_COPY = "当前情况不适合生成具体计划，建议咨询专业医生或营养师。";

    /** ADVISORY 固定文案。 */
    public static final String ADVISORY_COPY = "这些建议仅供参考，如有不适请及时咨询专业人士。";

    /** 单条版本化规则。 */
    public record RiskRule(String flag, List<String> keywords, HealthRiskLevel level, String copy) {
    }

    /** 风险评估结果。 */
    public record RiskDecision(HealthRiskLevel level, List<String> matchedFlags, String copy) {
        public boolean blocked() {
            return level == HealthRiskLevel.BLOCK_PLAN;
        }
    }

    /** 规则 V1：flag → 关键词 → 等级 → 固定文案。 */
    private static final List<RiskRule> RULES = List.of(
            new RiskRule("PREGNANCY", List.of("孕妇", "怀孕", "哺乳"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("UNDERAGE", List.of("未成年", "未满18", "小朋友", "小孩"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("ACUTE_SYMPTOMS", List.of("胸痛", "胸闷", "眩晕", "心悸", "恶心呕吐"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("TREATMENT", List.of("治疗", "诊断", "吃药", "药物", "康复", "术后", "骨折"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("EATING_DISORDER", List.of("绝食", "暴食", "催吐", "厌食"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("CHRONIC_CONDITION", List.of("糖尿病", "高血压", "心脏病", "肾病"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("SENIOR", List.of("65岁", "老年人", "老年"), HealthRiskLevel.ADVISORY, ADVISORY_COPY)
    );

    /** 支持的风险 flag 白名单（供意图解析器校验）。 */
    public static final List<String> SUPPORTED_FLAGS = RULES.stream().map(RiskRule::flag).toList();

    /** 评估用户输入关键词 + 意图风险信号，返回最高等级决策。 */
    public RiskDecision assess(String userInput, List<String> intentRiskFlags) {
        String text = userInput == null ? "" : userInput;
        List<String> matchedFlags = new ArrayList<>();
        HealthRiskLevel highest = HealthRiskLevel.NORMAL;
        String copy = null;
        for (RiskRule rule : RULES) {
            boolean flagMatched = intentRiskFlags != null && intentRiskFlags.contains(rule.flag());
            boolean keywordMatched = rule.keywords().stream().anyMatch(text::contains);
            if (flagMatched || keywordMatched) {
                matchedFlags.add(rule.flag());
                if (rule.level().ordinal() > highest.ordinal()) {
                    highest = rule.level();
                    copy = rule.copy();
                }
            }
        }
        if (highest == HealthRiskLevel.NORMAL) {
            return new RiskDecision(HealthRiskLevel.NORMAL, matchedFlags, null);
        }
        return new RiskDecision(highest, matchedFlags, copy);
    }
}
