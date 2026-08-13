package com.diet.health.eval;

import com.diet.health.rag.MealRetriever;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估运行器（33 号票验收；#70 迁移到审核读取模块并补模式隔离）。
 * <p>
 * 使用固定标注查询集（classpath diet/eval/labeled_meal_queries.json）对比
 * structured-only 与 hybrid 的 Recall@3、硬约束命中率和 Embedding 降级次数，
 * 结果写入 JSON 报告（diet.rag.eval-run=true 时启动执行）。
 * sourceId 映射从审核读取快照获得，不再处理 Mapper 行对象。
 * 本 runner 是 REVIEWED_DB 能力：FIXTURE_SEED 下显式启用必须 fail-fast。
 * hybrid 没有可复现提升时只记录结论，不宣称效果。
 */
@Component
public class RagEvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationRunner.class);

    private static final String QUERY_SET = "diet/eval/labeled_meal_queries.json";
    private static final int TOP_K = 3;

    static final String SWITCH_NAME = "diet.rag.eval-run";

    private final MealRetriever structuredRetriever;
    private final MealRetriever mealRetriever;
    private final ReviewedMealReader reviewedMealReader;
    private final ObjectMapper objectMapper;
    private final HealthResourceProvider resourceProvider;
    private final boolean evalRun;
    private final String reportPath;

    public RagEvaluationRunner(
            @Qualifier("structuredMealRetriever") MealRetriever structuredRetriever,
            @Qualifier("mealRetriever") MealRetriever mealRetriever,
            ReviewedMealReader reviewedMealReader,
            ObjectMapper objectMapper,
            HealthResourceProvider resourceProvider,
            @Value("${diet.rag.eval-run:false}") boolean evalRun,
            @Value("${diet.rag.eval-report-path:data/reports/rag_evaluation.json}") String reportPath
    ) {
        this.structuredRetriever = structuredRetriever;
        this.mealRetriever = mealRetriever;
        this.reviewedMealReader = reviewedMealReader;
        this.objectMapper = objectMapper;
        this.resourceProvider = resourceProvider;
        this.evalRun = evalRun;
        this.reportPath = reportPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!evalRun) {
            return;
        }
        resourceProvider.providerMode().requireReviewedCapability(SWITCH_NAME, "正式 DB RAG 评估");
        List<LabeledMealQuery> queries;
        try (InputStream in = new ClassPathResource(QUERY_SET).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            queries = objectMapper.readValue(json, new TypeReference<>() {
            });
        }
        Map<Long, String> sourceById = new HashMap<>();
        for (ReviewedMeal meal : reviewedMealReader.snapshotAll()) {
            sourceById.put(meal.id(), meal.sourceId());
        }
        RecallEvaluation evaluation = RecallEvaluationService.evaluate(
                structuredRetriever, mealRetriever, queries, sourceById, TOP_K);

        Map<String, Object> report = Map.of(
                "runAt", java.time.LocalDateTime.now().toString(),
                "topK", TOP_K,
                "queryCount", queries.size(),
                "sourceMappingCount", sourceById.size(),
                "structured", evaluation.structured(),
                "hybrid", evaluation.hybrid()
        );
        Path target = Path.of(reportPath);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        log.info("RAG 评估完成：structured Recall@3={} 硬约束={} 降级={}；hybrid Recall@3={} 硬约束={} 降级={} → {}",
                evaluation.structured().avgRecallAt3(),
                evaluation.structured().hardConstraintHitRate(),
                evaluation.structured().degradedCount(),
                evaluation.hybrid().avgRecallAt3(),
                evaluation.hybrid().hardConstraintHitRate(),
                evaluation.hybrid().degradedCount(),
                reportPath);
    }
}
