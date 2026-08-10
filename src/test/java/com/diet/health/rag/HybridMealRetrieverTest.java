package com.diet.health.rag;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hybrid 检索器测试（33 号票 RAG seam）。
 * 验证：结构分与语义分归一合并重排；embedding 不可用/无向量时降级为结构化；
 * 嵌入文本为空时使用槽位文本兜底。
 */
class HybridMealRetrieverTest {

    private MealRetriever structured;
    private EmbeddingClient embeddingClient;
    private MealEmbeddingMapperStub embeddingMapper;
    private HybridMealRetriever hybrid;

    @BeforeEach
    void setUp() {
        structured = mock(MealRetriever.class);
        embeddingClient = mock(EmbeddingClient.class);
        embeddingMapper = new MealEmbeddingMapperStub();
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v1");
        hybrid = new HybridMealRetriever(structured, embeddingClient, embeddingMapper,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void 语义分提升低结构分候选的重排() {
        // A：结构分满、语义分 0；B：结构分一半、语义分满 → 合并后 B 在前
        RetrievalItem a = item(1L, 1.0, null);
        RetrievalItem b = item(2L, 0.5, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a, b), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        embeddingMapper.vectors.put(1L, new float[]{0f, 1f});
        embeddingMapper.vectors.put(2L, new float[]{1f, 0f});

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.HYBRID, result.mode());
        assertNull(result.degradationReason());
        // A：0.5*1.0 + 0.5*0.0 = 0.5；B：0.5*0.5 + 0.5*1.0 = 0.75
        assertEquals(2L, result.items().get(0).meal().id(), "语义分更高的候选必须被提升");
        assertEquals(0.75, result.items().get(0).mergedScore(), 1e-9);
        assertEquals(0.5, result.items().get(1).mergedScore(), 1e-9);
        assertEquals(1.0, result.items().get(0).semanticScore(), 1e-9);
    }

    @Test
    void 合并分数按结构归一与语义余弦各半加权() {
        RetrievalItem a = item(1L, 0.8, null);
        RetrievalItem b = item(2L, 0.4, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a, b), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        embeddingMapper.vectors.put(1L, new float[]{0f, 1f});
        embeddingMapper.vectors.put(2L, new float[]{1f, 0f});

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        // maxS=0.8；A：0.5*1.0 + 0.5*0 = 0.5；B：0.5*0.5 + 0.5*1.0 = 0.75 → B 在前
        assertEquals(2L, result.items().get(0).meal().id());
        assertEquals(0.75, result.items().get(0).mergedScore(), 1e-9);
        assertEquals(0.5, result.items().get(1).mergedScore(), 1e-9);
    }

    @Test
    void embedding不可用时降级为结构化并标记原因() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.empty());

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertEquals("embedding_unavailable", result.degradationReason());
        assertEquals(1L, result.items().get(0).meal().id());
        assertNull(result.items().get(0).semanticScore());
    }

    @Test
    void 候选无向量时降级为结构化并标记原因() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        embeddingMapper.vectors.clear();

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertEquals("no_vectors", result.degradationReason());
    }

    @Test
    void 嵌入文本为空时使用槽位文本兜底() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        embeddingMapper.vectors.put(1L, new float[]{1f, 0f});

        hybrid.retrieve(new MealRetrievalQuery(
                Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("增肌")), List.of(), List.of(), ""), 10);

        verify(embeddingClient).embed(eq("增肌 晚餐"));
    }

    @Test
    void 结构化结果为空时直接返回结构化空结果() {
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null));
        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);
        assertTrue(result.items().isEmpty());
        assertEquals(RetrievalMode.STRUCTURED, result.mode());
    }

    @Test
    void 损坏向量行被跳过而不是使检索崩溃() {
        RetrievalItem a = item(1L, 0.9, null);
        RetrievalItem b = item(2L, 0.7, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a, b), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        embeddingMapper.vectors.put(1L, new float[]{1f, 0f});
        embeddingMapper.corruptVectors.add(2L);

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.HYBRID, result.mode());
        assertEquals(2, result.items().size(), "损坏向量只跳过该行语义分，不中断检索");
        assertEquals(1.0, result.items().get(0).semanticScore(), 1e-9);
    }

    private MealRetrievalQuery query(String text) {
        return new MealRetrievalQuery(Map.of("healthGoal", List.of("增肌")), List.of(), List.of(), text);
    }

    private RetrievalItem item(Long id, double structuredScore, Double semantic) {
        MealItem meal = new MealItem(id, SourceMode.PUBLIC, null, "餐" + id, SlotBundle.empty(), structuredScore);
        double merged = semantic == null ? structuredScore : 0.5 * structuredScore + 0.5 * semantic;
        return new RetrievalItem(meal, structuredScore, semantic, merged);
    }

    private MealRetrievalQuery anyQuery() {
        return org.mockito.ArgumentMatchers.any(MealRetrievalQuery.class);
    }

    /** 内存版 MealEmbeddingMapper（测试用），按 meal_id 返回向量；corruptVectors 里的行返回损坏 JSON。 */
    private static final class MealEmbeddingMapperStub implements MealEmbeddingMapper {

        final Map<Long, float[]> vectors = new HashMap<>();
        final List<Long> corruptVectors = new ArrayList<>();

        @Override
        public List<MealEmbeddingRow> findByMealIds(List<Long> mealIds, String model, String modelVersion) {
            return mealIds.stream().filter(vectors::containsKey)
                    .map(id -> {
                        MealEmbeddingRow row = new MealEmbeddingRow();
                        row.setMealId(id);
                        row.setModel(model);
                        row.setModelVersion(modelVersion);
                        float[] v = vectors.get(id);
                        StringBuilder json = new StringBuilder("[");
                        for (int i = 0; i < v.length; i++) {
                            if (i > 0) {
                                json.append(',');
                            }
                            json.append(v[i]);
                        }
                        json.append(']');
                        row.setVector(corruptVectors.contains(id) ? "{broken" : json.toString());
                        return row;
                    }).toList();
        }

        @Override
        public int upsert(MealEmbeddingRow row) {
            return 0;
        }
    }
}
