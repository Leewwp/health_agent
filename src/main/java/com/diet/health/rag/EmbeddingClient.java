package com.diet.health.rag;

import java.util.Optional;

/**
 * Embedding 客户端 seam（33 号票 RAG）。
 * 生产实现走 DashScope text-embedding；不可用（无 key、超时、异常）时返回 empty，
 * 由 hybrid 检索器降级为结构化检索，不阻塞推荐。
 */
public interface EmbeddingClient {

    /** 生成文本向量。失败/未配置返回 empty（不抛异常）。 */
    Optional<float[]> embed(String text);

    /** 模型名（text-embedding-v3）。 */
    String modelName();

    /** 模型版本（与 meal_item_embedding.model_version 对齐）。 */
    String modelVersion();

    /** 是否已配置真实模型（占位 key 视为未配置）。 */
    boolean configured();
}
