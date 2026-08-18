package com.diet.health.evalv2;

import com.diet.health.evalv2.HealthEvaluationEngine.PlanInput;
import com.diet.health.evalv2.HealthEvaluationEngine.PlanOutcome;
import com.diet.health.evalv2.HealthEvaluationEngine.TurnInput;
import com.diet.health.evalv2.HealthEvalReport.EnvironmentInfo;
import com.diet.health.evalv2.InMemoryEvalMappers.InMemoryAgentTraceMapper;
import com.diet.health.evalv2.InMemoryEvalMappers.InMemorySessionMapper;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.intent.HealthIntentRevisionService;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.orchestrator.HealthOrchestratorService;
import com.diet.health.plan.PlanItemDraft;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.risk.RiskRuleCatalog;
import com.diet.health.session.HealthSessionService;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.session.SessionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统一健康评估 v2 运行器（#73）：同一 runner 提供互不混算的三个档位。
 * <p>
 * <ul>
 *   <li>{@code diet.evalv2.run-mode=DETERMINISTIC_FIXTURE}：固定 Agent/resource fixture，
 *   无 API key/MySQL/Qdrant 也可运行，作普通回归；写 data/reports/health-eval-v2-report.json + .md。</li>
 *   <li>{@code diet.evalv2.run-mode=TRACE_AUDIT}：抽样读取真实 Trace（V9 expected_health_json 作 gold），
 *   #74 精确/回退归因，只作诊断，不作简历数字；写 health-eval-v2-audit.json + .md。</li>
 *   <li>{@code diet.evalv2.run-mode=LIVE_MODEL}：真实模型 + 审核库运行固定 BENCHMARK，
 *   记录模型/审核库/向量索引身份；无 API key 时允许失败记录，不作为普通 CI 门禁。</li>
 * </ul>
 * 开关默认关（OFF）。版本化 JSON 是唯一机器事实来源，Markdown 只做摘要。
 */
