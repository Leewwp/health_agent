package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.ProfileRiskCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 风险规则：关键词与意图信号 → 等级与固定文案。 */
class HealthRiskRuleServiceTest {

    private final HealthRiskRuleService rules = new HealthRiskRuleService();

    @Test
    void 普通输入为NORMAL() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("中午吃什么", List.of());
        assertEquals(HealthRiskLevel.NORMAL, decision.level());
        assertFalse(decision.blocked());
    }

    @Test
    void 孕产关键词直接BLOCK_PLAN() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("我怀孕了，怎么安排饮食", List.of());
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.blocked());
        assertTrue(decision.matchedFlags().contains("PREGNANCY"));
        assertEquals(HealthRiskRuleService.BLOCK_PLAN_COPY, decision.copy());
    }

    @Test
    void 意图风险信号参与评估() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("帮我推荐一下", List.of("ACUTE_SYMPTOMS"));
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
    }

    @Test
    void 多规则取最高等级() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("65岁老人胸痛", List.of());
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("SENIOR"));
        assertTrue(decision.matchedFlags().contains("ACUTE_SYMPTOMS"));
    }

    @Test
    void 老年关键词为ADVISORY() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("帮我安排65岁健身计划", List.of());
        assertEquals(HealthRiskLevel.ADVISORY, decision.level());
        assertEquals(HealthRiskRuleService.ADVISORY_COPY, decision.copy());
    }

    @Test
    void 未成年人被拦截() {
        HealthRiskRuleService.RiskDecision decision = rules.assess("未满18岁怎么减脂", List.of());
        assertTrue(decision.blocked());
    }

    // ---- 34 号：档案维度风险（候选前 Guard） ----

    @Test
    void 档案未满18岁BLOCK_PLAN() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(17, true, List.of());
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("UNDERAGE"));
        assertEquals(HealthRiskRuleService.UNDERAGE_COPY, decision.copy());
    }

    @Test
    void 档案65岁以上生成训练计划BLOCK_PLAN() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(70, true, List.of());
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("SENIOR_PLAN"));
        assertEquals(HealthRiskRuleService.SENIOR_TRAINING_COPY, decision.copy());
    }

    @Test
    void 档案65岁以上不含训练计划仅ADVISORY() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(70, false, List.of());
        assertEquals(HealthRiskLevel.ADVISORY, decision.level());
        assertTrue(decision.matchedFlags().contains("SENIOR_PLAN"));
    }

    @Test
    void 档案成年一般用户NORMAL() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(30, true, List.of());
        assertEquals(HealthRiskLevel.NORMAL, decision.level());
        assertFalse(decision.blocked());
    }

    // ---- 62 号票：档案结构化风险条件 ----

    @Test
    void 每个结构化风险条件均为BLOCK_PLAN() {
        for (ProfileRiskCondition condition : ProfileRiskCondition.values()) {
            HealthRiskRuleService.RiskDecision decision = rules.assessProfile(30, true, List.of(condition));
            assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level(),
                    "条件 " + condition + " 必须阻断具体计划");
            assertTrue(decision.blocked());
            assertEquals(HealthRiskRuleService.BLOCK_PLAN_COPY, decision.copy());
            assertTrue(decision.matchedFlags().contains(condition.name()),
                    "命中 flag 必须与条件对应，实际 " + decision.matchedFlags());
        }
    }

    @Test
    void 缺省风险条件为NORMAL() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(30, true, null);
        assertEquals(HealthRiskLevel.NORMAL, decision.level());
        assertFalse(decision.blocked());
        assertTrue(decision.matchedFlags().isEmpty());
    }

    @Test
    void 多条件与年龄评估取最高等级() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(70, false,
                List.of(ProfileRiskCondition.CHRONIC_CONDITION));
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level(),
                "65+ 非训练为 ADVISORY，但结构化慢病条件必须覆盖为 BLOCK_PLAN");
        assertTrue(decision.matchedFlags().contains("SENIOR_PLAN"));
        assertTrue(decision.matchedFlags().contains("CHRONIC_CONDITION"));
    }

    @Test
    void 未满18岁仍优先BLOCK_PLAN() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(17, true,
                List.of(ProfileRiskCondition.CHRONIC_CONDITION));
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("UNDERAGE"));
        assertEquals(HealthRiskRuleService.UNDERAGE_COPY, decision.copy());
    }
}
