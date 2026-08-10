package com.diet.health.rag;

import com.diet.model.MealItem;

/**
 * 单条检索结果。
 *
 * @param structuredScore 结构化标签分（旧链路重排分，[0,1]）
 * @param semanticScore   语义余弦分（[0,1]，纯结构化路径为 null）
 * @param mergedScore     合并分（结构化路径=结构化分；hybrid=0.5*归一结构分+0.5*语义分）
 */
public record RetrievalItem(MealItem meal, double structuredScore, Double semanticScore, double mergedScore) {
}
