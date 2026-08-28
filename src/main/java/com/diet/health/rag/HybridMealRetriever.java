package com.diet.health.rag;

import com.diet.health.reader.meal.MealAllergenConstraint;
import com.diet.health.reader.meal.MealDomainMapper;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.vectorstore.VectorFilter;
import com.diet.health.vectorstore.VectorHit;
import com.diet.health.vectorstore.VectorStore;
import com.diet.health.vectorstore.VectorStoreException;
import com.diet.model.MealItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hybrid 餐食检索器（M5 #52）：结构化召回 + Qdrant 独立向量召回 → 确定性融合。
 * <p>
 * 两条召回路径独立执行：结构化召回（JSON_OVERLAPS + 7 维重叠重排，审核 APPROVED 公共餐食）
 * 与 Qdrant 向量召回（余弦 top-N，payload 过滤审核状态/来源/过敏原/排除 ID）。
 * 合并规则：按餐食 ID 合并去重，再以 ID 回查审核读取模块（审核 APPROVED 公共餐食）作为
 * 事实源二次执行全部硬约束——Qdrant 结果不得绕过领域规则，过期索引命中（已不存在或
 * 硬约束不满足）直接丢弃。融合分 = fusionWeight * 归一结构分 + (1 - fusionWeight) * 语义余弦
 * （截断 [0,1]），权重经 {@code diet.rag.fusion-weight} 注入、默认 0.5（#77 消融接缝：
 * 评测通过构造器显式注入 0.3/0.7，不静默修改线上默认）。
 * Embedding/Qdrant 超时、不可用、维度不匹配或空结果时立即退回结构化检索并标记降级原因。
 * 数据读取经 {@link ReviewedMealReader}（方案 B），本层不接触 Mapper 行对象。
 */
