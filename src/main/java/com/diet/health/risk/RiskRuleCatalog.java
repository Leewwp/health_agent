package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 健康风险规则目录（44 号票）：风险关键词 → flag → 等级 → 固定文案的唯一事实来源。
 * <p>
 * 消费方只能读取本目录，不得各自复制关键词或文案：HealthRiskRuleService（正式评估）、
 * IntentRuleService（意图关键词降级）、FixtureAgentInvoker（固定夹具）允许有不同的解析
 * 方式，但规则语义必须一致。任何规则变更只改本文件，并同步递增 {@link #RULES_VERSION}。
 * <p>
 * 编码约定：SENIOR 为 65+ 关键词风险 flag（ADVISORY，意图信号用）；SENIOR_PLAN 为档案
 * 维度"65+ 请求训练计划"决策 flag（BLOCK_PLAN，assessProfile 输出）；SENIOR_TRAINING 为
 * 计划组合时校验规则码（PlanValidationService）。三者阶段不同、flag 不同，共用同一固定文案，
 * 避免 32 号审计中的三套相近编码漂移。
 */
public final class RiskRuleCatalog {

    private RiskRuleCatalog() {
    }

    /** 规则集版本：任何关键词/等级/文案变更必须同步递增。 */
    public static final String RULES_VERSION = "2026-08-10-v1";

    /** BLOCK_PLAN 固定文案。 */
    public static final String BLOCK_PLAN_COPY = "当前情况不适合生成具体计划，建议咨询专业医生或营养师。";

    /** ADVISORY 固定文案。 */
    public static final String ADVISORY_COPY = "这些建议仅供参考，如有不适请及时咨询专业人士。";

    /** 未满 18 岁固定文案（档案维度）。 */
    public static final String UNDERAGE_COPY = "未满 18 岁不适合生成具体的训练和饮食计划，建议保持均衡饮食和规律作息。";

    /** 65 岁以上具体训练计划固定文案（档案维度/组合校验共用）。 */
    public static final String SENIOR_TRAINING_COPY = "65 岁以上生成具体训练计划需要专业评估，当前建议先参考一般性健康建议。";

    /** 单条版本化规则：flag → 关键词 → 等级 → 固定文案。 */
    public record RiskRule(String flag, List<String> keywords, HealthRiskLevel level, String copy) {
    }

    /** 规则 V1（与 RULES_VERSION 绑定）。 */
    private static final List<RiskRule> RULES = List.of(
            new RiskRule("PREGNANCY", List.of("孕妇", "怀孕", "哺乳"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("UNDERAGE", List.of("未成年", "未满18", "小朋友", "小孩"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("ACUTE_SYMPTOMS", List.of("胸痛", "胸闷", "眩晕", "心悸", "恶心呕吐"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("TREATMENT", List.of("治疗", "诊断", "吃药", "药物", "康复", "术后", "骨折"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("EATING_DISORDER", List.of("绝食", "暴食", "催吐", "厌食"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("CHRONIC_CONDITION", List.of("糖尿病", "高血压", "心脏病", "肾病"), HealthRiskLevel.BLOCK_PLAN, BLOCK_PLAN_COPY),
            new RiskRule("SENIOR", List.of("65岁", "老年人", "老年"), HealthRiskLevel.ADVISORY, ADVISORY_COPY)
    );

    /** 不可变规则列表（声明顺序即评估优先级）。 */
    public static List<RiskRule> rules() {
        return RULES;
    }

    /** 支持的风险 flag 白名单（供意图解析器校验 LLM 输出）。 */
    public static List<String> supportedFlags() {
        return RULES.stream().map(RiskRule::flag).toList();
    }

    /** 文本命中的全部 flag（去重，保持规则声明顺序）。 */
    public static List<String> matchedFlags(String text) {
        List<String> matched = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matched;
        }
        for (RiskRule rule : RULES) {
            if (rule.keywords().stream().anyMatch(text::contains) && !matched.contains(rule.flag())) {
                matched.add(rule.flag());
            }
        }
        return matched;
    }

    /** 第一个命中文本的规则（fixture 单 flag 用），无命中返回空。 */
    public static Optional<RiskRule> firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (RiskRule rule : RULES) {
            if (rule.keywords().stream().anyMatch(text::contains)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** 按 flag 查规则，无命中返回空。 */
    public static Optional<RiskRule> ruleByFlag(String flag) {
        return RULES.stream().filter(rule -> rule.flag().equals(flag)).findFirst();
    }

    /** 关键词 → flag 扁平映射（意图降级用，语义与 RULES 完全一致）。 */
    public static Map<String, String> keywordToFlag() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (RiskRule rule : RULES) {
            for (String keyword : rule.keywords()) {
                mapping.put(keyword, rule.flag());
            }
        }
        return Map.copyOf(mapping);
    }
}