@Component
public class HealthEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HealthEvalRunner.class);

    public static final String MODE_DETERMINISTIC_FIXTURE = "DETERMINISTIC_FIXTURE";
    public static final String MODE_TRACE_AUDIT = "TRACE_AUDIT";
    public static final String MODE_LIVE_MODEL = "LIVE_MODEL";

    /** BENCHMARK 样本路径（仓库内版本化 JSONL）。 */
    public static final String DEFAULT_BENCHMARK_PATH = "data/eval/health-eval-v2-benchmark.jsonl";

    private final ObjectMapper objectMapper;
    private final HealthIntentAgentService intentAgentService;
    private final HealthClarifyRuleService clarifyRuleService;
    private final HealthClarifyAgentService clarifyAgentService;
    private final HealthRiskRuleService riskRuleService;
    private final MealModule mealModule;
    private final ExerciseModule exerciseModule;
    private final RoutineModule routineModule;
    private final HealthResourceProvider resourceProvider;
    private final HealthRecommendResponseService recommendResponseService;
    private final PlanValidationService planValidationService;
    private final AgentTraceService realTraceService;
    private final FeedbackMapper feedbackMapper;
    private final HealthOrchestratorService realOrchestrator;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreIdentity vectorStoreIdentity;

    private final String runMode;
    private final String benchmarkPath;
    private final String reportDir;
    private final long evalUserId;
    private final int traceWindowDays;
    private final int traceLimit;
    private final String agentMode;
    private final String mainModel;
    private final String lightModel;

    public HealthEvalRunner(
            ObjectMapper objectMapper,
            HealthIntentAgentService intentAgentService,
            HealthClarifyRuleService clarifyRuleService,
            HealthClarifyAgentService clarifyAgentService,
            HealthRiskRuleService riskRuleService,
            MealModule mealModule,
            ExerciseModule exerciseModule,
            RoutineModule routineModule,
            HealthResourceProvider resourceProvider,
            HealthRecommendResponseService recommendResponseService,
            PlanValidationService planValidationService,
            AgentTraceService realTraceService,
            FeedbackMapper feedbackMapper,
            HealthOrchestratorService realOrchestrator,
            EmbeddingClient embeddingClient,
            VectorStoreIdentity vectorStoreIdentity,
            @Value("${diet.evalv2.run-mode:OFF}") String runMode,
            @Value("${diet.evalv2.benchmark-path:" + DEFAULT_BENCHMARK_PATH + "}") String benchmarkPath,
            @Value("${diet.evalv2.report-dir:data/reports}") String reportDir,
            @Value("${diet.evalv2.user-id:1}") long evalUserId,
            @Value("${diet.evalv2.trace-window-days:30}") int traceWindowDays,
            @Value("${diet.evalv2.trace-limit:100}") int traceLimit,
            @Value("${diet.agent.mode:agentscope}") String agentMode,
            @Value("${diet.llm.main-model:qwen-turbo}") String mainModel,
            @Value("${diet.llm.light-model:qwen3.7-flash}") String lightModel
    ) {
        this.objectMapper = objectMapper;
        this.intentAgentService = intentAgentService;
        this.clarifyRuleService = clarifyRuleService;
        this.clarifyAgentService = clarifyAgentService;
        this.riskRuleService = riskRuleService;
        this.mealModule = mealModule;
        this.exerciseModule = exerciseModule;
        this.routineModule = routineModule;
        this.resourceProvider = resourceProvider;
        this.recommendResponseService = recommendResponseService;
        this.planValidationService = planValidationService;
        this.realTraceService = realTraceService;
        this.feedbackMapper = feedbackMapper;
        this.realOrchestrator = realOrchestrator;
        this.embeddingClient = embeddingClient;
        this.vectorStoreIdentity = vectorStoreIdentity;
        this.runMode = runMode;
        this.benchmarkPath = benchmarkPath;
        this.reportDir = reportDir;
        this.evalUserId = evalUserId;
        this.traceWindowDays = traceWindowDays;
        this.traceLimit = traceLimit;
        this.agentMode = agentMode;
        this.mainModel = mainModel;
        this.lightModel = lightModel;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = runMode == null || runMode.isBlank() ? "OFF" : runMode.trim().toUpperCase();
        switch (mode) {
            case "OFF" -> {
            }
            case MODE_DETERMINISTIC_FIXTURE -> runDeterministicFixture();
            case MODE_TRACE_AUDIT -> runTraceAudit();
            case MODE_LIVE_MODEL -> runLiveModel();
            default -> throw new IllegalStateException("未知的 diet.evalv2.run-mode: " + runMode
                    + "（可选 OFF/DETERMINISTIC_FIXTURE/TRACE_AUDIT/LIVE_MODEL）");
        }
    }

    // ---------- DETERMINISTIC_FIXTURE ----------

    private void runDeterministicFixture() throws Exception {
        if (!"fixture".equalsIgnoreCase(agentMode)) {
            throw new IllegalStateException("DETERMINISTIC_FIXTURE 要求 diet.agent.mode=fixture（无 API key 确定性回归），当前=" + agentMode);
        }
        if (resourceProvider.providerMode().isReviewed()) {
            throw new IllegalStateException("DETERMINISTIC_FIXTURE 要求 diet.resource.mode=fixture（内存种子），当前="
                    + resourceProvider.providerMode());
        }
        List<BenchmarkCase> samples = loadBenchmark();
        log.info("health-eval-v2 DETERMINISTIC_FIXTURE 开始：{} 条样本", samples.size());

        List<TurnInput> turns = new ArrayList<>();
        List<PlanInput> plans = new ArrayList<>();
        HealthEvalExecution execution = buildFixtureExecution();
        TraceFactReader reader = new TraceFactReader(objectMapper);
        for (List<BenchmarkCase> caseTurns : groupByCase(samples)) {
            for (BenchmarkCase sample : caseTurns) {
                if (BenchmarkCase.CASE_TYPE_PLAN_VALIDATION.equals(sample.caseType())) {
                    plans.add(new PlanInput(sample, validatePlan(sample)));
                } else if (!sample.excluded()) {
                    RequestTraceRow row = execution.executeTurn(sample);
                    turns.add(new TurnInput(sample, reader.read(row, List.of(), null)));
                }
            }
        }

        EnvironmentInfo environment = new EnvironmentInfo(
                resourceProvider.providerMode().name(), resourceProvider.resourceVersion(),
                FixtureAgentInvoker.FIXTURE_VERSION,
                RiskRuleCatalog.RULES_VERSION, RiskRuleCatalog.PROFILE_RULES_VERSION,
                PlanValidationService.RULES_VERSION, mainModel, lightModel,
                null, null, null, null);
        writeReport(turns, plans, environment, MODE_DETERMINISTIC_FIXTURE, "report", fixtureNotes());
    }

    /** 组装 fixture 编排器：内存会话/Trace Mapper，其余复用上下文 fixture 服务。 */
    private HealthEvalExecution buildFixtureExecution() {
        InMemorySessionMapper sessionMapper = new InMemorySessionMapper();
        InMemoryAgentTraceMapper traceMapper = new InMemoryAgentTraceMapper();
        AgentTraceService traceService = new AgentTraceService(traceMapper, objectMapper);
        SessionService messageService = new SessionService(sessionMapper, new JsonService(objectMapper), 10);
        HealthSessionService sessionService = new HealthSessionService(sessionMapper, objectMapper);
        try {
            Field secret = HealthSessionService.class.getDeclaredField("sessionSecret");
            secret.setAccessible(true);
            secret.set(sessionService, "health-eval-v2-fixture-secret");
        } catch (Exception error) {
            throw new IllegalStateException("无法注入健康会话密钥", error);
        }
        HealthInputNormalizer inputNormalizer = new HealthInputNormalizer();
        HealthOrchestratorService orchestrator = new HealthOrchestratorService(
                sessionService, messageService, intentAgentService,
                new HealthIntentRevisionService(inputNormalizer), inputNormalizer,
                clarifyRuleService, clarifyAgentService,
                riskRuleService, mealModule, exerciseModule, routineModule, resourceProvider,
                recommendResponseService, traceService, objectMapper);
        return new HealthEvalExecution(orchestrator, traceMapper, evalUserId);
    }

    // ---------- TRACE_AUDIT ----------

    private void runTraceAudit() throws Exception {
        List<RequestTraceRow> traces = realTraceService.findByTimeRange(
                evalUserId, LocalDateTime.now().minusDays(traceWindowDays), LocalDateTime.now(), false, traceLimit);
        log.info("health-eval-v2 TRACE_AUDIT 开始：userId={}，窗口={} 天，样本 {} 条",
                evalUserId, traceWindowDays, traces.size());
        if (traces.isEmpty()) {
            log.warn("TRACE_AUDIT 无 Trace 样本，报告只含空状态");
        }
        AuditFeedbackLoader loader = new AuditFeedbackLoader(feedbackMapper);
        Map<String, AuditFeedbackLoader.Attribution> attributions = loader.load(
                evalUserId, LocalDateTime.now().minusDays(traceWindowDays), LocalDateTime.now(), traces);

        TraceFactReader reader = new TraceFactReader(objectMapper);
        List<TurnInput> turns = new ArrayList<>();
        for (RequestTraceRow trace : traces) {
            ExpectedHealth gold = ExpectedHealth.parse(readTree(trace.getExpectedHealthJson()));
            AuditFeedbackLoader.Attribution attribution = attributions.getOrDefault(
                    AuditFeedbackLoader.attributionKey(trace), new AuditFeedbackLoader.Attribution(List.of(), null));
            TraceFacts facts = reader.read(trace, attribution.feedbacks(), attribution.attribution());
            turns.add(new TurnInput(BenchmarkCase.audit(trace.getTraceId(), gold), facts));
        }
        EnvironmentInfo environment = environmentWithVectorIdentity();
        writeReport(turns, List.of(), environment, MODE_TRACE_AUDIT, "audit", auditNotes(traces.size()));
    }

    // ---------- LIVE_MODEL ----------

    private void runLiveModel() throws Exception {
        if ("fixture".equalsIgnoreCase(agentMode)) {
            throw new IllegalStateException("LIVE_MODEL 要求真实模型（diet.agent.mode=agentscope），当前=" + agentMode);
        }
        if (resourceProvider.providerMode().isFixture()) {
            throw new IllegalStateException("LIVE_MODEL 要求 diet.resource.mode=reviewed（审核库证据），当前="
                    + resourceProvider.providerMode());
        }
        List<BenchmarkCase> samples = loadBenchmark();
        log.info("health-eval-v2 LIVE_MODEL 开始：{} 条样本，模型 {} / {}",
                samples.size(), mainModel, lightModel);

        List<TurnInput> turns = new ArrayList<>();
        List<PlanInput> plans = new ArrayList<>();
        TraceFactReader reader = new TraceFactReader(objectMapper);
        AuditFeedbackLoader loader = new AuditFeedbackLoader(feedbackMapper);
        List<RequestTraceRow> producedTraces = new ArrayList<>();
        for (List<BenchmarkCase> caseTurns : groupByCase(samples)) {
            for (BenchmarkCase sample : caseTurns) {
                if (BenchmarkCase.CASE_TYPE_PLAN_VALIDATION.equals(sample.caseType())) {
                    plans.add(new PlanInput(sample, validatePlan(sample)));
                    continue;
                }
                if (sample.excluded()) {
                    continue;
                }
                String sessionId = "evalv2-live-" + sample.caseId();
                String requestId = "evalv2-live-" + sample.caseId() + "-" + sample.turnIndex();
                RequestTraceRow row = null;
                try {
                    HealthChatResponse response = realOrchestrator.healthChat(evalUserId,
                            new HealthChatRequest(sessionId, requestId, sample.input(), sample.initialContext()));
                    row = realTraceService.findByTraceId(evalUserId, response.traceId());
                } catch (Exception error) {
                    log.warn("LIVE_MODEL 样本 {} 执行失败：{}", sample.caseId(), error.getMessage());
                    row = failedTraceRow(sample, sessionId, requestId);
                }
                producedTraces.add(row);
                turns.add(new TurnInput(sample, reader.read(row, List.of(), null)));
            }
        }
        // 精确 trace 归因反馈（LIVE_MODEL 全部 trace 都有 traceId，仅精确归因有效）。
        Map<String, AuditFeedbackLoader.Attribution> attributions = loader.load(
                evalUserId, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), producedTraces);
        for (int i = 0; i < turns.size(); i++) {
            RequestTraceRow row = producedTraces.get(i);
            AuditFeedbackLoader.Attribution attribution = attributions.getOrDefault(
                    AuditFeedbackLoader.attributionKey(row), new AuditFeedbackLoader.Attribution(List.of(), null));
            TraceFacts facts = reader.read(row, attribution.feedbacks(), attribution.attribution());
            turns.set(i, new TurnInput(turns.get(i).sample(), facts));
        }

        EnvironmentInfo environment = environmentWithVectorIdentity();
        writeReport(turns, plans, environment, MODE_LIVE_MODEL, "live", liveNotes());
    }

    private RequestTraceRow failedTraceRow(BenchmarkCase sample, String sessionId, String requestId) {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId("evalv2-live-" + sample.caseId() + "-" + sample.turnIndex() + "-failed");
        row.setRequestId(requestId);
        row.setSessionId(sessionId);
        row.setUserId(evalUserId);
        row.setStatus("FAILED");
        row.setTraceJson("{\"events\":[{\"eventType\":\"REQUEST_FAILED\",\"phase\":\"HTTP\","
                + "\"inputPayload\":\"LIVE_MODEL 执行失败\",\"outputPayload\":null,"
                + "\"latencyMs\":null,\"errorMessage\":\"LIVE_MODEL 样本执行失败\"}]}");
        return row;
    }

    // ---------- 计划校验 ----------

    /** 计划样本直接调用 PlanValidationService（复用规则，不复制）。 */
    private PlanOutcome validatePlan(BenchmarkCase sample) {
        BenchmarkCase.PlanValidationInput input = sample.planInput();
        PlanValidationService.ProfileContext profile = new PlanValidationService.ProfileContext(
                input.profile().age(), input.profile().calorieLow(), input.profile().calorieHigh());
        List<PlanItemDraft> items = input.items().stream().map(this::toDraft).toList();
        PlanValidationService.ResourceCatalog catalog = new PlanValidationService.ResourceCatalog(
                java.util.Set.copyOf(input.catalog().planReadyExerciseIds()),
                java.util.Set.copyOf(input.catalog().knownExerciseIds()),
                java.util.Set.copyOf(input.catalog().knownRoutineFactIds()));
        PlanValidationService.ValidationResult result = planValidationService.validate(profile, items, catalog);
        return AuditFeedbackLoader.toPlanOutcome(result);
    }

    private PlanItemDraft toDraft(BenchmarkCase.PlanValidationInput.ItemInput item) {
        return new PlanItemDraft(
                item.resourceType(), item.resourceId(), item.name(),
                parseDate(item.localDate()), parseTime(item.startTime()), parseTime(item.endTime()),
                null, item.planParams());
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    // ---------- 报告写出 ----------

    private void writeReport(List<TurnInput> turns, List<PlanInput> plans, EnvironmentInfo environment,
                             String mode, String suffix, List<String> notes) throws Exception {
        String datasetId = MODE_TRACE_AUDIT.equals(mode)
                ? "health-eval-v2-trace-audit" : BenchmarkDatasetLoader.DATASET_ID;
        HealthEvaluationEngine engine = new HealthEvaluationEngine(
                new HealthEvalReport.DatasetInfo(datasetId, "1.0.0",
                        turns.size() + plans.size(), benchmarkPath));
        HealthEvalReport report = engine.aggregate(
                turns, plans, environment, mode, gitCommit(), LocalDateTime.now().toString(),
                PlanValidationService.RULES_VERSION, notes);
        Path jsonPath = Path.of(reportDir, "health-eval-v2-" + suffix + ".json");
        Path mdPath = Path.of(reportDir, "health-eval-v2-" + suffix + ".md");
        Files.createDirectories(jsonPath.getParent());
        Files.writeString(jsonPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        Files.writeString(mdPath, HealthEvalMarkdownWriter.write(report), StandardCharsets.UTF_8);
        log.info("health-eval-v2 {} 完成：样本 {}，报告 → {} / {}", mode, report.status().total(), jsonPath, mdPath);
    }

    private List<String> fixtureNotes() {
        return List.of(
                "运行模式：DETERMINISTIC_FIXTURE——固定 Agent/resource fixture 与规则，无 API key/MySQL/Qdrant 也可运行，作普通回归",
                "所有指标给有效分母；缺 gold 或结构化事实为 null 不算 0",
                "计划样本（caseType=PLAN_VALIDATION）直接调用 PlanValidationService，不复制规则",
                "用户反馈仅统计 #74 精确 trace 归因；FAVORITE/UNFAVORITE 不进满意度；本档位无真实用户反馈",
                "BENCHMARK 为单标注者两遍复核（labeledAt/reviewedAt/reviewStatus=REVIEWED），不声称多人标注",
                "候选引用合规 = 最终展示 ID 全部属于本轮候选集，明细记违规 ID",
                "风险阻断、正常澄清和无候选不算 fallback；REQUEST_FAILED 为最严重 fallback 主类"
        );
    }

    private List<String> auditNotes(int traceCount) {
        List<String> notes = new ArrayList<>(fixtureNotes());
        notes.set(0, "运行模式：TRACE_AUDIT——抽样读取真实 Trace（" + traceCount + " 条）作诊断，不作简历数字；"
                + "gold 来自 V9 expected_health_json，无 gold 的 trace 记 NO_GOLD 不进指标分母");
        notes.add("TRACE_AUDIT 单次标注即可，不要求两遍复核");
        return notes;
    }

    private List<String> liveNotes() {
        List<String> notes = new ArrayList<>(fixtureNotes());
        notes.set(0, "运行模式：LIVE_MODEL——真实模型 + 审核库运行固定 BENCHMARK，生成真实面试证据；"
                + "无 API key 时样本记 REQUEST_FAILED 失败记录，不作为普通 CI 门禁");
        return notes;
    }

    private EnvironmentInfo environmentWithVectorIdentity() {
        return new EnvironmentInfo(
                resourceProvider.providerMode().name(), resourceProvider.resourceVersion(),
                FixtureAgentInvoker.FIXTURE_VERSION,
                RiskRuleCatalog.RULES_VERSION, RiskRuleCatalog.PROFILE_RULES_VERSION,
                PlanValidationService.RULES_VERSION, mainModel, lightModel,
                embeddingClient.modelName(), embeddingClient.modelVersion(),
                vectorStoreIdentity.provider(), vectorStoreIdentity.collectionName());
    }

    // ---------- 工具 ----------

    private List<BenchmarkCase> loadBenchmark() throws Exception {
        return new BenchmarkDatasetLoader(objectMapper).load(Path.of(benchmarkPath));
    }

    private List<List<BenchmarkCase>> groupByCase(List<BenchmarkCase> samples) {
        Map<String, List<BenchmarkCase>> grouped = new LinkedHashMap<>();
        for (BenchmarkCase sample : samples) {
            grouped.computeIfAbsent(sample.caseId(), key -> new ArrayList<>()).add(sample);
        }
        grouped.values().forEach(list -> list.sort(Comparator.comparingInt(BenchmarkCase::turnIndex)));
        return List.copyOf(grouped.values());
    }

    private String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(2, TimeUnit.SECONDS);
            return output.isBlank() ? "unknown" : output;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    /** fixture 执行器：一轮样本 → 本轮 Trace 行（从内存 Trace Mapper 读回）。 */
    private record HealthEvalExecution(HealthOrchestratorService orchestrator,
                                       InMemoryAgentTraceMapper traceMapper, long userId) {

        RequestTraceRow executeTurn(BenchmarkCase sample) {
            String sessionId = "evalv2-" + sample.caseId();
            String requestId = "evalv2-" + sample.caseId() + "-" + sample.turnIndex();
            HealthChatResponse response = orchestrator.healthChat(userId,
                    new HealthChatRequest(sessionId, requestId, sample.input(), sample.initialContext()));
            RequestTraceRow row = traceMapper.findByTraceId(userId, response.traceId());
            if (row == null) {
                throw new IllegalStateException("fixture 执行未产生 Trace: " + sample.caseId());
            }
            return row;
        }
    }
}
