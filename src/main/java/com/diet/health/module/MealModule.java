package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.MealRetrievalQuery;
import com.diet.health.rag.MealRetriever;
import com.diet.health.rag.RetrievalItem;
import com.diet.health.rag.RetrievalResult;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 餐食领域模块：旧饮食链路走 searchAndRank；健康链路走 MealRetriever（hybrid 或结构化）。
 * <p>
 * PERSONAL 空库不再由编排层全局提前返回，统一在模块内处理（返回空候选，由调用方按空结果提示）。
 */
@Service
public class MealModule {

    /** 健康链路候选上限（与旧链路 rank 的 10 条一致）。 */
    private static final int RECOMMEND_LIMIT = 10;

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;
    private final AgentTraceService agentTraceService;
    private final MealRetriever mealRetriever;
    private final PreferenceService preferenceService;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreIdentity vectorStoreIdentity;

    public MealModule(MealSearchService mealSearchService, MealRankService mealRankService,
                      AgentTraceService agentTraceService,
                      @Qualifier("mealRetriever") MealRetriever mealRetriever,
                      PreferenceService preferenceService,
                      EmbeddingClient embeddingClient,
                      VectorStoreIdentity vectorStoreIdentity) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
        this.agentTraceService = agentTraceService;
        this.mealRetriever = mealRetriever;
        this.preferenceService = preferenceService;
        this.embeddingClient = embeddingClient;
        this.vectorStoreIdentity = vectorStoreIdentity;
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

    /** 健康链路：走 MealRetriever（hybrid/结构化），转为类型化资源并记录检索模式 Trace。 */
    public List<HealthResource> recommendMeals(Map<String, List<String>> healthSlots, List<Long> excludeIds) {
        MealRetrievalQuery query = new MealRetrievalQuery(
                healthSlots,
                excludeIds,
                healthSlots.getOrDefault("allergen", List.of()),
                ""
        );
        RetrievalResult result = mealRetriever.retrieve(query, RECOMMEND_LIMIT);
        Map<String, Object> traceDetail = new LinkedHashMap<>();
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
                .map(item -> new HealthResource(
                        "MEAL",
                        String.valueOf(item.meal().id()),
                        item.meal().name(),
                        item.meal().sourceType() == null ? "PUBLIC" : item.meal().sourceType().name(),
                        "公共餐食库",
                        null,
                        false,
                        slotsToTags(item.meal().slots())
                ))
                .toList();
        return preferenceService.applyPreference(resources);
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
}
