package com.diet.health.chat;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.intent.HealthTaskEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 健康 CHAT 通道回答服务（2026-08-31 严格路由规格）：
 * 负责健康助手身份介绍、能力引导与一般健康常识对话。
 * 边界：不检索餐食/动作资源、不生成或修改计划、不做诊断/治疗承诺；
 * 风险拦截仍由既有风险规则目录在进入本服务前统一评估。
 * 模型失败/输出非法/未配置时返回确定性能力文案，流程永远可继续。
 */
@Service
public class HealthChatAnswerService {

    /** CHAT 通道确定性能力文案（与编排器降级文案同口径）。 */
    static final String CAPABILITY_COPY =
            "我是你的健康助手，可以帮你推荐餐食和健身动作、制定餐食、训练或综合的一周计划，也可以提供作息建议和一般健康常识。"
                    + "你可以直接说，例如“推荐一份清淡的晚餐”或“帮我安排这周的健身计划”，也可以先和我聊聊健康相关的话题。";

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthChatAnswerService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 生成 CHAT 回答；任何失败返回确定性能力文案（不得声称不支持推荐/计划）。 */
    public String answer(String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        String promptText = promptLoader.load("diet/prompts/health-chat.txt") + "\n\n"
                + "用户原话: " + text;
        AgentContractModule.ContractResult<String> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "ChatAnswerAgent",
                        AgentInvoker.ModelRole.LIGHT,
                        modelName,
                        promptVersion,
                        "chat-answer-v1",
                        promptText,
                        timeout,
                        HealthChatAnswerService::parseSpeechText,
                        null,
                        null
                )
        );
        if (result.parsed() && result.value() != null && !result.value().isBlank()) {
            return result.value();
        }
        return fallbackAnswer(text);
    }

    private static String parseSpeechText(JsonNode root) throws AgentFailureException {
        String speechText = root.path("speechText").asText(null);
        if (speechText == null || speechText.isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "speechText 缺失");
        }
        return speechText.trim();
    }

    /** 确定性降级：能力/聊天问句给能力介绍，其余给能力范围说明（不编造内容）。 */
    private String fallbackAnswer(String text) {
        if (new HealthTaskEvidence().isChatEscapeExpression(text)) {
            return CAPABILITY_COPY;
        }
        return "这个问题我先不展开。我是你的健康助手，可以帮你推荐餐食和健身动作、制定一周计划或提供作息建议，"
                + "可以直接告诉我你想做什么。";
    }
}
