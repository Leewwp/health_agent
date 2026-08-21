package com.diet.health.evalv2;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.health.clarify.HealthClarifyAgentService;
import com.diet.health.clarify.HealthClarifyRuleService;
import com.diet.health.feedback.PreferenceService;
import com.diet.health.intent.HealthIntentAgentService;
import com.diet.health.intent.IntentRuleService;
import com.diet.health.module.ExerciseModule;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineModule;
import com.diet.health.orchestrator.HealthOrchestratorService;
import com.diet.health.plan.PlanValidationService;
import com.diet.health.rag.EmbeddingClient;
import com.diet.health.rag.MealRetriever;
import com.diet.health.recommend.HealthRecommendResponseService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.vectorstore.VectorStoreIdentity;
import com.diet.mapper.FeedbackMapper;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DETERMINISTIC_FIXTURE 端到端回归（#73）：固定 36 条 BENCHMARK 经内存 fixture 管线
 * （无 API key/MySQL/Qdrant）生成版本化报告，全部聚合指标与手算期望值逐项核对。
 */
class HealthEvalRunnerTest {

    @TempDir
    Path tempDir;

    private HealthEvalRunner buildRunner() {
        ObjectMapper objectMapper = new ObjectMapper();
        InMemoryEvalMappers.InMemoryAgentTraceMapper traceMapper = new InMemoryEvalMappers.InMemoryAgentTraceMapper();
        AgentTraceService traceService = new AgentTraceService(traceMapper, objectMapper);
        AgentContractModule contract = new AgentContractModule(new FixtureAgentInvoker(),
                new com.diet.util.LlmJsonService(objectMapper), traceService);
        com.diet.health.intent.HealthSlotDictionary dictionary =
                new com.diet.health.intent.HealthSlotDictionary(
                        com.diet.health.TestSupport.slotOptionService());
        com.diet.health.intent.HealthInputNormalizer normalizer = new com.diet.health.intent.HealthInputNormalizer();
        HealthIntentAgentService intent = new HealthIntentAgentService(
                contract, new com.diet.agent.loader.PromptLoader(), dictionary,
                new IntentRuleService(normalizer), normalizer, "qwen-turbo", "v1", 1000);
        HealthClarifyRuleService clarifyRule = new HealthClarifyRuleService();
        HealthClarifyAgentService clarifyAgent = new HealthClarifyAgentService(
                contract, new com.diet.agent.loader.PromptLoader(), clarifyRule, "qwen-turbo", "v1", 1000);
        HealthRecommendResponseService recommend = new HealthRecommendResponseService(
                contract, new com.diet.agent.loader.PromptLoader(), "qwen-max", "v1", 1000);
        HealthResourceProvider provider = new SeedResourceProvider();
        PreferenceService preferenceService = new PreferenceService(mock(FeedbackMapper.class));
        MealModule mealModule = new MealModule(
                mock(MealSearchService.class), mock(MealRankService.class), traceService,
                mock(MealRetriever.class), preferenceService,
                mock(EmbeddingClient.class), new VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024"),
                provider);
        ExerciseModule exerciseModule = new ExerciseModule(provider, preferenceService);
        RoutineModule routineModule = new RoutineModule(provider);

        // runner 构造参数：除 fixture 服务外，其余（LIVE_MODEL 才用到）用 mock。
        return new HealthEvalRunner(
                objectMapper,
                intent, clarifyRule, clarifyAgent, new HealthRiskRuleService(),
                mealModule, exerciseModule, routineModule, provider, recommend,
                new PlanValidationService(),
                mock(AgentTraceService.class), mock(FeedbackMapper.class),
                mock(HealthOrchestratorService.class), mock(EmbeddingClient.class),
                new VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024"),
                HealthEvalRunner.MODE_DETERMINISTIC_FIXTURE,
                "data/eval/health-eval-v2-benchmark.jsonl",
                tempDir.toString(), 1L, 30, 100, "fixture", "qwen-max", "qwen-turbo");
    }

