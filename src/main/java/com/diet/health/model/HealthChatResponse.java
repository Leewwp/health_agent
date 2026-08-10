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
        List<String> missingSlots
) {

    public static HealthChatResponse answer(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                            List<String> riskFlags, HealthPhase phase, String speechText,
                                            List<HealthDisplayBlock> displayBlocks) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.ANSWER, domain, task, riskFlags, phase,
                speechText, displayBlocks == null ? List.of() : displayBlocks, HealthNextAction.WAIT_USER, null, List.of());
    }

    public static HealthChatResponse clarify(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String question, List<String> missingSlots) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.CLARIFY, domain, task, riskFlags,
                HealthPhase.CLARIFY, question, List.of(), HealthNextAction.ASK_CLARIFY, question,
                missingSlots == null ? List.of() : List.copyOf(missingSlots));
    }

    public static HealthChatResponse blocked(String sessionId, String traceId, HealthDomain domain, HealthTask task,
                                             List<String> riskFlags, String speechText) {
        return new HealthChatResponse(sessionId, traceId, HealthResponseType.BLOCKED, domain, task, riskFlags,
                HealthPhase.BLOCKED, speechText, List.of(), HealthNextAction.WAIT_USER, null, List.of());
    }
}
