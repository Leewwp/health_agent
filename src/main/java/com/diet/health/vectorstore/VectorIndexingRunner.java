package com.diet.health.vectorstore;

import com.diet.health.rag.EmbeddingClient;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核餐食向量批量索引（M5 #54；#70 迁移到审核读取模块并补模式隔离）。
 * <p>
 * {@code diet.vectorstore.index-on-startup=true} 且 mode=qdrant 时启动执行：
 * 从审核读取模块获取 APPROVED + PUBLIC 稳定快照与其 meal_item_embedding 向量，
 * 按固定批次幂等 upsert 到 Qdrant。
 * 幂等性由同 mealId 覆盖保证，可反复运行；索引失败只告警，不阻塞业务启动。
 * 必须早于 RAG 评估运行（@Order 0），否则评估会因 collection 尚未建立而全部降级。
 * 本 runner 是 REVIEWED_DB 能力：FIXTURE_SEED 下显式启用必须 fail-fast。
 */
@Component
@Order(0)
public class VectorIndexingRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexingRunner.class);

    /** 单批 upsert 点数（Qdrant 单请求友好大小）。 */
    private static final int BATCH_SIZE = 64;

    static final String SWITCH_NAME = "diet.vectorstore.index-on-startup";

    private final ReviewedMealReader reviewedMealReader;
    private final MealEmbeddingMapper embeddingMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;
    private final HealthResourceProvider resourceProvider;
    private final boolean indexOnStartup;

    public VectorIndexingRunner(ReviewedMealReader reviewedMealReader,
                                MealEmbeddingMapper embeddingMapper,
                                EmbeddingClient embeddingClient,
                                ObjectMapper objectMapper,
                                VectorStore vectorStore,
                                HealthResourceProvider resourceProvider,
                                @Value("${diet.vectorstore.index-on-startup:false}") boolean indexOnStartup) {
        this.reviewedMealReader = reviewedMealReader;
        this.embeddingMapper = embeddingMapper;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
        this.resourceProvider = resourceProvider;
        this.indexOnStartup = indexOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!indexOnStartup) {
            return;
        }
        if ("FIXTURE_SEED".equals(resourceProvider.providerMode())) {
            throw new IllegalStateException(
                    resourceProvider.providerMode() + " 模式下禁止启用 " + SWITCH_NAME
                            + "（正式 DB 向量索引不是 fixture 能力，请改用 diet.resource.mode=reviewed）");
        }
        if (!vectorStore.ping()) {
            log.warn("向量存储不可用，跳过餐食索引；结构化检索不受影响");
            return;
        }
        if (!vectorStore.ensureCollection()) {
            log.warn("向量 collection 不可用（可能维度漂移），跳过餐食索引；请按身份重建");
            return;
        }
        List<ReviewedMeal> meals = reviewedMealReader.snapshotAll();
        if (meals.isEmpty()) {
            log.info("无审核餐食，跳过向量索引");
            return;
        }
        List<Long> mealIds = meals.stream().map(ReviewedMeal::id).toList();
        List<MealEmbeddingRow> rows = embeddingMapper.findByMealIds(
                mealIds, embeddingClient.modelName(), embeddingClient.modelVersion());
        Map<Long, float[]> vectors = new HashMap<>();
        for (MealEmbeddingRow row : rows) {
            float[] vector = toVector(row);
            if (vector != null) {
                vectors.put(row.getMealId(), vector);
            }
        }
        Map<Long, ReviewedMeal> mealById = new HashMap<>();
        for (ReviewedMeal meal : meals) {
            mealById.put(meal.id(), meal);
        }

        int indexed = 0;
        int skipped = 0;
        List<VectorPoint> batch = new ArrayList<>(BATCH_SIZE);
        for (Long mealId : mealIds) {
            float[] vector = vectors.get(mealId);
            if (vector == null) {
                continue;
            }
            // 维度漂移防护：与身份维度不一致的向量拒绝入索引（防止混写错误 collection）
            if (vector.length != vectorStore.dimension()) {
                skipped++;
                log.warn("餐食 {} 向量维度 {} 与身份维度 {} 不一致，跳过索引", mealId, vector.length, vectorStore.dimension());
                continue;
            }
            batch.add(new VectorPoint(mealId, vector, payload(mealById.get(mealId))));
            if (batch.size() >= BATCH_SIZE) {
                indexed += upsertBatch(batch);
            }
        }
        indexed += upsertBatch(batch);
        log.info("餐食向量索引完成：{} / {} 条（collection {}，模型 {}，维度跳过 {} 条）",
                indexed, mealIds.size(), vectorStore.collectionName(), embeddingClient.modelName(), skipped);
    }

    private int upsertBatch(List<VectorPoint> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            vectorStore.upsert(batch);
            int size = batch.size();
            batch.clear();
            return size;
        } catch (VectorStoreException e) {
            log.warn("餐食向量索引批次失败（{} 条）: {}", batch.size(), e.getMessage());
            batch.clear();
            return 0;
        }
    }

    /** 检索所需 keyword payload：餐食槽位标签 + 过敏原 + 数据版本；不携带餐食事实字段（#70 契约不变）。 */
    private Map<String, List<String>> payload(ReviewedMeal meal) {
        if (meal == null) {
            return Map.of();
        }
        Map<String, List<String>> payload = new HashMap<>();
        payload.put("meal_time", meal.tags().getOrDefault("mealTime", List.of()));
        payload.put("cuisine", meal.tags().getOrDefault("cuisine", List.of()));
        payload.put("allergens", meal.allergens());
        payload.put("review_status", List.of(meal.reviewStatus()));
        payload.put("source_type", List.of(meal.sourceType()));
        payload.put("data_version", List.of(embeddingClient.modelVersion()));
        return payload;
    }

    private float[] toVector(MealEmbeddingRow row) {
        try {
            return objectMapper.readValue(row.getVector(), float[].class);
        } catch (Exception e) {
            log.warn("餐食 {} 向量解析失败，跳过索引", row.getMealId());
            return null;
        }
    }
}
