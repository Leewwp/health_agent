package com.diet.health.evalv2;

import com.diet.health.evalv2.HealthEvaluationEngine.PlanInput;
import com.diet.health.evalv2.HealthEvaluationEngine.PlanOutcome;
import com.diet.health.evalv2.HealthEvaluationEngine.TurnInput;
import com.diet.health.evalv2.HealthEvalReport.CaseDetail;
import com.diet.health.evalv2.HealthEvalReport.EnvironmentInfo;
import com.diet.health.evalv2.HealthEvalReport.Metric;
import com.diet.model.FeedbackRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估内核手算期望值（#73 契约 §6 最小验收）：
 * 餐食正常推荐、健身风险阻断（BLOCK_PLAN Recall=1）、正确澄清及 missing slots、
 * 无 HARD_ERROR 计划校验、精确 trace ADOPT 进 adoption 分母、无 trace 旧反馈只进 legacyFallbackCount；
 * 另覆盖域/任务/槽位/风险/澄清/候选合规/fallback/延迟/反馈与 null 分母语义。
 */
class HealthEvaluationEngineTest {

    private static final EnvironmentInfo ENV = new EnvironmentInfo(
            "FIXTURE_SEED", "2026-08-10-v1", "2026-08-10-v1",
            "rules-v1", "profile-v1", "plan-v1", "m", "l", null, null, null, null);

    private final HealthEvaluationEngine engine = new HealthEvaluationEngine(
            new HealthEvalReport.DatasetInfo("health-eval-v2-benchmark", "1.0.0", 0, "benchmark.jsonl"));

    // ---------- 工具 ----------

    private BenchmarkCase sample(String caseId, String caseType, ExpectedHealth gold) {
        return new BenchmarkCase("health-eval-v2-benchmark", "1.0.0", caseId, 1, caseType,
                "输入", Map.of(), gold, null, null, null, null, null, "REVIEWED");
    }

    private BenchmarkCase excludedSample(String caseId) {
        return new BenchmarkCase("health-eval-v2-benchmark", "1.0.0", caseId, 1, "MEAL",
                "输入", Map.of(), null, null, null, "AMBIGUOUS_INPUT", null, null, "REVIEWED");
    }

    private ExpectedHealth gold(String domain, String task, String risk, String responseType,
                               Map<String, List<String>> slots, List<String> missing) {
        return new ExpectedHealth("health-eval-v2", domain, task, slots, risk, responseType, missing);
    }

    private TraceFacts facts(String domain, String task, String risk, Map<String, List<String>> slots,
                             String responseType, List<String> missing, List<String> display,
                             List<String> candidates, String status, Long durationMs,
                             String intentReason, String responseReason) {
        return new TraceFacts(domain, task, risk, List.of(), slots, responseType, missing, display, candidates,
                status, durationMs, intentReason != null, intentReason, responseReason,
                null, null, Set.of(), List.of(), null);
    }

    private FeedbackRow feedback(String action, String traceId) {
        FeedbackRow row = new FeedbackRow();
        row.setTraceId(traceId);
        row.setAction(action);
        return row;
    }

    private HealthEvalReport aggregate(List<TurnInput> turns, List<PlanInput> plans) {
        return engine.aggregate(turns, plans, ENV, "DETERMINISTIC_FIXTURE", "abc123",
                "2026-08-13T00:00:00", "plan-v1", List.of());
    }

    // ---------- 手算验收 #1：餐食正常推荐（domain/task/slot/candidate） ----------

