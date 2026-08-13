package com.diet.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评估报告（#74 增加归因计数）：
 * exactAttributionCount = 反馈经 trace_id 精确归因（EXACT_TRACE）的 trace 数；
 * legacyFallbackCount = 旧反馈经 session/时间窗口回退归因（LEGACY_SESSION_FALLBACK）的 trace 数；
 * 两者之和可能小于 totalTraces（无反馈的 trace 不归因，明细标记为 null）。
 */
public record EvaluationReport(
        LocalDateTime startAt,
        LocalDateTime endAt,
        int totalTraces,
        int labeledTraces,
        Double avgScore,
        Map<String, Double> metricAverages,
        int exactAttributionCount,
        int legacyFallbackCount,
        List<TraceEvaluationResult> traceResults
) {
}
