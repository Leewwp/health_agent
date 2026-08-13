package com.diet.health.evalv2;

import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace 事实读取器（#73 契约 §4）：
 * INTENT_RECOGNIZED/RISK_ASSESSED/SLOTS_MERGED/CANDIDATES_RETRIEVED/MEAL_RETRIEVED/
 * RESPONSE_AGENT_RESULT/RESPONSE_READY 事件 + response_json 优先 + status/duration_ms +
 * 缺字段记 MISSING_TRACE_FACT；不得解析自然语言文案猜指标。
 */
class TraceFactReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TraceFactReader reader;

    @BeforeEach
    void setUp() {
        reader = new TraceFactReader(objectMapper);
    }

    private RequestTraceRow row() {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId("trace-test");
        row.setSessionId("sess-test");
        row.setUserId(1L);
        row.setStatus("SUCCESS");
        row.setDurationMs(123L);
        return row;
    }

    private String eventsJson(Object... events) throws Exception {
        StringBuilder out = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(events[i]);
        }
        out.append("]}");
        return out.toString();
    }

    private String payload(String field, Object value) throws Exception {
        return "{\"outputPayload\":" + objectMapper.writeValueAsString(Map.of(field, value)) + "}";
    }

    @Test
    void 正常推荐trace提取全部事实() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"INTENT_RECOGNIZED\",\"outputPayload\":{\"domain\":\"MEAL\",\"task\":\"RECOMMEND\",\"degraded\":false}}",
                "{\"eventType\":\"RISK_ASSESSED\",\"outputPayload\":{\"level\":\"NORMAL\",\"matchedFlags\":[]}}",
                "{\"eventType\":\"SLOTS_MERGED\",\"outputPayload\":{\"mealTime\":[\"午餐\"],\"healthGoal\":[\"清淡\"]}}",
                "{\"eventType\":\"CANDIDATES_RETRIEVED\",\"outputPayload\":{\"candidateIds\":[\"M7\",\"M8\",\"M9\"]}}",
                "{\"eventType\":\"RESPONSE_AGENT_RESULT\",\"outputPayload\":{\"fallbackReason\":null}}",
                "{\"eventType\":\"RESPONSE_READY\",\"outputPayload\":{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}}"
        ));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":["
                + "{\"resourceType\":\"MEAL\",\"resourceId\":\"M7\",\"name\":\"鸡胸蔬菜沙拉\"}]}");

        TraceFacts facts = reader.read(row, List.of(), null);

        assertEquals("MEAL", facts.domain());
        assertEquals("RECOMMEND", facts.task());
        assertEquals("NORMAL", facts.riskLevel());
        assertEquals(Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")), facts.slots());
        assertEquals("ANSWER", facts.responseType());
        assertEquals(List.of("M7"), facts.displayIds());
        assertEquals(List.of("M7", "M8", "M9"), facts.candidateIds());
        assertEquals("SUCCESS", facts.status());
        assertEquals(123L, facts.durationMs());
        assertFalse(facts.intentDegraded());
        assertNull(facts.responseFallbackReason());
        assertTrue(facts.missingTraceFacts().isEmpty(), "完整 trace 不应有缺失事实");
    }

    @Test
    void responseJson优先于RESPONSE_READY事件() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"RESPONSE_READY\",\"outputPayload\":{\"responseType\":\"CLARIFY\","
                        + "\"missingSlots\":[\"mealTime\"],\"displayBlocks\":[]}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertEquals("ANSWER", facts.responseType(), "response_json 必须覆盖 RESPONSE_READY 事件值");
        assertEquals(List.of(), facts.missingSlots());
    }

    @Test
    void 风险阻断trace不要求SLOTS_MERGED事实() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"INTENT_RECOGNIZED\",\"outputPayload\":{\"domain\":\"MEAL\",\"task\":\"RECOMMEND\",\"degraded\":false}}",
                "{\"eventType\":\"RISK_ASSESSED\",\"outputPayload\":{\"level\":\"BLOCK_PLAN\",\"matchedFlags\":[\"PREGNANCY\"]}}"));
        row.setResponseJson("{\"responseType\":\"BLOCKED\",\"missingSlots\":[],\"displayBlocks\":[]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertEquals("BLOCK_PLAN", facts.riskLevel());
        assertEquals(List.of("PREGNANCY"), facts.riskMatchedFlags());
        assertEquals("BLOCKED", facts.responseType());
        assertTrue(facts.missingTraceFacts().isEmpty(),
                "BLOCKED 不合并槽位是设计行为，不算缺失事实");
    }

    @Test
    void 意图降级标记INTENT_RULE_FALLBACK事实() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"INTENT_RECOGNIZED\",\"outputPayload\":{\"domain\":\"MEAL\",\"task\":\"RECOMMEND\","
                        + "\"degraded\":true,\"fallbackReason\":\"KEYWORD_FALLBACK\"}}",
                "{\"eventType\":\"RISK_ASSESSED\",\"outputPayload\":{\"level\":\"NORMAL\"}}",
                "{\"eventType\":\"SLOTS_MERGED\",\"outputPayload\":{}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertTrue(facts.intentDegraded());
        assertEquals("KEYWORD_FALLBACK", facts.intentFallbackReason());
        assertEquals("INTENT_RULE_FALLBACK", FallbackClassifier.classify(facts).main());
    }

    @Test
    void 缺必需事件时记MISSING_TRACE_FACT() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson("{\"events\":[]}");
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[{\"resourceId\":\"M1\"}]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertTrue(facts.missingTraceFacts().contains("INTENT_RECOGNIZED"));
        assertTrue(facts.missingTraceFacts().contains("RISK_ASSESSED"));
        assertTrue(facts.missingTraceFacts().contains("SLOTS_MERGED"));
        assertTrue(facts.missingTraceFacts().contains("CANDIDATES_RETRIEVED"),
                "产生了资源卡但无候选事件必须记缺失");
    }

    @Test
    void 餐食检索降级原因与响应模板降级被读取() throws Exception {
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"MEAL_RETRIEVED\",\"outputPayload\":{\"mode\":\"STRUCTURED\",\"degradationReason\":\"EMBEDDING_UNAVAILABLE\"}}",
                "{\"eventType\":\"RESPONSE_AGENT_RESULT\",\"outputPayload\":{\"fallbackReason\":\"调用超时\"}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertEquals("STRUCTURED", facts.mealRetrievalMode());
        assertEquals("EMBEDDING_UNAVAILABLE", facts.mealRetrievalDegradationReason());
        assertEquals("调用超时", facts.responseFallbackReason());
        assertEquals("RESPONSE_TEMPLATE_FALLBACK", FallbackClassifier.classify(facts).main(),
                "响应降级优先于餐食检索降级");
    }

    @Test
    void 生产小写降级原因正确映射主分类() throws Exception {
        // 生产链路（HybridMealRetriever/MealModule）写入的是小写原因，必须与分类器大小写无关匹配。
        RequestTraceRow row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"MEAL_RETRIEVED\",\"outputPayload\":{\"mode\":\"STRUCTURED\",\"degradationReason\":\"embedding_unavailable\"}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");

        TraceFacts facts = reader.read(row, List.of(), null);
        assertEquals("embedding_unavailable", facts.mealRetrievalDegradationReason());
        assertEquals("EMBEDDING_UNAVAILABLE", FallbackClassifier.classify(facts).main(),
                "生产小写 embedding_unavailable 必须映射到 EMBEDDING_UNAVAILABLE 主分类");

        row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"MEAL_RETRIEVED\",\"outputPayload\":{\"mode\":\"STRUCTURED\",\"degradationReason\":\"vector_store_unavailable\"}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");
        assertEquals("VECTOR_STORE_UNAVAILABLE", FallbackClassifier.classify(reader.read(row, List.of(), null)).main());

        row = row();
        row.setTraceJson(eventsJson(
                "{\"eventType\":\"MEAL_RETRIEVED\",\"outputPayload\":{\"mode\":\"STRUCTURED\",\"degradationReason\":\"no_vector_hits\"}}"));
        row.setResponseJson("{\"responseType\":\"ANSWER\",\"missingSlots\":[],\"displayBlocks\":[]}");
        assertEquals("NO_VECTOR_HITS", FallbackClassifier.classify(reader.read(row, List.of(), null)).main());
    }

    @Test
    void 失败trace主分类为REQUEST_FAILED() throws Exception {
        RequestTraceRow row = row();
        row.setStatus("FAILED");
        row.setTraceJson("{\"events\":[{\"eventType\":\"REQUEST_FAILED\",\"phase\":\"HTTP\"}]}");
        row.setResponseJson(null);

        TraceFacts facts = reader.read(row, List.of(), null);
        assertTrue(facts.failed());
        assertEquals("REQUEST_FAILED", FallbackClassifier.classify(facts).main());
        assertTrue(facts.missingTraceFacts().contains("INTENT_RECOGNIZED"));
    }
}
