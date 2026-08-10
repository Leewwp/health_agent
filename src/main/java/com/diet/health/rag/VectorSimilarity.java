package com.diet.health.rag;

/** 向量相似度数学工具（进程内暴力余弦，不引入向量数据库）。 */
public final class VectorSimilarity {

    private VectorSimilarity() {
    }

    /**
     * 余弦相似度。空向量、零向量或维度不一致返回 0。
     * 输入向量约定为归一化向量时，结果等于点积。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
