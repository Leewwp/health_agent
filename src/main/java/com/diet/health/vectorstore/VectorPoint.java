package com.diet.health.vectorstore;

import java.util.List;
import java.util.Map;

/**
 * 待索引的餐食向量点。
 *
 * @param mealId  餐食主键（MySQL meal_item.id，也是 Qdrant point ID）
 * @param vector  归一化向量（维度须与身份 dimension 一致）
 * @param payload 检索过滤所需的 keyword payload（如 meal_time/cuisine/allergens），
 *                不携带餐食事实字段——真相源始终是 MySQL
 */
public record VectorPoint(long mealId, float[] vector, Map<String, List<String>> payload) {
}
