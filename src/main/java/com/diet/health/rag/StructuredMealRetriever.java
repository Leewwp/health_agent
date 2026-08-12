package com.diet.health.rag;

import com.diet.health.reader.meal.MealAllergenConstraint;
import com.diet.health.reader.meal.MealDomainMapper;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化餐食检索器：审核读取模块 7 维 JSON_OVERLAPS 召回 + 7 维重叠重排。
 * <p>
 * 硬约束（过敏原、排除 ID）在打分前过滤；不调用任何 Agent。
 * 是 hybrid 检索的基础路径，也是 embedding 失败时的降级路径。
 * 数据读取经 {@link ReviewedMealReader}（方案 B），本层不接触 Mapper 行对象。
 */
@Service
public class StructuredMealRetriever implements MealRetriever {

    /** DB 检索上限（与旧 MealService.SEARCH_LIMIT 对齐）。 */
    private static final int SEARCH_LIMIT = 50;

    private final ReviewedMealReader reviewedMealReader;
    private final MealRankService mealRankService;

    public StructuredMealRetriever(ReviewedMealReader reviewedMealReader,
                                   MealRankService mealRankService) {
        this.reviewedMealReader = reviewedMealReader;
        this.mealRankService = mealRankService;
    }

    @Override
    public RetrievalResult retrieve(MealRetrievalQuery query, int limit) {
        SlotBundle slots = toSlotBundle(query == null ? null : query.slots());
        List<ReviewedMeal> meals = reviewedMealReader.recallStructured(query.slots(), SEARCH_LIMIT);
        Set<Long> excludeIds = new HashSet<>(query.excludeIds() == null ? List.of() : query.excludeIds());
        List<String> allergens = query.allergenTags() == null ? List.of() : query.allergenTags();

        // 只召回审核通过（APPROVED）的资源：读取模块已过滤，这里只做领域级硬约束
        List<MealItem> candidates = meals.stream()
                .filter(meal -> !excludeIds.contains(meal.id()))
                .filter(meal -> !MealAllergenConstraint.intersects(meal, allergens))
                .map(MealDomainMapper::toMealItem)
                .toList();

        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, query.excludeIds()));
        List<RetrievalItem> items = ranked.stream()
                .limit(Math.max(limit, 0))
                .map(item -> new RetrievalItem(item, item.matchScore(), null, item.matchScore()))
                .toList();
        return new RetrievalResult(items, RetrievalMode.STRUCTURED, null);
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
