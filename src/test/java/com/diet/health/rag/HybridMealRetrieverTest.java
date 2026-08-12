package com.diet.health.rag;

import com.diet.enums.SourceMode;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.vectorstore.InMemoryVectorStore;
import com.diet.health.vectorstore.VectorFilter;
import com.diet.health.vectorstore.VectorHit;
import com.diet.health.vectorstore.VectorPoint;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.health.vectorstore.VectorStoreException;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hybrid 检索器融合测试（M5 #52；#69 迁移到审核读取模块替身，不断言 Mapper 内部交互）。
 * 验证：结构化与向量独立召回合并/去重/排序；向量命中按 ID 回查审核读取模块二次硬约束
 * （过期索引丢弃）；Embedding/Qdrant 不可用或空结果时降级为结构化并标记原因。
 */
class HybridMealRetrieverTest {

    private MealRetriever structured;
    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private ReviewedMealReader reviewedMealReader;
    private HybridMealRetriever hybrid;

    @BeforeEach
    void setUp() {
        structured = mock(MealRetriever.class);
        embeddingClient = mock(EmbeddingClient.class);
        vectorStore = new InMemoryVectorStore(
                new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2"));
        reviewedMealReader = mock(ReviewedMealReader.class);
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v1");
        hybrid = new HybridMealRetriever(structured, embeddingClient, vectorStore, reviewedMealReader);
    }

    @Test
    void 独立向量召回提升结构分低的候选() {
        // 结构化：A 高分、B 低分；向量：B 余弦满、A 余弦 0 → 融合后 B 在前
        RetrievalItem a = item(1L, 1.0, null);
        RetrievalItem b = item(2L, 0.5, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a, b), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(
                new VectorPoint(2L, new float[]{1f, 0f}, approvedPayload()),
                new VectorPoint(1L, new float[]{0f, 1f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1"), meal(2L, "餐2")));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.HYBRID, result.mode());
        assertNull(result.degradationReason());
        // A：0.5*1.0 + 0.5*0.0 = 0.5；B：0.5*0.5 + 0.5*1.0 = 0.75
        assertEquals(2L, result.items().get(0).meal().id(), "向量召回的语义候选必须被提升");
        assertEquals(0.75, result.items().get(0).mergedScore(), 1e-9);
        assertEquals(1.0, result.items().get(0).semanticScore(), 1e-9);
        assertEquals(0.5, result.items().get(1).mergedScore(), 1e-9);
        assertEquals(0.0, result.items().get(1).semanticScore(), 1e-9, "命中向量但余弦为 0 仍是向量命中，语义分 0 而非 null");
    }

    @Test
    void 向量独有候选进入融合结果() {
        RetrievalItem a = item(1L, 1.0, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload()),
                new VectorPoint(1L, new float[]{0f, 1f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1"), meal(9L, "餐9")));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(2, result.items().size(), "向量独有命中必须进入候选，体现独立召回扩展");
        assertEquals(0.5, result.items().get(0).mergedScore(), 1e-9);
        assertEquals(0.5, result.items().get(1).mergedScore(), 1e-9, "仅向量候选：0.5*0 + 0.5*1.0");
        assertEquals(9L, result.items().get(1).meal().id(), "并列分按 id 升序，向量候选应出现在结果中");
    }

    @Test
    void 向量命中二次硬约束过敏原命中即丢弃() {
        RetrievalItem a = item(1L, 1.0, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        // 9 号向量点 payload 无过敏原标签（索引过期），但审核库行已带"花生"→ 二次校验必须丢弃
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1"), meal(9L, "餐9", List.of("花生"))));

        RetrievalResult result = hybrid.retrieve(
                new MealRetrievalQuery(Map.of("healthGoal", List.of("增肌")), List.of(), List.of("花生"), "增肌晚餐"),
                10);

        assertEquals(1, result.items().size(), "审核库二次校验发现过敏原，向量命中必须被丢弃");
        assertEquals(1L, result.items().get(0).meal().id());
    }

    @Test
    void 向量命中但审核库已不存在时按过期索引丢弃() {
        RetrievalItem a = item(1L, 1.0, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload())));
        // 回查审核读取模块：9 号已不存在（过期索引）→ 丢弃，且合并集按审核库行集为准
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1")));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(1, result.items().size());
        assertEquals(1L, result.items().get(0).meal().id());
    }

    @Test
    void 排除ID在向量检索与二次校验中都生效() {
        RetrievalItem a = item(1L, 1.0, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1"), meal(9L, "餐9")));

        RetrievalResult result = hybrid.retrieve(
                new MealRetrievalQuery(Map.of("healthGoal", List.of("增肌")), List.of(9L), List.of(), "增肌晚餐"), 10);

        assertEquals(1, result.items().size(), "排除 ID 必须同时作用于两条召回路径");
        assertEquals(1L, result.items().get(0).meal().id());
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
    void 向量存储不可用时降级为结构化并标记原因() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        hybrid = new HybridMealRetriever(structured, embeddingClient, new FailingVectorStore(), reviewedMealReader);

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertEquals("vector_store_unavailable", result.degradationReason());
    }

    @Test
    void 向量检索无命中时降级为结构化并标记原因() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.STRUCTURED, result.mode());
        assertEquals("no_vector_hits", result.degradationReason());
        assertEquals(1L, result.items().get(0).meal().id());
    }

    @Test
    void 向量过滤条件包含审核状态与来源约束() {
        RetrievalItem a = item(1L, 1.0, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        // 非审核状态/非公共来源的点必须被 payload 过滤排除
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload()),
                new VectorPoint(8L, new float[]{1f, 0f}, Map.of("review_status", List.of("PENDING"),
                        "source_type", List.of("PUBLIC"), "allergens", List.of())),
                new VectorPoint(7L, new float[]{1f, 0f}, Map.of("review_status", List.of("APPROVED"),
                        "source_type", List.of("PERSONAL"), "allergens", List.of()))));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1"), meal(9L, "餐9")));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(2, result.items().size(), "PENDING 与 PERSONAL 点必须在向量召回阶段被过滤");
        assertEquals(9L, result.items().get(1).meal().id(), "仅审核 APPROVED 且 PUBLIC 的向量点进入融合");
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
    void 结构化为空但向量有命中时返回向量独有候选() {
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(
                new VectorPoint(9L, new float[]{1f, 0f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(9L, "餐9")));

        RetrievalResult result = hybrid.retrieve(query("增肌晚餐"), 10);

        assertEquals(RetrievalMode.HYBRID, result.mode(), "两条路径独立执行，结构化为空时向量独有候选仍可进入融合");
        assertEquals(1, result.items().size());
        assertEquals(9L, result.items().get(0).meal().id());
        assertEquals(0.5, result.items().get(0).mergedScore(), 1e-9, "仅向量候选：0.5*0 + 0.5*1.0");
    }

    @Test
    void 嵌入文本为空时使用槽位文本兜底() {
        RetrievalItem a = item(1L, 0.9, null);
        when(structured.retrieve(anyQuery(), eq(10)))
                .thenReturn(new RetrievalResult(List.of(a), RetrievalMode.STRUCTURED, null));
        when(embeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{1f, 0f}));
        vectorStore.upsert(List.of(new VectorPoint(1L, new float[]{1f, 0f}, approvedPayload())));
        when(reviewedMealReader.findByIds(anyList()))
                .thenReturn(List.of(meal(1L, "餐1")));

        hybrid.retrieve(new MealRetrievalQuery(
                Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("增肌")), List.of(), List.of(), ""), 10);

        verify(embeddingClient).embed(eq("增肌 晚餐"));
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

    private Map<String, List<String>> approvedPayload() {
        Map<String, List<String>> payload = new LinkedHashMap<>();
        payload.put("review_status", List.of("APPROVED"));
        payload.put("source_type", List.of("PUBLIC"));
        payload.put("allergens", List.of());
        return payload;
    }

    private ReviewedMeal meal(long id, String name) {
        return meal(id, name, List.of());
    }

    private ReviewedMeal meal(long id, String name, List<String> allergens) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", List.of("晚餐"));
        tags.put("mood", List.of());
        tags.put("scene", List.of());
        tags.put("healthGoal", List.of("增肌"));
        tags.put("cuisine", List.of());
        tags.put("taste", List.of());
        tags.put("convenience", List.of());
        return new ReviewedMeal(
                id, name, null, List.of(), tags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                allergens, "REVIEWED", "APPROVED", "NONE", null,
                "foodcom-recipes-and-reviews-v2", "src-" + id, "v2", "PUBLIC"
        );
    }

    /** ping 返回 true 但 search 抛故障的替身：模拟 Qdrant 运行时不可用。 */
    private static final class FailingVectorStore extends InMemoryVectorStore {

        FailingVectorStore() {
            super(new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2"));
        }

        @Override
        public List<VectorHit> search(float[] queryVector, VectorFilter filter, int limit) {
            throw new VectorStoreException("Qdrant search 失败（测试故障注入）");
        }
    }
}
