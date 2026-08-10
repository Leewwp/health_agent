package com.diet.config;

import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.AgentScopeInvoker;
import com.diet.agent.invoker.FixtureAgentInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 健康 Agent 运行配置：按 diet.agent.mode 选择真实模型或固定夹具适配器。
 * prod 强制 agentscope，fixture 模式在 prod 下启动失败（防止把演示数据当生产能力）。
 */
@Configuration
public class HealthAgentConfiguration {

    @Bean("healthAgentInvoker")
    public AgentInvoker healthAgentInvoker(
            AgentScopeInvoker agentScopeInvoker,
            FixtureAgentInvoker fixtureAgentInvoker,
            Environment environment,
            @Value("${diet.agent.mode:agentscope}") String mode
    ) {
        boolean prodActive = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if ("fixture".equalsIgnoreCase(mode)) {
            if (prodActive) {
                throw new IllegalStateException("生产环境禁止 diet.agent.mode=fixture，必须使用 agentscope");
            }
            return fixtureAgentInvoker;
        }
        return agentScopeInvoker;
    }
}
