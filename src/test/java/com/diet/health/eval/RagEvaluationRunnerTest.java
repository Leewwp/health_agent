package com.diet.health.eval;

import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagEvaluationRunner 测试（33 号票验收；#70 迁移到审核读取模块替身并补模式隔离）。
 * 验证：开关关闭时零读取、fixture 显式启用 fail-fast、sourceId 映射来自审核快照、
 * 报告写出契约（sourceMappingCount 与真实快照一致）。
 */
class RagEvaluationRunnerTest {

    private MealRetriever structuredRetriever;
    private MealRetriever mealRetriever;
    private ReviewedMealReader reviewedMealReader;
    private HealthResourceProvider resourceProvider;
    private RagEvaluationRunner runner;
    private Path reportPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        structuredRetriever = mock(MealRetriever.class);
        mealRetriever = mock(MealRetriever.class);
        reviewedMealReader = mock(ReviewedMealReader.class);
        resourceProvider = mock(HealthResourceProvider.class);
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        when(structuredRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null));
        when(mealRetriever.retrieve(any(), anyInt()))
                .thenReturn(new RetrievalResult(List.of(), RetrievalMode.HYBRID, null));
        reportPath = tempDir.resolve("rag_evaluation.json");
        runner = new RagEvaluationRunner(structuredRetriever, mealRetriever, reviewedMealReader,
                new ObjectMapper(), resourceProvider, true, reportPath.toString());
    }

    @Test
    void 开关关闭时零读取() throws Exception {
        runner = new RagEvaluationRunner(structuredRetriever, mealRetriever, reviewedMealReader,
                new ObjectMapper(), resourceProvider, false, reportPath.toString());
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
    void 报告sourceMappingCount与审核快照一致() throws Exception {
        when(reviewedMealReader.snapshotAll()).thenReturn(List.of(meal(1L, "src-1"), meal(2L, "src-2")));

        runner.run(null);

        assertTrue(Files.exists(reportPath), "评估报告必须写出");
        String json = Files.readString(reportPath);
        Map<?, ?> report = new ObjectMapper().readValue(json, Map.class);
        assertEquals(2, report.get("sourceMappingCount"), "sourceMappingCount 必须与真实审核快照一致");
        assertEquals(3, report.get("topK"));
        assertEquals(10, report.get("queryCount"), "固定标注查询集 10 条");
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
