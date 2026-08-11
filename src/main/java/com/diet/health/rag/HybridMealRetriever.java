package com.diet.health.rag;

import com.diet.enums.SourceMode;
import com.diet.health.vectorstore.VectorFilter;
import com.diet.health.vectorstore.VectorHit;
import com.diet.health.vectorstore.VectorStore;
import com.diet.health.vectorstore.VectorStoreException;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItem;
import com.diet.model.MealItemRow;
import com.diet.model.SlotBundle;
import com.diet.util.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 合并规则：按餐食 ID 合并去重，再以 ID 回查 MySQL（审核 APPROVED 公共餐食）作为事实源
 * 二次执行全部硬约束——Qdrant 结果不得绕过领域规则，过期索引命中（MySQL 已不存在或
 * 硬约束不满足）直接丢弃。融合分 = 0.5 * 归一结构分 + 0.5 * 语义余弦（截断 [0,1]）。
 * Embedding/Qdrant 超时、不可用、维度不匹配或空结果时立即退回结构化检索并标记降级原因。
 */
@Service
public class HybridMealRetriever implements MealRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridMealRetriever.class);

    /** 结构化候选池：语义重排在更宽的池上进行，才能体现语义分对召回的作用。 */
    private static final int STRUCTURED_POOL = 10;

    /** 向量召回池：与结构化池一致，两条路径各自独立取 top-N 再融合。 */
    private static final int VECTOR_POOL = 10;

    private final MealRetriever structuredRetriever;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final MealMapper mealMapper;
    private final JsonService jsonService;

    public HybridMealRetriever(
            @Qualifier("structuredMealRetriever")
            MealRetriever structuredRetriever,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            MealMapper mealMapper,
            JsonService jsonService) {
        this.structuredRetriever = structuredRetriever;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.mealMapper = mealMapper;
        this.jsonService = jsonService;
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
        try {
            hits = vectorHits(query);
        } catch (VectorStoreException e) {
            log.warn("向量检索失败，hybrid 降级为结构化：{}", e.getMessage());
            return degrade("vector_store_unavailable", base, limit);
        }
        if (hits == null) {
            return degrade("embedding_unavailable", base, limit);
        }
        if (hits.isEmpty()) {
            if (base.items().isEmpty()) {
                return base;
            }
            return degrade("no_vector_hits", base, limit);
        }
        return merge(query, base, structuredById, maxStructured, hits, limit);
    }

    /** 融合：按 ID 回查 MySQL 二次校验硬约束后，确定性合并两条路径候选。 */
    private RetrievalResult merge(MealRetrievalQuery query, RetrievalResult base,
                                  Map<Long, RetrievalItem> structuredById, double maxStructured,
                                  List<VectorHit> hits, int limit) {
        // 回查 MySQL 二次校验：向量命中的 ID 必须仍存在且满足全部硬约束，过期索引命中直接丢弃
        Set<Long> mergeIds = new HashSet<>(structuredById.keySet());
        hits.forEach(hit -> mergeIds.add(hit.mealId()));
        Map<Long, MealItemRow> rowById = new HashMap<>();
        for (MealItemRow row : mealMapper.findApprovedPublicByIds(List.copyOf(mergeIds))) {
            rowById.put(row.getId(), row);
        }
        Set<Long> excludeIds = new HashSet<>(query.excludeIds() == null ? List.of() : query.excludeIds());
        Set<String> allergens = new HashSet<>(query.allergenTags() == null ? List.of() : query.allergenTags());
        Map<Long, Double> semanticById = new HashMap<>(hits.size());
        for (VectorHit hit : hits) {
            semanticById.put(hit.mealId(), clampCosine(hit.score()));
        }

        List<RetrievalItem> merged = new ArrayList<>();
        for (MealItemRow row : rowById.values()) {
            if (excludeIds.contains(row.getId()) || containsAllergen(row, allergens)) {
                continue;
            }
            Double semantic = semanticById.get(row.getId());
            RetrievalItem structured = structuredById.get(row.getId());
            double structuredScore = structured == null ? 0 : structured.structuredScore();
            double normalized = maxStructured > 0 ? structuredScore / maxStructured : 0;
            double finalScore = 0.5 * normalized + 0.5 * (semantic == null ? 0 : semantic);
            MealItem meal = structured == null ? toMealItem(row) : structured.meal();
            merged.add(new RetrievalItem(meal, structuredScore, semantic, finalScore));
        }
        merged.sort(Comparator.comparingDouble(RetrievalItem::mergedScore).reversed()
                .thenComparing(item -> item.meal().id()));
        return new RetrievalResult(merged.stream().limit(Math.max(limit, 0)).toList(),
                RetrievalMode.HYBRID, null);
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
    private RetrievalResult degrade(String reason, RetrievalResult base, int limit) {
        return new RetrievalResult(limitTo(base.items(), limit), RetrievalMode.STRUCTURED, reason);
    }

    private List<RetrievalItem> limitTo(List<RetrievalItem> items, int limit) {
        return items.stream().limit(Math.max(limit, 0)).toList();
    }

    /** 餐食过敏原标签与查询过敏原硬约束是否有交集（与结构化检索同口径的二次校验）。 */
    private boolean containsAllergen(MealItemRow row, Set<String> allergens) {
        if (allergens.isEmpty()) {
            return false;
        }
        Set<String> rowAllergens = new HashSet<>(jsonService.fromJsonArray(row.getAllergenJson()));
        rowAllergens.retainAll(allergens);
        return !rowAllergens.isEmpty();
    }

    private MealItem toMealItem(MealItemRow row) {
        return new MealItem(
                row.getId(),
                SourceMode.PUBLIC,
                null,
                row.getName(),
                new SlotBundle(
                        jsonService.fromJsonArray(row.getMealTime()),
                        jsonService.fromJsonArray(row.getMood()),
                        jsonService.fromJsonArray(row.getScene()),
                        jsonService.fromJsonArray(row.getHealthGoal()),
                        jsonService.fromJsonArray(row.getCuisine()),
                        jsonService.fromJsonArray(row.getTaste()),
                        jsonService.fromJsonArray(row.getConvenience())),
                0
        );
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
}
