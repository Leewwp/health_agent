package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 计划简报结构化提取 Agent 的输出契约测试。 */
class PlanBriefExtractionAgentServiceTest {

    @Test
    void 只接受候选字段置信度和原文证据() {
        PlanBriefExtractionAgentService service = serviceWith("""
                {"candidateFields":{"trainingDays":["MONDAY","WEDNESDAY"]},"confidence":0.92,"evidence":"周一和周三"}
                """);

        PlanBriefExtractionAgentService.ExtractionResult result = service.extract(
                "周一和周三", PlanBrief.empty(), "trainingDays", Duration.ofSeconds(1));

        assertTrue(result.parsed());
        assertEquals(List.of("MONDAY", "WEDNESDAY"), result.candidateFields().get("trainingDays"));
        assertEquals(0.92, result.confidence());
        assertEquals("周一和周三", result.evidence());
    }

    @Test
    void 越界字段和缺少证据都必须失败() {
        PlanBriefExtractionAgentService service = serviceWith(
                "{\"candidateFields\":{\"unexpected\":[\"value\"]},\"confidence\":0.9,\"evidence\":\"原话\"}");

        PlanBriefExtractionAgentService.ExtractionResult result = service.extract(
                "不确定的一句话", PlanBrief.empty(), "trainingGoal", Duration.ofSeconds(1));

        assertFalse(result.parsed());
        assertTrue(result.failureReason().contains("SCHEMA_VIOLATION"));
    }

    private PlanBriefExtractionAgentService serviceWith(String output) {
        AgentInvoker invoker = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult(output, "fixture-model", 1);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        AgentContractModule contract = new AgentContractModule(invoker,
                new LlmJsonService(new ObjectMapper()), mock(AgentTraceService.class));
        return new PlanBriefExtractionAgentService(contract, new PromptLoader(), new ObjectMapper(),
                "qwen-turbo", "plan-brief-extraction-v1", 1000);
    }
}
