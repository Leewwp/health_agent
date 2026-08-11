package com.diet.m5;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 闸门 #48：Qdrant Java Client 1.17.0 与 Spring Boot 3.3.13 / Java 21 依赖线的兼容性冒烟。
 * <p>
 * 验证 collection 创建、upsert、带 filter 查询、清理 collection 四个动作。
 * 依赖本地 Docker 运行 qdrant/qdrant:v1.17.0（gRPC 端口 6334），由系统属性
 * -Ditest.qdrant=true（Maven 会改写为 itest.qdrant）门控：未显式开启时跳过，
 * 保证 CI（当前无 qdrant 服务容器）保持绿色。冒烟不做任何应用接入。
 */
@EnabledIfSystemProperty(named = "itest.qdrant", matches = "true")
class QdrantGateSmokeTest {

    private static final String HOST = System.getProperty("qdrant.host", "localhost");
    private static final int GRPC_PORT = Integer.parseInt(System.getProperty("qdrant.port", "6334"));
    private static final String COLLECTION = "diet_gate_smoke";

    @Test
    void qdrantCreateUpsertFilterQueryAndCleanup() throws Exception {
        // 本地 Qdrant 6334 默认提供明文 gRPC；显式关闭 TLS，避免客户端默认 TLS 握手失败。
        QdrantClient client = new QdrantClient(QdrantGrpcClient.newBuilder(HOST, GRPC_PORT, false).build());
        boolean completed = false;
        try {
            qdrantCreateUpsertFilterQueryAndCleanup(client);
            completed = true;
        } finally {
            if (!completed) {
                deleteCollectionIfExists(client);
            }
            client.close();
        }
    }

    private void qdrantCreateUpsertFilterQueryAndCleanup(QdrantClient client) throws Exception {
        // 1. 创建 collection（4 维向量，Cosine 距离）
        client.createCollectionAsync(COLLECTION, Collections.VectorParams.newBuilder()
                        .setSize(4)
                        .setDistance(Collections.Distance.Cosine)
                        .build())
                .get(15, TimeUnit.SECONDS);
        Collections.CollectionInfo info = client.getCollectionInfoAsync(COLLECTION)
                .get(15, TimeUnit.SECONDS);
        assertNotNull(info);
        assertEquals(Collections.Distance.Cosine, info.getConfig().getParams().getVectorsConfig().getParams().getDistance());

        // 2. upsert 两个带 payload 的点（tag 分别打 a/b，用于 filter 查询）
        List<Points.PointStruct> points = List.of(
                Points.PointStruct.newBuilder()
                        .setId(Common.PointId.newBuilder().setNum(1))
                        .setVectors(Points.Vectors.newBuilder().setVector(
                                Points.Vector.newBuilder().addData(0.1f).addData(0.2f).addData(0.3f).addData(0.4f)))
                        .putPayload("tag", JsonWithInt.Value.newBuilder().setStringValue("a").build())
                        .build(),
                Points.PointStruct.newBuilder()
                        .setId(Common.PointId.newBuilder().setNum(2))
                        .setVectors(Points.Vectors.newBuilder().setVector(
                                Points.Vector.newBuilder().addData(0.9f).addData(0.8f).addData(0.7f).addData(0.6f)))
                        .putPayload("tag", JsonWithInt.Value.newBuilder().setStringValue("b").build())
                        .build());
        client.upsertAsync(COLLECTION, points).get(15, TimeUnit.SECONDS);

        // 3. 带 filter 查询：只匹配 tag=a，查询向量贴近点 1 应返回且仅返回点 1
        List<Points.ScoredPoint> hits = client.searchAsync(
                        Points.SearchPoints.newBuilder()
                                .setCollectionName(COLLECTION)
                                .addVector(0.11f)
                                .addVector(0.21f)
                                .addVector(0.31f)
                                .addVector(0.41f)
                                .setFilter(Common.Filter.newBuilder()
                                        .addMust(Common.Condition.newBuilder().setField(
                                                Common.FieldCondition.newBuilder()
                                                        .setKey("tag")
                                                        .setMatch(Common.Match.newBuilder().setKeyword("a")))))
                                .setLimit(5)
                                .build())
                .get(15, TimeUnit.SECONDS);
        assertNotNull(hits);
        assertEquals(1, hits.size(), "filter 查询应只命中 tag=a 的点");
        assertEquals(1, hits.getFirst().getId().getNum());
        assertTrue(hits.getFirst().getScore() > 0.99, "贴近向量命中分数应接近 1");

        // 4. 清理 collection
        client.deleteCollectionAsync(COLLECTION).get(15, TimeUnit.SECONDS);

        assertFalse(client.collectionExistsAsync(COLLECTION).get(15, TimeUnit.SECONDS),
                "collection 删除后应不存在");
    }

    /** 冒烟任一步失败时也清理固定名称 collection，保证后续可重复执行。 */
    private void deleteCollectionIfExists(QdrantClient client) {
        try {
            if (client.collectionExistsAsync(COLLECTION).get(15, TimeUnit.SECONDS)) {
                client.deleteCollectionAsync(COLLECTION).get(15, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // collection 不存在或 Qdrant 已不可用时无需覆盖原始测试失败
        }
    }
}
