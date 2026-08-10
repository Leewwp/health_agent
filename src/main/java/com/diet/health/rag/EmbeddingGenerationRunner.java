package com.diet.health.rag;

import com.diet.mapper.MealEmbeddingMapper;
import com.diet.mapper.MealMapper;
import com.diet.model.MealEmbeddingRow;
import com.diet.model.MealItemRow;
import com.diet.util.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 离线餐食向量生成（33 号票 RAG）。
 * <p>
 * 需要真实 DashScope key（diet.embedding.generate-on-startup=true 时启动执行）。
 * 对全部审核通过的公共餐食生成向量并幂等写入 meal_item_embedding；
 * Embedding 不可用时跳过并告警，不阻塞结构化资源上线。
 */
@Component
public class EmbeddingGenerationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGenerationRunner.class);

    private final MealMapper mealMapper;
    private final MealEmbeddingMapper embeddingMapper;
    private final EmbeddingClient embeddingClient;
    private final JsonService jsonService;
    private final boolean generateOnStartup;

    public EmbeddingGenerationRunner(MealMapper mealMapper,
                                     MealEmbeddingMapper embeddingMapper,
                                     EmbeddingClient embeddingClient,
                                     JsonService jsonService,
                                     @Value("${diet.embedding.generate-on-startup:false}") boolean generateOnStartup) {
        this.mealMapper = mealMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingClient = embeddingClient;
        this.jsonService = jsonService;
        this.generateOnStartup = generateOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!generateOnStartup) {
            return;
        }
        if (!embeddingClient.configured()) {
            log.warn("Embedding 未配置（缺 API key），跳过餐食向量生成；结构化检索不受影响");
            return;
        }
        List<MealItemRow> meals = mealMapper.findApprovedPublicMeals();
        int generated = 0;
        for (MealItemRow meal : meals) {
            Optional<float[]> vector = embeddingClient.embed(embedText(meal));
            if (vector.isEmpty()) {
                log.warn("餐食 {} 向量生成失败，中止本轮生成（已生成 {} 条）", meal.getId(), generated);
                return;
            }
            MealEmbeddingRow row = new MealEmbeddingRow();
            row.setMealId(meal.getId());
            row.setModel(embeddingClient.modelName());
            row.setModelVersion(embeddingClient.modelVersion());
            row.setDimension(vector.get().length);
            row.setVector(toVectorJson(vector.get()));
            embeddingMapper.upsert(row);
            generated++;
        }
        log.info("餐食向量生成完成：{} / {} 条（模型 {}）", generated, meals.size(), embeddingClient.modelName());
    }

    private String embedText(MealItemRow meal) {
        List<String> parts = List.of(
                meal.getName(),
                meal.getNameEn(),
                meal.getDescription(),
                String.join(" ", jsonService.fromJsonArray(meal.getIngredientsJson()))
        );
        return parts.stream().filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(" "));
    }

    private String toVectorJson(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
