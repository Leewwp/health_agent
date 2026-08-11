package com.diet.health.vectorstore;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QdrantVectorStore 真实集成测试（M5 #54）。
 * <p>
 * 依赖本地 Qdrant 1.17.0（gRPC 6334，明文），由 -Ditest.qdrant=true
 * （Maven 改写为 itest.qdrant）门控；未开启时跳过，CI 保持绿色。
 * 验证 create/upsert/filter search/clear、幂等 upsert 与维度不匹配降级信号。
 */
@EnabledIfSystemProperty(named = "itest.qdrant", matches = "true")
class QdrantVectorStoreIntegrationTest {

    private static final VectorStoreIdentity IDENTITY =
            new VectorStoreIdentity("dashscope", "text-embedding-v3", 2, "v3-2-itest");

    private QdrantVectorStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void 真实qdrant创建upsert过滤查询与清理() {
        store = new QdrantVectorStore(IDENTITY, "localhost", 6334, false, 5000);

        assertTrue(store.ensureCollection(), "collection 应创建成功");
        assertTrue(store.ensureCollection(), "重复 ensure 应幂等");

        store.upsert(List.of(
                point(101L, new float[]{1f, 0f}, Map.of("allergens", List.of("花生"))),
                point(102L, new float[]{0.9f, 0.1f}, Map.of("allergens", List.of("鸡蛋"))),
                point(103L, new float[]{0f, 1f}, Map.of())));

        // 排除 101 + 过敏原花生 → 只剩 102（0.9 余弦）与 103（0）
        List<VectorHit> hits = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(101L), List.of("花生")), 10);
        assertEquals(2, hits.size(), "must_not 排除 ID 与过敏原后应剩两个点");
        assertEquals(102L, hits.get(0).mealId());
        assertTrue(hits.get(0).score() > 0.8, "贴近查询向量的点应高相似度");
        assertEquals(103L, hits.get(1).mealId());

        // 幂等 upsert：同 ID 覆盖，不产生重复点
        store.upsert(List.of(point(102L, new float[]{1f, 0f}, Map.of("allergens", List.of("鸡蛋")))));
        List<VectorHit> after = store.search(new float[]{1f, 0f}, new VectorFilter(List.of(), List.of("花生")), 10);
        assertEquals(2, after.size(), "重复 upsert 同 ID 不应产生重复 point");

        // M5 #52：审核状态/来源 must 过滤（其余点无这些 payload key，Qdrant 按缺失字段不匹配处理）
        store.upsert(List.of(
                point(104L, new float[]{1f, 0f},
                        Map.of("review_status", List.of("APPROVED"), "source_type", List.of("PUBLIC"))),
                point(105L, new float[]{0.9f, 0.1f},
                        Map.of("review_status", List.of("PENDING"), "source_type", List.of("PUBLIC")))));
        List<VectorHit> approved = store.search(new float[]{1f, 0f},
                new VectorFilter(List.of(), List.of(), "APPROVED", "PUBLIC"), 10);
        assertEquals(1, approved.size(), "review_status/source_type must 过滤后只剩 104");
        assertEquals(104L, approved.get(0).mealId());

        store.clear();
        assertTrue(store.search(new float[]{1f, 0f}, VectorFilter.none(), 10).isEmpty(), "clear 后应无命中");
    }

    @Test
    void 已有collection维度不匹配时ensure返回false() {
        // 先绕过身份直接创建同名单但维度不同的 collection，模拟配置漂移
        String name = IDENTITY.collectionName();
        try (QdrantClient raw = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build())) {
            if (raw.collectionExistsAsync(name).get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                raw.deleteCollectionAsync(name).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            raw.createCollectionAsync(name, Collections.VectorParams.newBuilder()
                            .setSize(8)
                            .setDistance(Collections.Distance.Cosine)
                            .build())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        store = new QdrantVectorStore(IDENTITY, "localhost", 6334, false, 5000);
        assertFalse(store.ensureCollection(), "维度与身份不匹配必须返回 false，由调用方降级");

        // clear 按身份重建：删除漂移 collection 后恢复为身份维度，可继续使用
        store.clear();
        assertTrue(store.ensureCollection(), "clear 重建后应回到身份维度并可用");
    }

    private VectorPoint point(long mealId, float[] vector, Map<String, List<String>> payload) {
        return new VectorPoint(mealId, vector, payload);
    }
}
