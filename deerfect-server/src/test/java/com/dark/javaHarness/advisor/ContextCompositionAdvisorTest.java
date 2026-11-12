package com.dark.javaHarness.advisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.prompt.ContextCompositionRecorder;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SystemAssembly;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * 上下文构成 advisor 单测：读取最终 messages 记录快照，且不改写请求。
 */
class ContextCompositionAdvisorTest {

    @Test
    void adviseCall_recordsFinalMessagesWithoutRewritingRequest() {
        ContextCompositionRecorder recorder = mock(ContextCompositionRecorder.class);
        SystemAssembly system = new SystemAssembly("SYS",
                List.of(new PromptAssembler.RenderedSection("角色", "SYS", 1)), 0);
        ContextCompositionAdvisor advisor = new ContextCompositionAdvisor(
                "general", "s1", "m1", "t1", system, 7, recorder);
        List<Message> messages = List.of(new SystemMessage("SYS"), new UserMessage("当前问题"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .build();
        ChatClientResponse response = mock(ChatClientResponse.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse out = advisor.adviseCall(request, chain);

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).record(eq("s1"), eq("general"), eq("m1"), eq("t1"), eq(system), eq(7),
                captor.capture());
        assertSame(messages, captor.getValue(), "记录的就是请求最终消息，不额外改写");
        assertSame(response, out, "请求原样下发，返回值透传");
        verify(chain).nextCall(request);
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 4, advisor.getOrder(),
                "晚于 ContextAssembling/PromptBudget/LlmRequestLog");
    }
}
