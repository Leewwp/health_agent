package com.diet.health.rag;

import java.util.List;

/** 检索结果：候选列表 + 实际使用的检索模式与降级原因。 */
public record RetrievalResult(List<RetrievalItem> items, RetrievalMode mode, String degradationReason,
                              RetrievalEvidence evidence) {
    public RetrievalResult(List<RetrievalItem> items, RetrievalMode mode, String degradationReason) {
        this(items, mode, degradationReason,
                RetrievalEvidence.notApplicable(items == null ? 0 : items.size()));
    }
}
