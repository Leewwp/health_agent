package com.diet.health.intent;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康 IntentAgent：通过契约模块调用模型，输出 domain/task/riskFlags/slots/preferenceSignals/confidence。
 * 失败立即降级到 {@link IntentRuleService} 关键词路由，不自动重试。
 */
@Service
public class HealthIntentAgentService {

    private static final double MIN_CONFIDENCE = 0.65;

    /** 支持的风险 flag 白名单（与 HealthRiskRuleService 对齐）。 */
    private static final List<String> SUPPORTED_RISK_FLAGS = List.of(
            "PREGNANCY", "UNDERAGE", "ACUTE_SYMPTOMS", "TREATMENT", "EATING_DISORDER", "CHRONIC_CONDITION", "SENIOR"
    );

    private static final List<String> SUPPORTED_RESOURCE_TYPES = List.of("MEAL", "EXERCISE");
    private static final List<String> SUPPORTED_ACTIONS = List.of("LIKE", "DISLIKE", "FAVORITE", "UNFAVORITE", "ADOPT");

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final HealthSlotDictionary slotDictionary;
    private final IntentRuleService intentRuleService;
    private final HealthInputNormalizer inputNormalizer;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthIntentAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            HealthSlotDictionary slotDictionary,
            IntentRuleService intentRuleService,
            HealthInputNormalizer inputNormalizer,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.slotDictionary = slotDictionary;
        this.intentRuleService = intentRuleService;
        this.inputNormalizer = inputNormalizer;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 识别健康意图；LLM/契约失败时返回关键词降级结果。 */
    public HealthIntentResult recognize(String userInput, Map<String, List<String>> knownSlots,
                                        List<String> recentHistory) {
        return recognizeWithDiagnostics(userInput, knownSlots, recentHistory, timeout, false).result();
    }

    /** 识别并返回路径诊断；明确短语不触发模型，歧义输入最多一次调用。 */
    public Recognition recognizeWithDiagnostics(String userInput, Map<String, List<String>> knownSlots,
                                                List<String> recentHistory) {
        return recognizeWithDiagnostics(userInput, knownSlots, recentHistory, timeout, true);
    }

    /** 使用本轮请求剩余预算，确保模型调用不会越过应用截止时间。 */
    public Recognition recognizeWithDiagnostics(String userInput, Map<String, List<String>> knownSlots,
                                                List<String> recentHistory, Duration remainingBudget) {
        return recognizeWithDiagnostics(userInput, knownSlots, recentHistory, remainingBudget, true);
    }

