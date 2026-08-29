package com.diet.health.model;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthNextAction;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.enums.HealthTask;

import java.util.List;

/**
 * 健康聊天响应（规格 6.1 契约）。
 * responseType: ANSWER / CLARIFY / BLOCKED；nextAction: WAIT_USER / ASK_CLARIFY；
 * resultCode 用于表达候选耗尽等稳定领域结果。
 */
public record HealthChatResponse(
        String sessionId,
        String traceId,
        HealthResponseType responseType,
        HealthDomain domain,
        HealthTask task,
        List<String> riskFlags,
        HealthPhase phase,
        String speechText,
        List<HealthDisplayBlock> displayBlocks,
        HealthNextAction nextAction,
        String clarifyQuestion,
        List<String> missingSlots,
        com.diet.health.plan.PlanBrief planBrief,
        com.diet.health.plan.MealPlanBrief mealPlanBrief,
        List<HealthAction> actions,
        String resultCode,
        List<String> confirmedSlots,
        List<String> optionalSlots,
        boolean recommendationConfirmed,
        List<com.diet.health.model.SupplementableItem> supplementable
) {

    public static HealthChatResponse answer(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                            List<String> riskFlags, HealthPhase phase, String speechText,
                                            List<HealthDisplayBlock> displayBlocks) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.ANSWER, domain, task, riskFlags, phase,
                speechText, displayBlocks == null ? List.of() : displayBlocks, HealthNextAction.WAIT_USER, null, List.of(),
                com.diet.health.plan.PlanBrief.empty(), com.diet.health.plan.MealPlanBrief.empty(), List.of(), null,
                List.of(), List.of(), false, List.of());
    }

    public static HealthChatResponse clarify(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String question, List<String> missingSlots) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.CLARIFY, domain, task, riskFlags,
                HealthPhase.CLARIFY, question, List.of(), HealthNextAction.ASK_CLARIFY, question,
                missingSlots == null ? List.of() : List.copyOf(missingSlots),
                com.diet.health.plan.PlanBrief.empty(), com.diet.health.plan.MealPlanBrief.empty(), List.of(), null,
                List.of(), List.of(), false, List.of());
    }

    public static HealthChatResponse blocked(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String speechText) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.BLOCKED, domain, task, riskFlags,
                HealthPhase.BLOCKED, speechText, List.of(), HealthNextAction.WAIT_USER, null, List.of(),
                com.diet.health.plan.PlanBrief.empty(), com.diet.health.plan.MealPlanBrief.empty(), List.of(), null,
                List.of(), List.of(), false, List.of());
    }

    public HealthChatResponse withPlanBrief(com.diet.health.plan.PlanBrief brief, List<HealthAction> nextActions,
                                            HealthNextAction action) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, action, clarifyQuestion, missingSlots, brief, mealPlanBrief,
                nextActions == null ? List.of() : List.copyOf(nextActions), resultCode,
                confirmedSlots, optionalSlots, recommendationConfirmed, supplementable);
    }

    public HealthChatResponse withMealPlanBrief(com.diet.health.plan.MealPlanBrief brief) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, nextAction, clarifyQuestion, missingSlots, planBrief,
                brief == null ? com.diet.health.plan.MealPlanBrief.empty() : brief, actions, resultCode,
                confirmedSlots, optionalSlots, recommendationConfirmed, supplementable);
    }

    /** 为推荐响应追加明确的用户操作，不改变既有响应字段。 */
    public HealthChatResponse withActions(List<HealthAction> nextActions) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, nextAction, clarifyQuestion, missingSlots, planBrief, mealPlanBrief,
                nextActions == null ? List.of() : List.copyOf(nextActions), resultCode,
                confirmedSlots, optionalSlots, recommendationConfirmed, supplementable);
    }

    /** 为领域结果追加稳定机器码；null 表示没有额外结果码。 */
    public HealthChatResponse withResultCode(String code) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, nextAction, clarifyQuestion, missingSlots, planBrief, mealPlanBrief, actions, code,
                confirmedSlots, optionalSlots, recommendationConfirmed, supplementable);
    }

    /** 追加计划简报“可补充项”枚举（只列未填项），前端渲染为可点 chip。 */
    public HealthChatResponse withSupplementable(List<SupplementableItem> items) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, nextAction, clarifyQuestion, missingSlots, planBrief, mealPlanBrief, actions, resultCode,
                confirmedSlots, optionalSlots, recommendationConfirmed,
                items == null ? List.of() : List.copyOf(items));
    }

    /** 追加推荐前确认摘要； confirmedSlots 只展示结构化槽位，不暴露内部对象。 */
    public HealthChatResponse withRecommendationPreflight(List<String> confirmed, List<String> optional,
                                                           boolean confirmedNow) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, confirmedNow ? HealthNextAction.WAIT_USER : HealthNextAction.CONFIRM_RECOMMENDATION,
                clarifyQuestion, missingSlots, planBrief, mealPlanBrief, actions, resultCode,
                confirmed == null ? List.of() : List.copyOf(confirmed),
                optional == null ? List.of() : List.copyOf(optional), confirmedNow, supplementable);
    }
}
