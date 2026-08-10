package com.diet.health.recommend;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.enums.HealthDomain;
import com.diet.health.module.HealthResource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康 RecommendResponseAgent：只能解释输入候选，不能新增候选、不计算数值。
 * 候选越界 / Schema 失败 / 上游失败时立即使用确定性模板降级。
 */
@Service
public class HealthRecommendResponseService {

    /** 响应 Agent 输出。 */
    public record RecommendOutcome(String speechText, Map<String, String> reasons, String fallbackReason) {
    }

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthRecommendResponseService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            @Value("${diet.llm.main-model:qwen-max}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 解释已确认候选；失败返回确定性模板（候选名列表 + 固定理由）。 */
    public RecommendOutcome respond(HealthDomain domain, String userInput, List<HealthResource> candidates) {
        List<String> allowedIds = candidates.stream().map(HealthResource::resourceId).toList();
        String promptText = buildPrompt(domain, userInput, candidates);

        AgentContractModule.ContractResult<RecommendOutcome> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "RecommendResponseAgent",
                        modelName,
                        promptVersion,
                        "recommend-response-v1",
                        promptText,
                        timeout,
                        root -> parseOutcome(root, allowedIds),
                        allowedIds,
                        outcome -> outcome.reasons().keySet().stream().toList()
                )
        );
        if (result.parsed()) {
            return result.value();
        }
        return template(domain, candidates, result.fallbackReason());
    }

    private String buildPrompt(HealthDomain domain, String userInput, List<HealthResource> candidates) {
        List<Map<String, Object>> compact = candidates.stream()
                .map(candidate -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resourceId", candidate.resourceId());
                    item.put("name", candidate.name());
                    item.put("sourceName", candidate.sourceName());
                    item.put("tags", candidate.tags());
                    return item;
                })
                .toList();
        String candidatesJson;
        try {
            candidatesJson = objectMapper.writeValueAsString(compact);
        } catch (Exception error) {
            candidatesJson = String.valueOf(compact);
        }
        return promptLoader.load("diet/prompts/health-recommend-response.txt") + "\n\n"
                + "领域: " + domain + "\n"
                + "用户原话: " + userInput + "\n"
                + "候选资源（已排序，只能解释不能新增）: " + candidatesJson;
    }

    /** 输出校验：speechText 非空、reasons 非空、resourceId 必须在候选白名单内。 */
    private RecommendOutcome parseOutcome(JsonNode root, List<String> allowedIds) throws AgentFailureException {
        String speechText = root.path("speechText").asText(null);
        if (speechText == null || speechText.isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "speechText 缺失");
        }
        JsonNode reasonsNode = root.path("reasons");
        if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "reasons 为空");
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        for (JsonNode reason : reasonsNode) {
            String resourceId = reason.path("resourceId").asText(null);
            String text = reason.path("reason").asText(null);
            if (resourceId == null || text == null || text.isBlank()) {
                throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "reason 条目不完整");
            }
            if (!allowedIds.contains(resourceId)) {
                throw new AgentFailureException(AgentFailureType.CANDIDATE_VIOLATION, "resourceId 不在候选内: " + resourceId);
            }
            reasons.put(resourceId, text);
        }
        if (reasons.isEmpty()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "reasons 为空");
        }
        return new RecommendOutcome(speechText.trim(), reasons, null);
    }

    /** 确定性模板：只列候选名称与固定理由，不编造任何内容。 */
    private RecommendOutcome template(HealthDomain domain, List<HealthResource> candidates, String fallbackReason) {
        StringBuilder text = new StringBuilder("根据你的需求，为你推荐了");
        Map<String, String> reasons = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            HealthResource candidate = candidates.get(i);
            if (i > 0) {
                text.append("、");
            }
            text.append(candidate.name());
            reasons.put(candidate.resourceId(), "匹配你选择的偏好条件");
        }
        text.append("。");
        return new RecommendOutcome(text.toString(), reasons, fallbackReason);
    }
}
