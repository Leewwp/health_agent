package com.diet.health.model;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthNextAction;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthResponseType;
import com.diet.health.enums.HealthTask;

import java.util.List;

/**
 * 健康聊天响应（规格 6.1 契约）。
 * responseType: ANSWER / CLARIFY / BLOCKED；nextAction: WAIT_USER / ASK_CLARIFY。
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
        List<HealthAction> actions
) {

    public static HealthChatResponse answer(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                            List<String> riskFlags, HealthPhase phase, String speechText,
                                            List<HealthDisplayBlock> displayBlocks) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.ANSWER, domain, task, riskFlags, phase,
                speechText, displayBlocks == null ? List.of() : displayBlocks, HealthNextAction.WAIT_USER, null, List.of(),
                com.diet.health.plan.PlanBrief.empty(), List.of());
    }

    public static HealthChatResponse clarify(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String question, List<String> missingSlots) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.CLARIFY, domain, task, riskFlags,
                HealthPhase.CLARIFY, question, List.of(), HealthNextAction.ASK_CLARIFY, question,
                missingSlots == null ? List.of() : List.copyOf(missingSlots),
                com.diet.health.plan.PlanBrief.empty(), List.of());
    }

    public static HealthChatResponse blocked(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String speechText) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.BLOCKED, domain, task, riskFlags,
                HealthPhase.BLOCKED, speechText, List.of(), HealthNextAction.WAIT_USER, null, List.of(),
                com.diet.health.plan.PlanBrief.empty(), List.of());
    }

    public HealthChatResponse withPlanBrief(com.diet.health.plan.PlanBrief brief, List<HealthAction> nextActions,
                                            HealthNextAction action) {
        return new HealthChatResponse(sessionId, traceId, responseType, domain, task, riskFlags, phase, speechText,
                displayBlocks, action, clarifyQuestion, missingSlots, brief, nextActions == null ? List.of() : List.copyOf(nextActions));
    }
}
