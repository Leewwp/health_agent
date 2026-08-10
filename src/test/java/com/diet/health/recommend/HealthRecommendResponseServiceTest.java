package com.diet.health.recommend;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.health.module.HealthResource;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 推荐解释服务：只解释候选，越界/失败立即模板降级。 */
class HealthRecommendResponseServiceTest {

    private final List<HealthResource> candidates = List.of(
            new HealthResource("EXERCISE", "9001", "俯卧撑", "DATASET", "Gym visual", null, true,
                    Map.of("bodyParts", List.of("胸"))),
            new HealthResource("EXERCISE", "9002", "深蹲", "DATASET", "Gym visual", null, true,
                    Map.of("bodyParts", List.of("腿")))
    );

    private HealthRecommendResponseService service(AgentInvoker invoker) {
        AgentContractModule module = new AgentContractModule(invoker, new LlmJsonService(new ObjectMapper()), mock(AgentTraceService.class));
        return new HealthRecommendResponseService(module, new PromptLoader(), "qwen-max", "v1", 1000);
    }

    @Test
    void 夹具回显候选ID并生成解释() {
        HealthRecommendResponseService.RecommendOutcome outcome =
                service(new FixtureAgentInvoker()).respond(HealthDomain.EXERCISE, "想练胸", candidates);
        assertNull(outcome.fallbackReason());
        assertTrue(outcome.reasons().containsKey("9001"));
        assertTrue(outcome.reasons().containsKey("9002"));
        assertNotNull(outcome.speechText());
    }

    @Test
    void 候选越界立即模板降级() {
        AgentInvoker violating = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult(
                        "{\"speechText\":\"推荐\",\"reasons\":[{\"resourceId\":9999,\"reason\":\"越界\"}]}", "qwen-max", 3);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        HealthRecommendResponseService.RecommendOutcome outcome =
                service(violating).respond(HealthDomain.EXERCISE, "想练胸", candidates);
        assertEquals("CANDIDATE_VIOLATION: resourceId 不在候选内: 9999", outcome.fallbackReason());
        assertTrue(outcome.speechText().contains("俯卧撑"));
        assertTrue(outcome.reasons().containsKey("9001"));
    }

    @Test
    void 非法JSON模板降级() {
        AgentInvoker badJson = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult("坏掉了{{{", "qwen-max", 3);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        HealthRecommendResponseService.RecommendOutcome outcome =
                service(badJson).respond(HealthDomain.EXERCISE, "想练胸", candidates);
        assertEquals("INVALID_JSON: 输出不是合法 JSON", outcome.fallbackReason());
        assertTrue(outcome.speechText().contains("俯卧撑"));
    }

    @Test
    void 空reasons按Schema降级() {
        AgentInvoker emptyReasons = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult("{\"speechText\":\"推荐\",\"reasons\":[]}", "qwen-max", 3);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        HealthRecommendResponseService.RecommendOutcome outcome =
                service(emptyReasons).respond(HealthDomain.EXERCISE, "想练胸", candidates);
        assertEquals("SCHEMA_VIOLATION: reasons 为空", outcome.fallbackReason());
        assertTrue(outcome.speechText().contains("深蹲"));
    }
}
