package com.diet.health.module;

import com.diet.health.feedback.PreferenceService;
import com.diet.health.resource.HealthResourceProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 健身领域模块：从统一资源 Provider 按槽位筛选、排序。
 * 动作只要有基础字段即可浏览与单次推荐；plan_ready 是自动周计划资格。
 */
@Service
public class ExerciseModule {

    /** 单次推荐返回上限。 */
    private static final int RECOMMEND_LIMIT = 5;

    private final HealthResourceProvider resourceProvider;
    private final PreferenceService preferenceService;

    public ExerciseModule(HealthResourceProvider resourceProvider, PreferenceService preferenceService) {
        this.resourceProvider = resourceProvider;
        this.preferenceService = preferenceService;
    }

    public List<HealthResource> listAll() {
        return resourceProvider.exercises();
    }

    /** 按槽位命中打分排序，返回最多 RECOMMEND_LIMIT 条（排除 excludeIds，类型化字符串 resourceId）。 */
    public List<HealthResource> recommend(Map<String, List<String>> slots, List<String> excludeIds, int limit) {
        Set<String> exclude = new HashSet<>(excludeIds == null ? List.of() : excludeIds);
        int safeLimit = limit > 0 ? Math.min(limit, RECOMMEND_LIMIT) : RECOMMEND_LIMIT;
        List<Scored> scored = resourceProvider.singleRecommendationExercises().stream()
                .filter(item -> !exclude.contains(item.resourceId()))
                .map(item -> new Scored(item, score(item, slots)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
        boolean anyMatched = scored.stream().anyMatch(item -> item.score() > 0);
        List<HealthResource> ranked = scored.stream()
                .filter(item -> !anyMatched || item.score() > 0)
                .map(Scored::resource)
                .toList();
        return preferenceService.applyPreference(ranked).stream()
                .limit(safeLimit)
                .toList();
    }

    /** 槽位命中比例：查询中命中的标签数 / 查询标签总数。 */
    private double score(HealthResource item, Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) {
            return 0.5;
        }
        double total = 0;
        double hits = 0;
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            Set<String> itemTags = new HashSet<>(item.tags().getOrDefault(entry.getKey(), List.of()));
            for (String value : values) {
                total++;
                if (itemTags.contains(value)) {
                    hits++;
                }
            }
        }
        return total == 0 ? 0.5 : hits / total;
    }

    private record Scored(HealthResource resource, double score) {
    }
}
