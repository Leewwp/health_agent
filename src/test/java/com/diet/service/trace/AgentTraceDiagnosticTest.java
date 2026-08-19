package com.diet.service.trace;

import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Trace 工作台摘要合同：状态、降级、嵌套 payload、token 状态和 stepOrder 时间线。 */
class AgentTraceDiagnosticTest {

    private AgentTraceMapper mapper;
    private AgentTraceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentTraceMapper.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AgentTraceService(mapper, objectMapper);
    }

    @Test
    void 成功请求也能显示独立降级诊断和有序时间线() throws Exception {
        String traceId = "trace_" + "x".repeat(180);
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(traceId);
        row.setSessionId("session_" + "y".repeat(180));
        row.setStatus("SUCCESS");
        row.setDurationMs(240L);
        row.setEventCount(4);
        row.setTraceJson(objectMapper.writeValueAsString(Map.of(
                "traceId", traceId,
                "status", "SUCCESS",
                "events", List.of(
                        event(4, "PLAN_GUARD", "GUARD", null, null, 3L, Map.of("decision", "PASS")),
                        event(2, "AGENT_CALL", "AGENT", "PlanAgent", "qwen-turbo", 80L,
                                "{\"parseStatus\":\"DEGRADED\",\"fallbackReason\":\"INVALID_JSON\",\"result\":{\"nested\":true}}"),
                        event(1, "REQUEST_RECEIVED", "HTTP", null, null, null, Map.of("message", "ok")),
                        event(3, "AGENT_CALL", "AGENT", "PlanAgent", "qwen-turbo", 12L,
                                "{\"parseStatus\":\"PARSED\",\"result\":{\"schedule\":[1]}}")
                )
        )));
        when(mapper.findByTraceId(1L, traceId)).thenReturn(row);

        RequestTraceRow decorated = service.findByTraceId(1L, traceId);

        assertNotNull(decorated);
        assertEquals("SUCCESS", decorated.getStatus());
        assertEquals("DEGRADED", decorated.getDiagnosticStatus());
        assertEquals(92L, decorated.getAgentDurationMs());
        assertEquals(2, decorated.getAgentCallCount());
        assertEquals(1, decorated.getDegradedCount());
        assertEquals("NOT_PROVIDED", decorated.getTokenStatus());
        assertEquals(List.of("qwen-turbo"), decorated.getModelNames());
        assertEquals(List.of("DEGRADED", "PARSED"), decorated.getParseStatuses());
        assertEquals(List.of("INVALID_JSON"), decorated.getFallbackReasons());
        assertEquals(List.of("PASS"), decorated.getGuardResults());
        assertEquals(List.of(1, 2, 3, 4), decorated.getTimeline().stream()
                .map(RequestTraceRow.TraceTimelineEvent::stepOrder).toList());
        assertEquals("DEGRADED", decorated.getTimeline().get(1).result());
        assertEquals(traceId, decorated.getTraceId());
    }

    private Map<String, Object> event(int stepOrder, String eventType, String phase, String agentName,
                                      String modelName, Long latencyMs, Object outputPayload) {
        return Map.ofEntries(
                Map.entry("stepOrder", stepOrder),
                Map.entry("eventType", eventType),
                Map.entry("phase", phase),
                Map.entry("agentName", agentName == null ? "" : agentName),
                Map.entry("modelName", modelName == null ? "" : modelName),
                Map.entry("latencyMs", latencyMs == null ? 0L : latencyMs),
                Map.entry("outputPayload", outputPayload instanceof String ? outputPayload : toJson(outputPayload))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
