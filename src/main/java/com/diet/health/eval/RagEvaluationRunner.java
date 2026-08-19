package com.diet.health.eval;

import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.HybridMealRetriever;
import com.diet.health.rag.MealRetriever;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.vectorstore.VectorStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估运行器（33 号票验收；#70 迁移到审核读取模块并补模式隔离；#77 扩展）。
 * <p>
 * 使用固定标注查询集（classpath diet/eval/labeled_meal_queries.json，querySetVersion 随文件）
 * 评估结构化与 hybrid 的 Recall@3/MRR/NDCG@3/Precision@3/硬约束命中率/降级分布/P95 延迟，
 * 并按 #77 组织消融：融合权重 0.3/0.5/0.7 × 用户原话/槽位拼接嵌入文本，权重经测试接缝
 * 显式注入（生产 mealRetriever bean 默认 0.5，不被动修改线上权重）。
 * 结果写入 JSON 报告（diet.rag.eval-run=true 时启动执行）。
 * sourceId 映射从审核读取快照获得，不再处理 Mapper 行对象。
 * 本 runner 是 REVIEWED_DB 能力：FIXTURE_SEED 下显式启用必须 fail-fast。
 * 无 API key/Qdrant 的降级运行只能证明降级正确性，报告如实标注，不作为 Hybrid 效果数字。
 */
