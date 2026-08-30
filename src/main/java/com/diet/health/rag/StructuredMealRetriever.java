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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化餐食检索器：审核读取模块八槽位 JSON_OVERLAPS 召回 + 八槽位重叠重排。
 * <p>
 * 八个餐食槽位（mealTime/mood/scene/healthGoal/cuisine/foodType/taste/convenience）
 * 全部透传给 Reader 参与 SQL 召回（空维度即不过滤），领域层不再丢弃显式槽位；
 * 召回排序为 updated_at DESC + id DESC 确定性 tiebreaker（演示召回规格）。
 * 硬约束（过敏原、排除 ID）在打分前过滤；foodType 为硬过滤字段，不参与 {@link MealRankService} 打分。
 * 不调用任何 Agent。是 hybrid 检索的基础路径，也是 embedding 失败时的降级路径。
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
        List<ReviewedMeal> meals = reviewedMealReader.recallStructured(hardRecallSlots(query.slots()), SEARCH_LIMIT);
        Set<Long> excludeIds = new HashSet<>(query.excludeIds() == null ? List.of() : query.excludeIds());
        List<String> allergens = query.allergenTags() == null ? List.of() : query.allergenTags();

        // 只召回审核通过（APPROVED）的资源：读取模块已过滤，这里只做领域级硬约束
        List<MealItem> candidates = meals.stream()
                .filter(meal -> !excludeIds.contains(meal.id()))
                .filter(meal -> !MealAllergenConstraint.intersects(meal, allergens))
                .map(MealDomainMapper::toMealItem)
                .toList();

        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, query.excludeIds()));
        Map<Long, ReviewedMeal> sourceById = meals.stream()
                .collect(Collectors.toMap(ReviewedMeal::id, Function.identity(), (left, right) -> left));
        List<RetrievalItem> items = ranked.stream()
                .limit(Math.max(limit, 0))
                .map(item -> new RetrievalItem(item, item.matchScore(), null, item.matchScore(), sourceById.get(item.id())))
                .toList();
        return new RetrievalResult(items, RetrievalMode.STRUCTURED, null);
    }

    /** 健康槽位 Map → 旧链路 SlotBundle（与 MealModule 同口径）。 */
    public SlotBundle toSlotBundle(Map<String, List<String>> healthSlots) {
        return SlotBundle.fromHealthSlots(healthSlots);
    }

    /**
     * 八个餐食槽位全部参与召回：显式槽位不得在召回入口被静默丢弃（演示召回规格）。
     * 空维度以空列表传入，Reader 的 SQL 对空数组不过滤；领域仍执行
     * {@link com.diet.health.module.MealModule#matchesStrict} 作为最终防线，Hybrid 向量回查
     * 也复用同一套硬约束。键集合固定为八槽位，不含 allergen 等非餐食键。
     */
    private Map<String, List<String>> hardRecallSlots(Map<String, List<String>> slots) {
        Map<String, List<String>> recall = new java.util.LinkedHashMap<>();
        if (slots == null) {
            return recall;
        }
        for (String slot : MEAL_SLOT_KEYS) {
            recall.put(slot, slots.getOrDefault(slot, List.of()));
        }
        return recall;
    }

    /** 八槽位固定键（与 {@link com.diet.health.intent.HealthSlotDictionary#MEAL_SLOTS} 一致）。 */
    private static final List<String> MEAL_SLOT_KEYS =
            List.of("mealTime", "mood", "scene", "healthGoal", "cuisine", "foodType", "taste", "convenience");
}
