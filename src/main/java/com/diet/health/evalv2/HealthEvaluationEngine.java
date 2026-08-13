package com.diet.health.evalv2;

import com.diet.health.evalv2.ExpectedHealth.SlotFact;
import com.diet.health.evalv2.HealthEvalReport.CaseDetail;
import com.diet.health.evalv2.HealthEvalReport.CaseDetail.CaseDetailBuilder;
import com.diet.health.evalv2.HealthEvalReport.CitationViolation;
import com.diet.health.evalv2.HealthEvalReport.ClassMetrics;
import com.diet.health.evalv2.HealthEvalReport.ClarifyMetrics;
import com.diet.health.evalv2.HealthEvalReport.DatasetInfo;
import com.diet.health.evalv2.HealthEvalReport.DomainMetrics;
import com.diet.health.evalv2.HealthEvalReport.EnvironmentInfo;
import com.diet.health.evalv2.HealthEvalReport.FallbackMetrics;
import com.diet.health.evalv2.HealthEvalReport.FeedbackMetrics;
import com.diet.health.evalv2.HealthEvalReport.HealthMetrics;
import com.diet.health.evalv2.HealthEvalReport.LatencyGroup;
import com.diet.health.evalv2.HealthEvalReport.LatencyMetrics;
import com.diet.health.evalv2.HealthEvalReport.Metric;
import com.diet.health.evalv2.HealthEvalReport.PlanMetrics;
import com.diet.health.evalv2.HealthEvalReport.RiskMetrics;
import com.diet.health.evalv2.HealthEvalReport.SlotMicro;
import com.diet.health.evalv2.HealthEvalReport.StatusInfo;
import com.diet.model.FeedbackRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一健康评估内核（#73）：BENCHMARK/TRACE_AUDIT 共用的确定性指标聚合。
 * <p>
 * 覆盖契约 §3 全部指标：domain/task/domainTaskExactMatch、槽位（exact + 微精确率/召回率/F1，
 * 总分与分品类）、风险三级（accuracy/precision/recall/F1 + 混淆矩阵 + 单列 BLOCK_PLAN Recall）、
 * 澄清决策与 missingSlotF1（BLOCKED 不进澄清分母）、候选引用合规、计划校验通过率与硬错误计数、
 * fallback 互斥主分类分布、延迟 P50/P95/max（正常与 REQUEST_FAILED 分组）、
 * 用户反馈采纳率/正反馈率（仅 #74 精确 trace 归因，FAVORITE/UNFAVORITE 不进满意度）。
 * <p>
 * 所有指标给有效分母：缺 gold 或结构化事实为 null 不算 0。
 */
public class HealthEvaluationEngine {

    /** 计划校验结果包装：level 与规则码（全部 + 仅 HARD_ERROR）。 */
    public record PlanOutcome(String level, List<String> allRuleCodes, List<String> hardErrorRuleCodes) {
    }

    /** 单轮聊天样本 + 对应 Trace 事实。 */
    public record TurnInput(BenchmarkCase sample, TraceFacts facts) {
    }

    /** 计划样本 + PlanValidationService 结果。 */
    public record PlanInput(BenchmarkCase sample, PlanOutcome outcome) {
    }

    /** 满意度口径：FAVORITE/UNFAVORITE 不进入满意度（契约 §3）。 */
    private static final Set<String> SATISFACTION_ACTIONS = Set.of("ADOPT", "LIKE", "DISLIKE");

    private final DatasetInfo datasetInfo;

    public HealthEvaluationEngine(DatasetInfo datasetInfo) {
        this.datasetInfo = datasetInfo;
    }

