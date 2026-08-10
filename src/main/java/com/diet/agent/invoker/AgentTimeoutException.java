package com.diet.agent.invoker;

/** Agent 调用超时。契约层将其分类为 {@code TIMEOUT}。 */
public class AgentTimeoutException extends RuntimeException {
    public AgentTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
