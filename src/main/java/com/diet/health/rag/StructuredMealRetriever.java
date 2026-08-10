package com.diet.health.rag;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItem;
import com.diet.model.MealItemRow;
import com.diet.model.MealRankRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.util.JsonService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化餐食检索器：旧链路 JSON_OVERLAPS 召回 + 7 维重叠重排。
 * <p>
 * 硬约束（过敏原、排除 ID）在打分前过滤；不调用任何 Agent。
 * 是 hybrid 检索的基础路径，也是 embedding 失败时的降级路径。
 */
@Service
public class StructuredMealRetriever implements MealRetriever {

    /** DB 检索上限（与旧 MealService.SEARCH_LIMIT 对齐）。 */
    private static final int SEARCH_LIMIT = 50;

    private final MealMapper mealMapper;
    private final JsonService jsonService;
    private final MealRankService mealRankService;

    public StructuredMealRetriever(MealMapper mealMapper, JsonService jsonService,
                                   MealRankService mealRankService) {
        this.mealMapper = mealMapper;
        this.jsonService = jsonService;
        this.mealRankService = mealRankService;
    }

    @Override
    public RetrievalResult retrieve(MealRetrievalQuery query, int limit) {
        SlotBundle slots = toSlotBundle(query == null ? null : query.slots());
        List<MealItemRow> rows = mealMapper.search(
                SourceMode.PUBLIC, null,
                jsonService.toJsonArray(slots.mealTime()),
                jsonService.toJsonArray(slots.mood()),
                jsonService.toJsonArray(slots.scene()),
                jsonService.toJsonArray(slots.healthGoal()),
                jsonService.toJsonArray(slots.cuisine()),
                jsonService.toJsonArray(slots.taste()),
                jsonService.toJsonArray(slots.convenience()),
                SEARCH_LIMIT
        );
        Set<Long> excludeIds = new HashSet<>(query.excludeIds() == null ? List.of() : query.excludeIds());
        Set<String> allergens = new HashSet<>(query.allergenTags() == null ? List.of() : query.allergenTags());

        // 只召回审核通过（APPROVED）的资源：旧库 PENDING 行无来源记录，不进入审核检索链路
        List<MealItem> candidates = rows.stream()
                .filter(row -> "APPROVED".equals(row.getReviewStatus()))
                .filter(row -> !excludeIds.contains(row.getId()))
                .filter(row -> !containsAllergen(row, allergens))
                .map(this::toMealItem)
                .toList();

        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, query.excludeIds()));
        List<RetrievalItem> items = ranked.stream()
                .limit(Math.max(limit, 0))
                .map(item -> new RetrievalItem(item, item.matchScore(), null, item.matchScore()))
                .toList();
        return new RetrievalResult(items, RetrievalMode.STRUCTURED, null);
    }

    /** 餐食过敏原标签与查询过敏原硬约束是否有交集。 */
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

    /** 健康槽位 Map → 旧链路 SlotBundle（与 MealModule 同口径）。 */
    public SlotBundle toSlotBundle(Map<String, List<String>> healthSlots) {
        Map<String, List<String>> safe = healthSlots == null ? Map.of() : healthSlots;
        return new SlotBundle(
                safe.getOrDefault("mealTime", List.of()),
                safe.getOrDefault("mood", List.of()),
                safe.getOrDefault("scene", List.of()),
                safe.getOrDefault("healthGoal", List.of()),
                safe.getOrDefault("cuisine", List.of()),
                safe.getOrDefault("taste", List.of()),
                safe.getOrDefault("convenience", List.of())
        );
    }
}
