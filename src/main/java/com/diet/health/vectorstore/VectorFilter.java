package com.diet.health.vectorstore;

import java.util.List;

/**
 * 向量检索过滤条件（must_not 语义，先于相似度排序生效）。
 *
 * @param excludeMealIds 必须排除的餐食 ID
 * @param allergenTags   命中即排除的过敏原标签
 */
public record VectorFilter(List<Long> excludeMealIds, List<String> allergenTags) {

    public static VectorFilter none() {
        return new VectorFilter(List.of(), List.of());
    }
}
