package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.contract.AgentFailureException;
import com.diet.agent.contract.AgentFailureType;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康 PlanResponseAgent（34 号，规格 4）：
 * 只解释已通过确定性校验的计划结果；结构/Schema/候选越界/上游失败立即模板降级，
 * 输出后经 PlanOutputGuard 文本级校验。Agent 不新增候选、不改剂量、不写风险结论。
 */
@Service
public class HealthPlanResponseAgentService {

    /** 估算值固定后缀（数值一律标记估算）。 */
    public static final String ESTIMATE_DISCLAIMER = "以上能量区间与训练安排均为估算值，仅供参考，不是医疗处方。";

    /** 解释结果。 */
    public record PlanExplanation(String speechText, List<String> highlightIds, String fallbackReason) {
    }

    private final AgentContractModule contractModule;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String promptVersion;
    private final Duration timeout;

    public HealthPlanResponseAgentService(
            AgentContractModule contractModule,
            PromptLoader promptLoader,
            @Value("${diet.llm.main-model:qwen-turbo}") String modelName,
            @Value("${diet.prompt.version:v1}") String promptVersion,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.contractModule = contractModule;
        this.promptLoader = promptLoader;
        this.objectMapper = new ObjectMapper();
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 解释已校验计划；失败返回确定性模板。 */
    public PlanExplanation explain(HealthProfileView profile, List<PlanItemView> items) {
        List<String> allowedIds = items.stream().map(PlanItemView::resourceId).toList();
        String promptText = buildPrompt(profile, items);

        AgentContractModule.ContractResult<PlanExplanation> result = contractModule.call(
                new AgentContractModule.AgentContractRequest<>(
                        "PlanResponseAgent",
                        AgentInvoker.ModelRole.MAIN,
                        modelName,
                        promptVersion,
                        "plan-response-v1",
                        promptText,
                        timeout,
                        root -> parseOutcome(root),
                        allowedIds,
                        explanation -> explanation.highlightIds()
                )
        );
        if (!result.parsed()) {
            return template(profile, items, result.fallbackReason());
        }
        PlanExplanation parsed = result.value();
        java.util.Optional<String> guardFailure = PlanOutputGuard.validate(parsed.speechText());
        if (guardFailure.isPresent()) {
            return template(profile, items, "GUARD: " + guardFailure.get());
        }
        return new PlanExplanation(parsed.speechText() + " " + ESTIMATE_DISCLAIMER, parsed.highlightIds(), null);
    }

    private String buildPrompt(HealthProfileView profile, List<PlanItemView> items) {
        Map<String, Object> profileCompact = new LinkedHashMap<>();
        profileCompact.put("age", profile.age());
        profileCompact.put("sex", profile.sex() == null ? "未知" : profile.sex().name());
        profileCompact.put("calorieLow", profile.calorieLow());
        profileCompact.put("calorieHigh", profile.calorieHigh());
        profileCompact.put("estimated", true);
        List<Map<String, Object>> itemCompact = items.stream().map(item -> {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("resourceType", item.resourceType());
            compact.put("resourceId", item.resourceId());
            compact.put("name", item.name());
            compact.put("localDate", String.valueOf(item.localDate()));
            compact.put("startTime", String.valueOf(item.startTime()));
            compact.put("endTime", String.valueOf(item.endTime()));
            compact.put("params", item.params());
            return compact;
        }).toList();
        return promptLoader.load("diet/prompts/health-plan-response.txt") + "\n\n"
                + "健康档案摘要（估算值）: " + toJson(profileCompact) + "\n"
                + "已校验计划项目: " + toJson(itemCompact);
    }

    /** 输出校验：speechText 非空、highlightIds 为字符串数组（白名单由契约模块校验）。 */
    private PlanExplanation parseOutcome(JsonNode root) throws AgentFailureException {
        String speechText = root.path("speechText").asText(null);
        if (speechText == null || speechText.isBlank()) {
            throw new AgentFailureException(AgentFailureType.SCHEMA_VIOLATION, "speechText 缺失");
        }
        List<String> highlightIds = new ArrayList<>();
        JsonNode ids = root.path("highlightIds");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (id.isTextual() && !id.asText().isBlank()) {
                    highlightIds.add(id.asText());
                }
            }
        }
        return new PlanExplanation(speechText.trim(), highlightIds, null);
    }

    /** 确定性模板：只汇总已校验项目，不编造任何内容。 */
    private PlanExplanation template(HealthProfileView profile, List<PlanItemView> items, String fallbackReason) {
        long trainingDays = items.stream().filter(item -> "EXERCISE".equals(item.resourceType())).count();
        long mealCount = items.stream().filter(item -> "MEAL".equals(item.resourceType())).count();
        long sleepCount = items.stream().filter(item -> "ROUTINE".equals(item.resourceType())).count();
        String text = "已为你生成周计划草稿：共 " + items.size() + " 个项目，含每日作息 "
                + sleepCount + " 条、餐食 " + mealCount + " 条、训练 " + trainingDays + " 天。"
                + "日能量区间 " + profile.calorieLow() + "-" + profile.calorieHigh() + " kcal 为估算值，"
                + "请按自身感受调整。";
        return new PlanExplanation(text, List.of(), fallbackReason);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }
}
