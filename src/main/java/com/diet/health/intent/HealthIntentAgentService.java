package com.diet.health.intent;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
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
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthIntentAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            HealthSlotDictionary slotDictionary,
            IntentRuleService intentRuleService,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.slotDictionary = slotDictionary;
        this.intentRuleService = intentRuleService;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 识别健康意图；LLM/契约失败时返回关键词降级结果。 */
    public HealthIntentResult recognize(String userInput, Map<String, List<String>> knownSlots,
                                        List<String> recentHistory) {
        String promptText = buildPrompt(userInput, knownSlots, recentHistory);
        AgentContractModule.ContractResult<HealthIntentResult> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "IntentAgent",
                        modelName,
                        promptVersion,
                        "intent-v1",
                        promptText,
                        timeout,
                        this::parseIntentJson,
                        null,
                        null
                )
        );
        if (result.parsed()) {
            return result.value();
        }
        return intentRuleService.fallback(userInput, knownSlots, result.fallbackReason());
    }

    private String buildPrompt(String userInput, Map<String, List<String>> knownSlots, List<String> recentHistory) {
        Map<String, List<String>> slotOptions = slotDictionary.legalValues();
        return promptLoader.load("diet/prompts/health-intent.txt") + "\n\n"
                + "最近对话摘要: " + recentHistory + "\n"
                + "已知槽位: " + knownSlots + "\n"
                + "合法槽位选项: " + slotOptions + "\n"
                + "当前这一句: " + userInput;
    }

    /** 契约解析：枚举/必填字段校验 + 槽位合法性过滤 + 偏好信号校验。 */
    private HealthIntentResult parseIntentJson(JsonNode root) throws AgentFailureException {
        HealthDomain domain = parseEnum(root.path("domain").asText(null), HealthDomain.class);
        if (domain == null) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "domain 缺失或非法");
        }
        HealthTask task = parseEnum(root.path("task").asText(null), HealthTask.class);
        if (task == null) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "task 缺失或非法");
        }

        JsonNode slotsNode = root.path("slots");
        Map<String, List<String>> slots = new LinkedHashMap<>();
        boolean slotDegraded = false;
        if (slotsNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : slotsNode.properties()) {
                List<String> rawValues = toStringList(entry.getValue());
                if (rawValues.isEmpty()) {
                    continue;
                }
                List<String> legal = rawValues.stream()
                        .filter(value -> slotDictionary.isValid(entry.getKey(), value))
                        .toList();
                if (legal.size() != rawValues.size()) {
                    slotDegraded = true;
                }
                if (!legal.isEmpty()) {
                    slots.put(entry.getKey(), legal);
                }
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
}
