package com.diet.agent.invoker;

/** Agent 上游调用失败（网络、鉴权、限流等）。契约层将其分类为 {@code UPSTREAM_UNAVAILABLE}。 */
public class AgentInvocationException extends RuntimeException {
    public AgentInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
