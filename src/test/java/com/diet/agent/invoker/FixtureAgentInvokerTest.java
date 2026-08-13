package com.diet.agent.invoker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定夹具适配器：三角色场景 + 版本 + 无匹配失败。 */
class FixtureAgentInvokerTest {

    private final FixtureAgentInvoker invoker = new FixtureAgentInvoker();

    private AgentInvoker.AgentInvocation invocation(String role, String prompt) {
        return new AgentInvoker.AgentInvocation(role, "qwen-turbo", prompt, Duration.ofSeconds(1));
    }

    @Test
    void intent夹具覆盖饮食健身作息风险四场景() {
        String meal = invoker.invoke(invocation("IntentAgent", "午餐想吃什么")).text();
        assertTrue(meal.contains("\"MEAL\""));
        String exercise = invoker.invoke(invocation("IntentAgent", "想练胸")).text();
        assertTrue(exercise.contains("\"EXERCISE\""));
        String routine = invoker.invoke(invocation("IntentAgent", "几点睡合适")).text();
        assertTrue(routine.contains("\"ROUTINE\""));
        String risk = invoker.invoke(invocation("IntentAgent", "我怀孕了")).text();
        assertTrue(risk.contains("PREGNANCY"));
    }

    @Test
    void clarify夹具返回中文追问() {
        assertEquals("你今天想练哪个部位？", invoker.invoke(invocation("ClarifyAgent", "想练健身")).text());
        assertEquals("你平时大概几点睡、几点起？", invoker.invoke(invocation("ClarifyAgent", "作息")).text());
        assertEquals("这顿主要是早餐、午餐还是晚餐？", invoker.invoke(invocation("ClarifyAgent", "随便")).text());
    }

    @Test
    void recommend夹具回显输入候选ID() {
        String prompt = """
                候选资源（已排序）: [{"resourceId": 9001, "name": "俯卧撑"}, {"resourceId": 9002, "name": "深蹲"}]
                """;
        String text = invoker.invoke(invocation("RecommendResponseAgent", prompt)).text();
        assertTrue(text.contains("\"resourceId\":\"9001\""));
        assertTrue(text.contains("\"resourceId\":\"9002\""));
        assertTrue(Pattern.compile("\"speechText\"").matcher(text).find());
    }

    @Test
    void recommend夹具回显字母数字种子ID() {
        String prompt = """
                候选资源（已排序）: [{"resourceId": "M1", "name": "燕麦牛奶粥"}, {"resourceId": "R1", "name": "睡眠时长"}]
                """;
        String text = invoker.invoke(invocation("RecommendResponseAgent", prompt)).text();
        assertTrue(text.contains("\"resourceId\":\"M1\""));
        assertTrue(text.contains("\"resourceId\":\"R1\""));
    }

    @Test
    void 无匹配场景抛出上游失败() {
        assertThrows(AgentInvocationException.class,
                () -> invoker.invoke(invocation("UnknownRole", "whatever")));
    }

    @Test
    void 版本与配置状态固定() {
        assertEquals(FixtureAgentInvoker.FIXTURE_VERSION, invoker.fixtureVersion());
        assertTrue(invoker.configured());
    }
}
