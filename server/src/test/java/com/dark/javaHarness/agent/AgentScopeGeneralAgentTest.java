package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.agentic.AgentScopeModelFactory;
import com.dark.javaHarness.config.AgenticProperties;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.observe.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

/**
 * Agentic SIMPLE 路径装配与事件映射测试：
 * 真实模型用例验证 ReAct 输出可被 AgentService/SSE 契约消费并落一条观测；
 * 回退用例验证 AgentScope 初始化失败时切回 ChatClient 路径（离线）。
 */
class AgentScopeGeneralAgentTest {

    private static final String AGENT = "general";
    private static final String SESSION = "session-agentic-test";

    @Test
    void fallsBackToChatClientWhenAgentScopeInitFails() {
        Agent fallback = mock(Agent.class);
        when(fallback.executeStreamReactive(any())).thenReturn(Flux.just("fallback-reply"));

        AgentScopeModelFactory factory = mock(AgentScopeModelFactory.class);
        when(factory.build(any())).thenThrow(new IllegalStateException("模型不可用"));

        AgentScopeGeneralAgent agent = newAgent(factory, fallback);
        Goal goal = new Goal("goal-fallback", "你好", SESSION, "turn-1", "trace-1");

        String content = agent.executeStreamReactive(goal)
                .filter(line -> !ProgressLine.isProgress(line))
                .collectList()
                .block(Duration.ofSeconds(10))
                .stream().reduce("", String::concat);

        assertTrue(content.contains("fallback-reply"), "初始化失败应回退 ChatClient 路径，实际：" + content);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void streamsReActContentAndRecordsObservation() {
        AgentScopeModelFactory factory = mock(AgentScopeModelFactory.class);
        when(factory.build(any())).thenReturn(realDeepSeekModel());
        when(factory.modelName(any())).thenReturn("deepseek-chat");

        LlmCallRecorder recorder = mock(LlmCallRecorder.class);
        AgentScopeGeneralAgent agent = newAgent(factory, null, recorder);
        Goal goal = new Goal("goal-react", "用一句话介绍你自己", SESSION, "turn-1", "trace-1");

        String content = agent.executeStreamReactive(goal)
                .filter(line -> !ProgressLine.isProgress(line))
                .collectList()
                .block(Duration.ofSeconds(120))
                .stream().reduce("", String::concat);

        assertFalse(content.isBlank(), "ReAct 路径应产出文本内容");
        verify(recorder).record(any());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void historyInjectionAndSpringAiToolBridgeWorkTogether() {
        AgentScopeModelFactory factory = mock(AgentScopeModelFactory.class);
        when(factory.build(any())).thenReturn(realDeepSeekModel());
        when(factory.modelName(any())).thenReturn("deepseek-chat");

        LlmCallRecorder recorder = mock(LlmCallRecorder.class);
        ToolAssignments assignments = mock(ToolAssignments.class);
        when(assignments.forAgent(AGENT))
                .thenReturn(new ToolAssignments.ToolSet(List.of(new PocTools()), List.of()));
        when(assignments.purposeOf("poc_now")).thenReturn("返回服务器当前时间");
        List<Message> history = List.of(new UserMessage("请记住：我的名字是小明"),
                new AssistantMessage("好的，我记住了。"));
        AgentScopeGeneralAgent agent = newAgent(factory, null, recorder, history, assignments);
        Goal goal = new Goal("goal-react-tool",
                "我叫什么？请先调用 poc_now 工具，然后用我的名字和工具返回的时间回答。",
                SESSION, "turn-2", "trace-2");

        List<String> rows = agent.executeStreamReactive(goal)
                .collectList()
                .block(Duration.ofSeconds(120));
        assertFalse(rows.isEmpty(), "应产出内容/进度行");

        String content = rows.stream()
                .filter(line -> !ProgressLine.isProgress(line))
                .reduce("", String::concat);
        assertTrue(content.contains("小明"), "历史中的名字应被引用，实际：" + content);
        assertTrue(rows.stream().filter(ProgressLine::isProgress).anyMatch(line -> line.contains("poc_now")),
                "Spring AI 工具应经桥接被 ReAct 调用，实际进度行：" + rows);
    }

    /** Spring AI 反射工具：经 ToolCallbacks.from 转 ToolCallback，再桥接进 AgentScope Toolkit。 */
    static class PocTools {

        @Tool(name = "poc_now", description = "返回服务器当前时间的 ISO-8601 字符串")
        public String pocNow() {
            return Instant.now().toString();
        }
    }

    private static AgentScopeGeneralAgent newAgent(AgentScopeModelFactory factory, Agent fallback) {
        return newAgent(factory, fallback, mock(LlmCallRecorder.class), List.of(), null);
    }

    private static AgentScopeGeneralAgent newAgent(AgentScopeModelFactory factory, Agent fallback,
                                                   LlmCallRecorder recorder) {
        return newAgent(factory, fallback, recorder, List.of(), null);
    }

    private static AgentScopeGeneralAgent newAgent(AgentScopeModelFactory factory, Agent fallback,
                                                   LlmCallRecorder recorder, List<Message> history,
                                                   ToolAssignments assignments) {
        AgentService agentService = mock(AgentService.class);
        AgentConfig config = new AgentConfig(1L, "deepseek-chat", "你是测试助手", null, false);
        when(agentService.getAgentConfig(AGENT)).thenReturn(Optional.of(config));

        SessionService memoryStore = mock(SessionService.class);
        when(memoryStore.get(SESSION)).thenReturn(history);

        ToolAssignments effectiveAssignments = assignments;
        if (effectiveAssignments == null) {
            effectiveAssignments = mock(ToolAssignments.class);
            when(effectiveAssignments.forAgent(AGENT)).thenReturn(ToolAssignments.ToolSet.EMPTY);
        }

        ContextBudgetProperties budgets = new ContextBudgetProperties();
        budgets.setHistoryBudget(4000);
        budgets.setMaxTokensFinal(8000);
        budgets.setToolCallLimit(4);

        ChatTimeoutProperties timeouts = new ChatTimeoutProperties();
        timeouts.setReadTimeoutSeconds(300);

        AgenticProperties agentic = new AgenticProperties();
        agentic.setEnabled(true);
        agentic.setMaxIters(4);
        agentic.setMaxAttempts(1);
        agentic.setFallbackEnabled(true);

        PromptAssembler assembler = new PromptAssembler(agentService, effectiveAssignments,
                List.of(), List.of(), false);
        ToolLazyManager lazyTools = new ToolLazyManager(effectiveAssignments, false);

        return new AgentScopeGeneralAgent(AGENT, mock(ChatClientRegistry.class), agentService,
                memoryStore, effectiveAssignments, recorder, budgets, lazyTools, assembler, null, null,
                timeouts, factory, agentic, fallback);
    }

    private static OpenAIChatModel realDeepSeekModel() {
        return OpenAIChatModel.builder()
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .baseUrl(envOrDefault("POC_AGENT_BASE_URL", "https://api.deepseek.com/v1"))
                .modelName(envOrDefault("POC_AGENT_MODEL", "deepseek-chat"))
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .build();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
