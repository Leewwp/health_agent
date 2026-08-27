package com.diet.health.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 两段式餐食召回路由：强约束优先结构化；无强约束的主观/长尾表达才进入 Hybrid。
 * 该分类只消费已经归一化的槽位和用户原话，不从字符串猜测过敏原或餐次。
 */
@Service
public class MealRetrievalRouter {
    private static final List<String> SUBJECTIVE_MARKERS = List.of(
            "清淡", "有食欲", "顶饱", "饱腹", "像妈妈做的", "家常", "解馋", "舒服", "长尾");

    private final MealRetriever structured;
    private final MealRetriever hybrid;

    @org.springframework.beans.factory.annotation.Autowired
    public MealRetrievalRouter(@Qualifier("structuredMealRetriever") MealRetriever structured,
                               @Qualifier("hybridMealRetriever") MealRetriever hybrid) {
        this.structured = structured;
        this.hybrid = hybrid;
    }

    public MealRetrievalRoute classify(MealRetrievalQuery query) {
        if (query == null || hasStrongConstraint(query)) {
            return MealRetrievalRoute.STRUCTURED;
        }
        String text = query.text() == null ? "" : query.text().toLowerCase(Locale.ROOT);
        return SUBJECTIVE_MARKERS.stream().anyMatch(text::contains)
                ? MealRetrievalRoute.SEMANTIC_EXPERIMENT
                : MealRetrievalRoute.STRUCTURED;
    }

    public RetrievalResult retrieve(MealRetrievalQuery query, int limit) {
        return retrieveWithDecision(query, limit).result();
    }

    public MealRetrievalDecision retrieveWithDecision(MealRetrievalQuery query, int limit) {
        MealRetrievalRoute route = classify(query);
        long start = System.nanoTime();
        boolean semantic = route == MealRetrievalRoute.SEMANTIC_EXPERIMENT;
        RetrievalResult result = semantic ? hybrid.retrieve(query, limit) : structured.retrieve(query, limit);
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        return new MealRetrievalDecision(route, semantic ? "HYBRID" : "STRUCTURED", result, elapsedMs);
    }

    static boolean hasStrongConstraint(MealRetrievalQuery query) {
        Map<String, List<String>> slots = query.slots();
        return (slots != null && !slots.getOrDefault("mealTime", List.of()).isEmpty())
                || (query.allergenTags() != null && !query.allergenTags().isEmpty())
                || (query.excludeIds() != null && !query.excludeIds().isEmpty());
    }
}
