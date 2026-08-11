package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalMode;
import com.diet.health.rag.RetrievalResult;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 餐食模块：旧链路 searchAndRank 保持；健康链路走 MealRetriever seam。 */
class MealModuleTest {

    private final MealSearchService search = mock(MealSearchService.class);
    private final MealRetriever retriever = mock(MealRetriever.class);
    private final AgentTraceService trace = mock(AgentTraceService.class);
    private final com.diet.health.rag.EmbeddingClient embedding = mock(com.diet.health.rag.EmbeddingClient.class);
    private final com.diet.health.vectorstore.VectorStoreIdentity identity =
            new com.diet.health.vectorstore.VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024");
    private final MealModule module = new MealModule(
            search, new MealRankService(), trace, retriever, new PreferenceService(mock(FeedbackMapper.class)),
            embedding, identity);

    @Test
    void PERSONAL空库返回空候选不抛异常() {
        when(search.search(any())).thenReturn(List.of());
        List<MealItem> result = module.searchAndRank(SourceMode.PERSONAL, 1L, SlotBundle.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void 健康链路经检索器映射为类型化资源() {
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
    void 检索查询携带排除ID和过敏原约束() {
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼", SlotBundle.empty(), 0.9);
        when(retriever.retrieve(any(), eq(10))).thenReturn(new RetrievalResult(
                List.of(new RetrievalItem(item, 0.9, null, 0.9)), RetrievalMode.STRUCTURED, null));

        module.recommendMeals(Map.of("mealTime", List.of("午餐"), "allergen", List.of("花生")), List.of(3L));

        ArgumentCaptor<MealRetrievalQuery> captor = ArgumentCaptor.forClass(MealRetrievalQuery.class);
        verify(retriever).retrieve(captor.capture(), eq(10));
        assertEquals(List.of(3L), captor.getValue().excludeIds());
        assertEquals(List.of("花生"), captor.getValue().allergenTags());
        assertEquals("", captor.getValue().text(), "嵌入文本由检索器按槽位拼接，模块不重复实现");
    }

    @Test
    void 检索结果记录MEAL_RETRIEVED模式与降级原因Trace() {
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
        assertEquals("embedding_unavailable", detail.get("degradationReason"));
        assertEquals("dashscope", detail.get("vectorProvider"));
        assertEquals("text-embedding-v3", detail.get("vectorModel"));
        assertEquals("v3-1024", detail.get("vectorVersion"));
        assertEquals("meal_dashscope_text-embedding-v3_1024_v3-1024", detail.get("collection"));
        assertEquals(1, detail.get("candidateCount"));
    }
}
