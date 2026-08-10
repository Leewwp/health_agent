package com.diet.health.module;

/** 作息事实（结构化事实标识 + 来源引用，不做向量检索）。 */
public record RoutineFact(
        String factId,
        String category,
        String fact,
        String sourceName,
        String sourceDetail
) {
}
