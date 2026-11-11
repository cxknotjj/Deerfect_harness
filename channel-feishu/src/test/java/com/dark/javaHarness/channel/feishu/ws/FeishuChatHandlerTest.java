package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBinding;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.HarnessStatusPort;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import com.dark.javaHarness.contract.model.ComponentStatus;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.view.AgentItemView;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import com.dark.javaHarness.view.SessionMessagesView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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

    @Mock
    private AgentService agentService;

    @Mock
    private HarnessStatusPort statusPort;

    @Mock
    private VoiceArtifactStore voiceArtifacts;

    @Mock
    private FeishuAudioTranscoder audioTranscoder;

    /** 指令授权人：与用例里的普通用户 ou_user 区分开，才能测出「只有一个人能用」 */
    private static final String COMMANDER = "ou_admin";

    @TempDir
    Path tempDir;

    private FeishuChatHandler handler;

    @BeforeEach
    void setUp() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setAllowedUsers(new ArrayList<>(List.of(COMMANDER)));
        props.getWs().setCommandUser(COMMANDER);
        // 用例默认走普通发送：引用回复另有专门用例（否则每条断言都要同时照顾两条路径）
        props.getWs().setQuoteReply(false);
        handler = new FeishuChatHandler(chatService, sessionService, agentService, statusPort,
                bindingMapper, api, voiceArtifacts, audioTranscoder, props);
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
        FeishuChatHandler shortTimeout = new FeishuChatHandler(chatService, sessionService, agentService,
                statusPort, bindingMapper, api, voiceArtifacts, audioTranscoder, props);
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

    // ---------- 指令：只有授权人能执行，且不建会话、不调模型 ----------

    @Test
    void help_fromOwner_repliesWithoutCreatingSession() throws Exception {
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c1", "evt-c1", "/help", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("可用指令"));
        verify(chatService, never()).chat(any());
        verify(sessionService, never()).createSession(any(), any());
    }

    @Test
    void help_fromNonOwner_isRejectedWithoutCallingModel() throws Exception {
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_c2", "evt-c2", "/help", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq("ou_user"), contains("指令只有管理员能用"));
        verify(chatService, never()).chat(any());
    }

    @Test
    void new_fromOwner_deletesBindingOnly() throws Exception {
        when(bindingMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c3", "evt-c3", "/new", false))
                .get(5, TimeUnit.SECONDS);

        verify(bindingMapper).delete(any(Wrapper.class));
        verify(api).sendText(eq(COMMANDER), contains("已开启新对话"));
        verify(chatService, never()).chat(any());
    }

    @Test
    void reset_withoutBinding_saysNoActiveConversation() throws Exception {
        when(bindingMapper.delete(any(Wrapper.class))).thenReturn(0);
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c4", "evt-c4", "/reset", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("还没有进行中的对话"));
    }

    @Test
    void history_withoutBinding_saysNoRecords() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c5", "evt-c5", "/history", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("还没有聊天记录"));
        verify(sessionService, never()).listMessages(any());
    }

    @Test
    void history_returnsRequestedTail() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(sessionService.listMessages("7")).thenReturn(List.of(
                new SessionMessagesView.Item("user", "第一条提问", 1L),
                new SessionMessagesView.Item("assistant", "第一条回答", 2L),
                new SessionMessagesView.Item("user", "第二条提问", 3L)));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c6", "evt-c6", "/history 1", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("第二条提问"));
        verify(api, never()).sendText(eq(COMMANDER), contains("第一条提问"));
    }

    @Test
    void agent_list_marksBoundAgent() throws Exception {
        when(agentService.agentItems()).thenReturn(List.of(
                new AgentItemView(1L, "general"), new AgentItemView(2L, "writer")));
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(sessionService.getSession("7"))
                .thenReturn(new SessionInfo(7L, "会话", "feishu:ou_admin", null, 2, null));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c7", "evt-c7", "/agent", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("writer（当前）"));
    }

    @Test
    void agent_switch_updatesSessionBinding() throws Exception {
        when(agentService.agentItems()).thenReturn(List.of(
                new AgentItemView(1L, "general"), new AgentItemView(2L, "writer")));
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_c8", "evt-c8", "/agent writer", false))
                .get(5, TimeUnit.SECONDS);

        verify(sessionService).switchAgent("7", 2L);
        verify(api).sendText(eq(COMMANDER), contains("已切换到 Agent：writer"));
    }

    @Test
    void unrecognizedSlash_isTreatedAsNormalChat() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("普通回复"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_c9", "evt-c9", "/tmp 是什么", false))
                .get(5, TimeUnit.SECONDS);

        verify(chatService).chat(any(ChatRequest.class));
        verify(api).sendText("ou_user", "普通回复");
    }

    // ---------- 表情包：标记剥离 → 取图 → 上传 → 发图 ----------

    @Test
    void emojiMarker_isStrippedFromText() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("好耶！\n[emoji:happy]"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_e1", "evt-e1", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText("ou_user", "好耶！");
        verify(api, never()).uploadImage(any(), any());
    }

    @Test
    void emojiEnabled_uploadsAndSendsImage() throws Exception {
        Files.write(tempDir.resolve("happy_01.png"), new byte[] {1, 2, 3});
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"happy_01.png\"}");

        FeishuChatHandler withEmoji = new FeishuChatHandler(chatService, sessionService, agentService,
                statusPort, bindingMapper, api, voiceArtifacts, audioTranscoder, emojiProps());
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("好耶！\n[emoji:happy]"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));
        when(api.uploadImage(eq("happy_01"), any()))
                .thenReturn(new FeishuApiClient.Upload(true, "img_1", null));
        when(api.sendImage("ou_user", "img_1"))
                .thenReturn(new FeishuApiClient.Result(true, "om_img", null));

        withEmoji.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_e2", "evt-e2", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText("ou_user", "好耶！");
        verify(api).sendImage("ou_user", "img_1");
        withEmoji.destroy();
    }

    @Test
    void emojiUploadFails_textStillSent() throws Exception {
        Files.write(tempDir.resolve("happy_01.png"), new byte[] {1, 2, 3});
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"happy_01.png\"}");

        FeishuChatHandler withEmoji = new FeishuChatHandler(chatService, sessionService, agentService,
                statusPort, bindingMapper, api, voiceArtifacts, audioTranscoder, emojiProps());
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("好耶！\n[emoji:happy]"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));
        when(api.uploadImage(any(), any()))
                .thenReturn(new FeishuApiClient.Upload(false, null, "HTTP 403 no permission"));

        withEmoji.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_e3", "evt-e3", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText("ou_user", "好耶！");
        verify(api, never()).sendImage(any(), any());
        withEmoji.destroy();
    }

    // ---------- /status 与表情缓存 ----------

    @Test
    void status_listsSessionAndComponents() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(sessionService.getSession("7"))
                .thenReturn(new SessionInfo(7L, "会话", "feishu:ou_admin", null, 2, null));
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("general"));
        when(statusPort.components()).thenReturn(List.of(
                new ComponentStatus("飞书 / 长连接", true, true, "运行中，30 秒前收到过事件"),
                new ComponentStatus("QQ 渠道", true, false, "不可达（retcode=-1）")));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_s1", "evt-s1", "/status", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText(eq(COMMANDER), contains("Agent general"));
        verify(api).sendText(eq(COMMANDER), contains("● 飞书 / 长连接"));
        verify(api).sendText(eq(COMMANDER), contains("○ QQ 渠道"));
    }

    @Test
    void emojiKey_isCachedAcrossReplies() throws Exception {
        Files.write(tempDir.resolve("happy_01.png"), new byte[] {1, 2, 3});
        Files.writeString(tempDir.resolve("emoji-index.json"), "{\"happy_01\":\"happy_01.png\"}");

        FeishuChatHandler withEmoji = new FeishuChatHandler(chatService, sessionService, agentService,
                statusPort, bindingMapper, api, voiceArtifacts, audioTranscoder, emojiProps());
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("好耶！\n[emoji:happy]"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));
        when(api.uploadImage(any(), any())).thenReturn(new FeishuApiClient.Upload(true, "img_1", null));
        when(api.sendImage(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_i", null));

        for (int i = 0; i < 2; i++) {
            withEmoji.handleAsync(new FeishuInboundMessage(
                            "ou_user", "oc_chat", "om_" + i, "evt-" + i, "你好", false))
                    .get(5, TimeUnit.SECONDS);
        }

        verify(api, times(1)).uploadImage(any(), any());
        verify(api, times(2)).sendImage(any(), any());
        withEmoji.destroy();
    }

    // ---------- 引用回复 / @ / 撤回 / 回复模式 ----------

    @Test
    void quoteReply_usesReplyApi() throws Exception {
        FeishuChatHandler quoting = handlerWith(props -> props.getWs().setQuoteReply(true));
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("带引用的回复"));
        when(api.replyText(eq("om_in"), eq("带引用的回复"), eq(false)))
                .thenReturn(new FeishuApiClient.Result(true, "om_q", null));

        quoting.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-q1", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).replyText("om_in", "带引用的回复", false);
        verify(api, never()).sendText(any(), any());
        quoting.destroy();
    }

    @Test
    void quoteReplyFails_fallsBackToPlainSend() throws Exception {
        FeishuChatHandler quoting = handlerWith(props -> props.getWs().setQuoteReply(true));
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("回复"));
        when(api.replyText(any(), any(), any(Boolean.class)))
                .thenReturn(new FeishuApiClient.Result(false, null, "message not found"));
        when(api.sendText("ou_user", "回复"))
                .thenReturn(new FeishuApiClient.Result(true, "om_plain", null));

        quoting.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-q2", "你好", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendText("ou_user", "回复");
        quoting.destroy();
    }

    @Test
    void groupAtAsker_prefixesAtTag() throws Exception {
        FeishuChatHandler atHandler = handlerWith(props -> props.getWs().setAtAsker(true));
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("群回复"));
        when(api.sendTextToChat(eq("oc_chat"), any()))
                .thenReturn(new FeishuApiClient.Result(true, "om_g", null));

        atHandler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-g1", "你好", true))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendTextToChat(eq("oc_chat"),
                eq("<at user_id=\"ou_user\"></at> 群回复"));
        atHandler.destroy();
    }

    @Test
    void recall_deletesLastSentMessage() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class))).thenReturn(reply("回复"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_sent", null));
        when(api.deleteMessage("om_sent")).thenReturn(new FeishuApiClient.Result(true, "om_sent", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_1", "evt-r1", "你好", false))
                .get(5, TimeUnit.SECONDS);
        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_2", "evt-r2", "/recall", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).deleteMessage("om_sent");
        verify(api).sendText(eq(COMMANDER), contains("已撤回上一条"));
    }

    @Test
    void recall_withoutHistory_saysNothingToRecall() throws Exception {
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_r", null));

        handler.handleAsync(new FeishuInboundMessage(COMMANDER, "oc_chat", "om_3", "evt-r3", "/recall", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, never()).deleteMessage(any());
        verify(api).sendText(eq(COMMANDER), contains("没有可撤回"));
    }

    @Test
    void mentionMode_ignoresGroupMessageWithoutMention() throws Exception {
        FeishuChatHandler mentionOnly = handlerWith(props -> props.getWs().setReplyMode("mention"));

        mentionOnly.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_1", "evt-m1", "闲聊", true))
                .get(5, TimeUnit.SECONDS);

        verify(chatService, never()).chat(any());
        verify(bindingMapper, never()).selectOne(any());
        mentionOnly.destroy();
    }

    @Test
    void probabilityModeZero_neverReplies() throws Exception {
        FeishuChatHandler never = handlerWith(props -> {
            props.getWs().setReplyMode("probability");
            props.getWs().setReplyProbability(0.0);
        });

        never.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_1", "evt-p1", "闲聊", true))
                .get(5, TimeUnit.SECONDS);

        verify(chatService, never()).chat(any());
        never.destroy();
    }

    /** 用改过配置的 props 建一个独立处理器；调用方负责 destroy */
    private FeishuChatHandler handlerWith(java.util.function.Consumer<FeishuProperties> customizer) {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setAllowedUsers(new ArrayList<>(List.of(COMMANDER)));
        props.getWs().setCommandUser(COMMANDER);
        props.getWs().setQuoteReply(false);
        customizer.accept(props);
        return new FeishuChatHandler(chatService, sessionService, agentService, statusPort,
                bindingMapper, api, voiceArtifacts, audioTranscoder, props);
    }

    private FeishuProperties emojiProps() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setAllowedUsers(new ArrayList<>(List.of(COMMANDER)));
        props.getWs().setCommandUser(COMMANDER);
        props.getWs().setQuoteReply(false);
        props.getWs().getEmoji().setEnabled(true);
        props.getWs().getEmoji().setDir(tempDir.toString());
        props.getWs().getEmoji().setIndexFile(tempDir.resolve("emoji-index.json").toString());
        return props;
    }

    private static FeishuSessionBinding binding(long sessionId) {
        FeishuSessionBinding binding = new FeishuSessionBinding();
        binding.setSessionId(sessionId);
        return binding;
    }

    // ---------- 语音回复（voice:<id> flag） ----------

    @Test
    void voiceFlag_sendsAudioAndSkipsText() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(replyWithFlags("这段用语音说", Set.of("voice:v1")));
        when(voiceArtifacts.take("v1")).thenReturn(Optional.of(voiceArtifact("v1", "这段用语音说")));
        when(audioTranscoder.toOpus(any())).thenReturn(Optional.of(
                new FeishuAudioTranscoder.Encoded(new byte[]{4, 5}, 3480)));
        when(api.uploadFile(eq("opus"), eq("voice.opus"), any(byte[].class), eq(3480)))
                .thenReturn(new FeishuApiClient.Upload(true, "file_v1", null));
        when(api.sendAudio("ou_user", "file_v1"))
                .thenReturn(new FeishuApiClient.Result(true, "om_voice", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-v1", "念一下", false))
                .get(5, TimeUnit.SECONDS);

        verify(api).sendAudio("ou_user", "file_v1");
        verify(api, never()).sendText(any(), any());
    }

    @Test
    void voiceWithoutFfmpeg_fallsBackToText() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(replyWithFlags("这段用语音说", Set.of("voice:v1")));
        when(voiceArtifacts.take("v1")).thenReturn(Optional.of(voiceArtifact("v1", "这段用语音说")));
        when(audioTranscoder.toOpus(any())).thenReturn(Optional.empty());
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_t", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-v2", "念一下", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, never()).uploadFile(any(), any(), any(), any());
        verify(api).sendText("ou_user", "这段用语音说");
    }

    @Test
    void voiceUploadFails_fallsBackToText() throws Exception {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding(7L));
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(replyWithFlags("这段用语音说", Set.of("voice:v1")));
        when(voiceArtifacts.take("v1")).thenReturn(Optional.of(voiceArtifact("v1", "这段用语音说")));
        when(audioTranscoder.toOpus(any())).thenReturn(Optional.of(
                new FeishuAudioTranscoder.Encoded(new byte[]{4, 5}, 3480)));
        when(api.uploadFile(any(), any(), any(), any()))
                .thenReturn(new FeishuApiClient.Upload(false, null, "HTTP 403 code=234001：no permission"));
        when(api.sendText(any(), any())).thenReturn(new FeishuApiClient.Result(true, "om_t", null));

        handler.handleAsync(new FeishuInboundMessage("ou_user", "oc_chat", "om_in", "evt-v3", "念一下", false))
                .get(5, TimeUnit.SECONDS);

        verify(api, never()).sendAudio(any(), any());
        verify(api).sendText("ou_user", "这段用语音说");
    }

    /** 语音工件：TTS 产出的形态（mp3 字节 + 原文） */
    private static VoiceArtifactStore.VoiceArtifact voiceArtifact(String id, String text) {
        return new VoiceArtifactStore.VoiceArtifact(id, "turn-1", text, new byte[]{1, 2, 3}, "mp3", 0L);
    }

    private static ChatResponse replyWithFlags(String text, Set<String> flags) {
        return ChatResponse.success("7", false, "g1", text, List.of(), "agent", flags, null);
    }

    private static ChatResponse reply(String text) {
        return ChatResponse.success("7", false, "g1", text, List.of(), "agent", Set.of(), null);
    }
}
