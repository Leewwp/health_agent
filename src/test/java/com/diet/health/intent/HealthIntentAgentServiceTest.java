package com.diet.health.intent;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.TestSupport;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 健康意图识别：固定夹具下的合法/降级场景。
 */
class HealthIntentAgentServiceTest {

    private HealthIntentAgentService service(AgentInvoker invoker) {
        AgentContractModule module = new AgentContractModule(invoker, new LlmJsonService(new ObjectMapper()), mock(AgentTraceService.class));
        HealthSlotDictionary dictionary = new HealthSlotDictionary(TestSupport.slotOptionService());
        return new HealthIntentAgentService(module, new PromptLoader(), dictionary, new IntentRuleService(dictionary),
                "qwen-turbo", "v1", 1000);
    }

    @Test
    void 夹具合法输出解析为健身意图() {
        HealthIntentAgentService svc = service(new FixtureAgentInvoker());
        HealthIntentResult result = svc.recognize("想练胸", Map.of(), List.of());
        assertFalse(result.degraded());
        assertEquals(HealthDomain.EXERCISE, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
        assertEquals(List.of("胸"), result.slots().get("bodyParts"));
    }

    @Test
    void 夹具饮食场景解析出餐次槽位() {
        HealthIntentAgentService svc = service(new FixtureAgentInvoker());
        HealthIntentResult result = svc.recognize("午餐想吃清淡的", Map.of(), List.of());
        assertEquals(HealthDomain.MEAL, result.domain());
        assertEquals(List.of("午餐"), result.slots().get("mealTime"));
        assertEquals(List.of("清淡"), result.slots().get("healthGoal"));
    }

    @Test
    void 夹具无信息输入返回空槽位() {
        HealthIntentAgentService svc = service(new FixtureAgentInvoker());
        HealthIntentResult result = svc.recognize("帮我推荐", Map.of(), List.of());
        assertEquals(HealthDomain.MEAL, result.domain());
        assertTrue(result.slots().isEmpty());
    }

    @Test
    void 未知枚举导致关键词降级() {
        AgentInvoker badEnum = invokerReturning("{\"domain\":\"SPORTS\",\"task\":\"RECOMMEND\",\"riskFlags\":[],\"slots\":{},\"preferenceSignals\":[],\"confidence\":0.9}");
        HealthIntentResult result = service(badEnum).recognize("想练胸", Map.of(), List.of());
        assertTrue(result.degraded());
        assertEquals(HealthDomain.EXERCISE, result.domain());
    }

    @Test
    void 非法槽位值被过滤并标记降级() {
        AgentInvoker illegalSlot = invokerReturning("{\"domain\":\"EXERCISE\",\"task\":\"RECOMMEND\",\"riskFlags\":[],\"slots\":{\"bodyParts\":[\"外星部位\"]},\"preferenceSignals\":[],\"confidence\":0.9}");
        HealthIntentResult result = service(illegalSlot).recognize("想练胸", Map.of(), List.of());
        assertTrue(result.degraded());
        assertEquals(HealthDomain.EXERCISE, result.domain());
        assertTrue(result.slots().isEmpty());
    }

    @Test
    void 非法JSON触发关键词降级() {
        HealthIntentResult result = service(invokerReturning("不是JSON")).recognize("中午吃什么", Map.of(), List.of());
        assertTrue(result.degraded());
        assertEquals(HealthDomain.MEAL, result.domain());
    }

    @Test
    void 结构化作息槽位接受时间格式() {
        AgentInvoker routineInvoker = invokerReturning("{\"domain\":\"ROUTINE\",\"task\":\"RECOMMEND\",\"riskFlags\":[],\"slots\":{\"wakeTime\":[\"07:00\"]},\"preferenceSignals\":[],\"confidence\":0.9}");
        HealthIntentResult result = service(routineInvoker).recognize("几点起合适", Map.of(), List.of());
        assertFalse(result.degraded());
        assertEquals(List.of("07:00"), result.slots().get("wakeTime"));
    }

    private AgentInvoker invokerReturning(String text) {
        return new AgentInvoker() {
            @Override
            public AgentInvocationResult invoke(AgentInvocation invocation) {
                return new AgentInvocationResult(text, "qwen-turbo", 1);
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
    }
}
