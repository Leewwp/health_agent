package com.diet.health.rag;

import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Hybrid 餐食检索器：结构化召回 + 语义余弦分归一合并。
 * <p>
 * 合并规则：final = 0.5 * (结构化分 / 候选集最大结构化分) + 0.5 * 语义余弦（截断到 [0,1]）。
 * embedding 不可用或候选无向量时降级为纯结构化结果，并标记降级原因。
 */
@Service
public class HybridMealRetriever implements MealRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridMealRetriever.class);

    private final MealRetriever structuredRetriever;
    private final EmbeddingClient embeddingClient;
    private final MealEmbeddingMapper embeddingMapper;
    private final ObjectMapper objectMapper;

    public HybridMealRetriever(
            @Qualifier("structuredMealRetriever")
            MealRetriever structuredRetriever,
            EmbeddingClient embeddingClient,
            MealEmbeddingMapper embeddingMapper,
            ObjectMapper objectMapper) {
        this.structuredRetriever = structuredRetriever;
        this.embeddingClient = embeddingClient;
        this.embeddingMapper = embeddingMapper;
        this.objectMapper = objectMapper;
    }

    /** hybrid 内部的结构化候选池：语义重排在更宽的池上进行，才能体现语义分对召回的作用。 */
    private static final int STRUCTURED_POOL = 10;

    @Override
    public RetrievalResult retrieve(MealRetrievalQuery query, int limit) {
        RetrievalResult base = structuredRetriever.retrieve(query, STRUCTURED_POOL);
        if (base.items().isEmpty()) {
            return base;
        }
        String embedText = embedText(query);
        Optional<float[]> queryVector = embeddingClient.embed(embedText);
        if (queryVector.isEmpty()) {
            return new RetrievalResult(limitTo(base.items(), limit), RetrievalMode.STRUCTURED, "embedding_unavailable");
        }

        List<Long> mealIds = base.items().stream().map(item -> item.meal().id()).toList();
        Map<Long, float[]> vectors = loadVectors(mealIds);
        if (vectors.isEmpty()) {
            return new RetrievalResult(limitTo(base.items(), limit), RetrievalMode.STRUCTURED, "no_vectors");
        }

        double maxStructured = base.items().stream()
                .mapToDouble(RetrievalItem::structuredScore)
                .max().orElse(0);

        List<RetrievalItem> merged = new ArrayList<>();
        for (RetrievalItem item : base.items()) {
            double normalized = maxStructured > 0 ? item.structuredScore() / maxStructured : 0;
            float[] vector = vectors.get(item.meal().id());
            double semantic = vector == null ? 0 : clampCosine(
                    VectorSimilarity.cosine(queryVector.get(), vector));
            double finalScore = 0.5 * normalized + 0.5 * semantic;
            merged.add(new RetrievalItem(item.meal(), item.structuredScore(), semantic, finalScore));
        }
        merged.sort(Comparator.comparingDouble(RetrievalItem::mergedScore).reversed());
        return new RetrievalResult(merged.stream().limit(Math.max(limit, 0)).toList(),
                RetrievalMode.HYBRID, null);
    }
    private List<RetrievalItem> limitTo(List<RetrievalItem> items, int limit) {
        return items.stream().limit(Math.max(limit, 0)).toList();
    }

    private Map<Long, float[]> loadVectors(List<Long> mealIds) {
        List<MealEmbeddingRow> rows = embeddingMapper.findByMealIds(
                mealIds, embeddingClient.modelName(), embeddingClient.modelVersion());
        Map<Long, float[]> vectors = new HashMap<>();
        for (MealEmbeddingRow row : rows) {
            float[] vector = toVector(row);
            if (vector != null) {
                vectors.put(row.getMealId(), vector);
            }
        }
        return vectors;
    }

    private float[] toVector(MealEmbeddingRow row) {
        try {
            return objectMapper.readValue(row.getVector(), float[].class);
        } catch (Exception e) {
            log.warn("餐食 {} 向量解析失败，按无向量处理", row.getMealId());
            return null;
        }
    }

    /** 嵌入文本：查询显式文本优先，否则用槽位值拼接（排序保证确定性）。 */
    private String embedText(MealRetrievalQuery query) {
        if (query.text() != null && !query.text().isBlank()) {
            return query.text();
        }
        Map<String, List<String>> slots = query.slots() == null ? Map.of() : query.slots();
        return slots.values().stream()
                .flatMap(List::stream)
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private double clampCosine(double cosine) {
        return Math.max(0, Math.min(1, cosine));
    }
}
