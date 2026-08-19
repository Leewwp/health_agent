package com.diet.health.rag;

/** 一次餐食检索的路径证据，不承载餐食事实。 */
public record RetrievalEvidence(
        int structuredCandidateCount,
        int vectorCandidateCount,
        int fusedCandidateCount,
        VectorRetrievalStatus vectorStatus,
        double vectorLatencyMs
) {
    public RetrievalEvidence {
        if (structuredCandidateCount < 0 || vectorCandidateCount < 0 || fusedCandidateCount < 0) {
            throw new IllegalArgumentException("候选数量不能为负数");
        }
        if (vectorStatus == null || vectorLatencyMs < 0 || Double.isNaN(vectorLatencyMs)) {
            throw new IllegalArgumentException("向量证据状态或延迟无效");
        }
    }

    public static RetrievalEvidence notApplicable(int structuredCandidateCount) {
        return new RetrievalEvidence(structuredCandidateCount, 0, 0,
                VectorRetrievalStatus.NOT_APPLICABLE, 0);
    }
}
