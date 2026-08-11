package com.diet.service.trace;

import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Trace 持久化边界脱敏测试（M5 #53）：
 * 常见凭证（API key、Bearer token、Cookie、授权头）不会进入落库的 traceJson / responseJson / errorMessage。
 */
class AgentTraceRedactionTest {

    private static final String API_KEY = "sk-abcdef1234567890abcdef1234567890";
    private static final String BEARER = "eyJhbGciOiJIUzI1NiJ9.abc.def";
    private static final String SECRET = "my-session-secret-xyz";

    private AgentTraceMapper mapper;
    private AgentTraceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentTraceMapper.class);
        service = new AgentTraceService(mapper, new ObjectMapper());
    }

    private RequestTraceRow flush() {
        try (AgentTraceService.TraceScope scope = service.openTrace("trace_redact_test", "session-1", 1L, "req-1")) {
            scope.setResponse(Map.of("reply", "这是回复 Bearer " + BEARER));
        }
        ArgumentCaptor<RequestTraceRow> captor = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void 事件payload中的凭证不落库() {
        try (AgentTraceService.TraceScope scope = service.openTrace("trace_redact_payload", "session-1", 1L, null)) {
            service.recordEvent("REQUEST_RECEIVED", "HTTP",
                    Map.of("message", "帮我看看这个 key " + API_KEY),
                    Map.of("headers", "Authorization: Bearer " + BEARER));
            service.recordAgentEvent("AGENT_CALL", "AGENT", "intent", "qwen-max",
                    Map.of("promptText", "MCP_API_TOKEN=" + SECRET), Map.of("result", "ok"), 12L);
        }
        RequestTraceRow row = captureInserted();
        assertNotNull(row.getTraceJson());
        assertFalse(row.getTraceJson().contains(API_KEY), "API key 不应进入 traceJson");
        assertFalse(row.getTraceJson().contains(BEARER), "Bearer token 不应进入 traceJson");
        assertFalse(row.getTraceJson().contains(SECRET), "MCP token 不应进入 traceJson");
        assertTrue(row.getTraceJson().contains("[REDACTED]"), "脱敏占位符应存在");
    }

    @Test
    void responseJson中的凭证不落库() {
        RequestTraceRow row = flush();
        assertNotNull(row.getResponseJson());
        assertFalse(row.getResponseJson().contains(BEARER), "Bearer token 不应进入 responseJson");
        assertTrue(row.getResponseJson().contains("[REDACTED]"), "脱敏占位符应存在");
        assertTrue(row.getResponseJson().contains("这是回复"), "正常业务内容应保留");
    }

    @Test
    void 失败摘要中的凭证不落库() {
        try (AgentTraceService.TraceScope scope = service.openTrace("trace_redact_error", "session-1", 1L, null)) {
            service.recordError("REQUEST_FAILED", "HTTP", Map.of("message", "ok"),
                    new RuntimeException("调用失败: Bearer " + BEARER));
        }
        RequestTraceRow row = captureInserted();
        assertEquals("FAILED", row.getStatus());
        assertNotNull(row.getErrorMessage());
        assertFalse(row.getErrorMessage().contains(BEARER), "错误摘要不应包含 Bearer token");
        assertTrue(row.getErrorMessage().contains("[REDACTED]"), "错误摘要应含脱敏占位符");
    }

    @Test
    void 非敏感事件内容原样保留() {
        try (AgentTraceService.TraceScope scope = service.openTrace("trace_redact_plain", "session-1", 1L, null)) {
            service.recordEvent("INTENT_RECOGNIZED", "INTENT",
                    "晚餐想吃清淡点的鱼", Map.of("domain", "MEAL", "task", "RECOMMEND"));
        }
        RequestTraceRow row = captureInserted();
        assertTrue(row.getTraceJson().contains("晚餐想吃清淡点的鱼"), "非敏感内容应保留");
        assertTrue(row.getTraceJson().contains("domain"), "非敏感结构应保留");
        assertTrue(!row.getTraceJson().contains("[REDACTED]"), "无敏感内容时不应出现脱敏占位符");
    }

    private RequestTraceRow captureInserted() {
        ArgumentCaptor<RequestTraceRow> captor = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
