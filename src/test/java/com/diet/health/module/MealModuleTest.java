package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.MealRetrievalRouter;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import com.diet.health.seed.SeedResources;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 餐食模块：旧链路 searchAndRank 保持；健康链路 REVIEWED_DB 走 MealRetriever seam，
 * FIXTURE_SEED 走种子确定性路径（#69：完全绕过 MealRetriever/EmbeddingClient/审核读取模块）。
 */
class MealModuleTest {

    private final MealSearchService search = mock(MealSearchService.class);
    private final MealRetriever retriever = mock(MealRetriever.class);
    private final AgentTraceService trace = mock(AgentTraceService.class);
    private final com.diet.health.rag.EmbeddingClient embedding = mock(com.diet.health.rag.EmbeddingClient.class);
    private final com.diet.health.vectorstore.VectorStoreIdentity identity =
            new com.diet.health.vectorstore.VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024");
    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private final PreferenceService preference = new PreferenceService(mock(FeedbackMapper.class));

    private MealModule module;

    @BeforeEach
    void setUp() {
        module = new MealModule(search, new MealRankService(), trace,
                new MealRetrievalRouter(retriever, retriever), preference, embedding, identity, provider);
    }

    @Test
    void PERSONAL空库返回空候选不抛异常() {
        when(search.search(any())).thenReturn(List.of());
        List<MealItem> result = module.searchAndRank(SourceMode.PERSONAL, 1L, SlotBundle.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void 健康链路经检索器映射为类型化资源() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼",
                new SlotBundle(List.of("午餐"), List.of(), List.of(), List.of("清淡"), List.of(), List.of(), List.of()), 0.9);
        RetrievalResult result = new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.HYBRID, null);
        when(retriever.retrieve(any(), eq(10))).thenReturn(result);

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")), List.of());

