package com.diet.health.evalv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diet.model.RequestTraceRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trace 事实读取器（契约 §4）：从 diet_request_trace 行 + trace_json events + response_json
 * 提取评估所需的结构化事实。不得解析自然语言文案猜指标；
 * 缺少必需字段时记 {@code MISSING_TRACE_FACT}，对应指标保持 null。
 */
public class TraceFactReader {

    /** 缺失事实标记常量。 */
    public static final String MISSING_TRACE_FACT = "MISSING_TRACE_FACT";

    /** 反馈归因：按 traceId 精确命中（#74）。 */
    public static final String ATTRIBUTION_EXACT_TRACE = "EXACT_TRACE";

    /** 反馈归因：traceId 为空的旧反馈按 session/时间窗口回退命中（#74）。 */
    public static final String ATTRIBUTION_LEGACY_SESSION_FALLBACK = "LEGACY_SESSION_FALLBACK";

    private final ObjectMapper objectMapper;

    public TraceFactReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 读取一条 Trace 的评估事实。 */
    public TraceFacts read(RequestTraceRow row, List<com.diet.model.FeedbackRow> feedbackRows, String attribution) {
        String status = row.getStatus();
        JsonNode root = readTree(row.getTraceJson());
        JsonNode events = root.path("events");

        String domain = null;
        String task = null;
        String riskLevel = null;
        List<String> riskFlags = new ArrayList<>();
        Map<String, List<String>> slots = null;
        String responseTypeFromEvent = null;
        List<String> missingFromEvent = new ArrayList<>();
        List<String> displayFromEvent = new ArrayList<>();
        boolean sawResponseEvent = false;
        List<String> candidateIds = new ArrayList<>();
        boolean intentDegraded = false;
        String intentFallbackReason = null;
        String responseFallbackReason = null;
        String mealMode = null;
        String mealDegradation = null;

        if (events.isArray()) {
            for (JsonNode event : events) {
                String eventType = event.path("eventType").asText(null);
                JsonNode output = parsePayload(event.path("outputPayload"));
                switch (eventType == null ? "" : eventType) {
                    case "INTENT_RECOGNIZED" -> {
                        domain = text(output, "domain", domain);
                        task = text(output, "task", task);
                        if (output.path("degraded").asBoolean(false)) {
                            intentDegraded = true;
                        }
                        String reason = text(output, "fallbackReason", null);
                        if (reason != null) {
                            intentDegraded = true;
                            intentFallbackReason = reason;
                        }
                    }
                    case "RISK_ASSESSED" -> {
                        riskLevel = text(output, "level", riskLevel);
                        output.path("matchedFlags").forEach(flag -> {
                            if (flag.isTextual()) {
                                riskFlags.add(flag.asText());
                            }
                        });
                    }
                    case "SLOTS_MERGED" -> slots = slots(output);
                    case "CANDIDATES_RETRIEVED" -> candidateIds = stringList(output.path("candidateIds"));
                    case "MEAL_RETRIEVED" -> {
                        mealMode = text(output, "mode", mealMode);
                        mealDegradation = text(output, "degradationReason", mealDegradation);
                    }
                    case "RESPONSE_AGENT_RESULT" -> {
                        responseFallbackReason = text(output, "fallbackReason", responseFallbackReason);
                    }
                    case "RESPONSE_READY" -> {
                        sawResponseEvent = true;
                        responseTypeFromEvent = text(output, "responseType", responseTypeFromEvent);
                        missingFromEvent = stringList(output.path("missingSlots"));
                        displayFromEvent = ids(output.path("displayBlocks"), "resourceId");
                    }
                    case "REQUEST_FAILED" -> status = "FAILED";
                    default -> {
                    }
                }
            }
        }

        // 响应事实优先 response_json（契约 §4），缺失时回退 RESPONSE_READY 事件。
        JsonNode response = readTree(row.getResponseJson());
        boolean hasResponseJson = response.hasNonNull("responseType") && response.path("responseType").isTextual();
        String responseType = hasResponseJson ? response.path("responseType").asText() : responseTypeFromEvent;
        List<String> missingSlots = hasResponseJson && response.hasNonNull("missingSlots")
                ? stringList(response.path("missingSlots")) : missingFromEvent;
        List<String> displayIds = hasResponseJson && response.hasNonNull("displayBlocks")
                ? ids(response.path("displayBlocks"), "resourceId") : displayFromEvent;

        // 缺失必需结构化事实标记（契约 §4：缺字段记 MISSING_TRACE_FACT，指标为 null）。
        // 注意：CLARIFY/BLOCKED/引导回复等合法空资源卡响应不是缺失事实——只有 response_json 与
        // RESPONSE_READY 事件都不存在时才记 RESPONSE_READY 缺失。
        Set<String> missingFacts = new LinkedHashSet<>();
        if (domain == null && task == null) {
            missingFacts.add("INTENT_RECOGNIZED");
        }
        if (riskLevel == null) {
            missingFacts.add("RISK_ASSESSED");
        }
        boolean blocked = "BLOCKED".equalsIgnoreCase(responseType);
        if (slots == null && !blocked) {
            missingFacts.add("SLOTS_MERGED");
        }
        if (!hasResponseJson && !sawResponseEvent) {
            missingFacts.add("RESPONSE_READY");
        }
        if (!displayIds.isEmpty() && candidateIds.isEmpty()) {
            missingFacts.add("CANDIDATES_RETRIEVED");
        }
        if (status == null) {
            missingFacts.add("TRACE_STATUS");
        }

        return new TraceFacts(
                domain, task, riskLevel, riskFlags,
                slots == null ? Map.of() : slots,
                responseType, missingSlots, displayIds, candidateIds,
                status, row.getDurationMs(),
                intentDegraded, intentFallbackReason, responseFallbackReason,
                mealMode, mealDegradation,
                missingFacts, feedbackRows, attribution
        );
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asText(fallback);
    }

    private Map<String, List<String>> slots(JsonNode node) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.properties().forEach(entry -> {
            List<String> values = stringList(entry.getValue());
            if (!values.isEmpty()) {
                result.put(entry.getKey(), values);
            }
        });
        return result;
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText().trim());
                }
            });
        }
        return result;
    }

    private List<String> ids(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                if (item.isObject() && item.path(field).isTextual()) {
                    result.add(item.path(field).asText());
                }
            });
        }
        return result;
    }

    private JsonNode parse(String json) {
        return readTree(json);
    }

    /** 事件 payload 两种形态兼容：真实链路序列化为 JSON 字符串；对象形态（测试/防御）直接使用。 */
    private JsonNode parsePayload(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (node.isTextual()) {
            return readTree(node.asText());
        }
        return node;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
