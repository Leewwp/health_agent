package com.diet.health.rag;

import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * DashScope text-embedding 适配器（33 号票 RAG）。
 * <p>
 * 任何失败（占位 key、超时、异常、空响应）都返回 empty，由 hybrid 检索器降级为结构化检索；
 * 一次调用、不重试，与 AgentScopeInvoker 的单次调用约定一致。
 */
@Component
@ConditionalOnProperty(value = "diet.embedding.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);

    private static final String PLACEHOLDER_KEY = "请填入自己的apiKey";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public DashScopeEmbeddingClient(
            @Value("${agentscope.dashscope.api-key:}") String apiKey,
            @Value("${agentscope.dashscope.base-url:}") String baseUrl,
            @Value("${diet.embedding.base-url:}") String embeddingBaseUrl,
            @Value("${diet.embedding.model:text-embedding-v3}") String model,
            @Value("${diet.embedding.dimensions:1024}") int dimensions,
            @Value("${diet.embedding.timeout-ms:5000}") long timeoutMs
    ) {
        this.apiKey = apiKey;
        // embedding 走 dashscope-sdk-java 原生协议（/services/... 路径），与聊天（OpenAI 兼容端点）的
        // base-url 未必一致；未单独配置时回退到 agentscope.dashscope.base-url 保持原行为
        this.baseUrl = (embeddingBaseUrl == null || embeddingBaseUrl.isBlank())
                ? baseUrl : embeddingBaseUrl;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (!configured() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            DashScopeTextEmbedding model = DashScopeTextEmbedding.builder()
                    .apiKey(apiKey)
                    .modelName(this.model)
                    .dimensions(dimensions)
                    .baseUrl(baseUrl)
                    .executionConfig(ExecutionConfig.builder()
                            .timeout(timeout)
                            .maxAttempts(1)
                            .build())
                    .build();
            double[] vector = model.embed(TextBlock.builder().text(text).build())
                    .block(timeout);
            if (vector == null || vector.length == 0) {
                log.warn("Embedding 返回空向量（超时？），降级为结构化检索");
                return Optional.empty();
            }
            return Optional.of(toFloats(vector));
        } catch (RuntimeException e) {
            log.warn("Embedding 调用失败，降级为结构化检索: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private float[] toFloats(double[] vector) {
        float[] floats = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            floats[i] = (float) vector[i];
        }
        return floats;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String modelVersion() {
        return "v3-" + dimensions;
    }

    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }
}
