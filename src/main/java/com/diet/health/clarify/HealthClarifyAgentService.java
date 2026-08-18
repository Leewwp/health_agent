package com.diet.health.clarify;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 健康 ClarifyAgent：只负责把 Java 已确定的缺失字段优化成一句自然追问。
 * 契约失败立即返回模板追问，模板追问本身可以独立继续会话。
 */
@Service
public class HealthClarifyAgentService {

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final HealthClarifyRuleService clarifyRuleService;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthClarifyAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            HealthClarifyRuleService clarifyRuleService,
            @Value("${diet.llm.light-model:qwen3.7-flash}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.clarifyRuleService = clarifyRuleService;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 生成追问措辞；任何失败返回模板文案。 */
    public String wording(HealthDomain domain, String userInput, List<String> missingSlots,
                          Map<String, List<String>> knownSlots) {
        String promptText = promptLoader.load("diet/prompts/health-clarify.txt") + "\n\n"
                + "领域: " + domain + "\n"
                + "用户原话: " + userInput + "\n"
                + "已知信息: " + knownSlots + "\n"
                + "缺失字段: " + missingSlots;
        AgentContractModule.ContractResult<String> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "ClarifyAgent",
                        AgentInvoker.ModelRole.LIGHT,
                        modelName,
                        promptVersion,
                        "clarify-v1",
                        promptText,
                        timeout,
                        root -> {
                            String text = root.isTextual() ? root.asText() : root.asText(null);
                            if (text == null || text.isBlank()) {
                                throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "追问文本为空");
                            }
                            return text.trim();
                        },
                        null,
                        null
                )
        );
        if (result.parsed()) {
            return result.value();
        }
        return clarifyRuleService.fallbackQuestion(domain, missingSlots);
    }
}
