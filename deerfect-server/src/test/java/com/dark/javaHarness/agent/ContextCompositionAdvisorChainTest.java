package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.prompt.ContextCompositionRecorder;
import com.dark.javaHarness.prompt.ContextCompositionStore;
import com.dark.javaHarness.prompt.PromptAssembler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Path A 端到端接线验证：specFactory.build 挂载的 ContextCompositionAdvisor 真进入 advisor 链，
 * 在 stream 请求发出时记录快照（session/agent/turn + system/当前 user 组成项）。
 */
class ContextCompositionAdvisorChainTest {

    private static Flux<ChatResponse> fluxOf(String token) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
    }

    @Test
    void stream_recordsCompositionSnapshot() {
        ChatModel model = mock(ChatModel.class);
        lenient().when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        lenient().when(model.stream(any(Prompt.class))).thenReturn(fluxOf("ok"));
        lenient().when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

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
        when(registry.get(9L)).thenReturn(ChatClient.builder(model).build());
        PromptAssembler assembler = mock(PromptAssembler.class);
        when(assembler.assembleSections(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(new PromptAssembler.RenderedSection("角色", "SYS", 1)));

        ContextCompositionStore store = new ContextCompositionStore();
        ContextCompositionRecorder recorder = new ContextCompositionRecorder(store);
        AgentRequestSpecFactory factory = new AgentRequestSpecFactory(assembler,
                null, null, null, null, new ContextBudgetProperties(), null, null, List.of(), recorder,
                modelResolver, agentCatalog, registry);

        AgentRequestSpecFactory.Assembly assembly =
                new AgentRequestSpecFactory.Assembly(null, false, false, false, 0, null);
        ChatClient.ChatClientRequestSpec spec = factory.build(
                null, "s1", "general", "FALLBACK", "hello", assembly,
                new CallTrace("t1", null, null, null, null));
        spec.stream().chatResponse().collectList().block();

        ContextComposition composition = store.get("s1").orElseThrow(
                () -> new AssertionError("未记录上下文快照"));
        assertTrue(composition.parts().stream().anyMatch(p -> p.key().equals("system.role")),
                "system 角色段应计入快照");
        assertTrue(composition.parts().stream().anyMatch(p -> p.key().equals("conversation.currentUser")),
                "当前 user 消息应计入快照");
    }
}
