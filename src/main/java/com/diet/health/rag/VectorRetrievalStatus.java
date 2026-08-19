package com.diet.health.rag;

/** 向量阶段的可解释状态，用于评估报告和降级诊断。 */
public enum VectorRetrievalStatus {
    AVAILABLE,
    STORE_UNAVAILABLE,
    EMBEDDING_UNAVAILABLE,
    NO_HITS,
    NOT_APPLICABLE
}
