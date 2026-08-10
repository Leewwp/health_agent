package com.diet.health.risk;

import com.diet.health.enums.HealthRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险规则目录（44 号票）：唯一事实来源测试。
 * 关键词 → flag → 等级 → 文案同一目录承载，三个消费方（正式评估/意图降级/fixture）
 * 不允许各自复制语义，本测试锚定目录内容防漂移。
 */
class RiskRuleCatalogTest {

    @Test
    void 规则版本与文案固定() {
        assertEquals("2026-08-10-v1", RiskRuleCatalog.RULES_VERSION);
        assertFalse(RiskRuleCatalog.BLOCK_PLAN_COPY.isBlank());
        assertFalse(RiskRuleCatalog.ADVISORY_COPY.isBlank());
        assertFalse(RiskRuleCatalog.UNDERAGE_COPY.isBlank());
        assertFalse(RiskRuleCatalog.SENIOR_TRAINING_COPY.isBlank());
    }

    @Test
    void 七条规则覆盖产品边界() {
        List<String> flags = RiskRuleCatalog.supportedFlags();
        assertEquals(List.of("PREGNANCY", "UNDERAGE", "ACUTE_SYMPTOMS", "TREATMENT",
                "EATING_DISORDER", "CHRONIC_CONDITION", "SENIOR"), flags);
        assertEquals(HealthRiskLevel.BLOCK_PLAN, RiskRuleCatalog.ruleByFlag("PREGNANCY").orElseThrow().level());
        assertEquals(HealthRiskLevel.ADVISORY, RiskRuleCatalog.ruleByFlag("SENIOR").orElseThrow().level());
    }

    @Test
    void 文本命中返回全部去重flag() {
        List<String> matched = RiskRuleCatalog.matchedFlags("65岁老人胸痛");
        assertTrue(matched.contains("SENIOR"));
        assertTrue(matched.contains("ACUTE_SYMPTOMS"));
    }

    @Test
    void 首次命中返回声明顺序规则() {
        assertEquals("PREGNANCY", RiskRuleCatalog.firstMatch("我怀孕了").orElseThrow().flag());
        assertEquals("UNDERAGE", RiskRuleCatalog.firstMatch("未满18岁").orElseThrow().flag());
        assertTrue(RiskRuleCatalog.firstMatch("中午吃什么").isEmpty());
    }

    @Test
    void 关键词映射与目录规则一致() {
        var mapping = RiskRuleCatalog.keywordToFlag();
        assertEquals("SENIOR", mapping.get("65岁"));
        assertEquals("PREGNANCY", mapping.get("孕妇"));
        assertEquals("CHRONIC_CONDITION", mapping.get("高血压"));
        for (RiskRuleCatalog.RiskRule rule : RiskRuleCatalog.rules()) {
            for (String keyword : rule.keywords()) {
                assertEquals(rule.flag(), mapping.get(keyword), "关键词 " + keyword + " 映射必须与目录一致");
            }
        }
    }
}
