package com.diet.health.evalv2;

import com.diet.health.evalv2.HealthEvaluationEngine.PlanOutcome;
import com.diet.health.plan.PlanValidationService;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRow;
import com.diet.model.RequestTraceRow;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * TRACE_AUDIT 反馈归因（#74 口径，不混算）：先按 trace_id 精确读取反馈（EXACT_TRACE），
 * 只有 traceId 为空的旧 trace 才走 session/时间窗口回退（LEGACY_SESSION_FALLBACK）；
 * 有 traceId 但无匹配反馈的 trace 归因为无反馈，不把同 session 旧反馈伪装成精确命中。
 */
public class AuditFeedbackLoader {

    private final FeedbackMapper feedbackMapper;

    public AuditFeedbackLoader(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 按 trace 逐条归并反馈与归因标记，返回稳定归因键 → 归因结果。 */
    public Map<String, Attribution> load(Long userId, LocalDateTime startAt, LocalDateTime endAt,
                                         List<RequestTraceRow> traces) {
        List<String> traceIds = traces.stream()
                .map(RequestTraceRow::getTraceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, List<FeedbackRow>> byTraceId = traceIds.isEmpty() ? Map.of()
                : feedbackMapper.findByTraceIds(userId, traceIds).stream()
                        .collect(Collectors.groupingBy(FeedbackRow::getTraceId));

        List<String> legacySessions = traces.stream()
                .filter(trace -> !hasText(trace.getTraceId()))
                .map(RequestTraceRow::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, List<FeedbackRow>> bySession = legacySessions.isEmpty() ? Map.of()
                : feedbackMapper.findBySessions(userId, legacySessions, startAt, endAt).stream()
                        .collect(Collectors.groupingBy(FeedbackRow::getSessionId));

        Map<String, Attribution> result = new HashMap<>();
        for (RequestTraceRow trace : traces) {
            String traceId = trace.getTraceId();
            List<FeedbackRow> exact = hasText(traceId) ? byTraceId.getOrDefault(traceId, List.of()) : List.of();
            if (!exact.isEmpty()) {
                result.put(attributionKey(trace), new Attribution(exact, TraceFactReader.ATTRIBUTION_EXACT_TRACE));
            } else if (!hasText(traceId)) {
                List<FeedbackRow> fallback = bySession.getOrDefault(trace.getSessionId(), List.of());
                if (!fallback.isEmpty()) {
                    result.put(attributionKey(trace), new Attribution(fallback, TraceFactReader.ATTRIBUTION_LEGACY_SESSION_FALLBACK));
                } else {
                    result.put(attributionKey(trace), new Attribution(List.of(), null));
                }
            } else {
                result.put(attributionKey(trace), new Attribution(List.of(), null));
            }
        }
        return result;
    }

    /** 单条 trace 的反馈归因结果。 */
    public record Attribution(List<FeedbackRow> feedbacks, String attribution) {
    }

    /** 旧数据的 trace_id 为空，必须用行 id 隔离同一会话中的多条旧 trace；两者皆缺时快速失败，避免静默串扰。 */
    static String attributionKey(RequestTraceRow trace) {
        if (hasText(trace.getTraceId())) {
            return trace.getTraceId();
        }
        if (trace.getId() == null) {
            throw new IllegalStateException("归因键要求非空 trace_id 或行 id，实际两者皆缺：session=" + trace.getSessionId());
        }
        return "legacy-trace-" + trace.getId();
    }

    /** 计划校验结果 → 引擎 PlanOutcome（复用 PlanValidationService，不复制规则）。 */
    public static PlanOutcome toPlanOutcome(PlanValidationService.ValidationResult result) {
        List<String> allCodes = result.hits().stream()
                .map(PlanValidationService.RuleHit::ruleCode)
                .distinct()
                .toList();
        List<String> hardErrorCodes = result.hits().stream()
                .filter(hit -> hit.decision() == com.diet.health.enums.PlanValidationLevel.HARD_ERROR)
                .map(PlanValidationService.RuleHit::ruleCode)
                .distinct()
                .toList();
        return new PlanOutcome(result.level().name(), allCodes, hardErrorCodes);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
