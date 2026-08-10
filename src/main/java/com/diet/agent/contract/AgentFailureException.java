package com.diet.agent.contract;

/** 契约解析校验失败，由解析器抛出并携带失败分类。 */
public class AgentFailureException extends RuntimeException {
    private final AgentFailureType type;

    public AgentFailureException(AgentFailureType type, String message) {
        super(message);
        this.type = type;
    }

    public AgentFailureType type() {
        return type;
    }
}
