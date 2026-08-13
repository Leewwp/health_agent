package com.diet.health.eval;

import java.util.List;
import java.util.Map;

/**
 * 评测查询集（#77：JSON 顶层带 querySetVersion 与说明，与查询列表一起反序列化）。
 *
 * @param querySetVersion 查询集版本（如 1.1.0）
 * @param note            查询集说明（可空，仅文档用途）
 * @param queries         查询列表
 */
public record LabeledQuerySet(String querySetVersion, String note, List<LabeledMealQuery> queries) {

    /**
     * 兼容旧 JSON 结构（顶层直接是数组）：版本标记 unknown。
     */
    public static LabeledQuerySet of(List<LabeledMealQuery> queries) {
        return new LabeledQuerySet("unknown", null, queries);
    }
}
