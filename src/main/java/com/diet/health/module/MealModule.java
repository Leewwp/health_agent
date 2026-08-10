package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 餐食领域模块：封装旧饮食链路的检索 + 重排组合。
 * <p>
 * PERSONAL 空库不再由编排层全局提前返回，统一在模块内处理（返回空候选，由调用方按空结果提示）。
 */
@Service
public class MealModule {

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;
    private final AgentTraceService agentTraceService;

    public MealModule(MealSearchService mealSearchService, MealRankService mealRankService,
                      AgentTraceService agentTraceService) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
        this.agentTraceService = agentTraceService;
    }

    /** 检索 + 重排，返回最多 10 条候选，内部记录 MEAL_SEARCHED / MEAL_RANKED Trace 事件。 */
    public List<MealItem> searchAndRank(SourceMode sourceMode, Long userId, SlotBundle slots, List<Long> excludeMealIds) {
        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(sourceMode, userId, slots, excludeMealIds));
        agentTraceService.recordEvent("MEAL_SEARCHED", "SEARCH", slots,
                Map.of("candidateCount", candidates.size(), "candidates", candidates));
        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, excludeMealIds));
        agentTraceService.recordEvent("MEAL_RANKED", "RANK", Map.of("excludeMealIds", excludeMealIds),
                Map.of("rankedCount", ranked.size(), "ranked", ranked));
        return ranked;
    }

    /** 健康链路：把健康槽位 Map 映射为 SlotBundle，以 PUBLIC 数据源检索并转为类型化资源。 */
    public List<HealthResource> recommendMeals(Map<String, List<String>> healthSlots, List<Long> excludeIds) {
        SlotBundle slots = toSlotBundle(healthSlots);
        List<MealItem> ranked = searchAndRank(SourceMode.PUBLIC, null, slots, excludeIds);
        return ranked.stream()
                .map(item -> new HealthResource(
                        "MEAL",
                        String.valueOf(item.id()),
                        item.name(),
                        item.sourceType() == null ? "PUBLIC" : item.sourceType().name(),
                        "公共餐食库",
                        null,
                        false,
                        slotsToTags(item.slots())
                ))
                .toList();
    }

    /** 旧链路使用的 7 维槽位（SlotBundle）→ 标签 Map。 */
    public Map<String, List<String>> slotsToTags(SlotBundle slots) {
        if (slots == null) {
            return Map.of();
        }
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", slots.mealTime());
        tags.put("mood", slots.mood());
        tags.put("scene", slots.scene());
        tags.put("healthGoal", slots.healthGoal());
        tags.put("cuisine", slots.cuisine());
        tags.put("taste", slots.taste());
        tags.put("convenience", slots.convenience());
        return tags;
    }

    /** 健康槽位 Map → 旧链路 SlotBundle（只取饮食 7 维，其余领域槽位忽略）。 */
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
