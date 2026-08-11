package com.diet.health.vectorstore;

import java.util.List;

/**
 * 向量检索过滤条件（must_not/must 语义，先于相似度排序生效，M5 #52 扩展）。
 *
 * @param excludeMealIds 必须排除的餐食 ID
 * @param allergenTags   命中即排除的过敏原标签
 * @param reviewStatus   payload review_status 必须精确等于的值（如 APPROVED），null=不约束
 * @param sourceType     payload source_type 必须精确等于的值（如 PUBLIC），null=不约束
 */
public record VectorFilter(List<Long> excludeMealIds, List<String> allergenTags,
                           String reviewStatus, String sourceType) {

    /** payload 键：过敏原标签列表（与 VectorIndexingRunner 写入侧一致）。 */
    public static final String KEY_ALLERGENS = "allergens";
    /** payload 键：审核状态（VectorIndexingRunner 写入 APPROVED/PENDING）。 */
    public static final String KEY_REVIEW_STATUS = "review_status";
    /** payload 键：来源类型（VectorIndexingRunner 写入 PUBLIC/PERSONAL）。 */
    public static final String KEY_SOURCE_TYPE = "source_type";

    public VectorFilter(List<Long> excludeMealIds, List<String> allergenTags) {
        this(excludeMealIds, allergenTags, null, null);
    }

    public static VectorFilter none() {
        return new VectorFilter(List.of(), List.of(), null, null);
    }
}
