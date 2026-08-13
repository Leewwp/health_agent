package com.diet.health.evalv2;

import com.diet.model.FeedbackRow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单条 Trace 的评估事实快照（契约 §4）：只读取结构化字段，
 * 不解析自然语言文案猜指标；缺少必需结构化字段时记 MISSING_TRACE_FACT。
 * <p>
 * 事实来源：INTENT_RECOGNIZED / RISK_ASSESSED / SLOTS_MERGED /
 * CANDIDATES_RETRIEVED / MEAL_RETRIEVED / RESPONSE_AGENT_RESULT / RESPONSE_READY，
 * responseType/missingSlots/displayBlocks 优先 Trace 行 response_json；
 * 失败与总延迟来自 Trace 行 status/duration_ms；用户反馈来自 recommend_feedback.trace_id。
 */
public record TraceFacts(
        String domain,
        String task,
        String riskLevel,
        List<String> riskMatchedFlags,
        Map<String, List<String>> slots,
        String responseType,
        List<String> missingSlots,
        List<String> displayIds,
        List<String> candidateIds,
        String status,
        Long durationMs,
        boolean intentDegraded,
        String intentFallbackReason,
        String responseFallbackReason,
        String mealRetrievalMode,
        String mealRetrievalDegradationReason,
        Set<String> missingTraceFacts,
        List<FeedbackRow> feedbackRows,
        String feedbackAttribution
) {

    public TraceFacts {
        riskMatchedFlags = riskMatchedFlags == null ? List.of() : List.copyOf(riskMatchedFlags);
        slots = slots == null ? Map.of() : slots;
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        displayIds = displayIds == null ? List.of() : List.copyOf(displayIds);
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        missingTraceFacts = missingTraceFacts == null ? Set.of() : Set.copyOf(missingTraceFacts);
        feedbackRows = feedbackRows == null ? List.of() : List.copyOf(feedbackRows);
    }

    /** 是否请求失败（Trace 行 status=FAILED 或出现 REQUEST_FAILED 事件）。 */
    public boolean failed() {
        return "FAILED".equalsIgnoreCase(status);
    }

    /** 是否产生了资源卡片（候选引用合规与候选读取的分母条件）。 */
    public boolean producedResourceCards() {
        return displayIds != null && !displayIds.isEmpty();
    }
}