@Service
public class HybridMealRetriever implements MealRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridMealRetriever.class);

    /** 结构化候选池：语义重排在更宽的池上进行，才能体现语义分对召回的作用。 */
    private static final int STRUCTURED_POOL = 10;

    /** 向量召回池：与结构化池一致，两条路径各自独立取 top-N 再融合。 */
    private static final int VECTOR_POOL = 10;

    /** 生产默认融合权重（#77：可经 diet.rag.fusion-weight 覆盖）。 */
    static final double DEFAULT_FUSION_WEIGHT = 0.5;

    private final MealRetriever structuredRetriever;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ReviewedMealReader reviewedMealReader;
    private final double fusionWeight;

    /** 兼容旧构造（测试/历史调用点）：融合权重取默认 0.5。 */
    public HybridMealRetriever(
            @Qualifier("structuredMealRetriever")
            MealRetriever structuredRetriever,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            ReviewedMealReader reviewedMealReader) {
        this(structuredRetriever, embeddingClient, vectorStore, reviewedMealReader, DEFAULT_FUSION_WEIGHT);
    }

    /**
     * #77 权重接缝：Spring 注入 {@code diet.rag.fusion-weight}（默认 0.5），
     * 评测消融经该构造器显式注入 0.3/0.7 权重变体。
     */
    @Autowired
    public HybridMealRetriever(
            @Qualifier("structuredMealRetriever")
            MealRetriever structuredRetriever,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            ReviewedMealReader reviewedMealReader,
            @Value("${diet.rag.fusion-weight:0.5}") double fusionWeight) {
        this.structuredRetriever = structuredRetriever;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.reviewedMealReader = reviewedMealReader;
        this.fusionWeight = fusionWeight;
    }

    @Override
    public RetrievalResult retrieve(MealRetrievalQuery query, int limit) {
        RetrievalResult base = structuredRetriever.retrieve(query, STRUCTURED_POOL);
        Map<Long, RetrievalItem> structuredById = new HashMap<>();
        double maxStructured = 0;
        for (RetrievalItem item : base.items()) {
            structuredById.put(item.meal().id(), item);
            maxStructured = Math.max(maxStructured, item.structuredScore());
        }

        List<VectorHit> hits;
        long vectorStartNanos = System.nanoTime();
        try {
            hits = vectorHits(query);
        } catch (VectorStoreException e) {
            log.warn("向量检索失败，hybrid 降级为结构化：{}", e.getMessage());
            return degrade("vector_store_unavailable", base, limit,
                    VectorRetrievalStatus.STORE_UNAVAILABLE, elapsedMs(vectorStartNanos));
        }
        if (hits == null) {
            return degrade("embedding_unavailable", base, limit,
                    VectorRetrievalStatus.EMBEDDING_UNAVAILABLE, elapsedMs(vectorStartNanos));
        }
        if (hits.isEmpty()) {
            if (base.items().isEmpty()) {
                return new RetrievalResult(List.of(), RetrievalMode.STRUCTURED, null,
                        new RetrievalEvidence(0, 0, 0, VectorRetrievalStatus.NO_HITS,
                                elapsedMs(vectorStartNanos)));
            }
            return degrade("no_vector_hits", base, limit,
                    VectorRetrievalStatus.NO_HITS, elapsedMs(vectorStartNanos));
        }
        return merge(query, base, structuredById, maxStructured, hits, limit,
                elapsedMs(vectorStartNanos));
    }

    /** 融合：按 ID 回查审核读取模块二次校验硬约束后，确定性合并两条路径候选。 */
    private RetrievalResult merge(MealRetrievalQuery query, RetrievalResult base,
                                  Map<Long, RetrievalItem> structuredById, double maxStructured,
                                  List<VectorHit> hits, int limit, double vectorLatencyMs) {
        // 回查 MySQL 二次校验：向量命中的 ID 必须仍存在且满足全部硬约束，过期索引命中直接丢弃
        Set<Long> mergeIds = new HashSet<>(structuredById.keySet());
        hits.forEach(hit -> mergeIds.add(hit.mealId()));
        Map<Long, ReviewedMeal> mealById = new HashMap<>();
        for (ReviewedMeal meal : reviewedMealReader.findByIds(List.copyOf(mergeIds))) {
            mealById.put(meal.id(), meal);
        }
        Set<Long> excludeIds = new HashSet<>(query.excludeIds() == null ? List.of() : query.excludeIds());
        List<String> allergens = query.allergenTags() == null ? List.of() : query.allergenTags();
        Map<Long, Double> semanticById = new HashMap<>(hits.size());
        for (VectorHit hit : hits) {
            semanticById.put(hit.mealId(), clampCosine(hit.score()));
        }

        List<RetrievalItem> merged = new ArrayList<>();
        for (ReviewedMeal meal : mealById.values()) {
            if (excludeIds.contains(meal.id()) || MealAllergenConstraint.intersects(meal, allergens)
                    || !matchesSlots(meal, query.slots())) {
                continue;
            }
            Double semantic = semanticById.get(meal.id());
            RetrievalItem structured = structuredById.get(meal.id());
            double structuredScore = structured == null ? 0 : structured.structuredScore();
            double normalized = maxStructured > 0 ? structuredScore / maxStructured : 0;
            double finalScore = fusionWeight * normalized + (1 - fusionWeight) * (semantic == null ? 0 : semantic);
            MealItem mealItem = structured == null ? MealDomainMapper.toMealItem(meal) : structured.meal();
            merged.add(new RetrievalItem(mealItem, structuredScore, semantic, finalScore, meal));
        }
        merged.sort(Comparator.comparingDouble(RetrievalItem::mergedScore).reversed()
                .thenComparing(item -> item.meal().id()));
        return new RetrievalResult(merged.stream().limit(Math.max(limit, 0)).toList(),
                RetrievalMode.HYBRID, null,
                new RetrievalEvidence(base.items().size(), hits.size(), merged.size(),
                        VectorRetrievalStatus.AVAILABLE, vectorLatencyMs));
    }

    /** 向量命中回查时重做全部显式槽位硬过滤，防止语义召回绕过结构化条件。 */
    private boolean matchesSlots(ReviewedMeal meal, Map<String, List<String>> slots) {
        if (slots == null || slots.isEmpty()) return true;
        for (Map.Entry<String, List<String>> entry : slots.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;
            if (!meal.tags().containsKey(entry.getKey())) return false;
            List<String> tags = meal.tags().getOrDefault(entry.getKey(), List.of());
            if (tags.isEmpty() || values.stream().noneMatch(tags::contains)) return false;
        }
        return true;
    }

    /** 独立向量召回；embedding 不可用返回 null（降级信号），Qdrant 故障抛 {@link VectorStoreException}。 */
    private List<VectorHit> vectorHits(MealRetrievalQuery query) {
        if (!vectorStore.ping()) {
            log.warn("向量存储不可用，hybrid 降级为结构化检索");
            throw new VectorStoreException("vector store ping 失败");
        }
        String embedText = embedText(query);
        Optional<float[]> queryVector = embeddingClient.embed(embedText);
        if (queryVector.isEmpty()) {
            return null;
        }
        VectorFilter filter = new VectorFilter(query.excludeIds(), query.allergenTags(), "APPROVED", "PUBLIC");
        return vectorStore.search(queryVector.get(), filter, VECTOR_POOL);
    }

    /** 降级：原样返回结构化候选并标记降级原因。 */
    private RetrievalResult degrade(String reason, RetrievalResult base, int limit,
                                    VectorRetrievalStatus status, double vectorLatencyMs) {
        return new RetrievalResult(limitTo(base.items(), limit), RetrievalMode.STRUCTURED, reason,
                new RetrievalEvidence(base.items().size(), 0, 0, status, vectorLatencyMs));
    }

    private List<RetrievalItem> limitTo(List<RetrievalItem> items, int limit) {
        return items.stream().limit(Math.max(limit, 0)).toList();
    }

    /** 嵌入文本：查询显式文本优先，否则用槽位值拼接（排序保证确定性）。 */
    private String embedText(MealRetrievalQuery query) {
        if (query.text() != null && !query.text().isBlank()) {
            return query.text();
        }
        Map<String, List<String>> slots = query.slots() == null ? Map.of() : query.slots();
        return slots.values().stream()
                .flatMap(List::stream)
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private double clampCosine(double cosine) {
        return Math.max(0, Math.min(1, cosine));
    }

    private double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}
