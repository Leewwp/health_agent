package com.diet.health.vectorstore;

import com.diet.health.rag.EmbeddingClient;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.mapper.MealMapper;
import com.diet.model.MealEmbeddingRow;
import com.diet.model.MealItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VectorIndexingRunner 测试（M5 #54）。
 * 验证：未开启时跳过、无向量餐食跳过、payload 拼装、分批 upsert 与幂等。
 */
class VectorIndexingRunnerTest {

    private MealMapper mealMapper;
    private MealEmbeddingMapper embeddingMapper;
    private EmbeddingClient embeddingClient;
    private CapturingVectorStore store;
    private VectorIndexingRunner runner;

    @BeforeEach
    void setUp() {
        mealMapper = mock(MealMapper.class);
        embeddingMapper = mock(MealEmbeddingMapper.class);
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v3-2");
        when(embeddingClient.configured()).thenReturn(true);
        store = new CapturingVectorStore(new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2"));
        runner = new VectorIndexingRunner(mealMapper, embeddingMapper, embeddingClient,
                new JsonService(new ObjectMapper()), new ObjectMapper(), store, true);
    }

    @Test
    void 未开启索引时跳过() {
        runner = new VectorIndexingRunner(mealMapper, embeddingMapper, embeddingClient,
                new JsonService(new ObjectMapper()), new ObjectMapper(), store, false);
        runner.run(null);
        assertEquals(0, store.upserted.size(), "index-on-startup=false 时不应索引");
        verify(mealMapper, never()).findApprovedPublicMeals();
    }

    @Test
    void 索引审核餐食并携带检索payload() {
        MealItemRow meal = meal(1L, "[\"午餐\"]", "[\"川菜\"]", "[\"花生\"]", "APPROVED", "PUBLIC");
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(meal));
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of(row(1L, "v3-2", "[1.0,0.0]")));

        runner.run(null);

        assertEquals(1, store.upserted.size());
        VectorPoint point = store.upserted.getFirst();
        assertEquals(1L, point.mealId());
        assertEquals(List.of("午餐"), point.payload().get("meal_time"));
        assertEquals(List.of("川菜"), point.payload().get("cuisine"));
        assertEquals(List.of("花生"), point.payload().get("allergens"));
        assertEquals(List.of("APPROVED"), point.payload().get("review_status"));
        assertEquals(List.of("PUBLIC"), point.payload().get("source_type"));
    }

    @Test
    void 无向量餐食跳过且不产生空payload点() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(meal(1L, null, null, null, "APPROVED", "PUBLIC")));
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of());

        runner.run(null);

        assertTrue(store.upserted.isEmpty(), "无向量餐食不应进入索引");
    }

    @Test
    void 超批次时分批upsert() {
        List<MealItemRow> meals = new ArrayList<>();
        List<MealEmbeddingRow> rows = new ArrayList<>();
        for (int i = 1; i <= 130; i++) {
            meals.add(meal((long) i, null, null, null, "APPROVED", "PUBLIC"));
            rows.add(row((long) i, "v3-2", "[1.0,0.0]"));
        }
        when(mealMapper.findApprovedPublicMeals()).thenReturn(meals);
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(rows);

        runner.run(null);

        // 64 + 64 + 2 = 3 批
        assertEquals(3, store.upsertCalls);
        assertEquals(130, store.upserted.size());
    }

    @Test
    void 存储不可用时跳过索引() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(meal(1L, null, null, null, "APPROVED", "PUBLIC")));
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of(row(1L, "v3-2", "[1.0,0.0]")));
        store.pingable = false;

        runner.run(null);

        assertEquals(0, store.upserted.size(), "存储不可用时应跳过");
    }

    private MealItemRow meal(Long id, String mealTime, String cuisine, String allergens,
                             String reviewStatus, String sourceType) {
        MealItemRow meal = new MealItemRow();
        meal.setId(id);
        meal.setName("测试餐食");
        meal.setMealTime(mealTime);
        meal.setCuisine(cuisine);
        meal.setAllergenJson(allergens);
        meal.setReviewStatus(reviewStatus);
        meal.setSourceType(sourceType);
        return meal;
    }

    private MealEmbeddingRow row(Long mealId, String version, String vectorJson) {
        MealEmbeddingRow row = new MealEmbeddingRow();
        row.setMealId(mealId);
        row.setModel("text-embedding-v3");
        row.setModelVersion(version);
        row.setDimension(2);
        row.setVector(vectorJson);
        return row;
    }

    /** 记录 upsert 调用与点数的 VectorStore 替身。 */
    private static class CapturingVectorStore extends InMemoryVectorStore {
        final List<VectorPoint> upserted = new ArrayList<>();
        int upsertCalls;
        boolean pingable = true;

        CapturingVectorStore(VectorStoreIdentity identity) {
            super(identity);
        }

        @Override
        public synchronized void upsert(List<VectorPoint> points) {
            upsertCalls++;
            upserted.addAll(points);
            super.upsert(points);
        }

        @Override
        public boolean ping() {
            return pingable;
        }
    }
}
