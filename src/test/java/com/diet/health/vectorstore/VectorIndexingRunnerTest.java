package com.diet.health.vectorstore;

import com.diet.health.rag.EmbeddingClient;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VectorIndexingRunner 测试（M5 #54；#70 迁移到审核读取模块替身并补模式隔离）。
 * 验证：未开启时跳过且零读取、fixture 显式启用 fail-fast、无向量餐食跳过、
 * payload 拼装、分批 upsert 与幂等。
 */
class VectorIndexingRunnerTest {

    private ReviewedMealReader reviewedMealReader;
    private MealEmbeddingMapper embeddingMapper;
    private EmbeddingClient embeddingClient;
    private HealthResourceProvider resourceProvider;
    private CapturingVectorStore store;
    private VectorIndexingRunner runner;

    @BeforeEach
    void setUp() {
        reviewedMealReader = mock(ReviewedMealReader.class);
        embeddingMapper = mock(MealEmbeddingMapper.class);
        embeddingClient = mock(EmbeddingClient.class);
        resourceProvider = mock(HealthResourceProvider.class);
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v3-2");
        when(embeddingClient.configured()).thenReturn(true);
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        store = new CapturingVectorStore(new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2"));
        runner = new VectorIndexingRunner(reviewedMealReader, embeddingMapper, embeddingClient,
                new ObjectMapper(), store, resourceProvider, true);
    }

    @Test
    void 未开启索引时跳过且零读取() {
        runner = new VectorIndexingRunner(reviewedMealReader, embeddingMapper, embeddingClient,
                new ObjectMapper(), store, resourceProvider, false);
        runner.run(null);
        assertEquals(0, store.upserted.size(), "index-on-startup=false 时不应索引");
        verify(reviewedMealReader, never()).snapshotAll();
    }

    @Test
    void fixture显式启用时failFast且错误包含模式与开关名() {
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(null),
                "fixture + index-on-startup=true 必须启动失败");
        assertTrue(error.getMessage().contains("FIXTURE_SEED"), "错误信息必须包含模式: " + error.getMessage());
        assertTrue(error.getMessage().contains(VectorIndexingRunner.SWITCH_NAME), "错误信息必须包含开关名");
        verify(reviewedMealReader, never()).snapshotAll();
    }

    @Test
    void 索引审核餐食并携带检索payload() {
        ReviewedMeal meal = meal(1L, List.of("午餐"), List.of("川菜"), List.of("花生"), "APPROVED", "PUBLIC");
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal));
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
        assertEquals(List.of("v3-2"), point.payload().get("data_version"));
    }

    @Test
    void 向量维度与身份不一致时跳过() {
        ReviewedMeal meal = meal(1L, List.of("午餐"), null, null, "APPROVED", "PUBLIC");
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal));
        // 身份维度 2，但向量是 3 维 → 必须跳过，防止混写错误 collection
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of(row(1L, "v3-2", "[1.0,0.0,0.0]")));

        runner.run(null);

        assertTrue(store.upserted.isEmpty(), "维度不一致的向量不得入索引");
    }

    @Test
    void 无向量餐食跳过且不产生空payload点() {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, null, null, null, "APPROVED", "PUBLIC")));
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of());

        runner.run(null);

        assertTrue(store.upserted.isEmpty(), "无向量餐食不应进入索引");
    }

    @Test
    void 超批次时分批upsert() {
        List<ReviewedMeal> meals = new ArrayList<>();
        List<MealEmbeddingRow> rows = new ArrayList<>();
        for (int i = 1; i <= 130; i++) {
            meals.add(meal((long) i, null, null, null, "APPROVED", "PUBLIC"));
            rows.add(row((long) i, "v3-2", "[1.0,0.0]"));
        }
        when(reviewedMealReader.snapshotAll()).thenReturn(meals);
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(rows);

        runner.run(null);

        // 64 + 64 + 2 = 3 批
        assertEquals(3, store.upsertCalls);
        assertEquals(130, store.upserted.size());
    }

    @Test
    void 存储不可用时跳过索引() {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, null, null, null, "APPROVED", "PUBLIC")));
        when(embeddingMapper.findByMealIds(anyList(), eq("text-embedding-v3"), eq("v3-2")))
                .thenReturn(List.of(row(1L, "v3-2", "[1.0,0.0]")));
        store.pingable = false;

        runner.run(null);

        assertEquals(0, store.upserted.size(), "存储不可用时应跳过");
    }

    private ReviewedMeal meal(Long id, List<String> mealTime, List<String> cuisine, List<String> allergens,
                              String reviewStatus, String sourceType) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", mealTime == null ? List.of() : mealTime);
        tags.put("mood", List.of());
        tags.put("scene", List.of());
        tags.put("healthGoal", List.of());
        tags.put("cuisine", cuisine == null ? List.of() : cuisine);
        tags.put("taste", List.of());
        tags.put("convenience", List.of());
        return new ReviewedMeal(
                id, "测试餐食", null, List.of(), tags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                allergens == null ? List.of() : allergens,
                "REVIEWED", reviewStatus, null, "NONE", null,
                "foodcom-recipes-and-reviews-v2", "src-" + id, "v2", sourceType
        );
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
