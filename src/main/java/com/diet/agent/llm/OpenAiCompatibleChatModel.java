package com.diet.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容聊天端点适配（兼容-mode/v1/chat/completions）。
 * <p>
 * agentscope 1.0.11 的 DashScopeChatModel 只支持 DashScope 原生端点
 * （/api/v1/services/aigc/text-generation/generation），而部分 DashScope 专属
 * 空间网关只开放 OpenAI 兼容端点；本实现让同一套 Agent 编排直接对接兼容端点，
 * 契约（JSON 输出、降级、Trace）与原生模型一致。
 */
public class OpenAiCompatibleChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);

    private static final String PLACEHOLDER_KEY = "请填入自己的apiKey";

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatModel(String apiKey, String baseUrl, String modelName, long timeoutMs) {
        this.apiKey = apiKey;
        // 兼容端点地址通常形如 https://host/compatible-mode/v1，追加 /chat/completions 即完整 URL
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (messages == null || messages.isEmpty()) {
            return Flux.empty();
        }
        try {
            String body = buildRequestBody(messages, options);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofString());
            AtomicBoolean cancelled = new AtomicBoolean();
            return Mono.<ChatResponse>create(sink -> {
                sink.onCancel(() -> {
                    cancelled.set(true);
                    future.cancel(true);
                });
                future.whenComplete((response, error) -> {
                    if (cancelled.get()) {
                        return;
                    }
                    if (error != null) {
                        sink.error(error);
                        return;
                    }
                    try {
                        if (response.statusCode() / 100 != 2) {
                            throw new IllegalStateException("OpenAI 兼容端点返回 " + response.statusCode() + ": "
                                    + truncate(response.body()));
                        }
                        sink.success(parseResponse(response.body()));
                    } catch (Exception parseError) {
                        sink.error(new IllegalStateException("OpenAI 兼容响应解析失败", parseError));
                    }
                });
            })
                    .doOnError(error -> log.warn("OpenAI 兼容模型调用失败（{}）: {}", modelName, error.getMessage()))
                    .flux();
        } catch (Exception e) {
            log.warn("OpenAI 兼容模型调用失败（{}）: {}", modelName, e.getMessage());
            return Flux.error(e);
        }
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /** 构建 OpenAI messages 请求体（角色 + 文本内容；生成参数可选透传）。 */
    String buildRequestBody(List<Msg> messages, GenerateOptions options) throws Exception {
        JsonNode req = objectMapper.createObjectNode()
                .put("model", modelName);
        var arr = ((com.fasterxml.jackson.databind.node.ObjectNode) req).putArray("messages");
        for (Msg msg : messages) {
            arr.addObject()
                    .put("role", roleOf(msg.getRole()))
                    .put("content", msg.getTextContent() == null ? "" : msg.getTextContent());
        }
        if (options != null) {
            var params = (com.fasterxml.jackson.databind.node.ObjectNode) req;
            if (options.getTemperature() != null) {
                params.put("temperature", options.getTemperature());
            }
            Integer maxTokens = options.getMaxTokens() != null ? options.getMaxTokens() : options.getMaxCompletionTokens();
            if (maxTokens != null) {
                params.put("max_tokens", maxTokens);
            }
        }
        return objectMapper.writeValueAsString(req);
    }

    /** 解析 OpenAI 兼容响应为 agentscope ChatResponse（文本块 + 用量 + 结束原因）。 */
    ChatResponse parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String id = root.path("id").asText("openai-compat");
        JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                ? root.path("choices").get(0) : null;
        String content = choice == null ? null : choice.path("message").path("content").asText(null);
        String finishReason = choice == null ? null : choice.path("finish_reason").asText();
        JsonNode usage = root.path("usage");
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);

        List<ContentBlock> blocks = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            blocks.add(TextBlock.builder().text(content).build());
        }
        return ChatResponse.builder()
                .id(id)
                .content(blocks)
                .usage(ChatUsage.builder().inputTokens(input).outputTokens(output).time(0).build())
                .metadata(Map.of())
                .finishReason(finishReason)
                .build();
    }

    private String roleOf(MsgRole role) {
        if (role == MsgRole.ASSISTANT) {
            return "assistant";
        }
        if (role == MsgRole.SYSTEM) {
            return "system";
        }
        if (role == MsgRole.TOOL) {
            return "tool";
        }
        return "user";
    }

    private String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
