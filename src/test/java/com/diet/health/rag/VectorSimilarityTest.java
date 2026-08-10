package com.diet.health.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 余弦相似度纯数学测试：已知字面量验证（含归一化向量点积）。 */
class VectorSimilarityTest {

    @Test
    void 相同方向余弦为1() {
        assertEquals(1.0, VectorSimilarity.cosine(new float[]{1f, 0f}, new float[]{1f, 0f}), 1e-6);
        assertEquals(1.0, VectorSimilarity.cosine(new float[]{1f, 1f}, new float[]{2f, 2f}), 1e-6);
    }

    @Test
    void 垂直向量余弦为0() {
        assertEquals(0.0, VectorSimilarity.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}), 1e-6);
    }

    @Test
    void 相反方向余弦为负1() {
        assertEquals(-1.0, VectorSimilarity.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f}), 1e-6);
    }

    @Test
    void 部分重合余弦在0到1之间() {
        double cosine = VectorSimilarity.cosine(new float[]{1f, 1f}, new float[]{1f, 0f});
        assertEquals(1.0 / Math.sqrt(2), cosine, 1e-6);
    }

    @Test
    void 归一化向量余弦等于点积() {
        float[] a = {3f, 4f};
        float[] b = {0f, 5f};
        assertEquals(4.0 / 5.0, VectorSimilarity.cosine(a, b), 1e-6);
    }

    @Test
    void 空向量或维度不一致返回0() {
        assertEquals(0.0, VectorSimilarity.cosine(null, new float[]{1f}), 1e-6);
        assertEquals(0.0, VectorSimilarity.cosine(new float[]{1f}, null), 1e-6);
        assertEquals(0.0, VectorSimilarity.cosine(new float[]{}, new float[]{}), 1e-6);
        assertEquals(0.0, VectorSimilarity.cosine(new float[]{1f, 0f}, new float[]{1f}), 1e-6);
    }

    @Test
    void 零向量返回0() {
        assertEquals(0.0, VectorSimilarity.cosine(new float[]{0f, 0f}, new float[]{1f, 0f}), 1e-6);
    }
}
