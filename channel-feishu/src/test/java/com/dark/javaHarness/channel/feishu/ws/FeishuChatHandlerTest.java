package com.dark.javaHarness.channel.feishu.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBinding;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 对话处理器单测：绑定复用、新建绑定、群聊回复目标、失败不回复。
 *
 * <p>只 mock 边界（数据库、ChatService、飞书 API），处理器本身的编排逻辑走真实代码。
 */
@ExtendWith(MockitoExtension.class)
class FeishuChatHandlerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private SessionService sessionService;

    @Mock
    private FeishuSessionBindingMapper bindingMapper;

    @Mock
    private FeishuApiClient api;

    private FeishuChatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FeishuChatHandler(chatService, sessionService, bindingMapper, api, new FeishuProperties());
    }

    @Test
    void existingBinding_groupRepliesToChatId() {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("群回复"));
        when(api.sendTextToChat("oc_chat", "群回复"))
                .thenReturn(new FeishuApiClient.Result(true, "om_reply", null));

        handler.handle(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-1", "你好", true));

        verify(api).sendTextToChat("oc_chat", "群回复");
        verify(api, never()).sendText(any(), any());
    }

    @Test
    void newBinding_createsSessionThenReplies() {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(sessionService.createSession("feishu:ou_user", "你好")).thenReturn("42");
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("单聊回复"));
        when(api.sendText("ou_user", "单聊回复"))
                .thenReturn(new FeishuApiClient.Result(true, "om_reply", null));

        handler.handle(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-2", "你好", false));

        verify(bindingMapper).insert(any(FeishuSessionBinding.class));
        verify(api).sendText("ou_user", "单聊回复");
    }

    @Test
    void failedChat_doesNotReply() {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(new ChatResponse(
                "7", false, "g1", "FAILED", null, "model down", List.of(), "agent", Set.of(), null));

        handler.handle(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-3", "你好", false));

        verify(api, never()).sendText(any(), any());
        verify(api, never()).sendTextToChat(any(), any());
    }

    @Test
    void chatTimeout_doesNotReply() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setChatTimeoutSeconds(1);
        FeishuChatHandler shortTimeout = new FeishuChatHandler(chatService, sessionService, bindingMapper, api, props);
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return reply("晚到的回复");
        });

        shortTimeout.handle(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-timeout", "你好", false));

        verify(api, never()).sendText(any(), any());
        shortTimeout.destroy();
    }

    @Test
    void chatThrows_doesNotReply() {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("boom"));

        handler.handle(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-4", "你好", false));

        verify(api, never()).sendText(any(), any());
    }

    private static FeishuSessionBinding binding(long sessionId) {
        FeishuSessionBinding binding = new FeishuSessionBinding();
        binding.setSessionId(sessionId);
        return binding;
    }

    private static ChatResponse reply(String text) {
        return ChatResponse.success("7", false, "g1", text, List.of(), "agent", Set.of(), null);
    }
}
