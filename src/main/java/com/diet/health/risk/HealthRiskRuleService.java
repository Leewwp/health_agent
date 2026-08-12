package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.ProfileRiskCondition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    /** 档案结构化风险规则版本（62 号票，与 RiskRuleCatalog 同一事实来源）。 */
    public static final String PROFILE_RULES_VERSION = RiskRuleCatalog.PROFILE_RULES_VERSION;

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
     * 档案维度风险评估（34 号，候选前 Guard；62 号票补齐结构化风险条件）：
     * 未满 18 岁一律 BLOCK_PLAN；65 岁以上生成具体训练计划 BLOCK_PLAN，
     * 非训练计划只给 ADVISORY（当前唯一周计划为包含训练的综合计划，见 44 号票）；
     * 档案结构化风险条件（孕产/当前伤病/术后康复/进食障碍/需医疗干预慢病）各为 BLOCK_PLAN，
     * 与年龄评估取最高等级；条件缺省（未填写）不误判为有风险；
     * 未在规则目录登记的条件属内部错误，fail-closed（不静默跳过安全规则）。
     */
    public RiskDecision assessProfile(int age, boolean trainingPlan, List<ProfileRiskCondition> conditions) {
        List<String> matchedFlags = new ArrayList<>();
        if (age < 18) {
            return new RiskDecision(HealthRiskLevel.BLOCK_PLAN, List.of("UNDERAGE"), UNDERAGE_COPY);
        }
        HealthRiskLevel highest = HealthRiskLevel.NORMAL;
        String copy = null;
        if (age >= 65) {
            matchedFlags.add("SENIOR_PLAN");
            highest = trainingPlan ? HealthRiskLevel.BLOCK_PLAN : HealthRiskLevel.ADVISORY;
            copy = trainingPlan ? SENIOR_TRAINING_COPY : ADVISORY_COPY;
        }
        if (conditions != null) {
            for (ProfileRiskCondition condition : conditions) {
                RiskRuleCatalog.RiskRule rule = RiskRuleCatalog.ruleByCondition(condition)
                        .orElseThrow(() -> new IllegalStateException("档案风险条件未在目录登记: " + condition));
                if (rule.level().ordinal() > highest.ordinal()) {
                    highest = rule.level();
                    copy = rule.copy();
                }
                if (!matchedFlags.contains(rule.flag())) {
                    matchedFlags.add(rule.flag());
                }
            }
        }
        if (highest == HealthRiskLevel.NORMAL) {
            return new RiskDecision(HealthRiskLevel.NORMAL, List.of(), null);
        }
        return new RiskDecision(highest, List.copyOf(matchedFlags), copy);
    }
}