    @Test
    void fixture回归生成报告且手算指标全部符合() throws Exception {
        buildRunner().run(null);

        Path jsonPath = tempDir.resolve("health-eval-v2-report.json");
        Path mdPath = tempDir.resolve("health-eval-v2-report.md");
        assertTrue(Files.exists(jsonPath), "JSON 报告必须写出");
        assertTrue(Files.exists(mdPath), "Markdown 摘要必须写出");

        ObjectMapper mapper = new ObjectMapper();
        var report = mapper.readTree(Files.readString(jsonPath));
        assertEquals("health-eval-v2", report.get("schemaVersion").asText());
        assertEquals("DETERMINISTIC_FIXTURE", report.get("runMode").asText());
        assertEquals(36, report.get("status").get("total").asInt());
        assertEquals(36, report.get("status").get("reviewed").asInt());
        assertEquals(36, report.get("dataset").get("sampleCount").asInt());

        var metrics = report.get("metrics");
        var domainMismatches = new java.util.ArrayList<String>();
        report.get("cases").forEach(item -> {
            if (item.has("domainMatch") && !item.get("domainMatch").asBoolean()) {
                domainMismatches.add(item.get("caseId").asText() + ":" + item.get("predictedDomain").asText());
            }
        });
        assertTrue(domainMismatches.isEmpty(), "领域不匹配样本: " + domainMismatches);
        // 域/任务：32 条聊天样本全部命中（含 COMPOSITE/RISK_BLOCK 的 domain/task）
        assertMetric(metrics.get("domainAccuracy"), 1.0, 32, 32);
        assertMetric(metrics.get("taskAccuracy"), 1.0, 32, 32);
        assertMetric(metrics.get("domainTaskExactMatch"), 1.0, 32, 32);
        // 槽位：只剩 MEAL-04 的 allergen 未提取（FN=1）；RT-06 不再臆造 wakeTime
        assertMetric(metrics.get("slotExactMatch"), 31.0 / 32.0, 31, 32);
        assertMetric(metrics.get("slotMicro").get("precision"), 1.0, 38, 38);
        assertMetric(metrics.get("slotMicro").get("recall"), 38.0 / 39.0, 38, 39);
        // 风险：NORMAL/ADVISORY/BLOCK_PLAN 三级全对，BLOCK_PLAN Recall=1（3/3）
        assertMetric(metrics.get("risk").get("accuracy"), 1.0, 32, 32);
        assertMetric(metrics.get("risk").get("blockPlanRecall"), 1.0, 3, 3);
        assertEquals(3, metrics.get("risk").get("confusionMatrix").get("BLOCK_PLAN").get("BLOCK_PLAN").asInt());
        assertEquals(1, metrics.get("risk").get("confusionMatrix").get("ADVISORY").get("ADVISORY").asInt());
        // 澄清：BLOCKED 不进分母 → 29/29；健身必要信息收紧后 12 个 CLARIFY gold 的 missingSlotF1=1
        assertMetric(metrics.get("clarify").get("clarifyDecisionAccuracy"), 1.0, 29, 29);
        assertMetric(metrics.get("clarify").get("missingSlotF1"), 1.0, 12, 12);
        // 候选引用合规：信息完整后返回的 15 张资源卡全部来自本轮候选
        assertMetric(metrics.get("candidateCitationCompliance"), 1.0, 15, 15);
        // 计划：4 条样本 1 条 OK，硬错误 UNDERAGE/SCHEDULE_OVERLAP 各 1
        assertMetric(metrics.get("planValidation").get("passRate"), 0.25, 1, 4);
        assertEquals(1, metrics.get("planValidation").get("hardErrorCountByRule").get("UNDERAGE").asInt());
        assertEquals(1, metrics.get("planValidation").get("hardErrorCountByRule").get("SCHEDULE_OVERLAP").asInt());
        // fallback：fixture 正常推荐全部 NONE
        assertEquals(32, metrics.get("fallback").get("distribution").get("NONE").asInt());
        assertEquals(32, metrics.get("fallback").get("effectiveTotal").asInt());
        // 延迟：32 条正常样本，REQUEST_FAILED 空分组
        assertEquals(32, metrics.get("latency").get("normal").get("count").asInt());
        assertEquals(0, metrics.get("latency").get("requestFailed").get("count").asInt());
        // 反馈：fixture 无真实反馈 → null 指标 + 归因计数 0
        assertTrue(metrics.get("feedback").get("adoptionRate").isNull());
        assertEquals(0, metrics.get("feedback").get("exactAttributionCount").asInt());
        assertEquals(0, metrics.get("feedback").get("legacyFallbackCount").asInt());
        // 版本与身份：fixture 环境、无向量身份（NON_NULL 序列化，null 字段直接缺席）
        assertEquals("FIXTURE_SEED", report.get("environment").get("resourceProviderMode").asText());
        assertTrue(!report.get("environment").has("embeddingModel"), "fixture 运行不记录 embedding 身份");
        assertEquals(PlanValidationService.RULES_VERSION, metrics.get("planValidation").get("rulesVersion").asText());
    }