@Component
public class RagEvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationRunner.class);

    private static final String QUERY_SET = "diet/eval/labeled_meal_queries.json";
    private static final int TOP_K = 3;

    /** #77 消融权重：0.3 / 0.5（默认）/ 0.7。 */
    static final double[] ABLATION_WEIGHTS = {0.3, 0.5, 0.7};

    static final String SWITCH_NAME = "diet.rag.eval-run";

    private final MealRetriever structuredRetriever;
    private final MealRetriever mealRetriever;
    private final ReviewedMealReader reviewedMealReader;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final HealthResourceProvider resourceProvider;
    private final boolean evalRun;
    private final String reportPath;
    private final String gitCommit;
    private final String embeddingProvider;

    public RagEvaluationRunner(
            @Qualifier("structuredMealRetriever") MealRetriever structuredRetriever,
            @Qualifier("mealRetriever") MealRetriever mealRetriever,
            ReviewedMealReader reviewedMealReader,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            ObjectMapper objectMapper,
            HealthResourceProvider resourceProvider,
            @Value("${diet.rag.eval-run:false}") boolean evalRun,
            @Value("${diet.rag.eval-report-path:data/reports/rag_evaluation.json}") String reportPath,
            @Value("${diet.rag.git-commit:unknown}") String gitCommit,
            @Value("${diet.vectorstore.provider:dashscope}") String embeddingProvider
    ) {
        this.structuredRetriever = structuredRetriever;
        this.mealRetriever = mealRetriever;
        this.reviewedMealReader = reviewedMealReader;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.resourceProvider = resourceProvider;
        this.evalRun = evalRun;
        this.reportPath = reportPath;
        this.gitCommit = gitCommit;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!evalRun) {
            return;
        }
        resourceProvider.providerMode().requireReviewedCapability(SWITCH_NAME, "正式 DB RAG 评估");
        LabeledQuerySet querySet;
        try (InputStream in = new ClassPathResource(QUERY_SET).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (json.trim().startsWith("[")) {
                // 兼容旧结构：顶层数组
                List<LabeledMealQuery> legacy = objectMapper.readValue(json, new TypeReference<>() {
                });
                querySet = LabeledQuerySet.of(legacy);
            } else {
                querySet = objectMapper.readValue(json, LabeledQuerySet.class);
            }
        }
        List<LabeledMealQuery> queries = querySet.queries();
        Map<Long, String> sourceById = new LinkedHashMap<>();
        for (ReviewedMeal meal : reviewedMealReader.snapshotAll()) {
            sourceById.put(meal.id(), meal.sourceId());
        }

        // 基线：结构化 vs 生产 hybrid（0.5 权重 + 用户原话，即线上默认口径）
        RetrieverEvaluation structured = RecallEvaluationService.evaluateRetriever(
                structuredRetriever, queries, sourceById, TOP_K, false, EmbeddingTextMode.USER_TEXT);
        RetrieverEvaluation defaultHybrid = RecallEvaluationService.evaluateRetriever(
                mealRetriever, queries, sourceById, TOP_K, true, EmbeddingTextMode.USER_TEXT);

        // 消融矩阵：权重 0.3/0.5/0.7 × 用户原话/槽位拼接嵌入文本。
        // 权重经测试接缝显式构造变体；0.5 的槽位拼接变体仅用于消融对比，不改变生产 bean。
        Map<String, RetrieverEvaluation> ablations = new LinkedHashMap<>();
        for (double weight : ABLATION_WEIGHTS) {
            for (EmbeddingTextMode mode : EmbeddingTextMode.values()) {
                if (weight == 0.5 && mode == EmbeddingTextMode.USER_TEXT) {
                    continue; // 与 defaultHybrid（生产 bean）口径相同，不重复执行
                }
                MealRetriever variant = new HybridMealRetriever(
                        structuredRetriever, embeddingClient, vectorStore, reviewedMealReader, weight);
                String key = "hybrid_" + mode.name().toLowerCase() + "_" + weight;
                ablations.put(key, RecallEvaluationService.evaluateRetriever(
                        variant, queries, sourceById, TOP_K, true, mode));
            }
        }

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("gitCommit", gitCommit);
        environment.put("querySetVersion", querySet.querySetVersion());
        environment.put("resourceVersion", resourceProvider.resourceVersion());
        environment.put("embeddingProvider", embeddingProvider);
        environment.put("embeddingModel", embeddingClient.modelName());
        environment.put("embeddingModelVersion", embeddingClient.modelVersion());
        environment.put("embeddingDimension", vectorStore.dimension());
        environment.put("collection", vectorStore.collectionName());
        environment.put("fusionWeights", List.of(0.3, 0.5, 0.7));

        boolean degradedRun = defaultHybrid.degradedCount() == queries.size() && !queries.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runAt", java.time.LocalDateTime.now().toString());
        report.put("degradedRun", degradedRun);
        report.put("degradedRunNote", degradedRun
                ? "降级运行（未配置真实 API key 或向量存储不可用，hybrid " + defaultHybrid.degradedCount()
                + "/" + queries.size() + " 条按设计降级为结构化检索）："
                + "本报告只验证降级正确性，不作为 Hybrid 效果数字；对外效果只引用零降级或明确分层说明的真实运行。"
                : "零降级运行：hybrid 全程走真实语义融合，指标可作效果数字引用。");
        report.put("topK", TOP_K);
        report.put("queryCount", queries.size());
        report.put("sourceMappingCount", sourceById.size());
        report.put("environment", environment);
        report.put("structured", structured);
        report.put("hybrid", defaultHybrid);
        report.put("runClassification", classify(defaultHybrid, queries.size()));
        report.put("metricComparison", metricComparison(structured, defaultHybrid));
        report.put("fallbackEvidence", Map.of(
                "structuredIsHardConstraintBaseline", true,
                "hybridDegradedCount", defaultHybrid.degradedCount(),
                "degradationDistribution", defaultHybrid.degradationDistribution(),
                "interpretation", defaultHybrid.degradedCount() > 0
                        ? "本次运行包含结构化故障降级；Hybrid 指标不可单独作为零降级效果数字"
                        : "本次运行无故障降级；结构化基线仍保留用于对照"));
        report.put("ablations", ablations);

        Path target = Path.of(reportPath);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        log.info("RAG 评估完成：structured Recall@3={} MRR={} NDCG@3={} P@3={} 硬约束={}；"
                        + "hybrid Recall@3={} MRR={} NDCG@3={} P@3={} 硬约束={} 降级={}（P95 {}ms）→ {}",
                structured.avgRecallAt3(), structured.avgMrr(), structured.avgNdcgAt3(),
                structured.avgPrecisionAt3(), structured.hardConstraintHitRate(),
                defaultHybrid.avgRecallAt3(), defaultHybrid.avgMrr(), defaultHybrid.avgNdcgAt3(),
                defaultHybrid.avgPrecisionAt3(), defaultHybrid.hardConstraintHitRate(),
                defaultHybrid.degradedCount(), defaultHybrid.p95LatencyMs(), reportPath);
    }

    private String classify(RetrieverEvaluation hybrid, int queryCount) {
        if (hybrid.degradedCount() == 0) {
            return "REAL_HYBRID";
        }
        return hybrid.degradedCount() == queryCount ? "FALLBACK_ONLY" : "PARTIAL_HYBRID";
    }

    private Map<String, Object> metricComparison(RetrieverEvaluation structured,
                                                  RetrieverEvaluation hybrid) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("recallAt3Delta", hybrid.avgRecallAt3() - structured.avgRecallAt3());
        comparison.put("mrrDelta", hybrid.avgMrr() - structured.avgMrr());
        comparison.put("ndcgAt3Delta", hybrid.avgNdcgAt3() - structured.avgNdcgAt3());
        comparison.put("precisionAt3Delta", hybrid.avgPrecisionAt3() - structured.avgPrecisionAt3());
        comparison.put("p95LatencyMsDelta", hybrid.p95LatencyMs() - structured.p95LatencyMs());
        return comparison;
    }
}
