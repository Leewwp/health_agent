package com.diet.health.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MealRetrievalRouterTest {
    private final MealRetriever structured = (q, limit) -> new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null);
    private final MealRetriever hybrid = (q, limit) -> new RetrievalResult(List.of(), RetrievalMode.HYBRID, null);
    private final MealRetrievalRouter router = new MealRetrievalRouter(structured, hybrid);

    @Test
    void 强约束优先结构化() {
        assertEquals(MealRetrievalRoute.STRUCTURED, router.classify(query(Map.of("mealTime", List.of("早餐")), "早餐来点清淡的")));
        assertEquals(MealRetrievalRoute.STRUCTURED, router.classify(new MealRetrievalQuery(Map.of(), List.of(), List.of("乳糖"), "来点清淡的")));
        assertEquals(MealRetrievalRoute.STRUCTURED, router.classify(new MealRetrievalQuery(Map.of(), List.of(12L), List.of(), "像妈妈做的菜")));
    }

    @Test
    void 无强约束的主观表达进入语义实验() {
        assertEquals(MealRetrievalRoute.SEMANTIC_EXPERIMENT, router.classify(query(Map.of(), "来点清淡的")));
        assertEquals(MealRetrievalRoute.SEMANTIC_EXPERIMENT, router.classify(query(Map.of(), "有没有像妈妈做的菜")));
    }

    @Test
    void 空文本和未知词保持结构化() {
        assertEquals(MealRetrievalRoute.STRUCTURED, router.classify(query(Map.of(), "")));
        assertEquals(MealRetrievalRoute.STRUCTURED, router.classify(query(Map.of(), "推荐一份午餐")));
    }

    @Test
    void 路由调用对应检索器() {
        MealRetrievalDecision semantic = router.retrieveWithDecision(query(Map.of(), "顶饱一点"), 5);
        assertEquals(RetrievalMode.HYBRID, semantic.result().mode());
        assertEquals(MealRetrievalRoute.SEMANTIC_EXPERIMENT, semantic.route());
        assertEquals("HYBRID", semantic.actualRetriever());
        MealRetrievalDecision constrained = router.retrieveWithDecision(
                query(Map.of("mealTime", List.of("午餐")), "顶饱一点"), 5);
        assertEquals(RetrievalMode.STRUCTURED, constrained.result().mode());
        assertEquals("STRUCTURED", constrained.actualRetriever());
    }

    private MealRetrievalQuery query(Map<String, List<String>> slots, String text) {
        return new MealRetrievalQuery(slots, List.of(), List.of(), text);
    }
}
