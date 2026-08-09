package com.diet.config;

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

    /** 主模型用于推荐理由和最终应答，默认使用 qwen-max。 */
    @Value("${diet.llm.main-model:qwen-max}")
    private String mainModelName;

    /** 轻量模型用于意图识别和澄清追问，默认使用 qwen-turbo。 */
    @Value("${diet.llm.light-model:qwen-turbo}")
    private String lightModelName;

    /**
     * 主模型 Bean。
     * RecommendResponseAgent 会优先使用该模型。
     */
    @Bean("DietMainChatModel")
    public Model DietMainChatModel() {
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
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(lightModelName)
                .baseUrl(baseUrl)
                .build();
    }
}




