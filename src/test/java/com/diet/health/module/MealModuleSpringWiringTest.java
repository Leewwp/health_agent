package com.diet.health.module;

import com.diet.health.feedback.PreferenceService;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.MealRetrievalRouter;
import com.diet.health.rag.MealRetriever;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.mapper.FeedbackMapper;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class MealModuleSpringWiringTest {
    @Test
    void Spring上下文使用显式路由构造而不是兼容构造() {
        MealRetriever structured = mock(MealRetriever.class);
        MealRetriever hybrid = mock(MealRetriever.class);
        MealRetrievalRouter router = new MealRetrievalRouter(structured, hybrid);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("structuredMealRetriever", MealRetriever.class, () -> structured);
            context.registerBean("hybridMealRetriever", MealRetriever.class, () -> hybrid);
            context.registerBean(MealSearchService.class, () -> mock(MealSearchService.class));
            context.registerBean(MealRankService.class, MealRankService::new);
            context.registerBean(AgentTraceService.class, () -> mock(AgentTraceService.class));
            context.registerBean(MealRetrievalRouter.class, () -> router);
            context.registerBean(PreferenceService.class, () -> new PreferenceService(mock(FeedbackMapper.class)));
            context.registerBean(EmbeddingClient.class, () -> mock(EmbeddingClient.class));
            context.registerBean(VectorStoreIdentity.class,
                    () -> new VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024"));
            context.registerBean(HealthResourceProvider.class, () -> mock(HealthResourceProvider.class));
            context.registerBean(MealModule.class);
            context.refresh();
            assertSame(router, context.getBean(MealModule.class).retrievalRouterForTest());
        }
    }
}
