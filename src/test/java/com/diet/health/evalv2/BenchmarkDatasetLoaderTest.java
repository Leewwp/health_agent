package com.diet.health.evalv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BENCHMARK JSONL 样本完整性校验（#73 契约 §2/§5）：
 * 36 条、caseType 分层（饮食 12/健身 8/作息 6/计划与综合 6/风险阻断 4）、
 * 必填字段齐全、caseId+turnIndex 关联唯一、单标注者两遍复核标记（labeledAt/reviewedAt/REVIEWED）。
 */
class BenchmarkDatasetLoaderTest {

    /** 与 runner 默认路径一致（data/eval/health-eval-v2-benchmark.jsonl）。 */
    private static final String BENCHMARK = "data/eval/health-eval-v2-benchmark.jsonl";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BenchmarkDatasetLoader loader = new BenchmarkDatasetLoader(objectMapper);

    private List<BenchmarkCase> load() throws Exception {
        return loader.load(Path.of(BENCHMARK));
    }

    @Test
    void 共36条且caseType分层数量符合契约() throws Exception {
        List<BenchmarkCase> cases = load();
        assertEquals(36, cases.size(), "样本总数必须为 36");
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        cases.forEach(sample -> counts.merge(sample.caseType(), 1, Integer::sum));
        assertEquals(12, counts.get("MEAL"), "饮食 12 条");
        assertEquals(8, counts.get("EXERCISE"), "健身 8 条");
        assertEquals(6, counts.get("ROUTINE"), "作息 6 条");
        assertEquals(6, counts.get("PLAN_VALIDATION") + counts.get("COMPOSITE"), "计划/综合 6 条");
        assertEquals(4, counts.get("PLAN_VALIDATION"), "其中计划校验 4 条");
        assertEquals(4, counts.get("RISK_BLOCK"), "风险阻断 4 条");
    }

    @Test
    void 全部样本带必填字段与两遍复核标记() throws Exception {
        List<BenchmarkCase> cases = load();
        for (BenchmarkCase sample : cases) {
            assertEquals("health-eval-v2-benchmark", sample.datasetId());
            assertEquals("1.0.0", sample.datasetVersion());
            assertTrue(sample.caseId() != null && !sample.caseId().isBlank(), "caseId 必填");
            assertTrue(sample.turnIndex() >= 1, "turnIndex >= 1");
            assertTrue(sample.input() != null && !sample.input().isBlank(), "input 必填");
            assertNotNull(sample.initialContext(), "initialContext 必填（可为空对象）");
            assertEquals("REVIEWED", sample.reviewStatus(), "reviewStatus=REVIEWED");
            assertNotNull(sample.labeledAt(), "labeledAt 必填");
            assertNotNull(sample.reviewedAt(), "reviewedAt 必填");
            assertNull(sample.excludedReason(), "首版 36 条不应有排除样本");
        }
    }

    @Test
    void caseId加turnIndex关联唯一() throws Exception {
        List<BenchmarkCase> cases = load();
        Map<String, Integer> keys = new java.util.LinkedHashMap<>();
        cases.forEach(sample -> keys.merge(sample.caseId() + "#" + sample.turnIndex(), 1, Integer::sum));
        assertTrue(keys.values().stream().allMatch(count -> count == 1), "caseId+turnIndex 必须唯一");
        assertTrue(cases.stream().filter(sample -> sample.caseId().equals("MEAL-C1")).count() == 2,
                "MEAL-C1 为两轮多轮样本（turnIndex 1/2 关联）");
    }

    @Test
    void 聊天样本gold带完整expectedHealth结构() throws Exception {
        List<BenchmarkCase> cases = load();
        cases.stream().filter(sample -> !sample.caseType().equals("PLAN_VALIDATION")).forEach(sample -> {
            ExpectedHealth gold = sample.gold();
            assertNotNull(gold, "聊天样本必须有 gold.expectedHealth");
            assertEquals("health-eval-v2", gold.schemaVersion());
            assertNotNull(gold.expectedDomain(), "expectedDomain 必填");
            assertNotNull(gold.expectedTask(), "expectedTask 必填");
            assertNotNull(gold.expectedRiskLevel(), "expectedRiskLevel 必填");
            assertNotNull(gold.expectedResponseType(), "expectedResponseType 必填");
            assertNotNull(gold.expectedMissingSlots(), "expectedMissingSlots 必填");
        });
    }

    @Test
    void 计划样本gold带planValidation且planInput可解析() throws Exception {
        List<BenchmarkCase> cases = load();
        cases.stream().filter(sample -> sample.caseType().equals("PLAN_VALIDATION")).forEach(sample -> {
            assertNull(sample.gold(), "计划样本不要求 expectedHealth");
            assertNotNull(sample.planGold(), "计划样本必须有 gold.planValidation");
            assertNotNull(sample.planGold().expectedLevel(), "expectedLevel 必填");
            assertNotNull(sample.planGold().expectedRuleCodes(), "expectedRuleCodes 必填");
            assertNotNull(sample.planInput(), "计划样本必须有 planInput");
            assertTrue(!sample.planInput().items().isEmpty(), "planInput.items 非空");
        });
    }

    @Test
    void 风险阻断样本覆盖BLOCK_PLAN与ADVISORY两级() throws Exception {
        List<BenchmarkCase> cases = load();
        List<BenchmarkCase> risk = cases.stream().filter(sample -> sample.caseType().equals("RISK_BLOCK")).toList();
        assertEquals(3, risk.stream().filter(sample -> "BLOCK_PLAN".equals(sample.gold().expectedRiskLevel())).count(),
                "3 条 BLOCK_PLAN（BLOCK_PLAN Recall 分母）");
        assertEquals(1, risk.stream().filter(sample -> "ADVISORY".equals(sample.gold().expectedRiskLevel())).count(),
                "1 条 ADVISORY（三级风险覆盖）");
    }

    @Test
    void 缺失样本数或分层不符时解析失败() throws Exception {
        Path temp = Files.createTempFile("empty-evalv2", ".jsonl");
        try {
            assertThrows(IllegalArgumentException.class, () -> loader.load(temp),
                    "空输入必须被样本数校验拒绝");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
