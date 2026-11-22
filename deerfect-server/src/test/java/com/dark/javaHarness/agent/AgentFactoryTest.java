package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.agentic.AgentScopeModelFactory;
import com.dark.javaHarness.config.AgenticProperties;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.prompt.PromptAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * AgentFactory 单测：只负责变体选择，不承担注册表路由职责。
 */
@ExtendWith(MockitoExtension.class)
class AgentFactoryTest {

    @Mock
    private ObjectProvider<AgentService> agentServiceProvider;
    @Mock
    private AgentService agentService;
    @Mock
    private AgentChatCaller chatCaller;
    @Mock
    private AgentRequestSpecFactory specFactory;
    @Mock
    private PromptAssembler promptAssembler;
    @Mock
    private AgentScopeModelFactory agentScopeModelFactory;
    @Mock
    private LlmCallRecorder recorder;

    @BeforeEach
    void setUp() {
        when(agentServiceProvider.getObject()).thenReturn(agentService);
    }

    @Test
    void agenticEnabled_createsReActAgentsForEveryName() {
        AgentFactory factory = factory(true);

        assertInstanceOf(AgentScopeGeneralAgent.class, factory.create("general"),
                "判定为 agentic 的 general 路径应由 ReAct 承载");
        assertInstanceOf(AgentScopeGeneralAgent.class, factory.create("coder"),
                "显式指定 Agent 的直答同样走 ReAct");
    }

    @Test
    void agenticDisabled_createsChatClientAgents() {
        AgentFactory factory = factory(false);

        assertInstanceOf(GeneralAssistantAgent.class, factory.create("general"),
                "关闭 agentic 时应回退 ChatClient 单次调用路径");
    }

    private AgentFactory factory(boolean agenticEnabled) {
        AgenticProperties agentic = new AgenticProperties();
        agentic.setEnabled(agenticEnabled);
        return new AgentFactory(agentServiceProvider, chatCaller, specFactory, new LlmRetry(),
                new ContextBudgetProperties(), promptAssembler, new ChatTimeoutProperties(),
                agentic, agentScopeModelFactory, null, recorder);
    }
}
