package com.diet.health.rag;

/** 检索模式：STRUCTURED = 只走结构化（含降级），HYBRID = 结构化 + 语义合并。 */
public enum RetrievalMode {
    STRUCTURED,
    HYBRID
}
