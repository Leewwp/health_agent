package com.diet.health.eval;

import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import com.diet.health.vectorstore.InMemoryVectorStore;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagEvaluationRunner 测试（33 号票验收；#70 迁移到审核读取模块替身并补模式隔离；#77 扩展）。
 * 验证：开关关闭时零读取、fixture 显式启用 fail-fast、sourceId 映射来自审核快照、
 * 报告写出契约（60 条查询、querySetVersion、环境身份、降级标注、消融矩阵）。
 */
class RagEvaluationRunnerTest {

    private MealRetriever structuredRetriever;
    private MealRetriever mealRetriever;
    private ReviewedMealReader reviewedMealReader;
    private HealthResourceProvider resourceProvider;
    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private RagEvaluationRunner runner;
    private Path reportPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        structuredRetriever = mock(MealRetriever.class);
        mealRetriever = mock(MealRetriever.class);
        reviewedMealReader = mock(ReviewedMealReader.class);
        resourceProvider = mock(HealthResourceProvider.class);
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.modelName()).thenReturn("text-embedding-v3");
        when(embeddingClient.modelVersion()).thenReturn("v3-1024");
        when(embeddingClient.configured()).thenReturn(false);
        vectorStore = new InMemoryVectorStore(
                new VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024"));
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        when(resourceProvider.resourceVersion()).thenReturn("reviewed-2026-08-10-v1");
        when(structuredRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null));
        // 无 API key 环境：生产 hybrid 全量降级（与 embeddingClient.configured()=false 口径一致）
        when(mealRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, "embedding_unavailable"));
        reportPath = tempDir.resolve("rag_evaluation.json");
        runner = newRunner(true, reportPath.toString());
    }

    private RagEvaluationRunner newRunner(boolean evalRun, String reportPath) {
        return new RagEvaluationRunner(structuredRetriever, mealRetriever, reviewedMealReader,
                embeddingClient, vectorStore, new ObjectMapper(), resourceProvider,
                evalRun, reportPath, "e302b9e", "dashscope");
    }

    @Test
    void 开关关闭时零读取() throws Exception {
        runner = newRunner(false, reportPath.toString());
        runner.run(null);
        verify(reviewedMealReader, never()).snapshotAll();
        assertTrue(!Files.exists(reportPath), "eval-run=false 时不应写出报告");
    }

    @Test
    void fixture显式启用时failFast且错误包含模式与开关名() throws Exception {
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(null),
                "fixture + eval-run=true 必须启动失败");
        assertTrue(error.getMessage().contains("FIXTURE_SEED"), "错误信息必须包含模式: " + error.getMessage());
        assertTrue(error.getMessage().contains(RagEvaluationRunner.SWITCH_NAME), "错误信息必须包含开关名");
        verify(reviewedMealReader, never()).snapshotAll();
    }

    @Test
    void 报告写出60条查询与环境身份() throws Exception {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, "src-1"), meal(2L, "src-2")));

        runner.run(null);

        assertTrue(Files.exists(reportPath), "评估报告必须写出");
        String json = Files.readString(reportPath);
        Map<?, ?> report = new ObjectMapper().readValue(json, Map.class);
        assertEquals(2, report.get("sourceMappingCount"), "sourceMappingCount 必须与真实审核快照一致");
        assertEquals(3, report.get("topK"));
        assertEquals(60, report.get("queryCount"), "#77 固定标注查询集 60 条");
        assertTrue((Boolean) report.get("degradedRun"), "未配置真实 API key 时必须标注降级运行");
        assertNotNull(report.get("degradedRunNote"));
        // 环境身份
        Map<?, ?> environment = (Map<?, ?>) report.get("environment");
        assertEquals("1.1.0", environment.get("querySetVersion"));
        assertEquals("e302b9e", environment.get("gitCommit"));
        assertEquals("reviewed-2026-08-10-v1", environment.get("resourceVersion"));
        assertEquals("dashscope", environment.get("embeddingProvider"));
        assertEquals("text-embedding-v3", environment.get("embeddingModel"));
        assertEquals(1024, environment.get("embeddingDimension"));
        assertEquals("meal_dashscope_text-embedding-v3_1024_v3-1024", environment.get("collection"));
        assertEquals(List.of(0.3, 0.5, 0.7), environment.get("fusionWeights"));
        // 主对比与消融矩阵
        assertNotNull(report.get("structured"));
        assertNotNull(report.get("hybrid"));
        Map<?, ?> ablations = (Map<?, ?>) report.get("ablations");
        assertTrue(ablations.containsKey("hybrid_slot_concat_0.5"), "槽位拼接消融必须存在");
        assertTrue(ablations.containsKey("hybrid_slot_concat_0.3"), "0.3 权重消融必须存在");
        assertTrue(ablations.containsKey("hybrid_slot_concat_0.7"), "0.7 权重消融必须存在");
        assertTrue(ablations.containsKey("hybrid_user_text_0.3"), "用户原话 0.3 权重消融必须存在");
        assertTrue(ablations.containsKey("hybrid_user_text_0.7"), "用户原话 0.7 权重消融必须存在");
        assertFalse(ablations.containsKey("hybrid_user_text_0.5"), "0.5 用户原话与生产 bean 重复，不重复执行");
    }

    @Test
    void 消融变体逐条查询并计降级分布() throws Exception {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, "src-1"), meal(2L, "src-2")));
        // 生产 hybrid 与所有消融变体都走降级路径
        when(mealRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, "embedding_unavailable"));

        runner.run(null);

        String json = Files.readString(reportPath);
        Map<?, ?> report = new ObjectMapper().readValue(json, Map.class);
        Map<?, ?> hybrid = (Map<?, ?>) report.get("hybrid");
        assertEquals(60, hybrid.get("degradedCount"), "无 API key 时全部 60 条降级");
        Map<?, ?> dist = (Map<?, ?>) hybrid.get("degradationDistribution");
        assertEquals(60, dist.get("embedding_unavailable"), "降级分布必须记录 embedding_unavailable 原因");
        assertNotNull(hybrid.get("p95LatencyMs"));
        assertNotNull(hybrid.get("avgMrr"));
        assertNotNull(hybrid.get("avgNdcgAt3"));
        assertNotNull(hybrid.get("avgPrecisionAt3"));
        // 逐查询明细带分层
        List<?> queries = (List<?>) hybrid.get("queries");
        assertEquals(60, queries.size());
        Map<?, ?> first = (Map<?, ?>) queries.get(0);
        assertNotNull(first.get("stratum"));
        assertNotNull(first.get("mrr"));
        assertNotNull(first.get("ndcgAt3"));
        assertNotNull(first.get("precisionAt3"));
    }

    @Test
    void 零降级运行时不标注降级() throws Exception {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, "src-1"), meal(2L, "src-2")));
        when(embeddingClient.configured()).thenReturn(true);
        when(embeddingClient.embed(any())).thenReturn(Optional.of(new float[]{1.0f}));
        when(mealRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.HYBRID, null));

        runner.run(null);

        String json = Files.readString(reportPath);
        Map<?, ?> report = new ObjectMapper().readValue(json, Map.class);
        assertFalse((Boolean) report.get("degradedRun"), "配置 key 且向量存储可用时不标注降级");
    }

    private ReviewedMeal meal(Long id, String sourceId) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", List.of());
        return new ReviewedMeal(
                id, "测试餐", null, List.of(), tags, null, List.of(),
                new ReviewedMeal.Serving(0, BigDecimal.ONE, "份"),
                new ReviewedMeal.Nutrition(null, null, null, null, null, false),
                List.of(), "REVIEWED", "APPROVED", "NONE", null,
                "foodcom-recipes-and-reviews-v2", sourceId, "v2", "PUBLIC"
        );
    }
}