    @Test
    void 餐食正常推荐全部指标命中() {
        BenchmarkCase sample = sample("MEAL-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")), List.of()));
        TraceFacts facts = facts("MEAL", "RECOMMEND", "NORMAL",
                Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")),
                "ANSWER", List.of(), List.of("M7", "M8", "M9"), List.of("M5", "M7", "M8", "M9"),
                "SUCCESS", 120L, null, null);

        HealthEvalReport report = aggregate(List.of(new TurnInput(sample, facts)), List.of());

        assertEquals(1.0, value(report.metrics().domainAccuracy()));
        assertEquals(1.0, value(report.metrics().taskAccuracy()));
        assertEquals(1.0, value(report.metrics().domainTaskExactMatch()));
        assertEquals(1.0, value(report.metrics().slotExactMatch()));
        assertEquals(1.0, value(report.metrics().slotMicro().precision()));
        assertEquals(1.0, value(report.metrics().slotMicro().recall()));
        assertEquals(1.0, value(report.metrics().candidateCitationCompliance()));
        assertEquals(1, report.metrics().candidateCitationCompliance().denominator());
        assertEquals("NONE", report.cases().get(0).fallbackCategory());
    }

    // ---------- 手算验收 #2：健身风险阻断 BLOCK_PLAN Recall=1 ----------

    @Test
    void 健身风险阻断BLOCK_PLAN召回率等于一() {
        BenchmarkCase block1 = sample("RISK-01", "RISK_BLOCK", gold("MEAL", "RECOMMEND", "BLOCK_PLAN", "BLOCKED",
                Map.of(), List.of()));
        BenchmarkCase block2 = sample("RISK-02", "RISK_BLOCK", gold("EXERCISE", "RECOMMEND", "BLOCK_PLAN", "BLOCKED",
                Map.of(), List.of()));
        BenchmarkCase block3 = sample("RISK-03", "RISK_BLOCK", gold("MEAL", "RECOMMEND", "BLOCK_PLAN", "BLOCKED",
                Map.of(), List.of()));
        BenchmarkCase normal = sample("EX-01", "EXERCISE", gold("EXERCISE", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("bodyParts", List.of("胸")), List.of()));

        List<TurnInput> turns = List.of(
                new TurnInput(block1, facts("MEAL", "RECOMMEND", "BLOCK_PLAN", Map.of(), "BLOCKED", List.of(), List.of(), List.of(), "SUCCESS", 50L, null, null)),
                new TurnInput(block2, facts("EXERCISE", "RECOMMEND", "BLOCK_PLAN", Map.of(), "BLOCKED", List.of(), List.of(), List.of(), "SUCCESS", 60L, null, null)),
                new TurnInput(block3, facts("MEAL", "RECOMMEND", "BLOCK_PLAN", Map.of(), "BLOCKED", List.of(), List.of(), List.of(), "SUCCESS", 70L, null, null)),
                new TurnInput(normal, facts("EXERCISE", "RECOMMEND", "NORMAL", Map.of("bodyParts", List.of("胸")), "ANSWER", List.of(), List.of("9001"), List.of("9001"), "SUCCESS", 80L, null, null))
        );

        HealthEvalReport report = aggregate(turns, List.of());

        assertEquals(1.0, value(report.metrics().risk().blockPlanRecall()), "BLOCK_PLAN Recall 必须为 1");
        assertEquals(3, report.metrics().risk().blockPlanRecall().denominator());
        assertEquals(4, report.metrics().risk().accuracy().denominator());
        assertEquals(1.0, value(report.metrics().risk().accuracy()));
        assertEquals(3, report.metrics().risk().confusionMatrix().get("BLOCK_PLAN").get("BLOCK_PLAN"),
                "混淆矩阵对角：3 个 BLOCK_PLAN 全部预测正确");
        assertEquals(1.0, value(report.metrics().risk().byLevel().get("BLOCK_PLAN").recall()));
    }

    // ---------- 手算验收 #3：正确澄清及 missing slots ----------

    @Test
    void 澄清决策与missingSlotF1手算值() {
        BenchmarkCase correct = sample("MEAL-C1#1", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "CLARIFY",
                Map.of("mealTime", List.of("晚餐")), List.of("healthGoal")));
        BenchmarkCase wrong = sample("MEAL-10", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "CLARIFY",
                Map.of(), List.of("mealTime")));

        List<TurnInput> turns = List.of(
                new TurnInput(correct, facts("MEAL", "RECOMMEND", "NORMAL",
                        Map.of("mealTime", List.of("晚餐")), "CLARIFY", List.of("healthGoal"),
                        List.of(), List.of(), "SUCCESS", 30L, null, null)),
                new TurnInput(wrong, facts("MEAL", "RECOMMEND", "NORMAL",
                        Map.of(), "CLARIFY", List.of("healthGoal"),
                        List.of(), List.of(), "SUCCESS", 30L, null, null))
        );

        HealthEvalReport report = aggregate(turns, List.of());

        assertEquals(1.0, value(report.metrics().clarify().clarifyDecisionAccuracy()),
                "两条 gold 与预测都是 CLARIFY → 2/2 命中");
        // missingSlotF1：correct 命中 1 条（healthGoal）；wrong gold={mealTime} pred={healthGoal} 全 miss → TP=1/FP=1/FN=1 → F1=0.5
        assertEquals(0.5, value(report.metrics().clarify().missingSlotF1()), 0.0001);
        assertEquals(2, report.metrics().clarify().clarifyDecisionAccuracy().denominator());
        assertEquals(List.of("healthGoal"), report.cases().get(0).predictedMissingSlots());
        assertEquals(Boolean.TRUE, report.cases().get(0).clarifyDecisionMatch());
    }

    @Test
    void BLOCKED样本不进澄清分母() {
        BenchmarkCase blocked = sample("RISK-01", "RISK_BLOCK", gold("MEAL", "RECOMMEND", "BLOCK_PLAN", "BLOCKED",
                Map.of(), List.of()));
        HealthEvalReport report = aggregate(List.of(new TurnInput(blocked,
                facts("MEAL", "RECOMMEND", "BLOCK_PLAN", Map.of(), "BLOCKED", List.of(), List.of(), List.of(), "SUCCESS", 10L, null, null))), List.of());

        assertNull(report.metrics().clarify().clarifyDecisionAccuracy().value(),
                "BLOCKED gold 不进澄清分母 → 无有效分母为 null");
        assertEquals(0, report.metrics().clarify().clarifyDecisionAccuracy().denominator());
        assertNull(report.metrics().clarify().missingSlotF1(), "无澄清样本 → missingSlotF1 null");
    }

    private BenchmarkCase planSample(String caseId, String expectedLevel, List<String> expectedRuleCodes) {
        return new BenchmarkCase("health-eval-v2-benchmark", "1.0.0", caseId, 1, "PLAN_VALIDATION",
                "输入", Map.of(), null, new BenchmarkCase.PlanGold(expectedLevel, expectedRuleCodes),
                null, null, null, null, "REVIEWED");
    }

    // ---------- 手算验收 #4：无 HARD_ERROR 计划校验 ----------

    @Test
    void 计划校验通过率与硬错误计数() {
        PlanInput ok = new PlanInput(planSample("PLAN-01", "OK", List.of()),
                new PlanOutcome("OK", List.of(), List.of()));
        PlanInput hard = new PlanInput(planSample("PLAN-02", "HARD_ERROR", List.of("UNDERAGE")),
                new PlanOutcome("HARD_ERROR", List.of("UNDERAGE"), List.of("UNDERAGE")));
        PlanInput warning = new PlanInput(planSample("PLAN-04", "WARNING", List.of("ENERGY_OUT_OF_RANGE")),
                new PlanOutcome("WARNING", List.of("ENERGY_OUT_OF_RANGE"), List.of()));

        HealthEvalReport report = aggregate(List.of(), List.of(ok, hard, warning));

        assertEquals(1.0 / 3.0, value(report.metrics().planValidation().passRate()), 0.0001,
                "只有 PLAN-01 无 HARD_ERROR 通过");
        assertEquals(1, report.metrics().planValidation().hardErrorCountByRule().get("UNDERAGE"));
        assertTrue(!report.metrics().planValidation().hardErrorCountByRule().containsKey("ENERGY_OUT_OF_RANGE"),
                "WARNING 不是硬错误");
        assertEquals("plan-v1", report.metrics().planValidation().rulesVersion());
        assertEquals("OK", report.cases().get(0).predictedPlanLevel());
        assertEquals(Boolean.TRUE, report.cases().get(0).planLevelMatch());
    }

    // ---------- 手算验收 #5：精确 trace ADOPT 进 adoption 分母 ----------

    @Test
    void 精确trace的ADOPT进入采纳率分母() {
        BenchmarkCase sample = sample("MEAL-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of(), List.of()));
        TraceFacts adopt = new TraceFacts("MEAL", "RECOMMEND", "NORMAL", List.of(), Map.of(),
                "ANSWER", List.of(), List.of("M1"), List.of("M1"),
                "SUCCESS", 10L, false, null, null, null, null, Set.of(),
                List.of(feedback("ADOPT", "trace-a")), TraceFactReader.ATTRIBUTION_EXACT_TRACE);
        TraceFacts like = new TraceFacts("MEAL", "RECOMMEND", "NORMAL", List.of(), Map.of(),
                "ANSWER", List.of(), List.of("M2"), List.of("M2"),
                "SUCCESS", 10L, false, null, null, null, null, Set.of(),
                List.of(feedback("LIKE", "trace-b")), TraceFactReader.ATTRIBUTION_EXACT_TRACE);

        HealthEvalReport report = aggregate(List.of(
                new TurnInput(sample, adopt), new TurnInput(sample, like)), List.of());

        assertEquals(0.5, value(report.metrics().feedback().adoptionRate()), "ADOPT / (ADOPT+LIKE) = 1/2");
        assertEquals(1.0, value(report.metrics().feedback().positiveRate()), "(ADOPT+LIKE)/2 = 1");
        assertEquals(2, report.metrics().feedback().adoptionRate().denominator());
        assertEquals(2, report.metrics().feedback().exactAttributionCount());
    }

    @Test
    void FAVORITE不进满意度分母() {
        BenchmarkCase sample = sample("MEAL-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of(), List.of()));
        TraceFacts facts = new TraceFacts("MEAL", "RECOMMEND", "NORMAL", List.of(), Map.of(),
                "ANSWER", List.of(), List.of("M1"), List.of("M1"),
                "SUCCESS", 10L, false, null, null, null, null, Set.of(),
                List.of(feedback("ADOPT", "trace-a"), feedback("FAVORITE", "trace-a")),
                TraceFactReader.ATTRIBUTION_EXACT_TRACE);

        HealthEvalReport report = aggregate(List.of(new TurnInput(sample, facts)), List.of());

        assertEquals(1.0, value(report.metrics().feedback().adoptionRate()),
                "FAVORITE 不进分母 → ADOPT/1 = 1");
        assertEquals(1, report.metrics().feedback().adoptionRate().denominator());
    }

    // ---------- 手算验收 #6：无 trace 旧反馈只进 legacyFallbackCount ----------

    @Test
    void 无trace旧反馈只进legacyFallbackCount() {
        BenchmarkCase sample = sample("LEGACY-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of(), List.of()));
        TraceFacts legacy = new TraceFacts("MEAL", "RECOMMEND", "NORMAL", List.of(), Map.of(),
                "ANSWER", List.of(), List.of("M1"), List.of("M1"),
                "SUCCESS", 10L, false, null, null, null, null, Set.of(),
                List.of(feedback("LIKE", null)), TraceFactReader.ATTRIBUTION_LEGACY_SESSION_FALLBACK);

        HealthEvalReport report = aggregate(List.of(new TurnInput(sample, legacy)), List.of());

        assertNull(report.metrics().feedback().adoptionRate(), "回退归因不进比例分母 → null");
        assertEquals(0, report.metrics().feedback().exactAttributionCount());
        assertEquals(1, report.metrics().feedback().legacyFallbackCount(),
                "无 trace 旧反馈只计入 legacyFallbackCount");
        assertEquals("LEGACY_SESSION_FALLBACK", report.cases().get(0).feedbackAttribution());
    }

    // ---------- 其余指标与分母语义 ----------

    @Test
    void 候选引用合规记录违规ID() {
        BenchmarkCase sample = sample("MEAL-X", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of(), List.of()));
        TraceFacts facts = facts("MEAL", "RECOMMEND", "NORMAL", Map.of(),
                "ANSWER", List.of(), List.of("M1", "X99"), List.of("M1"),
                "SUCCESS", 10L, null, null);

        HealthEvalReport report = aggregate(List.of(new TurnInput(sample, facts)), List.of());

        assertEquals(0.0, value(report.metrics().candidateCitationCompliance()));
        assertEquals(1, report.metrics().candidateCitationCompliance().denominator());
        assertEquals(List.of("X99"), report.metrics().citationViolations().get(0).violatingIds());
        assertEquals(Boolean.FALSE, report.cases().get(0).citationCompliant());
    }

    @Test
    void fallback分类与延迟P50P95max手算() {
        BenchmarkCase sample = sample("MEAL-F", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of(), List.of()));
        List<TurnInput> turns = List.of(
                new TurnInput(sample, facts("MEAL", "RECOMMEND", "NORMAL", Map.of(), "ANSWER", List.of(),
                        List.of("M1"), List.of("M1"), "SUCCESS", 100L, null, null)),
                new TurnInput(sample, facts("MEAL", "RECOMMEND", "NORMAL", Map.of(), "ANSWER", List.of(),
                        List.of("M1"), List.of("M1"), "SUCCESS", 200L, null, null)),
                new TurnInput(sample, facts("MEAL", "RECOMMEND", "NORMAL", Map.of(), "ANSWER", List.of(),
                        List.of("M1"), List.of("M1"), "SUCCESS", 300L, null, null)),
                new TurnInput(sample, facts(null, null, null, Map.of(), "ANSWER", List.of(),
                        List.of(), List.of(), "FAILED", 5000L, "API key 未配置", null))
        );

        HealthEvalReport report = aggregate(turns, List.of());

        assertEquals(3, report.metrics().fallback().distribution().get("NONE"));
        assertEquals(1, report.metrics().fallback().distribution().get("REQUEST_FAILED"),
                "status=FAILED 的主分类为最严重的 REQUEST_FAILED");
        assertEquals(4, report.metrics().fallback().effectiveTotal());
        assertEquals(200.0, report.metrics().latency().normal().p50(), "ceil(0.5*3)-1=1 → 200");
        assertEquals(300.0, report.metrics().latency().normal().p95(), "ceil(0.95*3)-1=2 → 300");
        assertEquals(300.0, report.metrics().latency().normal().max());
        assertEquals(3, report.metrics().latency().normal().count());
        assertEquals(1, report.metrics().latency().requestFailed().count(), "REQUEST_FAILED 延迟独立分组");
        assertEquals(5000.0, report.metrics().latency().requestFailed().max());
    }

    @Test
    void 缺gold或缺事实为null不算0() {
        BenchmarkCase noGold = BenchmarkCase.audit("trace-no-gold", null);
        BenchmarkCase missingFacts = sample("MEAL-M", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("mealTime", List.of("午餐")), List.of()));
        TraceFacts emptyFacts = new TraceFacts(null, null, null, List.of(), Map.of(), "ANSWER", List.of(),
                List.of(), List.of(), "SUCCESS", 10L, false, null, null, null, null,
                Set.of("INTENT_RECOGNIZED", "RISK_ASSESSED", "SLOTS_MERGED"), List.of(), null);

        HealthEvalReport report = aggregate(List.of(
                new TurnInput(noGold, emptyFacts),
                new TurnInput(missingFacts, emptyFacts)), List.of());

        assertEquals(1, report.status().missingGold(), "无 gold 的 TRACE_AUDIT 样本记 NO_GOLD");
        assertEquals(1, report.status().missingTraceFact(), "缺事实样本记 MISSING_TRACE_FACT");
        assertEquals(0, report.metrics().domainAccuracy().denominator(), "无有效样本 → 分母 0");
        assertNull(report.metrics().domainAccuracy().value(), "分母为 0 时指标为 null 不算 0");
        assertNull(report.metrics().risk(), "无 expectedRiskLevel → risk 指标整体 null");
        assertEquals("NO_GOLD", report.cases().get(0).status());
        assertEquals("MISSING_TRACE_FACT", report.cases().get(1).status());
    }

    @Test
    void 排除样本不进分母但保留诊断() {
        BenchmarkCase excluded = excludedSample("MEAL-AMB");
        BenchmarkCase ok = sample("MEAL-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("mealTime", List.of("午餐")), List.of()));
        TraceFacts facts = facts("MEAL", "RECOMMEND", "NORMAL", Map.of("mealTime", List.of("午餐")),
                "ANSWER", List.of(), List.of("M7"), List.of("M7"), "SUCCESS", 10L, null, null);

        HealthEvalReport report = aggregate(List.of(
                new TurnInput(excluded, facts), new TurnInput(ok, facts)), List.of());

        assertEquals(1, report.status().excluded());
        assertEquals("EXCLUDED_AMBIGUOUS", report.cases().get(0).status());
        assertEquals("AMBIGUOUS_INPUT", report.cases().get(0).excludedReason());
        assertEquals(1, report.metrics().domainAccuracy().denominator(), "排除样本不进分母");
        assertEquals(1.0, value(report.metrics().domainAccuracy()));
    }

    @Test
    void 分品类指标按goldDomain分组() {
        BenchmarkCase meal = sample("MEAL-01", "MEAL", gold("MEAL", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("mealTime", List.of("午餐")), List.of()));
        BenchmarkCase exercise = sample("EX-01", "EXERCISE", gold("EXERCISE", "RECOMMEND", "NORMAL", "ANSWER",
                Map.of("bodyParts", List.of("胸")), List.of()));
        TraceFacts mealFacts = facts("MEAL", "RECOMMEND", "NORMAL", Map.of("mealTime", List.of("午餐")),
                "ANSWER", List.of(), List.of("M7"), List.of("M7"), "SUCCESS", 10L, null, null);
        TraceFacts wrongFacts = facts("EXERCISE", "CHAT", "NORMAL", Map.of("bodyParts", List.of("胸")),
                "ANSWER", List.of(), List.of("9001"), List.of("9001"), "SUCCESS", 10L, null, null);

        HealthEvalReport report = aggregate(List.of(
                new TurnInput(meal, mealFacts), new TurnInput(exercise, wrongFacts)), List.of());

        assertEquals(1.0, value(report.byDomain().get("MEAL").domainAccuracy()));
        assertEquals(1.0, value(report.byDomain().get("MEAL").taskAccuracy()));
        assertEquals(1.0, value(report.byDomain().get("MEAL").slotF1()));
        assertEquals(0.0, value(report.byDomain().get("EXERCISE").taskAccuracy()),
                "预测 CHAT ≠ gold RECOMMEND → 健身 task 命中 0");
        assertNotNull(report.byDomain().get("EXERCISE"));
    }

    private Double value(Metric metric) {
        return metric == null ? null : metric.value();
    }
}
