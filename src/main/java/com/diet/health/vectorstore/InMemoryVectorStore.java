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
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        Set<Long> exclude = new HashSet<>(filter.excludeMealIds() == null ? List.of() : filter.excludeMealIds());
        Set<String> allergens = new HashSet<>(filter.allergenTags() == null ? List.of() : filter.allergenTags());

        List<VectorHit> hits = new ArrayList<>();
        for (VectorPoint point : points.values()) {
            if (exclude.contains(point.mealId())) {
                continue;
            }
            if (containsAllergen(point, allergens)) {
                continue;
            }
            double score = VectorSimilarity.cosine(queryVector, point.vector());
            hits.add(new VectorHit(point.mealId(), score));
        }
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return hits.stream().limit(Math.max(limit, 0)).toList();
    }

    private boolean containsAllergen(VectorPoint point, Set<String> allergens) {
        if (allergens.isEmpty()) {
            return false;
        }
        List<String> pointAllergens = point.payload() == null ? List.of() : point.payload().get("allergens");
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
