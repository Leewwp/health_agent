package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetrievalDecision;
import com.diet.health.rag.MealRetrievalRouter;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.reader.meal.ReviewedMealIds;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 餐食领域模块：旧饮食链路走 searchAndRank；健康链路按资源模式分流——
 * REVIEWED_DB 走 MealRetriever（hybrid 或结构化）；FIXTURE_SEED 从
 * {@link HealthResourceProvider#planMealCandidates()} 读取 M1-M9 做确定性匹配/排序，
 * 完全绕过 Structured/Hybrid MealRetriever、EmbeddingClient 与审核餐食 DB adapter。
 * <p>
 * PERSONAL 空库不再由编排层全局提前返回，统一在模块内处理（返回空候选，由调用方按空结果提示）。
 */
@Service
public class MealModule {

    private static final Logger log = LoggerFactory.getLogger(MealModule.class);

    /** 健康链路候选上限（与旧链路 rank 的 10 条一致）。 */
    private static final int RECOMMEND_LIMIT = 10;

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;
    private final AgentTraceService agentTraceService;
    private final MealRetrievalRouter retrievalRouter;
    private final PreferenceService preferenceService;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreIdentity vectorStoreIdentity;
    private final HealthResourceProvider resourceProvider;

    /** Spring 线上构造：路由在结构化与语义实验路径之间选择。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MealModule(MealSearchService mealSearchService, MealRankService mealRankService,
                      AgentTraceService agentTraceService,
                      MealRetrievalRouter retrievalRouter,
                      PreferenceService preferenceService,
                      EmbeddingClient embeddingClient,
                      VectorStoreIdentity vectorStoreIdentity,
                      HealthResourceProvider resourceProvider) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
        this.agentTraceService = agentTraceService;
        this.retrievalRouter = retrievalRouter;
        this.preferenceService = preferenceService;
        this.embeddingClient = embeddingClient;
        this.vectorStoreIdentity = vectorStoreIdentity;
        this.resourceProvider = resourceProvider;
    }

    /** 旧饮食链路：检索 + 重排，返回最多 10 条候选，内部记录 MEAL_SEARCHED / MEAL_RANKED Trace 事件。 */
    public List<MealItem> searchAndRank(SourceMode sourceMode, Long userId, SlotBundle slots, List<Long> excludeMealIds) {
        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(sourceMode, userId, slots, excludeMealIds));
        agentTraceService.recordEvent("MEAL_SEARCHED", "SEARCH", slots,
                Map.of("candidateCount", candidates.size(), "candidates", candidates));
        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, excludeMealIds));
        agentTraceService.recordEvent("MEAL_RANKED", "RANK", Map.of("excludeMealIds", excludeMealIds),
                Map.of("rankedCount", ranked.size(), "ranked", ranked));
        return ranked;
    }

    /** 健康链路：按资源模式分流（fixture 走种子确定性路径，reviewed 走 MealRetriever）。 */
    public List<HealthResource> recommendMeals(Map<String, List<String>> healthSlots, List<String> excludeIds) {
        return recommendMeals(healthSlots, excludeIds, "");
    }

    /** 健康链路（可携带显式嵌入文本，M5 #47 MCP search_meals 用；为空时内部用槽位文本兜底）。 */
    public List<HealthResource> recommendMeals(Map<String, List<String>> healthSlots, List<String> excludeIds,
                                               String text) {
        if (resourceProvider.providerMode().isFixture()) {
            return recommendFromSeed(healthSlots, excludeIds);
        }
        // reviewed：类型化排除 ID 在进入数据库查询前只接受数值 ID，非法/跨模式 ID 显式忽略并记录
        List<Long> numericExclude = ReviewedMealIds.parseNumeric(excludeIds, log, "reviewed 餐食排除");
        MealRetrievalQuery query = new MealRetrievalQuery(
                healthSlots,
                numericExclude,
                healthSlots.getOrDefault("allergen", List.of()),
                text
        );
        MealRetrievalDecision decision = retrievalRouter.retrieveWithDecision(query, RECOMMEND_LIMIT);
        RetrievalResult result = decision.result();
        Map<String, Object> traceDetail = new LinkedHashMap<>();
        traceDetail.put("route", decision.route().name());
        traceDetail.put("actualRetriever", decision.actualRetriever());
        traceDetail.put("retrievalLatencyMs", decision.elapsedMs());
        traceDetail.put("mode", result.mode().name());
        traceDetail.put("degradationReason", result.degradationReason());
        traceDetail.put("vectorProvider", vectorStoreIdentity.provider());
        traceDetail.put("vectorModel", embeddingClient.modelName());
        traceDetail.put("vectorVersion", embeddingClient.modelVersion());
        traceDetail.put("collection", vectorStoreIdentity.collectionName());
        traceDetail.put("candidateCount", result.items().size());
        traceDetail.put("candidates", result.items().stream().map(RetrievalItem::meal).toList());
        agentTraceService.recordEvent("MEAL_RETRIEVED", "RETRIEVE", healthSlots, traceDetail);
        List<HealthResource> resources = result.items().stream()
                .map(item -> {
                    ReviewedMeal reviewed = item.reviewedMeal();
                    return new HealthResource(
                        "MEAL",
                        String.valueOf(item.meal().id()),
                        item.meal().name(),
                        reviewed == null ? (item.meal().sourceType() == null ? "PUBLIC" : item.meal().sourceType().name()) : reviewed.sourceType(),
                        reviewed == null ? "公共餐食库" : reviewed.sourceName(),
                        reviewed == null ? null : reviewed.mediaUrl(),
                        false,
                        reviewed == null ? slotsToTags(item.meal().slots()) : reviewed.tags(),
                        reviewed == null ? List.of() : reviewed.ingredients(),
                        reviewed == null ? null : toNutrition(reviewed.nutrition())
                    );
                })
                .filter(resource -> matchesStrict(resource, healthSlots))
                .collect(java.util.stream.Collectors.toMap(resource -> resource.resourceType() + ":" + resource.resourceId(),
                        resource -> resource, (left, right) -> left, java.util.LinkedHashMap::new))
                .values().stream().limit(RECOMMEND_LIMIT).toList();
        return preferenceService.applyPreference(resources);
    }

    /**
     * FIXTURE_SEED 餐食推荐（#69）：从 Provider 的 M1-M9 种子候选按健康槽位做确定性
     * 匹配/排序并转换为类型化资源，再复用偏好服务。完全绕过 MealRetriever、EmbeddingClient
     * 与审核餐食 DB adapter；fixture ID 保持 M1-M9 原样，不强转 Long、不冒充审核库主键。
     */
    private List<HealthResource> recommendFromSeed(Map<String, List<String>> healthSlots,
                                                   List<String> excludeIds) {
        Set<String> exclude = new HashSet<>(excludeIds == null ? List.of() : excludeIds);
        List<String> queryMealTime = healthSlots == null ? List.of() : healthSlots.getOrDefault("mealTime", List.of());
        List<PlanMealCandidate> candidates = resourceProvider.planMealCandidates().stream()
                .filter(candidate -> !exclude.contains(candidate.resourceId()))
                .filter(candidate -> matchesSeedMealTime(candidate, healthSlots))
                .toList();
        List<Scored> scored = candidates.stream()
                .map(candidate -> new Scored(candidate, mealTimeScore(candidate, queryMealTime)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingLong(scored1 -> scored1.candidate().sortKey()))
                .toList();
        List<HealthResource> ranked = scored.stream()
                .filter(item -> queryMealTime.isEmpty() || item.score() > 0)
                .map(item -> toSeedResource(item.candidate()))
                .limit(RECOMMEND_LIMIT)
                .toList();
        agentTraceService.recordEvent("MEAL_RETRIEVED", "RETRIEVE", healthSlots,
                fixtureTraceDetail(ranked));
        return preferenceService.applyPreference(ranked);
    }

    /** 显式槽位逐字段 AND、同字段多值 OR；缺少标签或空标签都不得视为命中。 */
    private boolean matchesStrict(HealthResource item, Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) return true;
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;
            // 审核资源的显式筛选条件必须有对应标签并命中，避免向量召回绕过硬约束。
            if (!item.tags().containsKey(entry.getKey())) return false;
            List<String> tags = item.tags().getOrDefault(entry.getKey(), List.of());
            boolean allDayMeal = tags.contains("三餐");
            if (tags.isEmpty() || values.stream().noneMatch(value -> tags.contains(value)
                    || (allDayMeal && List.of("早餐", "午餐", "晚餐").contains(value)))) return false;
        }
        return true;
    }

    /** 从当前餐食目录提取追加建议可用值。 */
    public Map<String, List<String>> availableSlotValues() {
        Map<String, java.util.LinkedHashSet<String>> values = new java.util.LinkedHashMap<>();
        resourceProvider.singleRecommendationMeals().forEach(resource -> resource.tags().forEach((slot, tags) ->
                values.computeIfAbsent(slot, key -> new java.util.LinkedHashSet<>()).addAll(tags)));
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((slot, slotValues) -> result.put(slot, List.copyOf(slotValues)));
        return Map.copyOf(result);
    }

    /** 种子候选餐次标签与查询餐次的重合比例（无查询餐次或候选全时段视为全部匹配）。 */
    private double mealTimeScore(PlanMealCandidate candidate, List<String> queryMealTime) {
        List<String> mealTimes = candidate.mealTimeTags();
        if (queryMealTime.isEmpty() || mealTimes.isEmpty()) {
            return 1;
        }
        long hits = mealTimes.stream().filter(tag -> queryMealTime.contains(tag)
                || ("三餐".equals(tag) && queryMealTime.stream().anyMatch(List.of("早餐", "午餐", "晚餐")::contains))).count();
        return (double) hits / mealTimes.size();
    }

    /** 种子候选 → 类型化资源：resourceId 保持 M1-M9，标签只携带种子实际提供的餐次口径。 */
    private HealthResource toSeedResource(PlanMealCandidate candidate) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", candidate.mealTimeTags());
        tags.put("cuisine", candidate.cuisineTags());
        tags.put("foodType", candidate.foodTypeTags());
        tags.put("taste", candidate.tasteTags());
        tags.put("convenience", candidate.convenienceTags());
        tags.put("healthGoal", candidate.nutritionPreferenceTags());
        return new HealthResource(
                "MEAL",
                candidate.resourceId(),
                candidate.name(),
                "PUBLIC",
                "公共餐食库",
                null,
                false,
                tags
        );
    }

    private boolean matchesSeedMealTime(PlanMealCandidate candidate, Map<String, List<String>> slots) {
        if (slots == null || slots.isEmpty()) return true;
        List<String> values = slots.getOrDefault("mealTime", List.of());
        if (values.isEmpty()) return true;
        List<String> tags = candidate.mealTimeTags();
        boolean allDay = tags.contains("三餐");
        return values.stream().anyMatch(value -> tags.contains(value)
                || (allDay && List.of("早餐", "午餐", "晚餐").contains(value)));
    }

    /** fixture 路径 Trace：明确记录 FIXTURE 模式与无向量 collection，不调用 Embedding/VectorStore。 */
    private Map<String, Object> fixtureTraceDetail(List<HealthResource> ranked) {
        Map<String, Object> traceDetail = new LinkedHashMap<>();
        traceDetail.put("mode", resourceProvider.providerMode().name());
        traceDetail.put("degradationReason", null);
        traceDetail.put("vectorProvider", null);
        traceDetail.put("vectorModel", null);
        traceDetail.put("vectorVersion", null);
        traceDetail.put("collection", null);
        traceDetail.put("candidateCount", ranked.size());
        traceDetail.put("candidates", ranked);
        return traceDetail;
    }

    /** 旧链路使用的餐食槽位（SlotBundle）→ 标签 Map。 */
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
        tags.put("foodType", slots.foodType());
        tags.put("taste", slots.taste());
        tags.put("convenience", slots.convenience());
        return tags;
    }

    private record Scored(PlanMealCandidate candidate, double score) {
    }

    private HealthResource.Nutrition toNutrition(ReviewedMeal.Nutrition nutrition) {
        if (nutrition == null) {
            return null;
        }
        return new HealthResource.Nutrition(nutrition.caloriesKcal(), nutrition.proteinG(), nutrition.fatG(),
                nutrition.carbohydrateG(), nutrition.basis(), nutrition.estimated());
    }

    /** 仅供 Spring wiring 测试确认生产构造注入了路由。 */
    MealRetrievalRouter retrievalRouterForTest() {
        return retrievalRouter;
    }
}
