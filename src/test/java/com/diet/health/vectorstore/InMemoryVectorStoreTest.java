package com.diet.health.vectorstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemoryVectorStore 适配器测试（M5 #54）。
 * 验证：幂等 upsert 覆盖、过滤查询（排除 ID/过敏原）、相似度降序、清空与身份。
 * 内存适配器是领域测试与离线演示的向量存储替身，行为必须与 Qdrant 适配器一致。
 */
class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore(new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2"));
    }

    @Test
    void 幂等upsert同mealId覆盖() {
        store.upsert(List.of(point(1L, new float[]{1f, 0f})));
        store.upsert(List.of(point(1L, new float[]{0f, 1f})));

        List<VectorHit> hits = store.search(new float[]{0f, 1f}, VectorFilter.none(), 10);
        assertEquals(1, hits.size());
        assertEquals(1L, hits.getFirst().mealId());
        assertTrue(hits.getFirst().score() > 0.99, "同 ID 后写入向量必须覆盖前值");
    }

    @Test
    void 相似度降序返回并截断limit() {
        store.upsert(List.of(
                point(1L, new float[]{1f, 0f}),
                point(2L, new float[]{0.9f, 0.1f}),
                point(3L, new float[]{0f, 1f})));

        List<VectorHit> hits = store.search(new float[]{1f, 0f}, VectorFilter.none(), 2);
        assertEquals(2, hits.size());
        assertEquals(1L, hits.get(0).mealId());
        assertEquals(2L, hits.get(1).mealId());
        assertTrue(hits.get(0).score() >= hits.get(1).score());
    }

    @Test
    void 排除ID在排序前过滤() {
        store.upsert(List.of(
                point(1L, new float[]{1f, 0f}),
                point(2L, new float[]{0.9f, 0.1f})));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(1L), List.of()), 10);
        assertEquals(1, hits.size());
        assertEquals(2L, hits.getFirst().mealId());
    }

    @Test
    void 过敏原命中即排除() {
        store.upsert(List.of(
                point(1L, new float[]{1f, 0f}, Map.of("allergens", List.of("花生"))),
                point(2L, new float[]{0.9f, 0.1f}, Map.of("allergens", List.of("鸡蛋")))));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of("花生")), 10);
        assertEquals(1, hits.size());
        assertEquals(2L, hits.getFirst().mealId());
    }

    @Test
    void 无payload视为无过敏原() {
        store.upsert(List.of(point(1L, new float[]{1f, 0f})));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of("花生")), 10);
        assertEquals(1, hits.size());
        assertEquals(1L, hits.getFirst().mealId());
    }

    @Test
    void 审核状态must过滤() {
        store.upsert(List.of(
                point(1L, new float[]{1f, 0f}, Map.of("review_status", List.of("APPROVED"))),
                point(2L, new float[]{0.9f, 0.1f}, Map.of("review_status", List.of("PENDING")))));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of(), "APPROVED", null), 10);
        assertEquals(1, hits.size());
        assertEquals(1L, hits.getFirst().mealId());
    }

    @Test
    void 来源类型must过滤() {
        store.upsert(List.of(
                point(1L, new float[]{1f, 0f}, Map.of("source_type", List.of("PUBLIC"))),
                point(2L, new float[]{0.9f, 0.1f}, Map.of("source_type", List.of("PERSONAL")))));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of(), null, "PUBLIC"), 10);
        assertEquals(1, hits.size());
        assertEquals(1L, hits.getFirst().mealId());
    }

    @Test
    void 缺payload关键字时不满足must条件() {
        store.upsert(List.of(point(1L, new float[]{1f, 0f})));

        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of(), "APPROVED", null), 10);
        assertTrue(hits.isEmpty(), "无 review_status payload 的点必须被 must 条件过滤");
    }

    @Test
    void 空向量query不产生命中() {
        store.upsert(List.of(point(1L, new float[]{1f, 0f})));

        List<VectorHit> hits = store.search(new float[]{}, VectorFilter.none(), 10);
        assertTrue(hits.isEmpty());
    }

    @Test
    void 清空后无命中() {
        store.upsert(List.of(point(1L, new float[]{1f, 0f})));
        store.clear();
        assertTrue(store.search(new float[]{1f, 0f}, VectorFilter.none(), 10).isEmpty());
    }

    @Test
    void 身份与可用性() {
        assertEquals("meal_dashscope_text-embedding-v3_2_v3-2", store.collectionName());
        assertTrue(store.ensureCollection());
        assertTrue(store.ping());
        store.close();
    }

    private VectorPoint point(long mealId, float[] vector) {
        return new VectorPoint(mealId, vector, Map.of());
    }

    private VectorPoint point(long mealId, float[] vector, Map<String, List<String>> payload) {
        return new VectorPoint(mealId, vector, payload);
    }
}
