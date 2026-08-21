package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.exception.HealthApiException;
import com.diet.health.module.HealthResource;
import com.diet.health.enums.PlanScope;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.ChatIdempotencySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 受约束训练计划的唯一高层生成入口。模型调用在事务外，最终写入由 WeeklyPlanService 开启短事务。 */
@Service
public class TrainingPlanGenerationService {

    public static final String PROMPT_VERSION = "2026-08-19-training-plan-v1";
    public static final String CONTRACT_VERSION = "training-plan-v1";
    public static final String GUARD_VERSION = "2026-08-19-training-plan-guard-v1";
    private static final int MIN_DURATION_MINUTES = 20;
    private static final int MAX_DURATION_MINUTES = 90;

    private final HealthSessionService sessionService;
    private final HealthProfileService profileService;
    private final HealthRiskRuleService riskRuleService;
    private final HealthResourceProvider resourceProvider;
    private final PlanValidationService validationService;
    private final WeeklyPlanComposerService composer;
    private final WeeklyPlanService weeklyPlanService;
    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final Duration applicationTimeout;
    private final Duration modelTimeout;
    private final Map<String, Object> requestLocks = new ConcurrentHashMap<>();

    public TrainingPlanGenerationService(
            HealthSessionService sessionService,
            HealthProfileService profileService,
            HealthRiskRuleService riskRuleService,
            HealthResourceProvider resourceProvider,
            PlanValidationService validationService,
            WeeklyPlanComposerService composer,
            WeeklyPlanService weeklyPlanService,
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            AgentTraceService traceService,
            ObjectMapper objectMapper,
            @Value("${diet.llm.main-model:qwen-turbo}") String modelName,
            @Value("${diet.plan-generation.timeout-ms:15000}") long timeoutMs
    ) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.riskRuleService = riskRuleService;
        this.resourceProvider = resourceProvider;
        this.validationService = validationService;
        this.composer = composer;
        this.weeklyPlanService = weeklyPlanService;
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        if (timeoutMs < 2) {
            throw new IllegalArgumentException("训练计划生成截止时间至少为 2ms");
        }
        this.applicationTimeout = Duration.ofMillis(timeoutMs);
        this.modelTimeout = Duration.ofMillis(Math.max(1, timeoutMs * 4 / 5));
    }

    public TrainingPlanGenerationResponse generate(Long userId, GenerateTrainingPlanRequest request) {
        if (request == null || request.planScope() == null || request.planScope() != PlanScope.EXERCISE) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "训练生成入口只接受 EXERCISE 范围");
        }
        String requestId = normalizeRequestId(request == null ? null : request.requestId());
        HealthSessionState initialSession = sessionService.loadOrCreate(request == null ? null : request.sessionId(), userId);
        RequestTraceRow previous = traceService.findByRequestId(userId, initialSession.sessionId(), requestId);
        if (ChatIdempotencySupport.hasSnapshot(previous)) {
            return ChatIdempotencySupport.restore(objectMapper, previous.getResponseJson(), TrainingPlanGenerationResponse.class);
        }

        String lockKey = userId + ":" + initialSession.sessionId() + ":" + requestId;
        Object requestLock = requestLocks.computeIfAbsent(lockKey, key -> new Object());
        try {
            synchronized (requestLock) {
                RequestTraceRow concurrent = traceService.findByRequestId(userId, initialSession.sessionId(), requestId);
                if (ChatIdempotencySupport.hasSnapshot(concurrent)) {
                    return ChatIdempotencySupport.restore(objectMapper, concurrent.getResponseJson(), TrainingPlanGenerationResponse.class);
                }
                // 锁内重读，确保 Agent 使用用户最新确认/纠正后的简报，而不是入口快照。
                HealthSessionState session = sessionService.loadOrCreate(initialSession.sessionId(), userId);
                String traceId = "trace_" + java.util.UUID.randomUUID().toString().replace("-", "");
                long deadlineNanos = System.nanoTime() + applicationTimeout.toNanos();
                try (AgentTraceService.TraceScope scope = traceService.openTrace(traceId, session.sessionId(), userId, requestId)) {
                traceService.recordEvent("PLAN_GENERATION_STARTED", "PLAN", Map.of("requestId", requestId),
                        Map.of("guardVersion", GUARD_VERSION, "promptVersion", PROMPT_VERSION,
                                "contractVersion", CONTRACT_VERSION, "applicationTimeoutMs", applicationTimeout.toMillis(),
                                "modelTimeoutMs", modelTimeout.toMillis()));
                HealthProfileView profile = requireProfile(userId);
                PlanBrief brief = requireConfirmedBrief(session);
                HealthRiskRuleService.RiskDecision risk = riskRuleService.assessProfile(
                        profile.age(), true, profile.riskConditions());
                if (risk.blocked()) {
                    traceService.recordEvent("PLAN_GUARD_REJECTED", "GUARD", brief,
                            Map.of("decision", "BLOCK_PLAN", "copy", risk.copy(), "matchedFlags", risk.matchedFlags()));
                    throw new HealthApiException(HealthApiException.CODE_RISK_BLOCKED, risk.copy());
                }

                CandidateSelection candidateSelection = filterCandidates(brief);
                List<HealthResource> candidates = candidateSelection.resources();
                traceService.recordEvent("PLAN_CANDIDATES_FILTERED", "RETRIEVE", brief,
                        Map.of("candidateCount", candidates.size(), "candidateIds", candidates.stream().map(HealthResource::resourceId).toList(),
                                "resourceVersion", resourceProvider.resourceVersion(),
                                "goalRelaxed", candidateSelection.goalRelaxed(),
                                "difficultyRelaxed", candidateSelection.difficultyRelaxed()));
                if (candidates.isEmpty()) {
                    throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核动作库没有满足部位和器材偏好的动作");
                }

                String source = "AGENT";
                String fallbackReason = null;
                String actualModel = "";
                List<PlanItemDraft> trainingItems;
                try {
                    PlanAgentOutput agentOutput = callAgent(brief, profile, candidates, deadlineNanos);
                    actualModel = modelName;
                    trainingItems = guardAgentOutput(brief, candidates, agentOutput);
                    // 在写入前复用同一套计划 Guard，覆盖时间冲突、连续部位和其他组合不变量。
                    validateForPersistence(profile, trainingItems);
                } catch (RuntimeException error) {
                    if (!(error instanceof AgentFailureException failure)
                            || failure.type() != AgentFailureType.MISSING_CONFIG) {
                        actualModel = modelName;
                    }
                    source = "FALLBACK";
                    fallbackReason = "MODEL_OR_GUARD_FAILED: " + error.getMessage();
                    trainingItems = fallback(brief, candidates);
                }
                if (trainingItems.isEmpty()) {
                    source = "FALLBACK";
                    fallbackReason = fallbackReason == null ? "EMPTY_SCHEDULE" : fallbackReason;
                    trainingItems = fallback(brief, candidates);
                }

                List<PlanItemDraft> allItems = new ArrayList<>(trainingItems);
                validateForPersistence(profile, allItems);
                traceService.recordEvent("PLAN_GUARD_PASSED", "GUARD",
                        Map.of("source", source, "fallbackReason", fallbackReason == null ? "" : fallbackReason),
                        Map.of("source", source, "itemCount", allItems.size(), "trainingCount", trainingItems.size(), "guardVersion", GUARD_VERSION,
                                "fallbackReason", fallbackReason == null ? "" : fallbackReason));
                ensureBeforeDeadline(deadlineNanos);
                PlanView plan = weeklyPlanService.persistGeneratedDraft(userId,
                        new DraftPlanRequest(session.sessionId(), brief.weekStart(), profile.timezone(), null,
                                PlanScope.EXERCISE),
                        allItems, source, generationMetadata(brief, candidates, candidateSelection.goalRelaxed(),
                                candidateSelection.difficultyRelaxed(),
                                source, modelName, actualModel, fallbackReason),
                        deterministicExplanation(source, trainingItems));
                traceService.recordEvent("PLAN_PERSISTED", "PERSIST", Map.of("planId", plan.id()),
                        Map.of("status", plan.status(), "generationSource", source));
                TrainingPlanGenerationResponse response = new TrainingPlanGenerationResponse(plan.id(), traceId, source,
                        "SUCCESS", "训练计划草稿已生成", plan);
                scope.setResponse(response);
                return response;
            } catch (RuntimeException error) {
                traceService.recordError("PLAN_GENERATION_FAILED", "PLAN", Map.of("requestId", requestId), error);
                throw error;
                }
            }
        } finally {
            requestLocks.remove(lockKey, requestLock);
        }
    }

    /** 综合计划使用的训练子计划生成：只返回已约束的 EXERCISE 项目，不负责持久化。 */
    public List<PlanItemDraft> generateExerciseItemsForComposite(Long userId, HealthSessionState session) {
        HealthProfileView profile = requireProfile(userId);
        PlanBrief brief = requireConfirmedBrief(session);
        CandidateSelection selection = filterCandidates(brief);
        if (selection.resources().isEmpty()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "当前审核动作库没有满足训练简报的候选");
        }
        try {
            List<PlanItemDraft> items = guardAgentOutput(brief, selection.resources(),
                    callAgent(brief, profile, selection.resources(), System.nanoTime() + applicationTimeout.toNanos()));
            validateForPersistence(profile, items);
            return items;
        } catch (RuntimeException error) {
            List<PlanItemDraft> fallbackItems = fallback(brief, selection.resources());
            validateForPersistence(profile, fallbackItems);
            return fallbackItems;
        }
    }

    private PlanAgentOutput callAgent(PlanBrief brief, HealthProfileView profile, List<HealthResource> candidates,
                                     long deadlineNanos) {
        String prompt = buildPrompt(brief, profile, candidates);
        Duration remaining = remainingBudget(deadlineNanos);
        Duration callTimeout = remaining.compareTo(modelTimeout) < 0 ? remaining : modelTimeout;
        AgentContractModule.ContractResult<PlanAgentOutput> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>("TrainingPlanAgent", AgentInvoker.ModelRole.MAIN,
                        modelName, PROMPT_VERSION, CONTRACT_VERSION, prompt, callTimeout,
                        this::parseOutput, candidates.stream().map(HealthResource::resourceId).toList(),
                        value -> value.schedule().stream().map(PlanAgentOutput.ScheduledExercise::exerciseId).toList()));
        if (!result.parsed()) {
            throw new AgentFailureException(result.failureType() == null ? AgentFailureType.UPSTREAM_UNAVAILABLE : result.failureType(),
                    result.fallbackReason());
        }
        return result.value();
    }

    private String buildPrompt(PlanBrief brief, HealthProfileView profile, List<HealthResource> candidates) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("profile", Map.of("age", profile.age(), "riskConditions", profile.riskConditions()));
        input.put("planBrief", brief);
        input.put("candidates", candidates.stream().map(resource -> {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("exerciseId", resource.resourceId());
            compact.put("name", resource.name());
            compact.put("bodyParts", resource.tags().getOrDefault("bodyParts", List.of()));
            compact.put("equipment", resource.tags().getOrDefault("equipment", List.of()));
            compact.put("difficulty", resource.tags().getOrDefault("difficulty", List.of()));
            compact.put("trainingGoal", resource.tags().getOrDefault("trainingGoal", List.of()));
            return compact;
        }).toList());
        return promptLoader.load("diet/prompts/training-plan-agent.txt") + "\n\n输入：" + toJson(input);
    }

    private PlanAgentOutput parseOutput(JsonNode root) throws AgentFailureException {
        JsonNode schedule = root.path("schedule");
        if (!schedule.isArray() || schedule.isEmpty()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "schedule 不能为空");
        }
        List<PlanAgentOutput.ScheduledExercise> items = new ArrayList<>();
        for (JsonNode item : schedule) {
            String id = text(item, "exerciseId");
            try {
                items.add(new PlanAgentOutput.ScheduledExercise(id, LocalDate.parse(text(item, "localDate")),
                        LocalTime.parse(text(item, "startTime")), item.path("durationMinutes").asInt(0)));
            } catch (Exception error) {
                throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "排期字段格式不合法");
            }
        }
        return new PlanAgentOutput(items);
    }

    private List<PlanItemDraft> guardAgentOutput(PlanBrief brief, List<HealthResource> candidates, PlanAgentOutput output) {
        if (output == null || output.schedule().isEmpty() || output.schedule().size() > brief.trainingDays().size()) {
            throw new IllegalArgumentException("训练安排数量不符合简报");
        }
        Map<String, HealthResource> byId = new HashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.resourceId(), candidate));
        List<PlanItemDraft> result = new ArrayList<>();
        for (PlanAgentOutput.ScheduledExercise item : output.schedule()) {
            HealthResource resource = byId.get(item.exerciseId());
            if (resource == null || !resource.planReady()) throw new IllegalArgumentException("动作不在审核候选白名单");
            if (!brief.scheduledDates().contains(item.localDate())) throw new IllegalArgumentException("训练日期不在允许训练日");
            int duration = item.durationMinutes() == null ? 0 : roundUpHalfHour(item.durationMinutes());
            if (duration < MIN_DURATION_MINUTES || duration > MAX_DURATION_MINUTES) throw new IllegalArgumentException("训练时长超出边界");
            LocalTime start = snapToHalfHour(item.startTime());
            long availableMinutes = Duration.between(start, brief.timeWindow().end()).toMinutes();
            if (!brief.timeWindow().contains(start) || availableMinutes < duration) {
                throw new IllegalArgumentException("训练时间不在用户可用窗口内");
            }
            LocalTime end = start.plusMinutes(duration);
            result.add(trainingItem(resource, item.localDate(), start, end, brief));
        }
        return result;
    }

    private List<PlanItemDraft> fallback(PlanBrief brief, List<HealthResource> candidates) {
        List<PlanItemDraft> result = new ArrayList<>();
        String previousPart = null;
        LocalDate previousDate = null;
        List<HealthResource> ordered = candidates.stream().sorted(Comparator.comparing(HealthResource::resourceId)).toList();
        for (LocalDate date : brief.scheduledDates()) {
            String previous = date.minusDays(1).equals(previousDate) ? previousPart : null;
            HealthResource selected = ordered.stream()
                    .filter(candidate -> previous == null
                            || !candidate.tags().getOrDefault("primaryBodyPart", List.of()).contains(previous))
                    .findFirst().orElse(null);
            if (selected == null) continue;
            int available = (int) Duration.between(brief.timeWindow().start(), brief.timeWindow().end()).toMinutes();
            int duration = Math.min(60, (available / 30) * 30);
            if (duration < MIN_DURATION_MINUTES) continue;
            LocalTime start = brief.timeWindow().start();
            result.add(trainingItem(selected, date, start, start.plusMinutes(duration), brief));
            previousPart = selected.tags().getOrDefault("primaryBodyPart", List.of()).stream().findFirst().orElse(null);
            previousDate = date;
        }
        if (result.isEmpty()) throw new HealthApiException(HealthApiException.CODE_CONFLICT, "无法依据当前训练偏好生成可执行安排");
        return result;
    }

    private PlanItemDraft trainingItem(HealthResource resource, LocalDate date, LocalTime start, LocalTime end, PlanBrief brief) {
        int sets = "入门".equals(brief.difficulty()) ? 2 : 3;
        int reps = "耐力".equals(brief.trainingGoal()) ? 15 : 10;
        String bodyPart = resource.tags().getOrDefault("primaryBodyPart", brief.bodyParts()).stream().findFirst().orElse(brief.bodyParts().get(0));
        return new PlanItemDraft("EXERCISE", resource.resourceId(), resource.name(), date, start, end, null,
                Map.of("bodyPart", bodyPart, "sets", sets, "reps", reps, "durationMinutes", Duration.between(start, end).toMinutes()));
    }

    private int roundUpHalfHour(int minutes) {
        return ((minutes + 29) / 30) * 30;
    }

    private LocalTime snapToHalfHour(LocalTime time) {
        int minute = time.getMinute() < 30 ? 0 : 30;
        return LocalTime.of(time.getHour(), minute);
    }

    private CandidateSelection filterCandidates(PlanBrief brief) {
        Set<String> excludedParts = Set.copyOf(brief.hardConstraints().getOrDefault("excludeBodyParts", List.of()));
        Set<String> excludedEquipment = Set.copyOf(brief.hardConstraints().getOrDefault("excludeEquipment", List.of()));
        Set<String> excludedExercises = Set.copyOf(brief.hardConstraints().getOrDefault("excludeExercises", List.of()));
        boolean allBody = brief.bodyParts().contains("全身");
        List<HealthResource> equipmentCandidates = resourceProvider.planReadyExercises().stream().filter(resource -> {
            List<String> parts = resource.tags().getOrDefault("bodyParts", List.of());
            List<String> equipment = resource.tags().getOrDefault("equipment", List.of());
            return resource.planReady()
                    && (allBody || brief.bodyParts().stream().anyMatch(parts::contains))
                    && brief.equipment().stream().anyMatch(equipment::contains)
                    && excludedParts.stream().noneMatch(parts::contains)
                    && excludedEquipment.stream().noneMatch(equipment::contains)
                    && excludedExercises.stream().noneMatch(excluded -> excluded.equalsIgnoreCase(resource.resourceId())
                            || excluded.equalsIgnoreCase(resource.name()));
        }).toList();
        List<HealthResource> strictDifficulty = equipmentCandidates.stream()
                .filter(resource -> resource.tags().getOrDefault("difficulty", List.of()).contains(brief.difficulty()))
                .toList();
        boolean difficultyRelaxed = strictDifficulty.isEmpty() && !equipmentCandidates.isEmpty();
        List<HealthResource> candidates = difficultyRelaxed ? equipmentCandidates : strictDifficulty;
        List<HealthResource> strictGoal = candidates.stream()
                .filter(resource -> resource.tags().getOrDefault("trainingGoal", List.of()).contains(brief.trainingGoal()))
                .toList();
        return strictGoal.isEmpty()
                ? new CandidateSelection(candidates, !candidates.isEmpty(), difficultyRelaxed)
                : new CandidateSelection(strictGoal, false, difficultyRelaxed);
    }

    private record CandidateSelection(List<HealthResource> resources, boolean goalRelaxed, boolean difficultyRelaxed) {
    }

    private void validateForPersistence(HealthProfileView profile, List<PlanItemDraft> items) {
        PlanValidationService.ResourceCatalog catalog = new PlanValidationService.ResourceCatalog(
                resourceProvider.planReadyExerciseIds().stream().collect(java.util.stream.Collectors.toSet()),
                resourceProvider.exercises().stream().map(HealthResource::resourceId).collect(java.util.stream.Collectors.toSet()),
                Set.copyOf(resourceProvider.allFactIds()));
        PlanValidationService.ValidationResult result = validationService.validate(
                new PlanValidationService.ProfileContext(profile.age(), profile.calorieLow(), profile.calorieHigh()), items, catalog);
        if (result.blocked()) throw new HealthApiException(HealthApiException.CODE_RISK_BLOCKED, result.copy());
    }

    private HealthProfileView requireProfile(Long userId) { return profileService.getProfile(userId); }

    private PlanBrief requireConfirmedBrief(HealthSessionState session) {
        if (session.planBrief() == null || !session.planBrief().isConfirmedAndComplete()) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "请先在当前会话确认完整的训练偏好");
        }
        return session.planBrief();
    }

    private Map<String, Object> generationMetadata(PlanBrief brief, List<HealthResource> candidates,
                                                   boolean goalRelaxed,
                                                   boolean difficultyRelaxed,
                                                   String generationSource, String requestedModel, String actualModel,
                                                   String fallbackReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("briefConfirmationVersion", brief.confirmationVersion());
        metadata.put("planScope", PlanScope.EXERCISE.name());
        metadata.put("candidateIds", candidates.stream().map(HealthResource::resourceId).toList());
        metadata.put("goalRelaxed", goalRelaxed);
        metadata.put("difficultyRelaxed", difficultyRelaxed);
        metadata.put("resourceVersion", resourceProvider.resourceVersion());
        metadata.put("generationSource", generationSource);
        metadata.put("requestedModel", requestedModel);
        metadata.put("actualModel", actualModel);
        metadata.put("promptVersion", PROMPT_VERSION);
        metadata.put("contractVersion", CONTRACT_VERSION);
        metadata.put("guardVersion", GUARD_VERSION);
        metadata.put("fallbackReason", fallbackReason == null ? "" : fallbackReason);
        return Map.copyOf(metadata);
    }

    private Duration remainingBudget(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw timeout();
        }
        return Duration.ofNanos(remainingNanos);
    }

    private void ensureBeforeDeadline(long deadlineNanos) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw timeout();
        }
    }

    private HealthApiException timeout() {
        return new HealthApiException(HealthApiException.CODE_TIMEOUT, "训练计划生成已超过应用截止时间，请重试");
    }

    private String deterministicExplanation(String source, List<PlanItemDraft> items) {
        return "训练安排已按你确认的训练日、时间窗口和动作偏好生成。" + ("AGENT".equals(source) ? "本次由 Agent 从审核候选中完成动作排期。" : "模型结果未通过校验，已使用同一批候选按规则降级。")
                + "组数和次数由确定性规则生成。";
    }

    private String text(JsonNode node, String field) throws AgentFailureException {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, field + " 缺失");
        }
        return node.get(field).asText();
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "requestId 不能为空且长度不能超过 128 个字符");
        }
        return requestId.trim();
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "训练计划输入序列化失败"); }
    }
}
