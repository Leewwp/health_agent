package com.diet.health.vectorstore;

/**
 * 向量检索命中。
 *
 * @param mealId 餐食主键
 * @param score  相似度分（Qdrant 为余弦相似度）
 */
public record VectorHit(long mealId, double score) {
}
