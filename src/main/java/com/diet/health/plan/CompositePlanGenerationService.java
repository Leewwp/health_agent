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

/** 综合计划生成：分别生成训练/餐食子计划，再一次性执行范围 Guard 和持久化；成功后同时关闭两侧简报。 */
@Service
public class CompositePlanGenerationService {

    /** 生成写入幂等操作（requestId 对同一用户全局唯一；COMPOSITE 同时关闭两侧生命周期）。 */
    public static final String OPERATION = "GENERATE_COMPOSITE";

    private final HealthSessionService sessionService;
    private final HealthProfileService profileService;
    private final TrainingPlanGenerationService trainingGenerationService;
    private final WeeklyPlanComposerService mealComposer;
    private final WeeklyPlanService weeklyPlanService;
    private final GenerationIdempotencyService idempotencyService;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper;

    public CompositePlanGenerationService(HealthSessionService sessionService, HealthProfileService profileService,
                                          TrainingPlanGenerationService trainingGenerationService,
                                          WeeklyPlanComposerService mealComposer, WeeklyPlanService weeklyPlanService,
                                          GenerationIdempotencyService idempotencyService,
                                          AgentTraceService traceService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.trainingGenerationService = trainingGenerationService;
        this.mealComposer = mealComposer;
        this.weeklyPlanService = weeklyPlanService;
        this.idempotencyService = idempotencyService;
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
        // 生成写幂等优先：命中记录时恢复既有响应并补偿生命周期回写，不重新生成计划
        GenerationIdempotencyService.ReplayedGeneration replay =
                idempotencyService.replay(userId, request.requestId(), OPERATION, session.sessionId());
        if (replay != null) {
            return new TrainingPlanGenerationResponse(replay.planId(), replay.traceId(),
                    replay.plan().generationSource(), "SUCCESS", "综合计划草稿已生成", replay.plan());
        }
        RequestTraceRow previous = traceService.findByRequestId(userId, session.sessionId(), request.requestId());
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), TrainingPlanGenerationResponse.class);
        }
        // 生成前服务端重读会话并校验两侧简报完整性与目标周一致；不再存在确认字段或确认版本。
        if (session.planBrief() == null || !session.planBrief().isComplete()
                || session.mealPlanBrief() == null || !session.mealPlanBrief().isComplete()
                || !session.planBrief().weekStart().equals(session.mealPlanBrief().weekStart())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                    "综合计划必须分别整理完整的训练和餐食简报，且目标周一致");
        }
        HealthProfileService.HealthProfileView profile = profileService.getProfile(userId);
        LocalDate weekStart = session.planBrief().weekStart();
        List<PlanItemDraft> exerciseItems = trainingGenerationService.generateExerciseItemsForComposite(userId, session);
        MealPlanBrief mealBrief = session.mealPlanBrief();
        // 综合计划的餐食侧与独立餐食计划一致地消费偏好，并形成按日回退说明
        WeeklyPlanComposerService.MealCompositionResult mealComposition =
                mealComposer.composeMealsWithPreferences(profile.calorieLow(), profile.calorieHigh(), weekStart,
                        mealBrief.mealTimes(), mealBrief);
        List<PlanItemDraft> mealItems = mealComposition.items();
        if (mealItems.isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核餐食库没有可生成的餐食候选");
        }
        List<PlanItemDraft> allItems = new ArrayList<>(exerciseItems);
        allItems.addAll(mealItems);
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        try (AgentTraceService.TraceScope scope = traceService.openTrace(traceId, session.sessionId(), userId, request.requestId())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("planScope", PlanScope.COMPOSITE.name());
            metadata.put("mealTimes", mealBrief.mealTimes());
            metadata.put("calorieAllocation", "按早餐/午餐/晚餐 30/40/30 权重对已选餐次归一化");
            metadata.put("generationSource", "COMPOSITE_RULE_MERGE");
            metadata.put(GenerationNotes.METADATA_KEY, mealComposition.generationNotes().toMetadata());
            PlanView plan = weeklyPlanService.persistScopedGeneratedDraft(userId,
                    new DraftPlanRequest(session.sessionId(), weekStart, profile.timezone(), null, PlanScope.COMPOSITE),
                    PlanScope.COMPOSITE, allItems, "COMPOSITE_RULE_MERGE", metadata,
                    "综合计划已分别生成训练和餐食安排，并完成范围校验。",
                    request.requestId(), OPERATION, traceId);
            TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId,
                    "COMPOSITE_RULE_MERGE", "SUCCESS", "综合计划草稿已生成", plan);
            traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                    Map.of("planScope", PlanScope.COMPOSITE, "exerciseCount", exerciseItems.size(),
                            "mealCount", mealItems.size()));
            scope.setResponse(response);
            // 计划已提交：COMPOSITE 同时关闭两侧简报；回写失败 → 5xx，重试经 GENERATE_COMPOSITE 写记录补偿
            sessionService.markBriefGenerated(userId, session.sessionId(),
                    GenerationIdempotencyService.scopesFor(OPERATION));
            return response;
        }
    }
}
