package com.diet.health.intent;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 歧义任务的单次受约束仲裁（ADR-0018「确定性路由与歧义仲裁」）：
 * 确定性规则无法唯一裁决（多个任务候选、规则与会话状态冲突、无法区分新建/修改/推荐/作息、
 * 否定/转折/指代/多时间从句）时，调用一次轻量意图仲裁 Agent。
 * <p>
 * Agent 只返回受限候选任务（NEW_PLAN/REVISE_PLAN/RECOMMEND/ROUTINE/CHAT）、领域、置信度和证据；
 * 不返回自由执行动作，不决定资源资格、风险结论或计划时间。结果经任务/领域枚举校验与
 * 置信度阈值复核；超时、非法 JSON、低置信返回 {@link Optional#empty()}，由编排器进入
 * 可理解澄清并保留规则已抽取的合法槽位，不猜测执行。无歧义路径不调用本服务。
 */
@Service
public class AmbiguityArbitrationAgentService {

    /** 受限候选任务（新/改/推荐/作息/闲聊），Agent 只能从中选择一个。 */
    public static final List<String> CANDIDATE_TASKS = List.of(
            "NEW_PLAN", "REVISE_PLAN", "RECOMMEND", "ROUTINE", "CHAT");

    /** 置信度阈值：低于该值视为低置信，不进入执行。 */
    public static final double MIN_CONFIDENCE = 0.65;

    private static final String PROMPT_VERSION = "2026-08-30-arbitration-v1";
    private static final String CONTRACT_VERSION = "arbitration-v1";

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final Duration timeout;

    public AmbiguityArbitrationAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 受限仲裁结果：任务候选 + 领域 + 置信度 + 简短证据。 */
    public record ArbitrationResult(String task, HealthDomain domain, double confidence, String evidence) {
    }

    /**
     * 单次受约束仲裁调用；任何失败（超时/非法输出/低置信）返回空，由调用方澄清。
     * 调用方必须先走规则快路径，只有规则无法唯一裁决时才调用本方法（最多一次模型调用）。
     */
    public Optional<ArbitrationResult> arbitrate(String userInput, String sessionContext, Duration remainingBudget) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> input = Map.of(
                "candidateTasks", CANDIDATE_TASKS,
                "sessionContext", sessionContext == null ? "" : sessionContext,
                "userInput", userInput);
        String prompt = promptLoader.load("diet/prompts/ambiguity-arbitration.txt") + "\n\n输入：" + toJson(input);
        AgentContractModule.ContractResult<ArbitrationResult> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "AmbiguityArbitrationAgent",
                        AgentInvoker.ModelRole.LIGHT,
                        modelName,
                        PROMPT_VERSION,
                        CONTRACT_VERSION,
                        prompt,
                        min(timeout, remainingBudget),
                        this::parseOutput,
                        null,
                        null
                )
        );
        if (!result.parsed()) {
            return Optional.empty();
        }
        return Optional.of(result.value());
    }

    /** 枚举/置信度校验：非法任务、非法领域组合或低置信抛 SCHEMA/LOW_CONFIDENCE（→ 空结果）。 */
    private ArbitrationResult parseOutput(JsonNode root) throws AgentFailureException {
        String task = root.path("task").asText(null);
        if (task == null || !CANDIDATE_TASKS.contains(task)) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION,
                    "仲裁任务不在受限候选" + CANDIDATE_TASKS + "内: " + task);
        }
        HealthDomain domain = parseDomain(root.path("domain").asText(null));
        if (domain == null || !validCombination(task, domain)) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION,
                    "仲裁领域与任务组合非法: " + task + "/" + domain);
        }
        double confidence = root.path("confidence").asDouble(0.5);
        if (confidence < MIN_CONFIDENCE) {
            throw new AgentFailureException(AgentFailureType.LOW_CONFIDENCE,
                    "仲裁置信度 " + confidence + " 低于阈值 " + MIN_CONFIDENCE);
        }
        String evidence = root.path("evidence").asText("");
        if (evidence.isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "仲裁证据缺失");
        }
        return new ArbitrationResult(task, domain, confidence, evidence);
    }

    /** 任务与领域的合法组合（Agent 不能选择自己的自由动作）。 */
    private boolean validCombination(String task, HealthDomain domain) {
        return switch (task) {
            case "NEW_PLAN", "REVISE_PLAN" ->
                    domain == HealthDomain.MEAL || domain == HealthDomain.EXERCISE || domain == HealthDomain.COMPOSITE;
            case "RECOMMEND" ->
                    domain == HealthDomain.MEAL || domain == HealthDomain.EXERCISE || domain == HealthDomain.ROUTINE;
            case "ROUTINE" -> domain == HealthDomain.ROUTINE;
            case "CHAT" -> domain == HealthDomain.OTHER;
            default -> false;
        };
    }

    /** 受限仲裁结果 → 最终意图（任务映射 + 规则槽位抽取；槽位由 Java 规则负责，不由 Agent 决定）。 */
    public static HealthIntentResult toIntentResult(ArbitrationResult decision, String userInput,
                                                    HealthInputNormalizer normalizer) {
        HealthDomain domain = decision.domain() == null ? HealthDomain.OTHER : decision.domain();
        HealthTask task = switch (decision.task()) {
            case "NEW_PLAN", "REVISE_PLAN" -> HealthTask.PLAN;
            case "ROUTINE" -> HealthTask.RECOMMEND;
            case "CHAT" -> HealthTask.CHAT;
            default -> HealthTask.RECOMMEND;
        };
        Map<String, List<String>> slots = normalizer.normalize(domain, userInput, Map.of()).slots();
        return HealthIntentResult.parsed(domain, task, List.of(), slots, List.of(), decision.confidence());
    }

    /** 仲裁时的会话上下文摘要（供 Agent 复核当前计划生命周期，不暴露内部字段）。 */
    public static String sessionContextOf(HealthSessionState state) {
        if (state == null) {
            return "无会话上下文";
        }
        String domain = state.domain() == null ? "无" : state.domain().name();
        String lifecycle = state.briefLifecycle() == null || state.briefLifecycle().isEmpty()
                ? "无活跃简报" : String.valueOf(state.briefLifecycle());
        return "当前领域=" + domain + "；简报生命周期=" + lifecycle;
    }

    private HealthDomain parseDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return HealthDomain.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private Duration min(Duration configured, Duration remaining) {
        if (remaining == null || remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return configured.compareTo(remaining) <= 0 ? configured : remaining;
    }
}