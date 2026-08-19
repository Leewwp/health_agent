package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在确定性规则无法安全解释时，一次性提取计划简报候选字段。 */
@Service
public class PlanBriefExtractionAgentService {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "trainingGoal", "bodyParts", "equipment", "difficulty", "weekStart",
            "trainingDays", "timeStart", "timeEnd");

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public PlanBriefExtractionAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName,
            @Value("${diet.plan-brief.prompt-version:plan-brief-extraction-v1}") String promptVersion,
            @Value("${diet.plan-brief.agent-timeout-ms:3000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(Math.max(1, timeoutMs));
    }

    /** 只调用一次模型；失败时返回空候选，调用方不得将失败输出写入简报。 */
    public ExtractionResult extract(String userInput, PlanBrief brief, String expectedField, Duration remainingBudget) {
        String prompt = buildPrompt(userInput, brief == null ? PlanBrief.empty() : brief, expectedField);
        Duration budget = remainingBudget == null || remainingBudget.isZero() || remainingBudget.isNegative()
                ? timeout : min(timeout, remainingBudget);
        AgentContractModule.ContractResult<AgentCandidate> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "PlanBriefExtractionAgent",
                        AgentInvoker.ModelRole.LIGHT,
                        modelName,
                        promptVersion,
                        "plan-brief-extraction-v1",
                        prompt,
                        budget,
                        this::parse,
                        null,
                        null
                ));
        if (!result.parsed()) {
            return ExtractionResult.failed(result.fallbackReason());
        }
        AgentCandidate candidate = result.value();
        return new ExtractionResult(true, candidate.candidateFields(), candidate.confidence(), candidate.evidence(), null);
    }

    private AgentCandidate parse(JsonNode root) {
        JsonNode fields = root.path("candidateFields");
        if (!fields.isObject() || fields.isEmpty()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "candidateFields 不能为空");
        }
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        fields.fields().forEachRemaining(entry -> {
            if (!ALLOWED_FIELDS.contains(entry.getKey())) {
                throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "候选字段不在白名单内");
            }
            List<String> values = values(entry.getValue());
            if (values.isEmpty()) {
                throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "候选字段值不能为空");
            }
            candidates.put(entry.getKey(), values);
        });
        JsonNode confidenceNode = root.get("confidence");
        double confidence = confidenceNode != null && confidenceNode.isNumber() ? confidenceNode.asDouble() : -1;
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "confidence 不在 0 到 1 范围内");
        }
        if (confidence < 0.65) {
            throw new AgentFailureException(AgentFailureType.LOW_CONFIDENCE, "confidence 低于最低可信度");
        }
        String evidence = root.path("evidence").asText(null);
        if (evidence == null || evidence.isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "evidence 不能为空");
        }
        return new AgentCandidate(candidates, confidence, evidence.trim());
    }

    private List<String> values(JsonNode node) {
        if (node.isTextual()) {
            return node.asText().isBlank() ? List.of() : List.of(node.asText().trim());
        }
        if (!node.isArray() || node.isEmpty()) return List.of();
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) return List.of();
            values.add(value.asText().trim());
        }
        return List.copyOf(values);
    }

    private String buildPrompt(String userInput, PlanBrief brief, String expectedField) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("expectedField", expectedField);
        Map<String, Object> briefContext = new LinkedHashMap<>();
        briefContext.put("summary", briefSummary(brief));
        briefContext.put("missingFields", brief.isComplete() ? List.of() : List.of("当前字段"));
        context.put("brief", briefContext);
        context.put("userInput", userInput == null ? "" : userInput);
        try {
            return promptLoader.load("diet/prompts/plan-brief-extraction.txt") + "\n\n输入："
                    + objectMapper.writeValueAsString(context);
        } catch (Exception error) {
            throw new IllegalStateException("计划简报提取输入序列化失败", error);
        }
    }

    private String briefSummary(PlanBrief brief) {
        return "目标=" + value(brief.trainingGoal()) + "，部位=" + brief.bodyParts()
                + "，器械=" + brief.equipment() + "，难度=" + value(brief.difficulty())
                + "，周起始=" + value(brief.weekStart()) + "，训练日=" + brief.trainingDays()
                + "，时间=" + (brief.timeWindow() == null ? value(brief.partialStartTime()) : brief.timeWindow());
    }

    private String value(Object value) {
        return value == null ? "未定" : String.valueOf(value);
    }

    private Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private record AgentCandidate(Map<String, List<String>> candidateFields, double confidence, String evidence) {
    }

    public record ExtractionResult(boolean parsed, Map<String, List<String>> candidateFields,
                                   double confidence, String evidence, String failureReason) {
        public ExtractionResult {
            candidateFields = candidateFields == null ? Map.of() : Map.copyOf(candidateFields);
            evidence = evidence == null ? "" : evidence;
            failureReason = failureReason == null ? "" : failureReason;
        }

        static ExtractionResult failed(String reason) {
            return new ExtractionResult(false, Map.of(), 0, "", reason == null ? "AGENT_FAILED" : reason);
        }
    }
}
