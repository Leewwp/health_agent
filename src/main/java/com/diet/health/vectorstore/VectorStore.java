package com.diet.health.vectorstore;

import java.util.List;

/**
 * 餐食向量存储 seam（M5 #54 号票）。
 * <p>
 * 小型、可替换、可重建、可降级：MySQL 始终是餐食事实的真相源，本接口只提供
 * 可按 {@code provider + model + dimension + version} 身份重建的向量索引能力。
 * 生产实现走 Qdrant（明文 gRPC，本地面向演示）；内存实现供领域测试与离线演示使用。
 */
public interface VectorStore {

    /** collection 身份名：provider + model + dimension + version（决定重建目标）。 */
    String collectionName();

    /**
     * 检查/创建 collection（幂等）。Qdrant 不可达、维度与身份不匹配时返回 false，
     * 由调用方降级为结构化检索；不抛异常。
     */
    boolean ensureCollection();

    /** 幂等批量 upsert（同 mealId 覆盖已有 point）。Qdrant 故障时抛 {@link VectorStoreException}。 */
    void upsert(List<VectorPoint> points);

    /**
     * 向量检索：先应用 must_not 过滤（排除 ID、过敏原），再按相似度降序返回至多 limit 条。
     * Qdrant 故障时抛 {@link VectorStoreException}，由调用方降级。
     */
    List<VectorHit> search(float[] queryVector, VectorFilter filter, int limit);

    /** 清空整个 collection（重建支持）。Qdrant 故障时抛 {@link VectorStoreException}。 */
    void clear();

    /** 可用性探测（不可达返回 false，不抛异常）。 */
    boolean ping();

    /** 释放底层 client 资源（幂等，可安全重复调用）。 */
    void close();
}