    @Test
    void 手算验收六项在逐case明细中成立() throws Exception {
        buildRunner().run(null);
        ObjectMapper mapper = new ObjectMapper();
        var report = mapper.readTree(Files.readString(tempDir.resolve("health-eval-v2-report.json")));

        // #1 餐食正常推荐：MEAL-01 domain/task/slot/candidate 全命中
        var meal01 = caseBy(report, "MEAL-01", 1);
        assertEquals("EVALUATED", meal01.get("status").asText());
        assertEquals(true, meal01.get("domainMatch").asBoolean());
        assertEquals(true, meal01.get("taskMatch").asBoolean());
        assertEquals(true, meal01.get("slotExactMatch").asBoolean());
        assertEquals(true, meal01.get("citationCompliant").asBoolean());
        // #2 健身风险阻断：RISK-02 BLOCK_PLAN Recall 命中（case 明细 riskMatch）
        var risk02 = caseBy(report, "RISK-02", 1);
        assertEquals("BLOCK_PLAN", risk02.get("goldRisk").asText());
        assertEquals(true, risk02.get("riskMatch").asBoolean());
        // #3 正确澄清及 missing slots：MEAL-C1#1 预测缺失槽位与 gold 一致
        var clarify = caseBy(report, "MEAL-C1", 1);
        assertEquals("CLARIFY", clarify.get("predictedResponseType").asText());
        assertEquals("healthGoal", clarify.get("predictedMissingSlots").get(0).asText());
        assertEquals(true, clarify.get("clarifyDecisionMatch").asBoolean());
        // #4 无 HARD_ERROR 计划校验：PLAN-01 预测 OK
        var plan01 = caseBy(report, "PLAN-01", 1);
        assertEquals("OK", plan01.get("predictedPlanLevel").asText());
        assertEquals("OK", plan01.get("goldPlanLevel").asText());
        assertEquals(true, plan01.get("planLevelMatch").asBoolean());
        // #5/#6 反馈归因在报告中单列区分（fixture 无反馈：两计数均为 0，指标 null）
        assertEquals(0, report.get("metrics").get("feedback").get("exactAttributionCount").asInt());
        assertEquals(0, report.get("metrics").get("feedback").get("legacyFallbackCount").asInt());
    }

    private void assertMetric(com.fasterxml.jackson.databind.JsonNode metric, double expected, int numerator, int denominator) {
        assertEquals(numerator, metric.get("numerator").asInt(), "numerator 不符");
        assertEquals(denominator, metric.get("denominator").asInt(), "denominator 不符");
        assertEquals(expected, metric.get("value").asDouble(), 0.0001, "value 不符");
    }

    private com.fasterxml.jackson.databind.JsonNode caseBy(com.fasterxml.jackson.databind.JsonNode report, String caseId, int turn) {
        for (var detail : report.get("cases")) {
            if (detail.get("caseId").asText().equals(caseId) && detail.get("turnIndex").asInt() == turn) {
                return detail;
            }
        }
        throw new AssertionError("未找到 case: " + caseId + "#" + turn);
    }
}
