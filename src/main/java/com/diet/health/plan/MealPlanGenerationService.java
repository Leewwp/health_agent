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

/** 独立餐食计划生成入口，只生成 MEAL 项目；成功后关闭会话餐食侧简报（GENERATED）。 */
@Service
public class MealPlanGenerationService {

    /** 生成写入幂等操作（requestId 对同一用户全局唯一）。 */
    public static final String OPERATION = "GENERATE_MEAL";

    private final HealthSessionService sessionService;
    private final HealthProfileService profileService;
    private final WeeklyPlanComposerService composer;
    private final WeeklyPlanService weeklyPlanService;
    private final GenerationIdempotencyService idempotencyService;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper;

    public MealPlanGenerationService(HealthSessionService sessionService, HealthProfileService profileService,
                                     WeeklyPlanComposerService composer, WeeklyPlanService weeklyPlanService,
                                     GenerationIdempotencyService idempotencyService,
                                     AgentTraceService traceService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.composer = composer;
        this.weeklyPlanService = weeklyPlanService;
        this.idempotencyService = idempotencyService;
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
        // 生成写幂等优先：命中记录时恢复既有响应并补偿生命周期回写，不重新生成计划
        GenerationIdempotencyService.ReplayedGeneration replay =
                idempotencyService.replay(userId, requestId, OPERATION, session.sessionId());
        if (replay != null) {
            return new TrainingPlanGenerationResponse(replay.planId(), replay.traceId(),
                    replay.plan().generationSource(), "SUCCESS", "餐食计划草稿已生成", replay.plan());
        }
        RequestTraceRow previous = traceService.findByRequestId(userId, session.sessionId(), requestId);
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), TrainingPlanGenerationResponse.class);
        }
        HealthProfileService.HealthProfileView profile = profileService.getProfile(userId);
        // 生成前服务端重读会话并校验当前简报完整性；不再存在确认字段或确认版本。
        if (session.mealPlanBrief() == null || !session.mealPlanBrief().isComplete()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "请先在当前会话整理完整的餐食计划简报，再开始生成");
        }
        LocalDate weekStart = session.mealPlanBrief().weekStart();
        MealPlanBrief brief = session.mealPlanBrief();
        // 真正消费简报可选偏好：先分桶偏好过滤，某日偏好池不足时整天回退并记录生成说明
        WeeklyPlanComposerService.MealCompositionResult composition =
                composer.composeMealsWithPreferences(profile.calorieLow(), profile.calorieHigh(), weekStart,
                        brief.mealTimes(), brief);
        List<PlanItemDraft> items = composition.items();
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
            metadata.put("mealTimes", brief.mealTimes());
            metadata.put("calorieAllocation", "按早餐/午餐/晚餐 30/40/30 权重对已选餐次归一化");
            metadata.put("generationSource", "RULE_MEAL_COMPOSER");
            // 生成说明三处可见合同的来源：metadata → 版本快照 generation 节点 → PlanView / 详情 API
            metadata.put(GenerationNotes.METADATA_KEY, composition.generationNotes().toMetadata());
            PlanView plan = weeklyPlanService.persistScopedGeneratedDraft(userId,
                    new DraftPlanRequest(session.sessionId(), weekStart, profile.timezone(), null, PlanScope.MEAL),
                    PlanScope.MEAL, items, "RULE_MEAL_COMPOSER", metadata,
                    "餐食计划已按当前简报选择的餐次和档案能量区间生成。",
                    requestId, OPERATION, traceId);
            TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId,
                    "RULE_MEAL_COMPOSER", "SUCCESS", "餐食计划草稿已生成", plan);
            traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                    Map.of("planScope", PlanScope.MEAL));
            scope.setResponse(response);
            // 计划已提交：关闭会话餐食侧简报；回写失败 → 请求 5xx，重试经 GENERATE_MEAL 写记录补偿
            sessionService.markBriefGenerated(userId, session.sessionId(),
                    GenerationIdempotencyService.scopesFor(OPERATION));
            return response;
        }
    }
}
