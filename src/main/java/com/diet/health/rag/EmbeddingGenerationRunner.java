package com.diet.health.rag;

import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.MealEmbeddingMapper;
import com.diet.model.MealEmbeddingRow;
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
 * 离线餐食向量生成（33 号票 RAG；#70 迁移到审核读取模块并补模式隔离）。
 * <p>
 * 需要真实 DashScope key（diet.embedding.generate-on-startup=true 时启动执行）。
 * 对审核读取模块提供的 APPROVED + PUBLIC 稳定快照生成向量并幂等写入 meal_item_embedding；
 * Embedding 不可用时跳过并告警，不阻塞结构化资源上线。
 * 本 runner 是 REVIEWED_DB 能力：FIXTURE_SEED 下显式启用必须 fail-fast，不静默执行
 * 正式 DB 批处理，也不把跳过伪装成成功。
 */
@Component
public class EmbeddingGenerationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGenerationRunner.class);

    static final String SWITCH_NAME = "diet.embedding.generate-on-startup";

    private final ReviewedMealReader reviewedMealReader;
    private final MealEmbeddingMapper embeddingMapper;
    private final EmbeddingClient embeddingClient;
    private final HealthResourceProvider resourceProvider;
    private final boolean generateOnStartup;

    public EmbeddingGenerationRunner(ReviewedMealReader reviewedMealReader,
                                     MealEmbeddingMapper embeddingMapper,
                                     EmbeddingClient embeddingClient,
                                     HealthResourceProvider resourceProvider,
                                     @Value("${diet.embedding.generate-on-startup:false}") boolean generateOnStartup) {
        this.reviewedMealReader = reviewedMealReader;
        this.embeddingMapper = embeddingMapper;
        this.embeddingClient = embeddingClient;
        this.resourceProvider = resourceProvider;
        this.generateOnStartup = generateOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!generateOnStartup) {
            return;
        }
        resourceProvider.providerMode().requireReviewedCapability(SWITCH_NAME, "正式 DB 向量生成");
        if (!embeddingClient.configured()) {
            log.warn("Embedding 未配置（缺 API key），跳过餐食向量生成；结构化检索不受影响");
            return;
        }
        List<ReviewedMeal> meals = reviewedMealReader.snapshotAll();
        int generated = 0;
        for (ReviewedMeal meal : meals) {
            Optional<float[]> vector = embeddingClient.embed(embedText(meal));
            if (vector.isEmpty()) {
                log.warn("餐食 {} 向量生成失败，中止本轮生成（已生成 {} 条）", meal.id(), generated);
                return;
            }
            MealEmbeddingRow row = new MealEmbeddingRow();
            row.setMealId(meal.id());
            row.setModel(embeddingClient.modelName());
            row.setModelVersion(embeddingClient.modelVersion());
            row.setDimension(vector.get().length);
            row.setVector(toVectorJson(vector.get()));
            embeddingMapper.upsert(row);
            generated++;
        }
        log.info("餐食向量生成完成：{} / {} 条（模型 {}）", generated, meals.size(), embeddingClient.modelName());
    }

    /** 嵌入文本拼接字段与失败即中止语义保持不变（#70）。 */
    private String embedText(ReviewedMeal meal) {
        List<String> parts = List.of(
                meal.name(),
                meal.nameEn(),
                meal.description(),
                String.join(" ", meal.ingredients())
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
