package com.diet.health.clarify;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class HealthClarifyAgentServiceTest {

    @Test
    void 澄清只使用确定性缺失字段模板且不调用模型() {
        AtomicInteger calls = new AtomicInteger();
        AgentInvoker invoker = new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                calls.incrementAndGet();
                return new AgentInvocationResult("不应使用的模型文案", "unused", 1);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
        AgentContractModule contract = new AgentContractModule(invoker,
                new LlmJsonService(new ObjectMapper()), mock(AgentTraceService.class));
        HealthClarifyRuleService rules = new HealthClarifyRuleService();
        HealthClarifyAgentService service = new HealthClarifyAgentService(
                contract, new PromptLoader(), rules, "unused", "v1", 1000);

        String question = service.wording(HealthDomain.EXERCISE, "推荐健身动作",
                List.of("bodyParts"), Map.of());

        assertEquals("你今天想练哪个部位？", question);
        assertEquals(0, calls.get());
    }
}