    private Recognition recognizeWithDiagnostics(String userInput, Map<String, List<String>> knownSlots,
                                                 List<String> recentHistory, Duration remainingBudget,
                                                 boolean allowFastPath) {
        if (allowFastPath) {
            HealthIntentResult fastPath = intentRuleService.fastPath(userInput, knownSlots);
            if (fastPath != null) {
                return new Recognition(fastPath, "FAST_PATH");
            }
        }
        String promptText = buildPrompt(userInput, knownSlots, recentHistory);
        AgentContractModule.ContractResult<HealthIntentResult> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "IntentAgent",
                        AgentInvoker.ModelRole.LIGHT,
                        modelName,
                        promptVersion,
                        "intent-v1",
                        promptText,
                        min(timeout, remainingBudget),
                        root -> parseIntentJson(root, userInput),
                        null,
                        null
                )
        );
        if (result.parsed()) {
            return new Recognition(result.value(), "AGENT");
        }
        return new Recognition(intentRuleService.fallback(userInput, knownSlots, result.fallbackReason()), "FALLBACK");
    }

    private String buildPrompt(String userInput, Map<String, List<String>> knownSlots, List<String> recentHistory) {
        Map<String, List<String>> slotOptions = relevantSlotOptions(userInput, knownSlots);
        List<String> history = recentHistory == null ? List.of()
                : recentHistory.stream().skip(Math.max(0, recentHistory.size() - 2L)).toList();
        return promptLoader.load("diet/prompts/health-intent.txt") + "\n\n"
                + "最近对话摘要: " + history + "\n"
                + "已知槽位: " + knownSlots + "\n"
                + "合法槽位选项: " + slotOptions + "\n"
                + "当前这一句: " + userInput;
    }

    /** 只携带已有领域相关词典；领域仍歧义时由输出契约和 Java 字典过滤兜底。 */
    private Map<String, List<String>> relevantSlotOptions(String userInput, Map<String, List<String>> knownSlots) {
        HealthIntentResult hint = intentRuleService.fastPath(userInput, knownSlots);
        if (hint == null || hint.domain() == HealthDomain.OTHER || hint.domain() == HealthDomain.COMPOSITE) {
            return Map.of();
        }
        Map<String, List<String>> relevant = new LinkedHashMap<>();
        slotDictionary.legalValues().forEach((slot, values) -> {
            if (slotDictionary.belongsTo(slot, hint.domain())) {
                relevant.put(slot, values);
            }
        });
        return relevant;
    }

    /** 契约解析：枚举/必填字段校验 + 槽位合法性过滤 + 偏好信号校验。 */
    private HealthIntentResult parseIntentJson(JsonNode root, String userInput) throws AgentFailureException {
        HealthDomain domain = parseEnum(root.path("domain").asText(null), HealthDomain.class);
        if (domain == null) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "domain 缺失或非法");
        }
        HealthTask task = parseEnum(root.path("task").asText(null), HealthTask.class);
        if (task == null) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "task 缺失或非法");
        }

        JsonNode slotsNode = root.path("slots");
        Map<String, List<String>> rawSlots = new LinkedHashMap<>();
        boolean slotDegraded = false;
        if (slotsNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : slotsNode.properties()) {
                List<String> rawValues = toStringList(entry.getValue());
                if (rawValues.isEmpty()) {
                    continue;
                }
                if (!slotDictionary.belongsTo(entry.getKey(), domain)) {
                    slotDegraded = true;
                    continue;
                }
                rawSlots.put(entry.getKey(), rawValues);
            }
        }
        HealthInputNormalizer.NormalizationResult normalization = inputNormalizer.normalize(domain, userInput, rawSlots);
        Map<String, List<String>> slots = new LinkedHashMap<>();
        normalization.slots().forEach((slot, values) -> {
            List<String> legal = values.stream()
                    .filter(value -> slotDictionary.isValid(slot, value))
                    .toList();
            if (!legal.isEmpty()) {
                slots.put(slot, legal);
            }
        });
        if (normalization.requiresClarification()
                || slots.values().stream().mapToInt(List::size).sum()
                < normalization.slots().values().stream().mapToInt(List::size).sum()) {
            slotDegraded = true;
        }
        for (Map.Entry<String, List<String>> entry : rawSlots.entrySet()) {
            HealthInputNormalizer.NormalizationResult rawNormalization = inputNormalizer.normalize(
                    domain, "", Map.of(entry.getKey(), entry.getValue()));
            int legalRawCount = rawNormalization.slots().getOrDefault(entry.getKey(), List.of()).stream()
                    .filter(value -> slotDictionary.isValid(entry.getKey(), value))
                    .toList().size();
            if (legalRawCount < entry.getValue().stream().distinct().count()) {
                slotDegraded = true;
            }
        }

        List<String> riskFlags = new ArrayList<>();
        boolean riskDegraded = false;
        JsonNode flagsNode = root.path("riskFlags");
        if (flagsNode.isArray()) {
            for (JsonNode flag : flagsNode) {
                String value = flag.asText(null);
                if (value == null) {
                    continue;
                }
                if (SUPPORTED_RISK_FLAGS.contains(value) && !riskFlags.contains(value)) {
                    riskFlags.add(value);
                } else {
                    riskDegraded = true;
                }
            }
        }

        List<PreferenceSignal> signals = new ArrayList<>();
        boolean signalDegraded = false;
        JsonNode signalsNode = root.path("preferenceSignals");
        if (signalsNode.isArray()) {
            for (JsonNode signal : signalsNode) {
                PreferenceSignal parsed = parseSignal(signal);
                if (parsed == null) {
                    signalDegraded = true;
                } else {
                    signals.add(parsed);
                }
            }
        }

        double confidence = root.path("confidence").asDouble(0.5);
        if (confidence < MIN_CONFIDENCE) {
            throw new AgentFailureException(AgentFailureType.LOW_CONFIDENCE,
                    "意图置信度 " + confidence + " 低于阈值 " + MIN_CONFIDENCE);
        }
        if (slotDegraded || riskDegraded || signalDegraded) {
            return HealthIntentResult.degraded(domain, task, riskFlags, slots, signals, "非法输出条目已过滤");
        }
        return HealthIntentResult.parsed(domain, task, riskFlags, slots, signals, confidence);
    }

    private PreferenceSignal parseSignal(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String resourceType = node.path("resourceType").asText(null);
        String action = node.path("action").asText(null);
        String resourceId = node.path("resourceId").asText(null);
        if (resourceType == null || action == null || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType) || !SUPPORTED_ACTIONS.contains(action)) {
            return null;
        }
        return new PreferenceSignal(resourceType, resourceId, action);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isTextual()) {
            result.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record Recognition(HealthIntentResult result, String source) {
    }

    private Duration min(Duration configured, Duration remaining) {
        if (remaining == null || remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return configured.compareTo(remaining) <= 0 ? configured : remaining;
    }
}
