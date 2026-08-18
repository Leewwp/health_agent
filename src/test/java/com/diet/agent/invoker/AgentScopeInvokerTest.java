package com.diet.agent.invoker;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/** AgentScope 真实适配器的模型职责路由测试。 */
class AgentScopeInvokerTest {

    @Test
    void 模型选择只依赖显式职责且不依赖模型名称() {
        Model mainModel = mock(Model.class);
        Model lightModel = mock(Model.class);
        AgentScopeInvoker invoker = new AgentScopeInvoker(mainModel, lightModel, "test-key", 1000);

        assertSame(mainModel, invoker.selectModel(AgentInvoker.ModelRole.MAIN));
        assertSame(lightModel, invoker.selectModel(AgentInvoker.ModelRole.LIGHT));
    }
}
