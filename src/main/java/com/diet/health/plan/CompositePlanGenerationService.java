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
        // 生成前服务端重读会话并校验两侧简报完整性；不再存在确认字段或确认版本。
        // ADR-0018：weekStart 不是必填——简报缺锚点时两侧共用生成边界派生的内部锚点，
        // 既有锚点（旧会话）必须一致。
        if (session.planBrief() == null || !session.planBrief().isComplete()
                || session.mealPlanBrief() == null || !session.mealPlanBrief().isComplete()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                    "综合计划必须分别整理完整的训练和餐食简报");
        }
        if (session.planBrief().weekStart() != null && session.mealPlanBrief().weekStart() != null
                && !session.planBrief().weekStart().equals(session.mealPlanBrief().weekStart())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                    "综合计划必须分别整理完整的训练和餐食简报，且既有目标周一致");
        }
        HealthProfileService.HealthProfileView profile = profileService.getProfile(userId);
        LocalDate weekStart = session.planBrief().weekStart() != null
                ? session.planBrief().weekStart()
                : session.mealPlanBrief().weekStart() != null
                ? session.mealPlanBrief().weekStart()
                : WeekAnchorProvider.currentMonday(profile.timezone());
        PlanBrief effectiveTraining = session.planBrief().withWeekStart(weekStart);
        List<PlanItemDraft> exerciseItems =
                trainingGenerationService.generateExerciseItemsForComposite(userId, effectiveTraining);
        MealPlanBrief mealBrief = session.mealPlanBrief();
        // 综合计划的餐食侧与独立餐食计划一致地消费偏好，并形成按日回退说明
        WeeklyPlanComposerService.MealCompositionResult mealComposition =
                mealComposer.composeMealsWithPreferences(profile.calorieLow(), profile.calorieHigh(), weekStart,
                        mealBrief.mealTimes(), mealBrief);
        List<PlanItemDraft> mealItems = mealComposition.items();
        if (mealItems.isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核餐食库没有可生成的餐食候选");
        }
        // ADR-0018 训练优先餐训时间适配：训练时间是硬约束，餐食按确定性顺序避让；
        // 无可行时段抛稳定错误，综合计划整体失败（事务回滚，不留半成品）。
        MealTrainingScheduleAdapter.AdaptationResult adaptation =
                MealTrainingScheduleAdapter.adapt(mealItems, exerciseItems);
        List<PlanItemDraft> allItems = new ArrayList<>(exerciseItems);
        allItems.addAll(adaptation.mealItems());
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        try (AgentTraceService.TraceScope scope = traceService.openTrace(traceId, session.sessionId(), userId, request.requestId())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("planScope", PlanScope.COMPOSITE.name());
            metadata.put("mealTimes", mealBrief.mealTimes());
            metadata.put("calorieAllocation", "按早餐/午餐/晚餐 30/40/30 权重对已选餐次归一化");
            metadata.put("generationSource", "COMPOSITE_RULE_MERGE");
            metadata.put(GenerationNotes.METADATA_KEY, mealComposition.generationNotes()
                    .toMetadataWithAdditionalMealAdaptations(adaptation.notes()));
            Map<String, Object> adaptationMetadata = new LinkedHashMap<>();
            adaptation.notes().forEach(note -> adaptationMetadata.put(
                    note.date() + ":" + note.mealTime(), note.toMetadata()));
            metadata.put("mealTimeAdaptations", adaptationMetadata);
            traceService.recordEvent("MEAL_TIME_ADAPTED", "PLAN",
                    Map.of("adaptedCount", adaptation.notes().size()), adaptationMetadata);
            PlanView plan = weeklyPlanService.persistScopedGeneratedDraft(userId,
                    new DraftPlanRequest(session.sessionId(), weekStart, profile.timezone(), null, PlanScope.COMPOSITE),
                    PlanScope.COMPOSITE, allItems, "COMPOSITE_RULE_MERGE", metadata,
                    compositeExplanation(adaptation.notes().size(), adaptation),
                    request.requestId(), OPERATION, traceId);
            TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId,
                    "COMPOSITE_RULE_MERGE", "SUCCESS", "综合计划草稿已生成", plan);
            traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                    Map.of("planScope", PlanScope.COMPOSITE, "exerciseCount", exerciseItems.size(),
                            "mealCount", mealItems.size(), "adaptedMealCount", adaptation.notes().size()));
            scope.setResponse(response);
            // 计划已提交：COMPOSITE 同时关闭两侧简报；回写失败 → 5xx，重试经 GENERATE_COMPOSITE 写记录补偿
            sessionService.markBriefGenerated(userId, session.sessionId(),
                    GenerationIdempotencyService.scopesFor(OPERATION));
            return response;
        }
    }

    /** 综合计划生成说明：餐训自动适配对被移动餐次与方向（训练前/训练后）对用户可见。 */
    private String compositeExplanation(int adaptedCount, MealTrainingScheduleAdapter.AdaptationResult adaptation) {
        StringBuilder copy = new StringBuilder("综合计划已分别生成训练和餐食安排，并完成范围校验。");
        if (adaptedCount > 0) {
            List<String> parts = new ArrayList<>();
            for (MealTrainingScheduleAdapter.AdaptationNote note : adaptation.notes()) {
                parts.add(note.date() + " " + note.mealTime() + " " + note.originalStart() + "-"
                        + note.originalEnd() + " 自动调整到 " + note.finalStart() + "-" + note.finalEnd()
                        + "（" + note.direction().label() + "）");
            }
            copy.append("餐食时间已按训练安排自动适配：").append(String.join("；", parts)).append("。");
        }
        return copy.toString();
    }
}