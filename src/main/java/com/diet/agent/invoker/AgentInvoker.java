package com.diet.agent.invoker;

import java.time.Duration;

/**
 * 外部模型依赖的运行接口。
 * <p>
 * 业务模块只依赖该接口，不得直接获取 {@code ReActAgent}。
 * 提供两个适配器：{@link AgentScopeInvoker}（真实 DashScope）和 {@link FixtureAgentInvoker}（固定夹具/离线演示）。
 */
public interface AgentInvoker {

    /** 模型职责；路由不得依赖某个历史模型名称。 */
    enum ModelRole {
        MAIN,
        LIGHT
    }

    /**
     * 执行一次 Agent 调用并返回原始文本结果。
     *
     * @throws AgentTimeoutException     调用超时
     * @throws AgentInvocationException  上游不可用等运行时失败
     */
    AgentInvocationResult invoke(AgentInvocation invocation);

    /**
     * 当前适配器是否具备可用配置（如 API key 是否已注入）。
     * 返回 false 时契约层按 {@code MISSING_CONFIG} 直接确定性降级，不发起真实调用。
     */
    boolean configured();

    /** 一次 Agent 调用的完整入参。Prompt/契约版本由契约层负责记录 Trace，不入调用参数。 */
    record AgentInvocation(
            String agentRole,        // 角色名，如 IntentAgent / ClarifyAgent / RecommendResponseAgent
            ModelRole modelRole,     // 模型职责，如 MAIN / LIGHT
            String modelName,        // 配置解析后的实际模型名
            String promptText,       // 完整用户消息
            Duration timeout         // 单次调用超时
    ) {
    }

    /** 一次 Agent 调用的结果。 */
    record AgentInvocationResult(String text, String modelName, long latencyMs) {
    }
}
