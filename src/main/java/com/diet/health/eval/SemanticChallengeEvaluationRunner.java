package com.diet.health.eval;

import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetrievalRouter;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** semantic-challenge-v1 独立评测；人工标注未完成时只输出 NOT_RUN。 */
@Component
public class SemanticChallengeEvaluationRunner implements ApplicationRunner {
    static final String QUERY_SET = "diet/eval/semantic_challenge_v1.json";
    private final MealRetriever structured;
    private final MealRetriever hybrid;
    private final MealRetrievalRouter router;
    private final ReviewedMealReader reader;
    private final HealthResourceProvider provider;
    private final ObjectMapper objectMapper;
    private final boolean evalRun;
    private final String reportPath;

    public SemanticChallengeEvaluationRunner(@Qualifier("structuredMealRetriever") MealRetriever structured,
                                              @Qualifier("hybridMealRetriever") MealRetriever hybrid,
                                              MealRetrievalRouter router, ReviewedMealReader reader,
                                              HealthResourceProvider provider, ObjectMapper objectMapper,
                                              @Value("${diet.rag.semantic-eval-run:false}") boolean evalRun,
                                              @Value("${diet.rag.semantic-eval-report-path:data/reports/semantic_challenge_v1.json}") String reportPath) {
        this.structured = structured;
        this.hybrid = hybrid;
        this.router = router;
        this.reader = reader;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.evalRun = evalRun;
        this.reportPath = reportPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!evalRun) return;
        provider.providerMode().requireReviewedCapability("diet.rag.semantic-eval-run", "语义挑战评测");
        JsonNode root;
        try (var input = new ClassPathResource(QUERY_SET).getInputStream()) { root = objectMapper.readTree(input); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runAt", LocalDateTime.now().toString());
        report.put("challengeSetVersion", root.path("challengeSetVersion").asText());
        report.put("corpusVersion", "current-corpus-v1");
        report.put("queryCount", root.path("queries").size());
        if (!"COMPLETE".equalsIgnoreCase(root.path("annotationStatus").asText())) {
            report.put("status", "NOT_RUN");
            report.put("runClassification", "NOT_RUN");
            report.put("reason", "人工 Top-5 标注未完成；不得生成 Structured/Hybrid/TwoStage 效果指标。");
            report.put("topK", List.of(3, 5, 10));
            report.put("strategies", List.of("STRUCTURED", "HYBRID", "TWOSTAGE"));
        } else {
            report.put("status", "REAL_OR_DEGRADED");
            report.put("sourceMappingCount", reader.snapshotAll().size());
            report.put("results", evaluate(root));
        }
        Path target = Path.of(reportPath);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }

    private Map<String, Object> evaluate(JsonNode root) {
        Map<Long, String> sourceById = new LinkedHashMap<>();
        for (ReviewedMeal meal : reader.snapshotAll()) sourceById.put(meal.id(), meal.sourceId());
        List<SemanticChallengeQuery> queries = objectMapper.convertValue(root.path("queries"), new TypeReference<>() {});
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("STRUCTURED", evaluateRetriever(structured, queries, sourceById));
        results.put("HYBRID", evaluateRetriever(hybrid, queries, sourceById));
        results.put("TWOSTAGE", evaluateRouter(queries, sourceById));
        return results;
    }

    private Map<String, Object> evaluateRetriever(MealRetriever retriever, List<SemanticChallengeQuery> queries,
                                                   Map<Long, String> sourceById) {
        String route = retriever == structured ? "STRUCTURED" : "SEMANTIC_EXPERIMENT";
        return evaluateResults(queries, sourceById,
                q -> new EvaluationOutcome(route, retriever.retrieve(toQuery(q, sourceById), 10)));
    }

    private Map<String, Object> evaluateRouter(List<SemanticChallengeQuery> queries, Map<Long, String> sourceById) {
        return evaluateResults(queries, sourceById, q -> {
            var decision = router.retrieveWithDecision(toQuery(q, sourceById), 10);
            return new EvaluationOutcome(decision.route().name(), decision.result());
        });
    }

    private Map<String, Object> evaluateResults(List<SemanticChallengeQuery> queries, Map<Long, String> sourceById,
                                                java.util.function.Function<SemanticChallengeQuery, EvaluationOutcome> fn) {
        // 每个策略/问题只检索一次，复用同一份 top-10 结果计算多个 Top-K，避免重复调用外部 embedding 服务。
        List<EvaluationSample> samples = new ArrayList<>(queries.size());
        for (SemanticChallengeQuery query : queries) {
            long start = System.nanoTime();
            EvaluationOutcome outcome = fn.apply(query);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            samples.add(new EvaluationSample(query, outcome, latencyMs));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (int topK : List.of(3, 5, 10)) {
            double recall = 0, latency = 0;
            int violations = 0;
            Map<String, Integer> routes = new LinkedHashMap<>();
            Map<String, Integer> actualModes = new LinkedHashMap<>();
            Map<String, Integer> degradation = new LinkedHashMap<>();
            for (EvaluationSample sample : samples) {
                SemanticChallengeQuery query = sample.query();
                EvaluationOutcome outcome = sample.outcome();
                RetrievalResult result = outcome.result();
                latency += sample.latencyMs();
                List<String> ids = result.items().stream().map(RetrievalItem::meal)
                        .map(item -> sourceById.get(item.id())).filter(java.util.Objects::nonNull).limit(topK).toList();
                List<String> expected = query.expectedTop5() == null ? List.of() : query.expectedTop5();
                recall += expected.isEmpty() ? 0 : (double) ids.stream().filter(expected::contains).count() / expected.size();
                boolean excluded = query.excludeSourceIds() != null && query.excludeSourceIds().stream().anyMatch(ids::contains);
                boolean allergen = result.items().stream().limit(topK).map(RetrievalItem::reviewedMeal)
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(meal -> com.diet.health.reader.meal.MealAllergenConstraint.intersects(meal, query.allergens()));
                if (excluded || allergen) violations++;
                routes.merge(outcome.routeCategory(), 1, Integer::sum);
                actualModes.merge(result.mode().name(), 1, Integer::sum);
                degradation.merge(result.degradationReason() == null ? "无" : result.degradationReason(), 1, Integer::sum);
            }
            out.put("top" + topK, Map.of("avgRecall", queries.isEmpty() ? 0 : recall / queries.size(),
                    "hardConstraintViolations", violations, "avgLatencyMs", queries.isEmpty() ? 0 : latency / queries.size(),
                    "routeCategories", routes, "actualModes", actualModes, "degradationReasons", degradation));
        }
        return out;
    }

    private MealRetrievalQuery toQuery(SemanticChallengeQuery query, Map<Long, String> sourceById) {
        List<Long> excludeIds = sourceById.entrySet().stream()
                .filter(entry -> query.excludeSourceIds() != null && query.excludeSourceIds().contains(entry.getValue()))
                .map(Map.Entry::getKey).toList();
        return new MealRetrievalQuery(query.slots() == null ? Map.of() : query.slots(), excludeIds,
                query.allergens() == null ? List.of() : query.allergens(), query.text());
    }

    private record EvaluationOutcome(String routeCategory, RetrievalResult result) {
    }

    private record EvaluationSample(SemanticChallengeQuery query, EvaluationOutcome outcome, double latencyMs) {
    }
}
