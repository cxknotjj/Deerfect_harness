package com.dark.javaHarness.agent;

import com.dark.javaHarness.agent.agentic.AgentScopeModelFactory;
import com.dark.javaHarness.config.AgenticProperties;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.prompt.ContextCompositionRecorder;
import com.dark.javaHarness.prompt.PromptAssembler;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 对话 Agent 变体工厂：按 agentic 开关选择 ReAct 或 ChatClient 路径。
 *
 * <p>AgentRegistry 只负责注册/查找；本类持有共享执行器与变体所需协作者，避免注册表同时承担
 * 路由表与对象装配两种职责。AgentService 经 ObjectProvider 在构造实例时现取，断开
 * AgentService → AgentRegistry 的创建期循环。
 */
public class AgentFactory {

    private final ObjectProvider<AgentService> agentServiceProvider;
    private final AgentChatCaller chatCaller;
    private final AgentRequestSpecFactory specFactory;
    private final LlmRetry llmRetry;
    private final ContextBudgetProperties budgets;
    private final PromptAssembler promptAssembler;
    private final ChatTimeoutProperties timeouts;
    private final AgenticProperties agenticProperties;
    private final AgentScopeModelFactory agentScopeModelFactory;
    private final ContextCompositionRecorder compositionRecorder;
    private final LlmCallRecorder recorder;

    public AgentFactory(ObjectProvider<AgentService> agentServiceProvider,
                        AgentChatCaller chatCaller,
                        AgentRequestSpecFactory specFactory,
                        LlmRetry llmRetry,
                        ContextBudgetProperties budgets,
                        PromptAssembler promptAssembler,
                        ChatTimeoutProperties timeouts,
                        AgenticProperties agenticProperties,
                        AgentScopeModelFactory agentScopeModelFactory,
                        ContextCompositionRecorder compositionRecorder,
                        LlmCallRecorder recorder) {
        this.agentServiceProvider = agentServiceProvider;
        this.chatCaller = chatCaller;
        this.specFactory = specFactory;
        this.llmRetry = llmRetry;
        this.budgets = budgets;
        this.promptAssembler = promptAssembler;
        this.timeouts = timeouts;
        this.agenticProperties = agenticProperties;
        this.agentScopeModelFactory = agentScopeModelFactory;
        this.compositionRecorder = compositionRecorder;
        this.recorder = recorder;
    }

    /** 按 agent 名创建可路由实例；ChatClient 实例同时作为 ReAct 初始化失败的回退。 */
    public Agent create(String agentName) {
        AgentService agentService = agentServiceProvider.getObject();
        Agent chatClientAgent = new GeneralAssistantAgent(agentName, agentService, chatCaller,
                llmRetry, budgets);
        if (!agenticProperties.isEnabled()) {
            return chatClientAgent;
        }
        return new AgentScopeGeneralAgent(agentName, agentService, promptAssembler, budgets,
                timeouts, agentScopeModelFactory, agenticProperties, specFactory, chatClientAgent,
                compositionRecorder, recorder);
    }
}
