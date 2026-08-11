package com.diet.health.vectorstore;

import com.diet.health.rag.VectorSimilarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存向量存储适配器（M5 #54）。
 * <p>
 * 与 {@link QdrantVectorStore} 行为对齐的测试/离线替身：幂等 upsert、
 * 排除 ID 与过敏原 must_not 过滤、余弦降序。不持久化，只服务领域测试与离线演示。
 */
public class InMemoryVectorStore implements VectorStore {

    private final VectorStoreIdentity identity;
    private final Map<Long, VectorPoint> points = new HashMap<>();

    public InMemoryVectorStore(VectorStoreIdentity identity) {
        this.identity = identity;
    }

    @Override
    public String collectionName() {
        return identity.collectionName();
    }

    @Override
    public int dimension() {
        return identity.dimension();
    }

    @Override
    public boolean ensureCollection() {
        return true;
    }

    @Override
    public synchronized void upsert(List<VectorPoint> batch) {
        for (VectorPoint point : batch) {
            points.put(point.mealId(), point);
        }
    }

    @Override
    public synchronized List<VectorHit> search(float[] queryVector, VectorFilter filter, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        VectorFilter safeFilter = filter == null ? VectorFilter.none() : filter;
        Set<Long> exclude = new HashSet<>(safeFilter.excludeMealIds() == null ? List.of() : safeFilter.excludeMealIds());
        Set<String> allergens = new HashSet<>(safeFilter.allergenTags() == null ? List.of() : safeFilter.allergenTags());

        List<VectorHit> hits = new ArrayList<>();
        for (VectorPoint point : points.values()) {
            if (exclude.contains(point.mealId())) {
                continue;
            }
            if (containsAllergen(point, allergens)) {
                continue;
            }
            if (!matchesKeyword(point, VectorFilter.KEY_REVIEW_STATUS, safeFilter.reviewStatus())) {
                continue;
            }
            if (!matchesKeyword(point, VectorFilter.KEY_SOURCE_TYPE, safeFilter.sourceType())) {
                continue;
            }
            double score = VectorSimilarity.cosine(queryVector, point.vector());
            hits.add(new VectorHit(point.mealId(), score));
        }
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return hits.stream().limit(limit).toList();
    }

    /** payload 指定 key 的值列表是否精确包含期望关键字；期望为 null 时不做约束。 */
    private boolean matchesKeyword(VectorPoint point, String key, String expected) {
        if (expected == null) {
            return true;
        }
        List<String> values = point.payload() == null ? List.of() : point.payload().get(key);
        return values != null && values.contains(expected);
    }

    private boolean containsAllergen(VectorPoint point, Set<String> allergens) {
        if (allergens.isEmpty()) {
            return false;
        }
        List<String> pointAllergens = point.payload() == null ? List.of() : point.payload().get(VectorFilter.KEY_ALLERGENS);
        if (pointAllergens == null) {
            return false;
        }
        return allergens.stream().anyMatch(pointAllergens::contains);
    }

    @Override
    public synchronized void clear() {
        points.clear();
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public void close() {
        points.clear();
    }
}
