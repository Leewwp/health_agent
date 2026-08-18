package com.diet.config;

import com.diet.agent.llm.OpenAiCompatibleChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 模型配置。
 */
@Configuration
public class DietAgentScopeConfig {

    /** DashScope API Key，所有 AgentScope 模型调用都依赖该配置。 */
    @Value("${agentscope.dashscope.api-key:}")
    private String apiKey;

    /** DashScope 自定义工作空间 Endpoint，缺省时使用官方默认地址。 */
    @Value("${agentscope.dashscope.base-url:}")
    private String baseUrl;

    /** 主模型用于推荐理由和最终应答，默认使用 qwen-turbo。 */
    @Value("${diet.llm.main-model:qwen-turbo}")
    private String mainModelName;

    /** 轻量模型用于意图识别和澄清追问，默认使用 qwen3.7-flash。 */
    @Value("${diet.llm.light-model:qwen3.7-flash}")
    private String lightModelName;

    /** 真实调用超时（毫秒），OpenAI 兼容模型使用。 */
    @Value("${diet.agent.timeout-ms:15000}")
    private long timeoutMs;

    /**
     * 聊天端点形态：false=agentscope 原生 DashScope 端点（默认）；
     * true=OpenAI 兼容端点（/chat/completions，适配只开放兼容端点的专属空间）。
     */
    @Value("${diet.llm.openai-compatible:false}")
    private boolean openAiCompatible;

    /**
     * 主模型 Bean。
     * RecommendResponseAgent 会优先使用该模型。
     */
    @Bean("DietMainChatModel")
    public Model DietMainChatModel() {
        if (openAiCompatible) {
            return new OpenAiCompatibleChatModel(apiKey, baseUrl, mainModelName, timeoutMs);
        }
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(mainModelName)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 轻量模型 Bean。
     * IntentAgent 和 ClarifyAgent 使用它降低延迟和成本。
     */
    @Bean("DietLightChatModel")
    public Model DietLightChatModel() {
        if (openAiCompatible) {
            return new OpenAiCompatibleChatModel(apiKey, baseUrl, lightModelName, timeoutMs);
        }
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(lightModelName)
                .baseUrl(baseUrl)
                .build();
    }
}




