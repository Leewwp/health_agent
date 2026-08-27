package com.diet.health.rag;

/** 路由决策及实际检索结果，用于业务 Trace 与评测证据。 */
public record MealRetrievalDecision(
        MealRetrievalRoute route,
        String actualRetriever,
        RetrievalResult result,
        double elapsedMs
) {
}
