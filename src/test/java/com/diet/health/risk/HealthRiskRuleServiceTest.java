package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
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
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(17, true);
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("UNDERAGE"));
        assertEquals(HealthRiskRuleService.UNDERAGE_COPY, decision.copy());
    }

    @Test
    void 档案65岁以上生成训练计划BLOCK_PLAN() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(70, true);
        assertEquals(HealthRiskLevel.BLOCK_PLAN, decision.level());
        assertTrue(decision.matchedFlags().contains("SENIOR_PLAN"));
        assertEquals(HealthRiskRuleService.SENIOR_TRAINING_COPY, decision.copy());
    }

    @Test
    void 档案65岁以上不含训练计划仅ADVISORY() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(70, false);
        assertEquals(HealthRiskLevel.ADVISORY, decision.level());
        assertTrue(decision.matchedFlags().contains("SENIOR_PLAN"));
    }

    @Test
    void 档案成年一般用户NORMAL() {
        HealthRiskRuleService.RiskDecision decision = rules.assessProfile(30, true);
        assertEquals(HealthRiskLevel.NORMAL, decision.level());
        assertFalse(decision.blocked());
    }
}
