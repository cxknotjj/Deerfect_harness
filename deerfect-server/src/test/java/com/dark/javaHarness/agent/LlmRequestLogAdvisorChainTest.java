package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.prompt.PromptAssembler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * 端到端验证：specFactory.build 挂载的 LlmRequestLogAdvisor 是否真的进入 advisor 链
 * 并在 stream() 时执行（回归背景：生产 general 路径未打出 [llm-call] 日志）。
 */
class LlmRequestLogAdvisorChainTest {

    private static Flux<ChatResponse> fluxOf(String token) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
    }

    @Test
    void stream_invokesRequestLogAdvisor() {
        ChatModel model = mock(ChatModel.class);
        lenient().when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        lenient().when(model.stream(any(Prompt.class))).thenReturn(fluxOf("ok"));
        lenient().when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        ChatClient client = ChatClient.builder(model).build();

        LlmClientRegistry registry = mock(LlmClientRegistry.class);
        AgentCatalog agentCatalog = mock(AgentCatalog.class);
        ModelResolver modelResolver = mock(ModelResolver.class);
        com.dark.javaHarness.domain.entity.AgentEntity routeAgent =
                new com.dark.javaHarness.domain.entity.AgentEntity();
        routeAgent.setAgentName("general");
        routeAgent.setRouteId(1L);
        when(agentCatalog.require("general")).thenReturn(routeAgent);
        when(modelResolver.resolve(1L)).thenReturn(new RoutePlan(1L, "main", List.of(
                new RoutePlan.Member(9L, 1L, "dashscope", "https://example.com", "m", "m",
                        10, "{}", null))));
        when(registry.get(9L)).thenReturn(client);
        PromptAssembler assembler = mock(PromptAssembler.class);
        when(assembler.assembleSections(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(new PromptAssembler.RenderedSection("角色", "SYS", 1)));

        AgentRequestSpecFactory factory = new AgentRequestSpecFactory(assembler,
                null, null, null, null, new ContextBudgetProperties(), null, null, List.of(), null,
                modelResolver, agentCatalog, registry);

        Logger logger = (Logger) LoggerFactory.getLogger(com.dark.javaHarness.observability.LlmRequestLogAdvisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AgentRequestSpecFactory.Assembly assembly =
                    new AgentRequestSpecFactory.Assembly(null, false, false, false, 0, null);
            ChatClient.ChatClientRequestSpec spec = factory.build(
                    null, "s1", "general", "FALLBACK", "hello", assembly, null);
            spec.stream().chatResponse().collectList().block();

            String joined = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(joined.contains("[llm-call] 发起 agent=general"),
                    "advisor 未被执行！实际日志：" + joined);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
