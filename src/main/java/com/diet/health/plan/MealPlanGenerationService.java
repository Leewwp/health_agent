package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanScope;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.ChatIdempotencySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 独立餐食计划生成入口，只生成 MEAL 项目。 */
@Service
public class MealPlanGenerationService {

    private final HealthSessionService sessionService;
    private final HealthProfileService profileService;
    private final WeeklyPlanComposerService composer;
    private final WeeklyPlanService weeklyPlanService;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper;

    public MealPlanGenerationService(HealthSessionService sessionService, HealthProfileService profileService,
                                     WeeklyPlanComposerService composer, WeeklyPlanService weeklyPlanService,
                                     AgentTraceService traceService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.composer = composer;
        this.weeklyPlanService = weeklyPlanService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public TrainingPlanGenerationResponse generate(Long userId, GenerateTrainingPlanRequest request) {
        if (request == null || request.planScope() != PlanScope.MEAL) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "餐食生成入口只接受 MEAL 范围");
        }
        String requestId = request.requestId();
        if (requestId == null || requestId.isBlank()) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "requestId 不能为空");
        }
        HealthSessionState session = sessionService.loadOrCreate(request.sessionId(), userId);
        RequestTraceRow previous = traceService.findByRequestId(userId, session.sessionId(), requestId);
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), TrainingPlanGenerationResponse.class);
        }
        HealthProfileService.HealthProfileView profile = profileService.getProfile(userId);
        if (session.mealPlanBrief() == null || !session.mealPlanBrief().isConfirmedAndComplete()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "请先完成并确认餐食计划简报");
        }
        LocalDate weekStart = session.mealPlanBrief().weekStart();
        MealPlanBrief brief = session.mealPlanBrief();
        List<PlanItemDraft> items = composer.composeMeals(profile.calorieLow(), profile.calorieHigh(), weekStart,
                brief.mealTimes());
        if (items.isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核餐食库没有可生成的餐食候选");
        }
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        try (AgentTraceService.TraceScope scope = traceService.openTrace(traceId, session.sessionId(), userId, requestId)) {
            traceService.recordEvent("PLAN_GENERATION_STARTED", "PLAN",
                    Map.of("planScope", PlanScope.MEAL, "requestId", requestId),
                    Map.of("source", "RULE_MEAL_COMPOSER"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("planScope", PlanScope.MEAL.name());
            metadata.put("mealBriefConfirmationVersion", session.mealPlanBrief().confirmationVersion());
            metadata.put("mealTimes", brief.mealTimes());
            metadata.put("calorieAllocation", "按早餐/午餐/晚餐 30/40/30 权重对已选餐次归一化");
            metadata.put("generationSource", "RULE_MEAL_COMPOSER");
            PlanView plan = weeklyPlanService.persistScopedGeneratedDraft(userId,
                    new DraftPlanRequest(session.sessionId(), weekStart, profile.timezone(), null, PlanScope.MEAL),
                    PlanScope.MEAL, items, "RULE_MEAL_COMPOSER", metadata,
                    "餐食计划已按已确认的餐次和档案能量区间生成。");
            TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId,
                    "RULE_MEAL_COMPOSER", "SUCCESS", "餐食计划草稿已生成", plan);
            traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                    Map.of("planScope", PlanScope.MEAL));
            scope.setResponse(response);
            return response;
        }
    }
}
