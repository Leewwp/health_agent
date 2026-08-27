package com.diet.config;

import com.diet.health.rag.HybridMealRetriever;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.StructuredMealRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 餐食检索器配置：diet.rag.mode=structured（默认）时只用结构化检索，hybrid 时启用实验性语义合并。
 * embedding 失败时 hybrid 内部自动降级为结构化，不影响推荐可用性。
 */
@Configuration
public class RagConfiguration {

    @Bean("mealRetriever")
    public MealRetriever mealRetriever(
            @Qualifier("structuredMealRetriever") StructuredMealRetriever structured,
            @Qualifier("hybridMealRetriever") HybridMealRetriever hybrid,
            @Value("${diet.rag.mode:structured}") String mode
    ) {
        return "structured".equalsIgnoreCase(mode) ? structured : hybrid;
    }
}