    /** 聚合完整报告（runAt/gitCommit/runMode 由 runner 提供）。 */
    public HealthEvalReport aggregate(
            List<TurnInput> turns, List<PlanInput> plans, EnvironmentInfo environment,
            String runMode, String gitCommit, String runAt, String planRulesVersion, List<String> notes) {
        List<CaseDetail> cases = new ArrayList<>();
        for (TurnInput turn : turns) {
            cases.add(evaluateTurn(turn));
        }
        for (PlanInput plan : plans) {
            cases.add(evaluatePlan(plan));
        }
        StatusInfo status = status(cases);

        List<TurnInput> evaluatedTurns = turns.stream()
                .filter(turn -> !turn.sample().excluded() && turn.sample().gold() != null)
                .filter(turn -> turn.facts().missingTraceFacts().isEmpty())
                .toList();
        HealthMetrics metrics = metrics(evaluatedTurns, plans, planRulesVersion);
        Map<String, DomainMetrics> byDomain = byDomain(evaluatedTurns);
        return new HealthEvalReport(
                "health-eval-v2", 1, runAt, runMode, gitCommit, datasetInfo, environment, status,
                metrics, byDomain, cases, notes
        );
    }

    // ---------- 单条样本评估 ----------

    private CaseDetail evaluateTurn(TurnInput input) {
        BenchmarkCase sample = input.sample();
        if (sample.excluded()) {
            return base(sample).status("EXCLUDED_AMBIGUOUS").excludedReason(sample.excludedReason()).build();
        }
        ExpectedHealth gold = sample.gold();
        if (gold == null) {
            return base(sample).status("NO_GOLD").build();
        }
        TraceFacts facts = input.facts();
        boolean domainMatch = equal(gold.expectedDomain(), facts.domain());
        boolean taskMatch = equal(gold.expectedTask(), facts.task());
        boolean slotExactMatch = slotMapsEqual(gold.expectedSlots(), facts.slots());
        boolean clarifyGold = "CLARIFY".equalsIgnoreCase(gold.expectedResponseType());
        boolean clarifyPredicted = "CLARIFY".equalsIgnoreCase(facts.responseType());
        Boolean clarifyMatch = "BLOCKED".equalsIgnoreCase(gold.expectedResponseType())
                ? null : clarifyGold == clarifyPredicted;
        boolean citationCompliant = facts.producedResourceCards()
                && new LinkedHashSet<>(facts.candidateIds()).containsAll(facts.displayIds());
        FallbackClassifier.Classification fallback = FallbackClassifier.classify(facts);
        List<String> feedbackActions = facts.feedbackRows() == null ? List.of()
                : facts.feedbackRows().stream().map(FeedbackRow::getAction).toList();

        String caseStatus = facts.missingTraceFacts().isEmpty() ? "EVALUATED" : "MISSING_TRACE_FACT";
        return base(sample)
                .status(caseStatus)
                .goldDomain(gold.expectedDomain()).predictedDomain(facts.domain()).domainMatch(domainMatch)
                .goldTask(gold.expectedTask()).predictedTask(facts.task()).taskMatch(taskMatch)
                .goldRisk(gold.expectedRiskLevel()).predictedRisk(facts.riskLevel())
                .riskMatch(equal(gold.expectedRiskLevel(), facts.riskLevel()))
                .goldResponseType(gold.expectedResponseType()).predictedResponseType(facts.responseType())
                .goldSlots(gold.expectedSlots().isEmpty() ? null : gold.expectedSlots())
                .predictedSlots(facts.slots().isEmpty() ? null : facts.slots())
                .slotExactMatch(gold.expectedSlots() == null ? null : slotExactMatch)
                .goldMissingSlots(gold.expectedMissingSlots().isEmpty() ? null : gold.expectedMissingSlots())
                .predictedMissingSlots(facts.missingSlots().isEmpty() ? null : facts.missingSlots())
                .clarifyDecisionMatch(clarifyMatch)
                .predictedCandidateIds(facts.candidateIds().isEmpty() ? null : facts.candidateIds())
                .predictedDisplayIds(facts.displayIds().isEmpty() ? null : facts.displayIds())
                .citationCompliant(facts.producedResourceCards() ? citationCompliant : null)
                .fallbackCategory(fallback.main())
                .fallbackReasons(fallback.reasons().isEmpty() ? null : fallback.reasons())
                .durationMs(facts.durationMs())
                .traceStatus(facts.status())
                .missingTraceFacts(facts.missingTraceFacts().isEmpty() ? null : new ArrayList<>(facts.missingTraceFacts()))
                .feedbackAttribution(facts.feedbackAttribution())
                .feedbackActions(feedbackActions.isEmpty() ? null : feedbackActions)
                .build();
    }

