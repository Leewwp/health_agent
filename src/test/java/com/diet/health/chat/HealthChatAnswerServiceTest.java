package com.diet.health.chat;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.loader.PromptLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHAT 回答服务合同（2026-08-31 严格路由规格）：
 * 模型输出经契约解析透传；任何失败降级为确定性能力文案；
 * 能力文案必须声明推荐与计划能力（不得自称只是聊天助手）。
 */
class HealthChatAnswerServiceTest {

    @SuppressWarnings("unchecked")
    private HealthChatAnswerService serviceWith(AgentContractModule.ContractResult<?> result) {
        AgentContractModule module = mock(AgentContractModule.class);
        when(module.call(any())).thenAnswer(invocation -> result);
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.load(anyString())).thenReturn("提示词");
        return new HealthChatAnswerService(module, promptLoader, "qwen-turbo", "v1", 1000);
    }

    @Test
    void 模型成功输出生成回答() {
        HealthChatAnswerService service = serviceWith(
                AgentContractModule.ContractResult.ok("我是你的健康助手，可以帮你安排饮食和训练。", 12));
        assertEquals("我是你的健康助手，可以帮你安排饮食和训练。", service.answer("你是谁"));
    }

    @Test
    void 模型失败降级为确定性能力文案且声明推荐与计划能力() {
        HealthChatAnswerService service = serviceWith(
                AgentContractModule.ContractResult.degraded(AgentFailureType.MISSING_CONFIG, 1, "API key 未配置"));
        String answer = service.answer("你能帮我做什么");
        assertNotNull(answer);
        assertTrue(answer.contains("健康助手"), answer);
        assertTrue(answer.contains("推荐") && answer.contains("计划"), "不得声称不支持推荐/计划：" + answer);
    }

    @Test
    void 畸形输出降级且不抛异常() {
        HealthChatAnswerService service = serviceWith(
                AgentContractModule.ContractResult.degraded(AgentFailureType.SCHEMA_VIOLATION, 1, "speechText 缺失"));
        String answer = service.answer("能陪我聊天吗");
        assertTrue(answer.contains("健康助手"), answer);
    }

    @Test
    void 能力文案常量声明推荐与计划能力() {
        assertTrue(HealthChatAnswerService.CAPABILITY_COPY.contains("推荐"));
        assertTrue(HealthChatAnswerService.CAPABILITY_COPY.contains("计划"));
    }
}
