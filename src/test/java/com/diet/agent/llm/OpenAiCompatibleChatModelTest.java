package com.diet.agent.llm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容聊天模型适配器单测：请求体组装、响应解析与模型名。
 */
class OpenAiCompatibleChatModelTest {

    private final OpenAiCompatibleChatModel model =
            new OpenAiCompatibleChatModel("sk-test", "https://example.com/compatible-mode/v1", "qwen-test", 5000);

    @Test
    void buildRequestBody_角色与内容映射() throws Exception {
        Msg user = Msg.builder().role(MsgRole.USER).textContent("你好").build();
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("你是助手").build();
        String body = model.buildRequestBody(List.of(system, user), null);

        assertTrue(body.contains("\"model\":\"qwen-test\""));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"你好\""));
    }

    @Test
    void buildRequestBody_透传温度与最大token() throws Exception {
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).maxTokens(256).build();
        String body = model.buildRequestBody(List.of(user), options);

        assertTrue(body.contains("\"temperature\":0.7"));
        assertTrue(body.contains("\"max_tokens\":256"));
    }

    @Test
    void parseResponse_解析文本内容与用量() throws Exception {
        String json = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"推荐结果\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}";
        ChatResponse response = model.parseResponse(json);

        assertEquals("chatcmpl-1", response.getId());
        assertNotNull(response.getContent());
        assertEquals("推荐结果", response.getContent().get(0).toString());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void getModelName_返回配置模型名() {
        assertEquals("qwen-test", model.getModelName());
    }
}