    private CaseDetail evaluatePlan(PlanInput input) {
        BenchmarkCase sample = input.sample();
        if (sample.excluded()) {
            return base(sample).status("EXCLUDED_AMBIGUOUS").excludedReason(sample.excludedReason()).build();
        }
        BenchmarkCase.PlanGold gold = sample.planGold();
        if (gold == null) {
            return base(sample).status("NO_GOLD").build();
        }
        boolean levelMatch = equal(gold.expectedLevel(), input.outcome().level());
        return base(sample)
                .status("EVALUATED")
                .goldPlanLevel(gold.expectedLevel())
                .predictedPlanLevel(input.outcome().level())
                .planLevelMatch(levelMatch)
                .goldRuleCodes(gold.expectedRuleCodes().isEmpty() ? null : gold.expectedRuleCodes())
                .predictedRuleCodes(input.outcome().allRuleCodes().isEmpty() ? null : input.outcome().allRuleCodes())
                .build();
    }

    private CaseDetailBuilder base(BenchmarkCase sample) {
        return CaseDetail.builder()
                .caseId(sample.caseId())
                .turnIndex(sample.turnIndex())
                .caseType(sample.caseType());
    }

    // ---------- 指标聚合 ----------

    private HealthMetrics metrics(List<TurnInput> turns, List<PlanInput> plans, String planRulesVersion) {
        List<MetricHit> domainHits = new ArrayList<>();
        List<MetricHit> taskHits = new ArrayList<>();
        List<MetricHit> domainTaskHits = new ArrayList<>();
        List<MetricHit> slotExactHits = new ArrayList<>();
        List<SlotFactsCount> slotCounts = new ArrayList<>();
        List<MetricHit> riskHits = new ArrayList<>();
        List<String> riskGold = new ArrayList<>();
        List<String> riskPredicted = new ArrayList<>();
        List<MetricHit> clarifyHits = new ArrayList<>();
        List<SlotFactsCount> missingSlotCounts = new ArrayList<>();
        int candidateDenominator = 0;
        int candidateNumerator = 0;
        List<CitationViolation> violations = new ArrayList<>();
        Map<String, Integer> fallbackCounts = new LinkedHashMap<>();
        List<Long> normalLatency = new ArrayList<>();
        List<Long> failedLatency = new ArrayList<>();
        Map<String, List<SlotFactsCount>> slotCountsByDomain = new LinkedHashMap<>();
        int exactFeedbackCount = 0;
        int legacyFeedbackCount = 0;
        int adoptionNumerator = 0;
        int adoptionDenominator = 0;
        int positiveNumerator = 0;

        for (TurnInput input : turns) {
            BenchmarkCase sample = input.sample();
            ExpectedHealth gold = sample.gold();
            TraceFacts facts = input.facts();
            String domain = gold.expectedDomain();
            boolean domainMatch = equal(domain, facts.domain());
            boolean taskMatch = equal(gold.expectedTask(), facts.task());
            domainHits.add(new MetricHit(domainMatch));
            taskHits.add(new MetricHit(taskMatch));
            domainTaskHits.add(new MetricHit(domainMatch && taskMatch));
            if (gold.expectedSlots() != null) {
                boolean exact = slotMapsEqual(gold.expectedSlots(), facts.slots());
                slotExactHits.add(new MetricHit(exact));
                SlotFactsCount count = slotFacts(gold.expectedSlots(), facts.slots());
                slotCounts.add(count);
                slotCountsByDomain.computeIfAbsent(domain, key -> new ArrayList<>()).add(count);
            }
            if (gold.expectedRiskLevel() != null) {
                riskHits.add(new MetricHit(equal(gold.expectedRiskLevel(), facts.riskLevel())));
                riskGold.add(gold.expectedRiskLevel());
                riskPredicted.add(facts.riskLevel());
            }
            if (gold.expectedResponseType() != null && !"BLOCKED".equalsIgnoreCase(gold.expectedResponseType())) {
                boolean clarifyGold = "CLARIFY".equalsIgnoreCase(gold.expectedResponseType());
                boolean clarifyPredicted = "CLARIFY".equalsIgnoreCase(facts.responseType());
                clarifyHits.add(new MetricHit(clarifyGold == clarifyPredicted));
                if (clarifyGold) {
                    missingSlotCounts.add(missingSlotCounts(gold.expectedMissingSlots(), facts.missingSlots()));
                }
            }
            if (facts.producedResourceCards()) {
                candidateDenominator++;
                boolean compliant = new LinkedHashSet<>(facts.candidateIds()).containsAll(facts.displayIds());
                if (compliant) {
                    candidateNumerator++;
                } else {
                    violations.add(new CitationViolation(sample.caseId(), difference(facts.displayIds(), facts.candidateIds())));
                }
            }
            FallbackClassifier.Classification fallback = FallbackClassifier.classify(facts);
            fallbackCounts.merge(fallback.main(), 1, Integer::sum);
            if (facts.durationMs() != null) {
                (facts.failed() ? failedLatency : normalLatency).add(facts.durationMs());
            }
            if (TraceFactReader.ATTRIBUTION_EXACT_TRACE.equals(facts.feedbackAttribution())) {
                exactFeedbackCount++;
            } else if (TraceFactReader.ATTRIBUTION_LEGACY_SESSION_FALLBACK.equals(facts.feedbackAttribution())) {
                legacyFeedbackCount++;
            }
            if (TraceFactReader.ATTRIBUTION_EXACT_TRACE.equals(facts.feedbackAttribution())) {
                for (FeedbackRow feedback : facts.feedbackRows()) {
                    String action = feedback.getAction() == null ? "" : feedback.getAction().toUpperCase(Locale.ROOT);
                    if (SATISFACTION_ACTIONS.contains(action)) {
                        adoptionDenominator++;
                        if ("LIKE".equals(action) || "ADOPT".equals(action)) {
                            positiveNumerator++;
                        }
                        if ("ADOPT".equals(action)) {
                            adoptionNumerator++;
                        }
                    }
                }
            }
        }

        Map<String, Metric> slotF1ByDomain = new LinkedHashMap<>();
        slotCountsByDomain.forEach((domain, counts) -> slotF1ByDomain.put(domain, f1Metric(counts)));

        return new HealthMetrics(
                metric(domainHits),
                metric(taskHits),
                metric(domainTaskHits),
                metric(slotExactHits),
                slotMicro(slotCounts),
                slotF1ByDomain,
                risk(riskHits, riskGold, riskPredicted),
                new ClarifyMetrics(metric(clarifyHits), f1Metric(missingSlotCounts)),
                candidateDenominator == 0 ? null : metric(candidateNumerator, candidateDenominator),
                violations.isEmpty() ? null : violations,
                plan(plans, planRulesVersion),
                new FallbackMetrics(fallbackCounts, turns.size()),
                latency(normalLatency, failedLatency),
                new FeedbackMetrics(
                        adoptionDenominator == 0 ? null : metric(adoptionNumerator, adoptionDenominator),
                        adoptionDenominator == 0 ? null : metric(positiveNumerator, adoptionDenominator),
                        exactFeedbackCount, legacyFeedbackCount)
        );
    }