        assertEquals(1, resources.size());
        HealthResource resource = resources.get(0);
        assertEquals("MEAL", resource.resourceType());
        assertEquals("5", resource.resourceId());
        assertEquals("清蒸鲈鱼", resource.name());
        assertTrue(resource.tags().get("healthGoal").contains("清淡"));
    }

    @Test
    void 显式槽位缺少标签或标签为空时严格拒绝资源() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "未标注餐食", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.STRUCTURED, null));

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("早餐")), List.of());

        assertTrue(resources.isEmpty());
    }

    @Test
    void 同字段多值按OR且跨字段按AND严格匹配() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem matched = new MealItem(5L, SourceMode.PUBLIC, null, "高蛋白午餐",
                new SlotBundle(List.of("午餐"), List.of(), List.of(), List.of("高蛋白"),
                        List.of(), List.of(), List.of()), 0.9);
        MealItem wrongGoal = new MealItem(6L, SourceMode.PUBLIC, null, "普通午餐",
                new SlotBundle(List.of("午餐"), List.of(), List.of(), List.of("均衡"),
                        List.of(), List.of(), List.of()), 0.8);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(List.of(
                new RetrievalItem(matched, 0.9, null, 0.9),
                new RetrievalItem(wrongGoal, 0.8, null, 0.8)), RetrievalMode.STRUCTURED, null));

        List<HealthResource> resources = module.recommendMeals(Map.of(
                "mealTime", List.of("早餐", "午餐"),
                "healthGoal", List.of("高蛋白")), List.of());

        assertEquals(List.of("5"), resources.stream().map(HealthResource::resourceId).toList());
    }

    @Test
    void 检索查询携带排除ID和过敏原约束() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.STRUCTURED, null));

        module.recommendMeals(Map.of("mealTime", List.of("午餐"), "allergen", List.of("花生")), List.of("3"));

        ArgumentCaptor<MealRetrievalQuery> captor = ArgumentCaptor.forClass(MealRetrievalQuery.class);
        verify(retriever).retrieve(captor.capture(), eq(10));
        assertEquals(List.of(3L), captor.getValue().excludeIds());
        assertEquals(List.of("花生"), captor.getValue().allergenTags());
        assertEquals("", captor.getValue().text(), "嵌入文本由检索器按槽位拼接，模块不重复实现");
    }

    @Test
    void 显式文本重载进入检索查询() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.HYBRID, null));

        module.recommendMeals(Map.of("mealTime", List.of("晚餐"), "healthGoal", List.of("高蛋白")),
                List.of("7"), "晚上想要高蛋白清淡晚餐");

        ArgumentCaptor<MealRetrievalQuery> captor = ArgumentCaptor.forClass(MealRetrievalQuery.class);
        verify(retriever).retrieve(captor.capture(), eq(10));
        assertEquals("晚上想要高蛋白清淡晚餐", captor.getValue().text(), "显式文本应原样进入查询");
        assertEquals(List.of(7L), captor.getValue().excludeIds());
    }

    @Test
    void 检索结果记录MEAL_RETRIEVED模式与降级原因Trace() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.STRUCTURED, "embedding_unavailable"));
        when(embedding.modelName()).thenReturn("text-embedding-v3");
        when(embedding.modelVersion()).thenReturn("v3-1024");

        module.recommendMeals(Map.of("mealTime", List.of("午餐")), List.of());

        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(trace).recordEvent(eq("MEAL_RETRIEVED"), eq("RETRIEVE"), any(), detailCaptor.capture());
        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals("STRUCTURED", detail.get("mode"));
        assertEquals("STRUCTURED", detail.get("route"));
        assertEquals("STRUCTURED", detail.get("actualRetriever"));
        assertTrue((Double) detail.get("retrievalLatencyMs") >= 0);
        assertEquals("embedding_unavailable", detail.get("degradationReason"));
        assertEquals("dashscope", detail.get("vectorProvider"));
        assertEquals("text-embedding-v3", detail.get("vectorModel"));
        assertEquals("v3-1024", detail.get("vectorVersion"));
        assertEquals("meal_dashscope_text-embedding-v3_1024_v3-1024", detail.get("collection"));
        assertEquals(1, detail.get("candidateCount"));
    }

    @Test
    void fixture模式走种子路径且检索器零调用() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(SeedResources.MEAL_CANDIDATES);

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("早餐")), List.of());

        verify(retriever, never()).retrieve(any(), anyInt());
        verify(embedding, never()).embed(any());
        assertEquals(3, resources.size(), "M1/M2/M3 带早餐标签的种子候选按 sortKey 序返回");
        assertEquals("M1", resources.get(0).resourceId(), "fixture ID 保持 M1-M9 原样，不强转 Long");
        assertTrue(resources.get(0).name().contains("燕麦"));
    }

    @Test
    void fixture模式排除ID原样生效() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(SeedResources.MEAL_CANDIDATES);

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("早餐")), List.of("M1", "M2"));

        assertEquals(1, resources.size());
        assertEquals("M3", resources.get(0).resourceId(), "M3 同时带午餐标签，早餐查询仍命中");
        verify(retriever, never()).retrieve(any(), anyInt());
    }

    @Test
    void fixture模式无匹配餐次时返回空候选而不静默放宽() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(SeedResources.MEAL_CANDIDATES);

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("夜宵")), List.of());

        assertTrue(resources.isEmpty(), "显式餐次无匹配时不得回退到错误餐次");
    }

    @Test
    void fixture模式只严格校验种子实际提供的餐次维度() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(SeedResources.MEAL_CANDIDATES);

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("午餐"), "cuisine", List.of("西餐")), List.of());

        assertEquals(4, resources.size(), "fixture 不冒充正式库多维筛选，只保证其餐次维度");
    }

    @Test
    void fixture模式Trace记录FIXTURE模式与无向量collection() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(SeedResources.MEAL_CANDIDATES);

        module.recommendMeals(Map.of("mealTime", List.of("午餐")), List.of());

        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(trace).recordEvent(eq("MEAL_RETRIEVED"), eq("RETRIEVE"), any(), detailCaptor.capture());
        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals("FIXTURE_SEED", detail.get("mode"));
        assertEquals(null, detail.get("collection"), "fixture 无向量 collection");
        assertEquals(null, detail.get("vectorModel"));
        assertEquals(4, detail.get("candidateCount"), "M3/M4/M5/M6 带午餐标签");
    }

    @Test
    void fixture模式空候选返回空列表() {
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        when(provider.planMealCandidates()).thenReturn(List.of());

        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("早餐")), List.of());

        assertTrue(resources.isEmpty());
        verify(retriever, never()).retrieve(any(), anyInt());
    }

    @Test
    void 路由在生产构造中保护强约束且语义请求才调用Hybrid() {
        MealRetriever structured = mock(MealRetriever.class);
        MealRetriever hybrid = mock(MealRetriever.class);
        MealRetrievalRouter router = new MealRetrievalRouter(structured, hybrid);
        MealModule routed = new MealModule(search, new MealRankService(), trace, router, preference,
                embedding, identity, provider);
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        when(structured.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null));
        when(hybrid.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(List.of(), RetrievalMode.HYBRID, null));

        routed.recommendMeals(Map.of("mealTime", List.of("早餐")), List.of(), "早餐来点清淡的");
        verify(structured).retrieve(any(), eq(10));
        verify(hybrid, never()).retrieve(any(), anyInt());

        routed.recommendMeals(Map.of(), List.of(), "有没有像妈妈做的菜");
        verify(hybrid).retrieve(any(), eq(10));
    }

    @Test
    void reviewed非数值排除ID忽略且不抛异常() {
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.STRUCTURED, null));

        module.recommendMeals(Map.of("mealTime", List.of("午餐")), List.of("M1", "9001", "abc", "5"));

        ArgumentCaptor<MealRetrievalQuery> captor = ArgumentCaptor.forClass(MealRetrievalQuery.class);
        verify(retriever).retrieve(captor.capture(), eq(10));
        assertEquals(List.of(9001L, 5L), captor.getValue().excludeIds(),
                "非数值/跨模式 ID（M1/abc）显式忽略，数值 ID 正常进入 reviewed 查询");
    }
}
