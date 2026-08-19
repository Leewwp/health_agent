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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 综合计划生成：分别生成训练/餐食子计划，再一次性执行范围 Guard 和持久化。 */
@Service
public class CompositePlanGenerationService {

    private final HealthSessionService sessionService;
    private final HealthProfileService profileService;
    private final TrainingPlanGenerationService trainingGenerationService;
    private final WeeklyPlanComposerService mealComposer;
    private final WeeklyPlanService weeklyPlanService;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper;

    public CompositePlanGenerationService(HealthSessionService sessionService, HealthProfileService profileService,
                                          TrainingPlanGenerationService trainingGenerationService,
                                          WeeklyPlanComposerService mealComposer, WeeklyPlanService weeklyPlanService,
                                          AgentTraceService traceService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.trainingGenerationService = trainingGenerationService;
        this.mealComposer = mealComposer;
        this.weeklyPlanService = weeklyPlanService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public TrainingPlanGenerationResponse generate(Long userId, GenerateTrainingPlanRequest request) {
        if (request == null || request.planScope() != PlanScope.COMPOSITE) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "综合生成入口只接受 COMPOSITE 范围");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "requestId 不能为空");
        }
        HealthSessionState session = sessionService.loadOrCreate(request.sessionId(), userId);
        RequestTraceRow previous = traceService.findByRequestId(userId, session.sessionId(), request.requestId());
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), TrainingPlanGenerationResponse.class);
        }
        if (session.planBrief() == null || !session.planBrief().isConfirmedAndComplete()
                || session.mealPlanBrief() == null || !session.mealPlanBrief().isConfirmedAndComplete()
                || !session.planBrief().weekStart().equals(session.mealPlanBrief().weekStart())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                    "综合计划必须分别完成并确认训练和餐食简报");
        }
        HealthProfileService.HealthProfileView profile = profileService.getProfile(userId);
        LocalDate weekStart = session.planBrief().weekStart();
        List<PlanItemDraft> exerciseItems = trainingGenerationService.generateExerciseItemsForComposite(userId, session);
        List<PlanItemDraft> mealItems = mealComposer.composeMeals(profile.calorieLow(), profile.calorieHigh(), weekStart);
        if (mealItems.isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核餐食库没有可生成的餐食候选");
        }
        List<PlanItemDraft> allItems = new ArrayList<>(exerciseItems);
        allItems.addAll(mealItems);
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        try (AgentTraceService.TraceScope scope = traceService.openTrace(traceId, session.sessionId(), userId, request.requestId())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("planScope", PlanScope.COMPOSITE.name());
            metadata.put("exerciseBriefConfirmationVersion", session.planBrief().confirmationVersion());
            metadata.put("mealBriefConfirmationVersion", session.mealPlanBrief().confirmationVersion());
            metadata.put("generationSource", "COMPOSITE_RULE_MERGE");
            PlanView plan = weeklyPlanService.persistScopedGeneratedDraft(userId,
                    new DraftPlanRequest(session.sessionId(), weekStart, profile.timezone(), null, PlanScope.COMPOSITE),
                    PlanScope.COMPOSITE, allItems, "COMPOSITE_RULE_MERGE", metadata,
                    "综合计划已分别生成训练和餐食安排，并完成范围校验。");
            TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId,
                    "COMPOSITE_RULE_MERGE", "SUCCESS", "综合计划草稿已生成", plan);
            traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                    Map.of("planScope", PlanScope.COMPOSITE, "exerciseCount", exerciseItems.size(),
                            "mealCount", mealItems.size()));
            scope.setResponse(response);
            return response;
        }
    }
}
