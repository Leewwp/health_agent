package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 健康风险规则服务（版本化小规则集，25 号契约落地，44 号票统一到 RiskRuleCatalog）。
 * <p>
 * 三档等级 NORMAL / ADVISORY / BLOCK_PLAN，多规则取最高；关键词、flag、等级与固定文案
 * 全部来自 {@link RiskRuleCatalog}（唯一事实来源），本服务只负责评估编排。
 * 实现前置（候选前）校验与档案维度评估，组合时与输出后校验由 PlanValidationService /
 * PlanOutputGuard 补齐。
 */
@Service
public class HealthRiskRuleService {

    /** 规则集版本（与 RiskRuleCatalog 同一事实来源）。 */
    public static final String RULES_VERSION = RiskRuleCatalog.RULES_VERSION;

    /** BLOCK_PLAN 固定文案。 */
    public static final String BLOCK_PLAN_COPY = RiskRuleCatalog.BLOCK_PLAN_COPY;

    /** ADVISORY 固定文案。 */
    public static final String ADVISORY_COPY = RiskRuleCatalog.ADVISORY_COPY;

    /** 未满 18 岁固定文案（34 号，档案维度）。 */
    public static final String UNDERAGE_COPY = RiskRuleCatalog.UNDERAGE_COPY;

    /** 65 岁以上具体训练计划固定文案（34 号，档案维度）。 */
    public static final String SENIOR_TRAINING_COPY = RiskRuleCatalog.SENIOR_TRAINING_COPY;

    /** 风险评估结果。 */
    public record RiskDecision(HealthRiskLevel level, List<String> matchedFlags, String copy) {
        public boolean blocked() {
            return level == HealthRiskLevel.BLOCK_PLAN;
        }
    }

    /** 支持的风险 flag 白名单（供意图解析器校验，来自目录）。 */
    public static final List<String> SUPPORTED_FLAGS = RiskRuleCatalog.supportedFlags();

    /** 评估用户输入关键词 + 意图风险信号，返回最高等级决策（规则与文案来自目录）。 */
    public RiskDecision assess(String userInput, List<String> intentRiskFlags) {
        List<String> matchedFlags = RiskRuleCatalog.matchedFlags(userInput == null ? "" : userInput);
        if (intentRiskFlags != null) {
            for (String flag : intentRiskFlags) {
                if (RiskRuleCatalog.ruleByFlag(flag).isPresent() && !matchedFlags.contains(flag)) {
                    matchedFlags.add(flag);
                }
            }
        }
        HealthRiskLevel highest = HealthRiskLevel.NORMAL;
        String copy = null;
        for (String flag : matchedFlags) {
            RiskRuleCatalog.RiskRule rule = RiskRuleCatalog.ruleByFlag(flag).orElse(null);
            if (rule == null || rule.level().ordinal() <= highest.ordinal()) {
                continue;
            }
            highest = rule.level();
            copy = rule.copy();
        }
        if (highest == HealthRiskLevel.NORMAL) {
            return new RiskDecision(HealthRiskLevel.NORMAL, matchedFlags, null);
        }
        return new RiskDecision(highest, matchedFlags, copy);
    }

    /**
     * 档案维度风险评估（34 号，候选前 Guard）：
     * 未满 18 岁一律 BLOCK_PLAN；65 岁以上生成具体训练计划 BLOCK_PLAN，
     * 非训练计划只给 ADVISORY（当前唯一周计划为包含训练的综合计划，见 44 号票）。
     */
    public RiskDecision assessProfile(int age, boolean trainingPlan) {
        if (age < 18) {
            return new RiskDecision(HealthRiskLevel.BLOCK_PLAN, List.of("UNDERAGE"), UNDERAGE_COPY);
        }
        if (age >= 65) {
            if (trainingPlan) {
                return new RiskDecision(HealthRiskLevel.BLOCK_PLAN, List.of("SENIOR_PLAN"), SENIOR_TRAINING_COPY);
            }
            return new RiskDecision(HealthRiskLevel.ADVISORY, List.of("SENIOR_PLAN"), ADVISORY_COPY);
        }
        return new RiskDecision(HealthRiskLevel.NORMAL, List.of(), null);
    }
}
