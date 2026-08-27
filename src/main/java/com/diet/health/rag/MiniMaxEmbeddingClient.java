package com.diet.health.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** MiniMax 原生 Embeddings 适配器；失败时返回 empty，由 Hybrid 检索降级。 */
@Component
@ConditionalOnProperty(value = "diet.embedding.provider", havingValue = "minimax")
public class MiniMaxEmbeddingClient implements TypedEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxEmbeddingClient.class);
    private static final String PLACEHOLDER_KEY = "<rotated-key>";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String groupId;
    private final int dimensions;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MiniMaxEmbeddingClient(
            @Value("${diet.embedding.minimax.api-key:${MINIMAX_API_KEY:}}") String apiKey,
            @Value("${diet.embedding.minimax.base-url:${MINIMAX_EMBEDDING_BASE_URL:https://api.minimaxi.com}}") String baseUrl,
            @Value("${diet.embedding.minimax.model:${MINIMAX_EMBEDDING_MODEL:embo-01}}") String model,
            @Value("${diet.embedding.minimax.group-id:${MINIMAX_GROUP_ID:}}") String groupId,
            @Value("${diet.embedding.dimensions:1536}") int dimensions,
            @Value("${diet.embedding.timeout-ms:10000}") long timeoutMs,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = model;
        this.groupId = groupId;
        this.dimensions = dimensions;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Optional<float[]> embed(String text) {
        return embed(text, "query");
    }

    @Override
    public Optional<float[]> embedDocument(String text) {
        return embed(text, "db");
    }

    private Optional<float[]> embed(String text, String type) {
        if (!configured() || text == null || text.isBlank()) return Optional.empty();
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("model", model);
            payload.put("type", type);
            payload.put("texts", List.of(text));
            String endpoint = baseUrl + "/v1/embeddings";
            if (groupId != null && !groupId.isBlank()) {
                endpoint += "?GroupId=" + URLEncoder.encode(groupId, StandardCharsets.UTF_8);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("MiniMax embedding HTTP {}，降级为结构化检索", response.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode baseResp = root.path("base_resp");
            if (baseResp.has("status_code") && baseResp.path("status_code").asInt() != 0) {
                log.warn("MiniMax embedding 业务失败：{}，降级为结构化检索", baseResp.path("status_msg").asText());
                return Optional.empty();
            }
            JsonNode vectors = root.path("vectors");
            if (!vectors.isArray() || vectors.isEmpty() || !vectors.get(0).isArray()) return Optional.empty();
            JsonNode vector = vectors.get(0);
            List<Float> values = new ArrayList<>(vector.size());
            for (JsonNode value : vector) values.add((float) value.asDouble());
            if (values.isEmpty() || (dimensions > 0 && values.size() != dimensions)) {
                log.warn("MiniMax embedding 维度异常：实际 {}，配置 {}，降级为结构化检索", values.size(), dimensions);
                return Optional.empty();
            }
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("MiniMax embedding 调用失败，降级为结构化检索：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override public String modelName() { return model; }

    @Override public String modelVersion() { return "minimax-" + dimensions; }

    @Override public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "https://api.minimaxi.com";
        return value.replaceAll("/+$", "");
    }
}