    private RiskMetrics risk(List<MetricHit> hits, List<String> gold, List<String> predicted) {
        if (gold.isEmpty()) {
            return null;
        }
        List<String> levels = List.of("NORMAL", "ADVISORY", "BLOCK_PLAN");
        Map<String, ClassMetrics> byLevel = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        for (String goldLevel : levels) {
            Map<String, Integer> row = new LinkedHashMap<>();
            levels.forEach(predictedLevel -> row.put(predictedLevel, 0));
            confusion.put(goldLevel, row);
        }
        for (int i = 0; i < gold.size(); i++) {
            confusion.get(safeLevel(gold.get(i))).merge(safeLevel(predicted.get(i)), 1, Integer::sum);
        }
        for (String level : levels) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            for (int i = 0; i < gold.size(); i++) {
                String g = safeLevel(gold.get(i));
                String p = safeLevel(predicted.get(i));
                if (level.equals(p)) {
                    if (level.equals(g)) {
                        tp++;
                    } else {
                        fp++;
                    }
                } else if (level.equals(g)) {
                    fn++;
                }
            }
            byLevel.put(level, new ClassMetrics(
                    metric(tp, tp + fp), metric(tp, tp + fn), f1Metric(tp, fp, fn)));
        }
        int blockGold = (int) gold.stream().filter("BLOCK_PLAN"::equals).count();
        int blockCorrect = 0;
        for (int i = 0; i < gold.size(); i++) {
            if ("BLOCK_PLAN".equals(gold.get(i)) && "BLOCK_PLAN".equals(predicted.get(i))) {
                blockCorrect++;
            }
        }
        return new RiskMetrics(
                metric(hits), byLevel,
                blockGold == 0 ? null : metric(blockCorrect, blockGold),
                confusion);
    }

    private String safeLevel(String level) {
        return level == null ? "NORMAL" : level;
    }

    private PlanMetrics plan(List<PlanInput> plans, String planRulesVersion) {
        if (plans.isEmpty()) {
            return null;
        }
        int passed = 0;
        Map<String, Integer> hardErrors = new LinkedHashMap<>();
        for (PlanInput input : plans) {
            if ("OK".equals(input.outcome().level())) {
                passed++;
            }
            input.outcome().hardErrorRuleCodes().forEach(code -> hardErrors.merge(code, 1, Integer::sum));
        }
        return new PlanMetrics(metric(passed, plans.size()), hardErrors, planRulesVersion);
    }

    private LatencyMetrics latency(List<Long> normal, List<Long> failed) {
        return new LatencyMetrics(group(normal), group(failed));
    }

    private LatencyGroup group(List<Long> values) {
        if (values.isEmpty()) {
            return new LatencyGroup(null, null, null, 0);
        }
        List<Long> sorted = values.stream().sorted().toList();
        return new LatencyGroup(
                percentile(sorted, 0.5), percentile(sorted, 0.95),
                (double) sorted.get(sorted.size() - 1), sorted.size());
    }

    private Double percentile(List<Long> sorted, double ratio) {
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return round1(sorted.get(index));
    }

    // ---------- 分组 ----------

    private Map<String, DomainMetrics> byDomain(List<TurnInput> turns) {
        Map<String, List<MetricHit>> domainHits = new LinkedHashMap<>();
        Map<String, List<MetricHit>> taskHits = new LinkedHashMap<>();
        Map<String, List<MetricHit>> slotExact = new LinkedHashMap<>();
        Map<String, List<SlotFactsCount>> slotCounts = new LinkedHashMap<>();
        for (TurnInput input : turns) {
            ExpectedHealth gold = input.sample().gold();
            String domain = gold == null ? null : gold.expectedDomain();
            if (domain == null) {
                continue;
            }
            TraceFacts facts = input.facts();
            domainHits.computeIfAbsent(domain, key -> new ArrayList<>())
                    .add(new MetricHit(equal(domain, facts.domain())));
            taskHits.computeIfAbsent(domain, key -> new ArrayList<>())
                    .add(new MetricHit(equal(gold.expectedTask(), facts.task())));
            if (gold.expectedSlots() != null) {
                slotExact.computeIfAbsent(domain, key -> new ArrayList<>())
                        .add(new MetricHit(slotMapsEqual(gold.expectedSlots(), facts.slots())));
                slotCounts.computeIfAbsent(domain, key -> new ArrayList<>())
                        .add(slotFacts(gold.expectedSlots(), facts.slots()));
            }
        }
        Map<String, DomainMetrics> result = new LinkedHashMap<>();
        domainHits.forEach((domain, hits) -> result.put(domain, new DomainMetrics(
                metric(hits), metric(taskHits.getOrDefault(domain, List.of())),
                metric(slotExact.getOrDefault(domain, List.of())),
                f1Metric(slotCounts.getOrDefault(domain, List.of())))));
        return result;
    }

    private StatusInfo status(List<CaseDetail> cases) {
        int excluded = 0;
        int missingTraceFact = 0;
        int missingGold = 0;
        for (CaseDetail detail : cases) {
            switch (detail.status()) {
                case "EXCLUDED_AMBIGUOUS" -> excluded++;
                case "MISSING_TRACE_FACT" -> missingTraceFact++;
                case "NO_GOLD" -> missingGold++;
                default -> {
                }
            }
        }
        int total = datasetInfo == null ? cases.size() : datasetInfo.sampleCount();
        return new StatusInfo(total, total - excluded - missingGold - missingTraceFact,
                excluded, missingGold, missingTraceFact);
    }

    // ---------- 统计辅助 ----------

    private record MetricHit(boolean hit) {
    }

    private record SlotFactsCount(int tp, int fp, int fn) {
    }

    private SlotFactsCount slotFacts(Map<String, List<String>> gold, Map<String, List<String>> predicted) {
        Set<SlotFact> goldSet = new LinkedHashSet<>(facts(gold));
        Set<SlotFact> predictedSet = new LinkedHashSet<>(facts(predicted));
        int tp = 0;
        for (SlotFact fact : predictedSet) {
            if (goldSet.contains(fact)) {
                tp++;
            }
        }
        int fp = predictedSet.size() - tp;
        int fn = 0;
        for (SlotFact fact : goldSet) {
            if (!predictedSet.contains(fact)) {
                fn++;
            }
        }
        return new SlotFactsCount(tp, fp, fn);
    }

    /** 缺失槽位集合的微计数（missingSlotF1 用）：gold 独有记 FN、预测独有记 FP，不重复计入双边。 */
    private SlotFactsCount missingSlotCounts(List<String> gold, List<String> predicted) {
        Set<String> goldSet = new LinkedHashSet<>(gold == null ? List.of() : gold);
        Set<String> predictedSet = new LinkedHashSet<>(predicted == null ? List.of() : predicted);
        int tp = 0;
        for (String slot : goldSet) {
            if (predictedSet.contains(slot)) {
                tp++;
            }
        }
        int fp = predictedSet.size() - tp;
        int fn = goldSet.size() - tp;
        return new SlotFactsCount(tp, fp, fn);
    }

    private List<SlotFact> facts(Map<String, List<String>> slots) {
        List<SlotFact> result = new ArrayList<>();
        if (slots != null) {
            slots.forEach((name, values) -> values.forEach(value -> result.add(new SlotFact(name, value))));
        }
        return result;
    }

    private boolean slotMapsEqual(Map<String, List<String>> gold, Map<String, List<String>> predicted) {
        return normalized(gold).equals(normalized(predicted));
    }

    private Map<String, List<String>> normalized(Map<String, List<String>> slots) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (slots != null) {
            slots.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    result.put(key, values.stream().map(String::trim).sorted().toList());
                }
            });
        }
        return result;
    }

    private Metric metric(List<MetricHit> hits) {
        if (hits.isEmpty()) {
            return new Metric(null, 0, 0);
        }
        int numerator = (int) hits.stream().filter(MetricHit::hit).count();
        return metric(numerator, hits.size());
    }

    private Metric metric(int numerator, int denominator) {
        if (denominator == 0) {
            return new Metric(null, 0, 0);
        }
        return new Metric(round4(numerator / (double) denominator), numerator, denominator);
    }

    private SlotMicro slotMicro(List<SlotFactsCount> counts) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (SlotFactsCount count : counts) {
            tp += count.tp();
            fp += count.fp();
            fn += count.fn();
        }
        return new SlotMicro(
                metric(tp, tp + fp), metric(tp, tp + fn), f1Metric(tp, fp, fn));
    }

    private Metric f1Metric(List<SlotFactsCount> counts) {
        if (counts.isEmpty()) {
            return null;
        }
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (SlotFactsCount count : counts) {
            tp += count.tp();
            fp += count.fp();
            fn += count.fn();
        }
        return f1Metric(tp, fp, fn);
    }

    private Metric f1Metric(int tp, int fp, int fn) {
        int positive = tp + fp;
        int actual = tp + fn;
        if (positive == 0 && actual == 0) {
            return new Metric(null, 0, 0);
        }
        if (positive == 0 || actual == 0) {
            return new Metric(0.0, tp, tp + fp + fn);
        }
        double precision = tp / (double) positive;
        double recall = tp / (double) actual;
        return new Metric(round4(2 * precision * recall / (precision + recall)), tp, tp + fp + fn);
    }

    private double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private boolean equal(String a, String b) {
        return a != null && b != null && a.equals(b);
    }

    private List<String> difference(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(a));
        result.removeAll(new LinkedHashSet<>(b));
        return result;
    }
}
