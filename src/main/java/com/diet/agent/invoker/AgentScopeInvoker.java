package com.diet.agent.invoker;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 AgentScope/DashScope 的真实模型适配器。
 * <p>
 * 每次调用创建一个无状态 ReActAgent，业务模块不持有 Agent 实例。
 * API key 为占位符时 {@link #configured()} 返回 false，契约层直接确定性降级。
 */
@Component
public class AgentScopeInvoker implements AgentInvoker {

    /** 主模型（推荐解释等生成任务）。 */
    private final Model mainModel;

    /** 轻量模型（意图理解、澄清措辞等任务）。 */
    private final Model lightModel;

    /** DashScope API key，用于判断是否已配置真实模型。 */
    private final String apiKey;

    /** 单次调用超时，来自配置 diet.agent.timeout-ms。 */
    private final Duration timeout;

    /** 需要拒绝调用的占位 key（application.yml 默认值）。 */
    private static final String PLACEHOLDER_KEY = "请填入自己的apiKey";

    public AgentScopeInvoker(
            @Qualifier("DietMainChatModel") Model mainModel,
            @Qualifier("DietLightChatModel") Model lightModel,
            @Value("${agentscope.dashscope.api-key:}") String apiKey,
            @Value("${diet.agent.timeout-ms:15000}") long timeoutMs
    ) {
        this.mainModel = mainModel;
        this.lightModel = lightModel;
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocation invocation) {
        long startedAt = System.nanoTime();
        Model model = "qwen-max".equals(invocation.modelName()) ? mainModel : lightModel;
        ReActAgent agent = ReActAgent.builder()
                .name("health_" + invocation.agentRole().toLowerCase())
                .model(model)
                .sysPrompt("")
                .memory(new InMemoryMemory())
                .build();
        try {
            Msg response = agent.call(Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(invocation.promptText())
                            .build())
                    .block(invocation.timeout() == null ? timeout : invocation.timeout());
            if (response == null) {
                // block(Duration) 超时返回 null
                throw new AgentTimeoutException("Agent 调用超时: " + invocation.agentRole(), null);
            }
            if (response.getTextContent() == null) {
                throw new AgentInvocationException("Agent 返回空响应: " + invocation.agentRole(), null);
            }
            return new AgentInvocationResult(
                    response.getTextContent().trim(),
                    invocation.modelName(),
                    (System.nanoTime() - startedAt) / 1_000_000
            );
        } catch (AgentTimeoutException error) {
            throw error;
        } catch (AgentInvocationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AgentInvocationException("Agent 调用失败: " + invocation.agentRole(), error);
        }
    }

    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }
}
