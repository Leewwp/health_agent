package com.diet.health.evalv2;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * health-eval-v2 版本化报告模型（契约 §6）：版本化 JSON 是唯一机器事实来源，
 * Markdown 只做摘要和解释。所有指标带有效分母；缺 gold/结构化事实时 value 为 null，不得算 0。
 */
public record HealthEvalReport(
        String schemaVersion,
        int reportVersion,
        String runAt,
        String runMode,
        String gitCommit,
        DatasetInfo dataset,
        EnvironmentInfo environment,
        StatusInfo status,
        HealthMetrics metrics,
        Map<String, DomainMetrics> byDomain,
        List<CaseDetail> cases,
        List<String> notes
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatasetInfo(String id, String version, int sampleCount, String benchmarkPath) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentInfo(
            String resourceProviderMode,
            String resourceVersion,
            String agentFixtureVersion,
            String rulesVersion,
            String profileRulesVersion,
            String planRulesVersion,
            String mainModel,
            String lightModel,
            String embeddingModel,
            String embeddingModelVersion,
            String vectorStoreProvider,
            String vectorCollection
    ) {
    }

    public record StatusInfo(int total, int reviewed, int excluded, int missingGold, int missingTraceFact) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthMetrics(
            Metric domainAccuracy,
            Metric taskAccuracy,
            Metric domainTaskExactMatch,
            Metric slotExactMatch,
            SlotMicro slotMicro,
            Map<String, Metric> slotF1ByDomain,
            RiskMetrics risk,
            ClarifyMetrics clarify,
            Metric candidateCitationCompliance,
            List<CitationViolation> citationViolations,
            PlanMetrics planValidation,
            FallbackMetrics fallback,
            LatencyMetrics latency,
            FeedbackMetrics feedback
    ) {
    }

    /** 指标值：value=null 表示无有效分母；denominator 恒为有效样本数。 */
    public record Metric(Double value, int numerator, int denominator) {
    }

    public record SlotMicro(Metric precision, Metric recall, Metric f1) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskMetrics(
            Metric accuracy,
            Map<String, ClassMetrics> byLevel,
            Metric blockPlanRecall,
            Map<String, Map<String, Integer>> confusionMatrix
    ) {
    }

    public record ClassMetrics(Metric precision, Metric recall, Metric f1) {
    }

    public record ClarifyMetrics(Metric clarifyDecisionAccuracy, Metric missingSlotF1) {
    }

    public record CitationViolation(String caseId, List<String> violatingIds) {
    }

    public record PlanMetrics(Metric passRate, Map<String, Integer> hardErrorCountByRule, String rulesVersion) {
    }

    public record FallbackMetrics(Map<String, Integer> distribution, int effectiveTotal) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LatencyMetrics(LatencyGroup normal, LatencyGroup requestFailed) {
    }

    public record LatencyGroup(Double p50, Double p95, Double max, int count) {
    }

    public record FeedbackMetrics(
            Metric adoptionRate,
            Metric positiveRate,
            int exactAttributionCount,
            int legacyFallbackCount
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainMetrics(Metric domainAccuracy, Metric taskAccuracy, Metric slotExactMatch, Metric slotF1) {
    }

    /** 单条样本的评估明细（预测/标注/命中/缺失事实/归因）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseDetail(
            String caseId,
            int turnIndex,
            String caseType,
            String status,
            String excludedReason,
            String goldDomain,
            String predictedDomain,
            Boolean domainMatch,
            String goldTask,
            String predictedTask,
            Boolean taskMatch,
            String goldRisk,
            String predictedRisk,
            Boolean riskMatch,
            String goldResponseType,
            String predictedResponseType,
            Map<String, List<String>> goldSlots,
            Map<String, List<String>> predictedSlots,
            Boolean slotExactMatch,
            List<String> goldMissingSlots,
            List<String> predictedMissingSlots,
            Boolean clarifyDecisionMatch,
            List<String> predictedCandidateIds,
            List<String> predictedDisplayIds,
            Boolean citationCompliant,
            String fallbackCategory,
            List<String> fallbackReasons,
            Long durationMs,
            String traceStatus,
            List<String> missingTraceFacts,
            String predictedPlanLevel,
            String goldPlanLevel,
            Boolean planLevelMatch,
            List<String> predictedRuleCodes,
            List<String> goldRuleCodes,
            String feedbackAttribution,
            List<String> feedbackActions
    ) {

        public static CaseDetailBuilder builder() {
            return new CaseDetailBuilder();
        }

        /** CaseDetail 构建器（字段多，用 builder 保证可读性）。 */
        public static final class CaseDetailBuilder {
            private String caseId;
            private int turnIndex;
            private String caseType;
            private String status;
            private String excludedReason;
            private String goldDomain;
            private String predictedDomain;
            private Boolean domainMatch;
            private String goldTask;
            private String predictedTask;
            private Boolean taskMatch;
            private String goldRisk;
            private String predictedRisk;
            private Boolean riskMatch;
            private String goldResponseType;
            private String predictedResponseType;
            private Map<String, List<String>> goldSlots;
            private Map<String, List<String>> predictedSlots;
            private Boolean slotExactMatch;
            private List<String> goldMissingSlots;
            private List<String> predictedMissingSlots;
            private Boolean clarifyDecisionMatch;
            private List<String> predictedCandidateIds;
            private List<String> predictedDisplayIds;
            private Boolean citationCompliant;
            private String fallbackCategory;
            private List<String> fallbackReasons;
            private Long durationMs;
            private String traceStatus;
            private List<String> missingTraceFacts;
            private String predictedPlanLevel;
            private String goldPlanLevel;
            private Boolean planLevelMatch;
            private List<String> predictedRuleCodes;
            private List<String> goldRuleCodes;
            private String feedbackAttribution;
            private List<String> feedbackActions;

            public CaseDetailBuilder caseId(String caseId) { this.caseId = caseId; return this; }
            public CaseDetailBuilder turnIndex(int turnIndex) { this.turnIndex = turnIndex; return this; }
            public CaseDetailBuilder caseType(String caseType) { this.caseType = caseType; return this; }
            public CaseDetailBuilder status(String status) { this.status = status; return this; }
            public CaseDetailBuilder excludedReason(String excludedReason) { this.excludedReason = excludedReason; return this; }
            public CaseDetailBuilder goldDomain(String v) { this.goldDomain = v; return this; }
            public CaseDetailBuilder predictedDomain(String v) { this.predictedDomain = v; return this; }
            public CaseDetailBuilder domainMatch(Boolean v) { this.domainMatch = v; return this; }
            public CaseDetailBuilder goldTask(String v) { this.goldTask = v; return this; }
            public CaseDetailBuilder predictedTask(String v) { this.predictedTask = v; return this; }
            public CaseDetailBuilder taskMatch(Boolean v) { this.taskMatch = v; return this; }
            public CaseDetailBuilder goldRisk(String v) { this.goldRisk = v; return this; }
            public CaseDetailBuilder predictedRisk(String v) { this.predictedRisk = v; return this; }
            public CaseDetailBuilder riskMatch(Boolean v) { this.riskMatch = v; return this; }
            public CaseDetailBuilder goldResponseType(String v) { this.goldResponseType = v; return this; }
            public CaseDetailBuilder predictedResponseType(String v) { this.predictedResponseType = v; return this; }
            public CaseDetailBuilder goldSlots(Map<String, List<String>> v) { this.goldSlots = v; return this; }
            public CaseDetailBuilder predictedSlots(Map<String, List<String>> v) { this.predictedSlots = v; return this; }
            public CaseDetailBuilder slotExactMatch(Boolean v) { this.slotExactMatch = v; return this; }
            public CaseDetailBuilder goldMissingSlots(List<String> v) { this.goldMissingSlots = v; return this; }
            public CaseDetailBuilder predictedMissingSlots(List<String> v) { this.predictedMissingSlots = v; return this; }
            public CaseDetailBuilder clarifyDecisionMatch(Boolean v) { this.clarifyDecisionMatch = v; return this; }
            public CaseDetailBuilder predictedCandidateIds(List<String> v) { this.predictedCandidateIds = v; return this; }
            public CaseDetailBuilder predictedDisplayIds(List<String> v) { this.predictedDisplayIds = v; return this; }
            public CaseDetailBuilder citationCompliant(Boolean v) { this.citationCompliant = v; return this; }
            public CaseDetailBuilder fallbackCategory(String v) { this.fallbackCategory = v; return this; }
            public CaseDetailBuilder fallbackReasons(List<String> v) { this.fallbackReasons = v; return this; }
            public CaseDetailBuilder durationMs(Long v) { this.durationMs = v; return this; }
            public CaseDetailBuilder traceStatus(String v) { this.traceStatus = v; return this; }
            public CaseDetailBuilder missingTraceFacts(List<String> v) { this.missingTraceFacts = v; return this; }
            public CaseDetailBuilder predictedPlanLevel(String v) { this.predictedPlanLevel = v; return this; }
            public CaseDetailBuilder goldPlanLevel(String v) { this.goldPlanLevel = v; return this; }
            public CaseDetailBuilder planLevelMatch(Boolean v) { this.planLevelMatch = v; return this; }
            public CaseDetailBuilder predictedRuleCodes(List<String> v) { this.predictedRuleCodes = v; return this; }
            public CaseDetailBuilder goldRuleCodes(List<String> v) { this.goldRuleCodes = v; return this; }
            public CaseDetailBuilder feedbackAttribution(String v) { this.feedbackAttribution = v; return this; }
            public CaseDetailBuilder feedbackActions(List<String> v) { this.feedbackActions = v; return this; }

            public CaseDetail build() {
                return new CaseDetail(
                        caseId, turnIndex, caseType, status, excludedReason, goldDomain, predictedDomain,
                        domainMatch, goldTask, predictedTask, taskMatch, goldRisk, predictedRisk, riskMatch,
                        goldResponseType, predictedResponseType, goldSlots, predictedSlots, slotExactMatch,
                        goldMissingSlots, predictedMissingSlots, clarifyDecisionMatch, predictedCandidateIds,
                        predictedDisplayIds, citationCompliant, fallbackCategory, fallbackReasons, durationMs,
                        traceStatus, missingTraceFacts, predictedPlanLevel, goldPlanLevel, planLevelMatch,
                        predictedRuleCodes, goldRuleCodes, feedbackAttribution, feedbackActions);
            }
        }
    }
}
