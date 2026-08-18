package com.diet.agent.contract;

import com.diet.agent.invoker.AgentInvocationException;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.AgentTimeoutException;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 契约模块固定场景：合法输出、非法 JSON、Schema 越界、候选越界、超时、上游失败、缺配置。
 */
class AgentContractModuleTest {

    private AgentInvoker invoker;
    private AgentContractModule module;

    /** 解析为字符串的简单契约。 */
    private record StringOutcome(String value) {
    }

    @BeforeEach
    void setUp() {
        invoker = mock(AgentInvoker.class);
        module = new AgentContractModule(invoker, new LlmJsonService(new ObjectMapper()), mock(AgentTraceService.class));
    }

    private AgentContractModule.AgentContractRequest<StringOutcome> request() {
        return new AgentContractModule.AgentContractRequest<>(
                "TestAgent", AgentInvoker.ModelRole.LIGHT, "qwen-turbo", "v1", "test-v1", "prompt",
                Duration.ofSeconds(1),
                root -> new StringOutcome(root.path("value").asText(null)),
                null, null);
    }

    @Test
    void 合法输出返回解析结果() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentInvoker.AgentInvocationResult("{\"value\":\"ok\"}", "qwen-turbo", 10));
        var result = module.call(request());
        assertTrue(result.parsed());
        assertEquals("ok", result.value().value());
        assertNull(result.fallbackReason());
    }

    @Test
    void 非法JSON返回INVALID_JSON降级() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentInvoker.AgentInvocationResult("这不是JSON{{{", "qwen-turbo", 5));
        var result = module.call(request());
        assertFalse(result.parsed());
        assertEquals(AgentFailureType.INVALID_JSON, result.failureType());
        assertEquals("INVALID_JSON: 输出不是合法 JSON", result.fallbackReason());
    }

    @Test
    void schema越界返回SCHEMA_VIOLATION() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentInvoker.AgentInvocationResult("{\"value\":null}", "qwen-turbo", 5));
        AgentContractModule.AgentContractRequest<StringOutcome> strict = new AgentContractModule.AgentContractRequest<>(
                "TestAgent", AgentInvoker.ModelRole.LIGHT, "qwen-turbo", "v1", "test-v1", "prompt", Duration.ofSeconds(1),
                root -> {
                    if (root.path("value").asText(null) == null) {
                        throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "value 缺失");
                    }
                    return new StringOutcome(root.path("value").asText());
                },
                null, null);
        var result = module.call(strict);
        assertEquals(AgentFailureType.SCHEMA_VIOLATION, result.failureType());
    }

    @Test
    void 候选越界返回CANDIDATE_VIOLATION() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentInvoker.AgentInvocationResult("{\"value\":\"999\"}", "qwen-turbo", 5));
        AgentContractModule.AgentContractRequest<StringOutcome> whitelist = new AgentContractModule.AgentContractRequest<>(
                "TestAgent", AgentInvoker.ModelRole.LIGHT, "qwen-turbo", "v1", "test-v1", "prompt", Duration.ofSeconds(1),
                root -> new StringOutcome(root.path("value").asText()),
                List.of("1", "2"),
                outcome -> List.of(outcome.value()));
        var result = module.call(whitelist);
        assertEquals(AgentFailureType.CANDIDATE_VIOLATION, result.failureType());
    }

    @Test
    void 超时返回TIMEOUT() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentTimeoutException("超时", null));
        var result = module.call(request());
        assertEquals(AgentFailureType.TIMEOUT, result.failureType());
    }

    @Test
    void 上游失败返回UPSTREAM_UNAVAILABLE() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(true);
        org.mockito.Mockito.when(invoker.invoke(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentInvocationException("上游不可用", null));
        var result = module.call(request());
        assertEquals(AgentFailureType.UPSTREAM_UNAVAILABLE, result.failureType());
    }

    @Test
    void 未配置返回MISSING_CONFIG且不调用() {
        org.mockito.Mockito.when(invoker.configured()).thenReturn(false);
        var result = module.call(request());
        assertEquals(AgentFailureType.MISSING_CONFIG, result.failureType());
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.never()).invoke(org.mockito.ArgumentMatchers.any());
    }
}
