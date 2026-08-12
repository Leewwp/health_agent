package com.diet.health.rag;

import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingGenerationRunner 测试（33 号票 RAG；#70 迁移到审核读取模块替身并补模式隔离）。
 * 验证：开关关闭时零读取、fixture 显式启用 fail-fast、未配置跳过、快照生成与失败即中止。
 */
class EmbeddingGenerationRunnerTest {

    private ReviewedMealReader reviewedMealReader;
    private MealEmbeddingMapper embeddingMapper;
    private EmbeddingClient embeddingClient;
    private HealthResourceProvider resourceProvider;
    private EmbeddingGenerationRunner runner;

    @BeforeEach
    void setUp() {
        reviewedMealReader = mock(ReviewedMealReader.class);
        embeddingMapper = mock(MealEmbeddingMapper.class);
        embeddingClient = mock(EmbeddingClient.class);
        resourceProvider = mock(HealthResourceProvider.class);
        when(resourceProvider.providerMode()).thenReturn("REVIEWED_DB");
        when(embeddingClient.configured()).thenReturn(true);
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v3-2");
        runner = new EmbeddingGenerationRunner(reviewedMealReader, embeddingMapper, embeddingClient,
                resourceProvider, true);
    }

    @Test
    void 开关关闭时零读取() {
        runner = new EmbeddingGenerationRunner(reviewedMealReader, embeddingMapper, embeddingClient,
                resourceProvider, false);
        runner.run(null);
        verify(reviewedMealReader, never()).snapshotAll();
        verify(embeddingMapper, never()).upsert(any());
    }

    @Test
    void fixture显式启用时failFast且错误包含模式与开关名() {
        when(resourceProvider.providerMode()).thenReturn("FIXTURE_SEED");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(null),
                "fixture + generate-on-startup=true 必须启动失败");
        assertTrue(error.getMessage().contains("FIXTURE_SEED"), "错误信息必须包含模式: " + error.getMessage());
        assertTrue(error.getMessage().contains(EmbeddingGenerationRunner.SWITCH_NAME), "错误信息必须包含开关名");
        verify(reviewedMealReader, never()).snapshotAll();
    }

    @Test
    void 未配置Embedding时跳过且零读取() {
        when(embeddingClient.configured()).thenReturn(false);
        runner.run(null);
        verify(reviewedMealReader, never()).snapshotAll();
        verify(embeddingMapper, never()).upsert(any());
    }

    @Test
    void 从稳定快照生成向量并幂等写入() {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L), meal(2L)));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f, 0.2f}));

        runner.run(null);

        verify(reviewedMealReader).snapshotAll();
        ArgumentCaptor<MealEmbeddingRow> captor = ArgumentCaptor.forClass(MealEmbeddingRow.class);
        verify(embeddingMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        List<MealEmbeddingRow> rows = captor.getAllValues();
        assertEquals(List.of(1L, 2L), rows.stream().map(MealEmbeddingRow::getMealId).toList());
        assertEquals(2, rows.get(0).getDimension());
        assertEquals("[0.1,0.2]", rows.get(0).getVector());
    }

    @Test
    void 嵌入文本拼接字段保持不变() {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L)));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        runner.run(null);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient).embed(textCaptor.capture());
        assertEquals("测试餐食 Test Meal 清爽描述 番茄 鸡蛋", textCaptor.getValue());
    }

    @Test
    void 部分成功后失败即中止() {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L), meal(2L)));
        when(embeddingClient.embed(anyString()))
                .thenReturn(Optional.of(new float[]{0.1f}))
                .thenReturn(Optional.empty());
        runner.run(null);
        verify(embeddingMapper, org.mockito.Mockito.times(1)).upsert(any());
        verify(embeddingClient, org.mockito.Mockito.times(2)).embed(anyString());
    }

    private ReviewedMeal meal(Long id) {
        Map<String, List<String>> tags = new java.util.LinkedHashMap<>();
        tags.put("mealTime", List.of());
        return new ReviewedMeal(
                id, "测试餐食", "Test Meal", List.of(), tags, "清爽描述", List.of("番茄", "鸡蛋"),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                List.of(), "REVIEWED", "APPROVED", "NONE", null,
                "foodcom-recipes-and-reviews-v2", "src-" + id, "v2", "PUBLIC"
        );
    }
}
