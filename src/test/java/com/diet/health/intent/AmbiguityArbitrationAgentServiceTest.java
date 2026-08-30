package com.diet.health.intent;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker.AgentInvocation;
import com.diet.agent.invoker.AgentInvoker.AgentInvocationResult;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0018「确定性路由与歧义仲裁」（票据 05）：
 * 受限候选枚举与置信度校验；非法输出/低置信/非法组合返回空（由编排器澄清）；
 * 任务与领域映射不改变规则槽位抽取。
 */
class AmbiguityArbitrationAgentServiceTest {

    private AmbiguityArbitrationAgentService service(String fixtureJson) {
        ObjectMapper objectMapper = new ObjectMapper();
        FixtureAgentInvoker invoker = new FixtureAgentInvoker(invocation ->
                "AmbiguityArbitrationAgent".equals(invocation.agentRole()) ? fixtureJson : null,
                FixtureAgentInvoker.FIXTURE_VERSION);
        AgentContractModule contract = new AgentContractModule(invoker, new LlmJsonService(objectMapper),
                new AgentTraceService(org.mockito.Mockito.mock(com.diet.mapper.AgentTraceMapper.class), objectMapper));
        return new AmbiguityArbitrationAgentService(contract, new PromptLoader(), objectMapper,
                "qwen-turbo", 1000);
    }

    @Test
    void 合法仲裁输出返回受限任务与领域() {
        AmbiguityArbitrationAgentService api = service(
                "{\"task\":\"RECOMMEND\",\"domain\":\"MEAL\",\"confidence\":0.9,\"evidence\":\"想调整推荐内容\"}");
        Optional<AmbiguityArbitrationAgentService.ArbitrationResult> result =
                api.arbitrate("想调整一下", "无会话上下文", Duration.ofMillis(500));
        assertTrue(result.isPresent());
        assertEquals("RECOMMEND", result.get().task());
        assertEquals(HealthDomain.MEAL, result.get().domain());
        assertTrue(result.get().confidence() >= 0.9);
    }

    @Test
    void 候选任务之外的输出被拒绝() {
        AmbiguityArbitrationAgentService api = service(
                "{\"task\":\"DELETE_PLAN\",\"domain\":\"MEAL\",\"confidence\":0.9,\"evidence\":\"越界任务\"}");
        assertFalse(api.arbitrate("任意输入", "无会话上下文", Duration.ofMillis(500)).isPresent(),
                "非受限候选任务必须被拒绝");
    }

    @Test
    void 任务与领域非法组合被拒绝() {
        AmbiguityArbitrationAgentService api = service(
                "{\"task\":\"ROUTINE\",\"domain\":\"MEAL\",\"confidence\":0.9,\"evidence\":\"作息不能挂餐食域\"}");
        assertFalse(api.arbitrate("任意输入", "无会话上下文", Duration.ofMillis(500)).isPresent());
    }

    @Test
    void 低置信度被拒绝() {
        AmbiguityArbitrationAgentService api = service(
                "{\"task\":\"CHAT\",\"domain\":\"OTHER\",\"confidence\":0.4,\"evidence\":\"太模糊\"}");
        assertFalse(api.arbitrate("任意输入", "无会话上下文", Duration.ofMillis(500)).isPresent(),
                "低置信不得进入执行，交由澄清");
    }

    @Test
    void 证据缺失被拒绝() {
        AmbiguityArbitrationAgentService api = service(
                "{\"task\":\"CHAT\",\"domain\":\"OTHER\",\"confidence\":0.9,\"evidence\":\"\"}");
        assertFalse(api.arbitrate("任意输入", "无会话上下文", Duration.ofMillis(500)).isPresent());
    }

    @Test
    void 超时或非法JSON返回空且不抛错() {
        AmbiguityArbitrationAgentService api = service("not-json");
        assertFalse(api.arbitrate("任意输入", "无会话上下文", Duration.ofMillis(500)).isPresent());
    }

    @Test
    void 仲裁结果映射为任务且槽位仍由规则抽取() {
        AmbiguityArbitrationAgentService.ArbitrationResult decision =
                new AmbiguityArbitrationAgentService.ArbitrationResult(
                        "REVISE_PLAN", HealthDomain.EXERCISE, 0.9, "改计划");
        HealthIntentResult intent = AmbiguityArbitrationAgentService.toIntentResult(
                decision, "想练胸", new HealthInputNormalizer());
        assertEquals(HealthDomain.EXERCISE, intent.domain());
        assertEquals(HealthTask.PLAN, intent.task());
        assertFalse(intent.slots().isEmpty(), "槽位由规则归一抽取，不依赖 Agent");
        assertEquals(HealthTask.CHAT, AmbiguityArbitrationAgentService.toIntentResult(
                new AmbiguityArbitrationAgentService.ArbitrationResult(
                        "CHAT", HealthDomain.OTHER, 0.8, "闲聊"), "你好", new HealthInputNormalizer()).task());
    }
}