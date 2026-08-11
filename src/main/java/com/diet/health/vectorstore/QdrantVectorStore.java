package com.diet.health.vectorstore;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量存储适配器（M5 #54）。
 * <p>
 * collection 名由 {@link VectorStoreIdentity} 派生（provider+model+dimension+version），
 * 距离固定 Cosine；point 只保存 mealId、向量和检索所需 keyword payload，
 * 餐食事实始终以 MySQL 为真相源。任何 gRPC 故障包装为 {@link VectorStoreException}，
 * 由上层降级为结构化检索。
 */
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final VectorStoreIdentity identity;
    private final QdrantClient client;
    private final Duration timeout;

    public QdrantVectorStore(VectorStoreIdentity identity, String host, int grpcPort,
                             boolean useTls, long timeoutMs) {
        this.identity = identity;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = new QdrantClient(QdrantGrpcClient.newBuilder(host, grpcPort, useTls).build());
    }

    @Override
    public String collectionName() {
        return identity.collectionName();
    }

    @Override
    public int dimension() {
        return identity.dimension();
    }

    @Override
    public boolean ensureCollection() {
        String name = collectionName();
        try {
            boolean exists = client.collectionExistsAsync(name).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exists) {
                client.createCollectionAsync(name, Collections.VectorParams.newBuilder()
                                .setSize(identity.dimension())
                                .setDistance(Collections.Distance.Cosine)
                                .build())
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                log.info("Qdrant collection 已创建：{}（维度 {}，Cosine）", name, identity.dimension());
                return true;
            }
            long actual = client.getCollectionInfoAsync(name)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getConfig().getParams().getVectorsConfig().getParams().getSize();
            if (actual != identity.dimension()) {
                log.warn("Qdrant collection {} 维度 {} 与身份 {} 不匹配，拒绝使用；请按新身份重建", name, actual, identity.dimension());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Qdrant ensureCollection 失败（{}）: {}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        List<Points.PointStruct> structs = new ArrayList<>(points.size());
        for (VectorPoint point : points) {
            Points.Vector.Builder vector = Points.Vector.newBuilder();
            for (float v : point.vector()) {
                vector.addData(v);
            }
            Points.PointStruct.Builder builder = Points.PointStruct.newBuilder()
                    .setId(Common.PointId.newBuilder().setNum(point.mealId()))
                    .setVectors(Points.Vectors.newBuilder().setVector(vector));
            if (point.payload() != null) {
                for (Map.Entry<String, List<String>> entry : point.payload().entrySet()) {
                    builder.putPayload(entry.getKey(), toStringListValue(entry.getValue()));
                }
            }
            structs.add(builder.build());
        }
        try {
            client.upsertAsync(collectionName(), structs).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new VectorStoreException("Qdrant upsert 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorHit> search(float[] queryVector, VectorFilter filter, int limit) {
        // 与 InMemoryVectorStore 语义一致：空向量/非正 limit 直接返回空，不触碰 Qdrant
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        Points.SearchPoints.Builder search = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName())
                .setLimit(limit);
        for (float v : queryVector) {
            search.addVector(v);
        }
        Common.Filter.Builder qdrantFilter = Common.Filter.newBuilder();
        VectorFilter safeFilter = filter == null ? VectorFilter.none() : filter;
        if (safeFilter.excludeMealIds() != null && !safeFilter.excludeMealIds().isEmpty()) {
            Common.HasIdCondition.Builder hasId = Common.HasIdCondition.newBuilder();
            for (Long id : safeFilter.excludeMealIds()) {
                hasId.addHasId(Common.PointId.newBuilder().setNum(id));
            }
            qdrantFilter.addMustNot(Common.Condition.newBuilder().setHasId(hasId));
        }
        if (safeFilter.allergenTags() != null) {
            for (String allergen : safeFilter.allergenTags()) {
                qdrantFilter.addMustNot(Common.Condition.newBuilder().setField(
                        Common.FieldCondition.newBuilder()
                                .setKey("allergens")
                                .setMatch(Common.Match.newBuilder().setKeyword(allergen))));
            }
        }
        search.setFilter(qdrantFilter);

        try {
            List<Points.ScoredPoint> hits = client.searchAsync(search.build())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<VectorHit> result = new ArrayList<>(hits.size());
            for (Points.ScoredPoint hit : hits) {
                result.add(new VectorHit(hit.getId().getNum(), hit.getScore()));
            }
            return result;
        } catch (Exception e) {
            throw new VectorStoreException("Qdrant search 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void clear() {
        String name = collectionName();
        try {
            boolean exists = client.collectionExistsAsync(name).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (exists) {
                client.deleteCollectionAsync(name).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                log.info("Qdrant collection 已清理：{}", name);
            }
            // 重建为可用状态：删除后按身份重新创建，与 InMemoryVectorStore 的"清空"语义一致
            if (!ensureCollection()) {
                throw new VectorStoreException("Qdrant clear 后重建 collection 失败");
            }
        } catch (VectorStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new VectorStoreException("Qdrant clear 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            // collectionExists 是最轻的探测操作；collection 不存在也算可达
            client.collectionExistsAsync(collectionName()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Qdrant ping 失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** 字符串列表 → Qdrant keyword list payload；单元素时仍用 list，保证过滤语义一致。 */
    private JsonWithInt.Value toStringListValue(List<String> values) {
        List<String> safe = values == null ? List.of() : values;
        JsonWithInt.ListValue.Builder list = JsonWithInt.ListValue.newBuilder();
        for (String value : safe) {
            list.addValues(JsonWithInt.Value.newBuilder().setStringValue(value));
        }
        return JsonWithInt.Value.newBuilder().setListValue(list).build();
    }
}
