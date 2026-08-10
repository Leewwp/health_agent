package com.diet.health.rag;

import java.util.List;
import java.util.Map;

/**
 * 餐食检索查询（33 号票 RAG seam）。
 *
 * @param slots        健康槽位（7 维饮食标签 + 领域槽位）
 * @param excludeIds   需要排除的资源 ID（如会话中已推荐过的）
 * @param allergenTags 过敏原硬约束（命中即排除，先于任何打分）
 * @param text         嵌入文本（槽位语义的补充，为空时用槽位值拼接兜底）
 */
public record MealRetrievalQuery(
        Map<String, List<String>> slots,
        List<Long> excludeIds,
        List<String> allergenTags,
        String text
) {
}
