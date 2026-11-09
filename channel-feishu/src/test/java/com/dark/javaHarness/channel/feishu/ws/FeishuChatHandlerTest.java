package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 对话处理器单测：绑定复用、新建绑定、群聊回复目标、失败不回复，以及同一会话的串行语义。
 *
 * <p>只 mock 边界（数据库、ChatService、飞书 API），处理器本身的编排逻辑走真实代码。
 * 串行相关用例靠 {@link CountDownLatch} 把第一条卡在「模型调用中」，再发第二条，
 * 断言第二条不会并排开跑。
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

    @AfterEach
    void tearDown() {
        handler.destroy();
    }

    @Test
    void existingBinding_groupRepliesToChatId() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("群回复"));
        when(api.sendTextToChat("oc_chat", "群回复"))
                .thenReturn(new FeishuApiClient.Result(true, "om_reply", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-1", "你好", true))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendTextToChat("oc_chat", "群回复");
        verify(api, never()).sendText(any(), any());
    }

    @Test
    void newBinding_createsSessionThenReplies() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(sessionService.createSession("feishu:ou_user", "你好")).thenReturn("42");
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("单聊回复"));
        when(api.sendText("ou_user", "单聊回复"))
                .thenReturn(new FeishuApiClient.Result(true, "om_reply", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-2", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(bindingMapper).insert(any(FeishuSessionBinding.class));
        verify(api).sendText("ou_user", "单聊回复");
    }

    @Test
    void failedChat_doesNotReply() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(new ChatResponse(
                "7", false, "g1", "FAILED", null, "model down", List.of(), "agent", Set.of(), null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-3", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, never()).sendText(any(), any());
        verify(api, never()).sendTextToChat(any(), any());
    }

    @Test
    void chatTimeout_doesNotReply() throws Exception {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setChatTimeoutSeconds(1);
        FeishuChatHandler shortTimeout = new FeishuChatHandler(chatService, sessionService, bindingMapper, api, props);
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return reply("晚到的回复");
        });

        shortTimeout.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-timeout", "你好", false))
                .get(10, TimeUnit.SECONDS);

        verify(api, never()).sendText(any(), any());
        shortTimeout.destroy();
    }

    @Test
    void chatThrows_doesNotReply() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("boom"));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-4", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, never()).sendText(any(), any());
    }

    /**
     * 连发两条必须按序：第一条还在模型调用里时，第二条不能开始。
     *
     * <p>这是「第二条看不到第一条」那个竞态的回归用例——并发跑的话 order 里会出现
     * start:第二条 早于 end:第一条。
     */
    @Test
    void sameSession_twoMessages_runInOrder() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            order.add("start:" + request.message());
            if (request.message().startsWith("第一条")) {
                firstStarted.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
            } else {
                secondStarted.countDown();
            }
            order.add("end:" + request.message());
            return reply("回复:" + request.message());
        });
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        CompletableFuture<Void> first = handler.handleAsync(
                new FeishuInboundMessage("ou_user", "oc_chat", "om_1", "evt-a", "第一条", false));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "第一条应已进入模型调用");

        CompletableFuture<Void> second = handler.handleAsync(
                new FeishuInboundMessage("ou_user", "oc_chat", "om_2", "evt-b", "第二条", false));

        // 关键断言：给并发实现一个 300ms 的「抢跑窗口」。串行时第二条还在排队，
        // 不可能在这段时间里进入模型调用；一旦被并发发射，这里会立刻失败。
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS),
                "第二条不应在第一条结束前开始——同一会话必须串行");

        releaseFirst.countDown();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertEquals(List.of("start:第一条", "end:第一条", "start:第二条", "end:第二条"), order,
                "完成顺序应与投递顺序一致");
        verify(api).sendText("ou_user", "回复:第一条");
        verify(api).sendText("ou_user", "回复:第二条");
    }

    /** 第三条积压时拒收，并回一句「还在处理」——不能静默丢 */
    @Test
    void sameSession_thirdMessage_isRejectedWithNotice() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> handled = Collections.synchronizedList(new ArrayList<>());

        when(chatService.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            handled.add(request.message());
            firstStarted.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return reply("回复:" + request.message());
        });
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        CompletableFuture<Void> first = handler.handleAsync(
                new FeishuInboundMessage("ou_user", "oc_chat", "om_1", "evt-a", "第一条", false));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> second = handler.handleAsync(
                new FeishuInboundMessage("ou_user", "oc_chat", "om_2", "evt-b", "第二条", false));
        handler.handleAsync(
                new FeishuInboundMessage("ou_user", "oc_chat", "om_3", "evt-c", "第三条", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, timeout(3000)).sendText("ou_user", "上一条还在处理，稍等片刻再发。");

        releaseFirst.countDown();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("第一条", "第二条"), handled, "第三条应被拒收，不进模型");
    }

    /** 一次异常不能让会话永久卡死：后续消息仍要能跑 */
    @Test
    void unfinishedTurn_doesNotBlockNextMessage() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(reply("第二条回复"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_1", "evt-a", "第一条", false))
                .get(5, TimeUnit.SECONDS);
        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_2", "evt-b", "第二条", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText("ou_user", "第二条回复");
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
