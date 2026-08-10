package com.diet.health.eval;

import java.util.List;
import java.util.Map;

/**
 * 固定标注查询（RAG 评估集，见 src/main/resources/diet/eval/labeled_meal_queries.json）。
 *
 * @param id                查询 ID
 * @param text              嵌入文本（可为空，用槽位拼接）
 * @param slots             检索槽位
 * @param allergens         过敏原硬约束
 * @param excludeSourceIds  排除来源 ID（硬约束）
 * @param expectedSourceIds 期望相关餐食的来源 ID（Recall@3 真值）
 */
public record LabeledMealQuery(
        String id,
        String text,
        Map<String, List<String>> slots,
        List<String> allergens,
        List<String> excludeSourceIds,
        List<String> expectedSourceIds
) {
}
