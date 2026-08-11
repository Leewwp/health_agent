package com.diet.health.vectorstore;

import com.diet.health.rag.EmbeddingClient;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.mapper.MealMapper;
import com.diet.model.MealEmbeddingRow;
import com.diet.model.MealItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核餐食向量批量索引（M5 #54）。
 * <p>
 * {@code diet.vectorstore.index-on-startup=true} 且 mode=qdrant 时启动执行：
 * 从 MySQL 读取审核餐食与其 meal_item_embedding 向量，按固定批次幂等 upsert 到 Qdrant。
 * 幂等性由同 mealId 覆盖保证，可反复运行；索引失败只告警，不阻塞业务启动。
 */
@Component
public class VectorIndexingRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexingRunner.class);

    /** 单批 upsert 点数（Qdrant 单请求友好大小）。 */
    private static final int BATCH_SIZE = 64;

    private final MealMapper mealMapper;
    private final MealEmbeddingMapper embeddingMapper;
    private final EmbeddingClient embeddingClient;
    private final JsonService jsonService;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;
    private final boolean indexOnStartup;

    public VectorIndexingRunner(MealMapper mealMapper,
                                MealEmbeddingMapper embeddingMapper,
                                EmbeddingClient embeddingClient,
                                JsonService jsonService,
                                ObjectMapper objectMapper,
                                VectorStore vectorStore,
                                @Value("${diet.vectorstore.index-on-startup:false}") boolean indexOnStartup) {
        this.mealMapper = mealMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingClient = embeddingClient;
        this.jsonService = jsonService;
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
        this.indexOnStartup = indexOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!indexOnStartup) {
            return;
        }
        if (!vectorStore.ping()) {
            log.warn("向量存储不可用，跳过餐食索引；结构化检索不受影响");
            return;
        }
        if (!vectorStore.ensureCollection()) {
            log.warn("向量 collection 不可用（可能维度漂移），跳过餐食索引；请按身份重建");
            return;
        }
        List<MealItemRow> meals = mealMapper.findApprovedPublicMeals();
        if (meals.isEmpty()) {
            log.info("无审核餐食，跳过向量索引");
            return;
        }
        List<Long> mealIds = meals.stream().map(MealItemRow::getId).toList();
        List<MealEmbeddingRow> rows = embeddingMapper.findByMealIds(
                mealIds, embeddingClient.modelName(), embeddingClient.modelVersion());
        Map<Long, float[]> vectors = new HashMap<>();
        for (MealEmbeddingRow row : rows) {
            float[] vector = toVector(row);
            if (vector != null) {
                vectors.put(row.getMealId(), vector);
            }
        }
        Map<Long, MealItemRow> mealById = new HashMap<>();
        for (MealItemRow meal : meals) {
            mealById.put(meal.getId(), meal);
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

    /** 检索所需 keyword payload：餐食槽位标签 + 过敏原 + 数据版本；不携带餐食事实字段。 */
    private Map<String, List<String>> payload(MealItemRow meal) {
        if (meal == null) {
            return Map.of();
        }
        Map<String, List<String>> payload = new HashMap<>();
        payload.put("meal_time", jsonService.fromJsonArray(meal.getMealTime()));
        payload.put("cuisine", jsonService.fromJsonArray(meal.getCuisine()));
        payload.put("allergens", jsonService.fromJsonArray(meal.getAllergenJson()));
        payload.put("review_status", List.of(meal.getReviewStatus()));
        payload.put("source_type", List.of(meal.getSourceType()));
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
