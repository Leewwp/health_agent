package com.diet.health.rag;

import java.util.Optional;

/** 支持区分查询向量与文档向量类型的可选 embedding 扩展。 */
public interface TypedEmbeddingClient extends EmbeddingClient {

    /** 生成文档入库向量（MiniMax 使用 type=db）。 */
    Optional<float[]> embedDocument(String text);
}
