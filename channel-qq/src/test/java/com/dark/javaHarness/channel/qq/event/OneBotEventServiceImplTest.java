package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.channel.qq.client.NapCatApiClient;
import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.channel.qq.reply.EmojiReplies;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.channel.qq.persistence.OneBotSessionBinding;
import com.dark.javaHarness.channel.qq.persistence.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OneBotEventServiceImpl 单测：自消息过滤、群聊 @ 触发（message 数组为准）、
 * 限频丢弃、会话绑定复用/新建、FAILED 静默、超长截断（整段单条发送）。
 */
@ExtendWith(MockitoExtension.class)
class OneBotEventServiceImplTest {

    private static final long SELF_ID = 3896564418L;
    private static final long UID = 10001L;
    private static final long GID = 88L;

    @Mock
    private ChatService chatService;
    @Mock
    private SessionService sessionService;
    @Mock
    private AgentService agentService;
    @Mock
    private OneBotSessionBindingMapper bindingMapper;
    @Mock
    private NapCatApiClient apiClient;

    @TempDir
    Path tempDir;

    private NapCatProperties props;
    private OneBotEventServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new NapCatProperties();
        props.setEnabled(true);
        props.setSelfId(String.valueOf(SELF_ID));
        props.getGroupTrigger().setMode("at");
        props.getGroupTrigger().setPrefix("/ai");
        props.getRateLimit().setPerUserSeconds(0);
        props.getReply().setMaxLength(3000);
        service = new OneBotEventServiceImpl(chatService, sessionService, agentService, bindingMapper, apiClient, props);
    }

    private static OneBotEvent privateMsg(long messageId, String text) {
        return privateMsg(UID, messageId, text);
    }

    private static OneBotEvent privateMsg(long uid, long messageId, String text) {
        return new OneBotEvent(1700000000L, SELF_ID, "message", "private", uid, null, messageId, text,
                List.of(new MessageSegment("text", Map.of("text", text))),
                new OneBotEvent.Sender(uid, "tester", null));
    }

    private static OneBotEvent groupMsg(long messageId, boolean withAt, String text) {
        List<MessageSegment> segments = new java.util.ArrayList<>();
        if (withAt) {
            segments.add(new MessageSegment("at", Map.of("qq", String.valueOf(SELF_ID))));
        }
        segments.add(new MessageSegment("text", Map.of("text", text)));
        return new OneBotEvent(1700000000L, SELF_ID, "message", "group", UID, GID, messageId, text,
                segments, new OneBotEvent.Sender(UID, "tester", "群名片"));
    }

    private static OneBotEvent groupReplyMsg(long messageId, long replyTarget, String text) {
        List<MessageSegment> segments = new java.util.ArrayList<>();
        segments.add(new MessageSegment("reply", Map.of("id", String.valueOf(replyTarget))));
        segments.add(new MessageSegment("text", Map.of("text", text)));
        return new OneBotEvent(1700000000L, SELF_ID, "message", "group", UID, GID, messageId, text,
                segments, new OneBotEvent.Sender(UID, "tester", "群名片"));
    }

    private static OneBotSessionBinding binding(long id, String key, long sessionId) {
        OneBotSessionBinding row = new OneBotSessionBinding();
        row.setId(id);
        row.setSessionKey(key);
        row.setSessionId(sessionId);
        row.setQqUserId(String.valueOf(UID));
        return row;
    }

    private void stubChatSuccess(String reply) {
        when(chatService.chat(any()))
                .thenReturn(ChatResponse.success("1", false, null, reply, null, null,
                        java.util.Set.of(), null));
    }

    @Test
    void handle_selfMessageIgnored() {
        OneBotEvent self = new OneBotEvent(1700000000L, SELF_ID, "message", "private",
                SELF_ID, null, 1L, "hi", List.of(new MessageSegment("text", Map.of("text", "hi"))), null);
        service.handle(self);
        verifyNoInteractions(chatService, sessionService, bindingMapper, apiClient);
    }

    @Test
    void handle_privateChat_reusesBinding() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(1L, "qq:private:" + UID, 5L));
        stubChatSuccess("你好");
        service.handle(privateMsg(10L, "你好，介绍一下自己"));
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertEquals("你好，介绍一下自己", captor.getValue().message());
        assertEquals("5", captor.getValue().sessionId());
        verify(bindingMapper, never()).insert(any(OneBotSessionBinding.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), segCaptor.capture());
        assertEquals("你好", segCaptor.getValue().get(0).text());
    }

    @Test
    void handle_privateChat_createsBindingOnFirstSeen() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(sessionService.createSession("qq:" + UID, "在吗")).thenReturn("7");
        stubChatSuccess("在");
        service.handle(privateMsg(11L, "在吗"));
        ArgumentCaptor<OneBotSessionBinding> rowCaptor = ArgumentCaptor.forClass(OneBotSessionBinding.class);
        verify(bindingMapper).insert(rowCaptor.capture());
        assertEquals(7L, rowCaptor.getValue().getSessionId());
        assertEquals("qq:private:" + UID, rowCaptor.getValue().getSessionKey());
        assertEquals(String.valueOf(UID), rowCaptor.getValue().getQqUserId());
        verify(chatService).chat(new ChatRequest("在吗", "7", null));
    }

    @Test
    void handle_privateChat_firstTimeBindsConfiguredAgent() {
        // 首次建档那条下发配置 Agent（core 据此把 agent_id 绑到新会话）
        props.setAgentId(10L);
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(sessionService.createSession("qq:" + UID, "你好")).thenReturn("7");
        stubChatSuccess("你好呀");
        service.handle(privateMsg(12L, "你好"));
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertEquals(10L, captor.getValue().agentId(), "首次建档应下发配置 Agent");
    }

    @Test
    void handle_privateChat_reusedBindingDoesNotResendAgent() {
        // 复用旧绑定：不下发 agentId，尊重会话已绑定的 Agent（否则 core 会覆盖回配置值，/agent 切换失效）
        props.setAgentId(10L);
        when(bindingMapper.selectOne(any())).thenReturn(binding(13L, "qq:private:" + UID, 5L));
        stubChatSuccess("嗯");
        service.handle(privateMsg(13L, "再说一句"));
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertNull(captor.getValue().agentId(), "复用绑定时不应下发 Agent");
    }

    @Test
    void handle_afterAgentSwitch_nextMessageKeepsSessionBinding() {
        // /agent 切换后再来消息仍不下发 agentId：使切换持久生效（不被配置值静默回退）
        props.setAgentId(10L);
        when(bindingMapper.selectOne(any())).thenReturn(binding(14L, "qq:private:" + UID, 5L));
        when(agentService.agentItems()).thenReturn(List.of(
                new com.dark.javaHarness.domain.dto.AgentItemView(1L, "general"),
                new com.dark.javaHarness.domain.dto.AgentItemView(2L, "writer")));
        stubChatSuccess("嗯");
        service.handle(privateMsg(14L, "/agent writer"));
        service.handle(privateMsg(15L, "切换后继续聊"));
        verify(sessionService).switchAgent("5", 2L);
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertNull(captor.getValue().agentId(), "切换后的下一轮消息不应回退到配置 Agent");
    }

    @Test
    void handle_groupChat_requiresAtSegment() {
        service.handle(groupMsg(20L, false, "没人理我"));
        verifyNoInteractions(chatService, apiClient);
    }

    @Test
    void handle_groupChat_repliesWithQuote() {
        props.getRateLimit().setPerUserSeconds(0);
        when(bindingMapper.selectOne(any())).thenReturn(binding(2L, "qq:group:" + GID + ":" + UID, 9L));
        stubChatSuccess("群答");
        service.handle(groupMsg(21L, true, "群友提问"));
        verify(chatService).chat(new ChatRequest("群友提问", "9", null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendGroupMsg(eq(GID), segCaptor.capture());
        List<MessageSegment> segments = segCaptor.getValue();
        assertEquals("reply", segments.get(0).type(), "群聊首条带 reply 段引用原消息");
        assertEquals(21L, segments.get(0).messageId());
        assertEquals("群答", segments.get(1).text());
    }

    @Test
    void handle_groupReply_atAskerSegmentPrepended() {
        props.getReply().setAtAsker(true);
        when(bindingMapper.selectOne(any())).thenReturn(binding(70L, "qq:group:" + GID + ":" + UID, 9L));
        stubChatSuccess("群答");
        service.handle(groupMsg(700L, true, "群问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendGroupMsg(eq(GID), captor.capture());
        List<MessageSegment> segs = captor.getValue();
        assertEquals("reply", segs.get(0).type());
        assertEquals("at", segs.get(1).type(), "reply 段之后、text 段之前插入 at");
        assertEquals(String.valueOf(UID), segs.get(1).qq());
        assertEquals("text", segs.get(2).type());
    }

    @Test
    void handle_groupReply_atAskerOff_noAtSegment() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(71L, "qq:group:" + GID + ":" + UID, 9L));
        stubChatSuccess("群答");
        service.handle(groupMsg(701L, true, "群问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendGroupMsg(eq(GID), captor.capture());
        assertTrue(captor.getValue().stream().noneMatch(s -> "at".equals(s.type())));
    }

    @Test
    void handle_groupChat_rateLimitedSecondMessageDropped() {
        // 限频间隔在服务构造时快照：必须先设间隔再重建 service（setUp 默认 0 = 不限频）
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, agentService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(3L, "qq:group:" + GID + ":" + UID, 5L));
        stubChatSuccess("回");
        service.handle(groupMsg(30L, true, "第一问"));
        service.handle(groupMsg(31L, true, "第二问"));
        verify(chatService, times(1)).chat(any());
    }

    @Test
    void handle_groupReplyToBot_triggersWithoutAt() {
        props.getGroupTrigger().setReplyToBot(true);
        service.sentMessageTracker.record("group:" + GID, 500L);
        when(bindingMapper.selectOne(any())).thenReturn(binding(40L, "qq:group:" + GID + ":" + UID, 9L));
        stubChatSuccess("收到");
        service.handle(groupReplyMsg(600L, 500L, "接着刚才的说"));
        verify(chatService).chat(new ChatRequest("接着刚才的说", "9", null));
    }

    @Test
    void handle_groupReplyToBot_disabled_ignored() {
        // 默认 reply-to-bot=false：回复机器人消息不触发
        service.handle(groupReplyMsg(601L, 500L, "有人吗"));
        verifyNoInteractions(chatService, apiClient);
    }

    @Test
    void handle_groupReplyToUnknownMessage_ignored() {
        props.getGroupTrigger().setReplyToBot(true);
        // 回复历史里非我方的消息（未命中 tracker）不触发
        service.handle(groupReplyMsg(602L, 424242L, "路人回复"));
        verifyNoInteractions(chatService, apiClient);
    }

    @Test
    void handle_group_sentMessageIdRecorded() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(41L, "qq:group:" + GID + ":" + UID, 9L));
        when(apiClient.sendGroupMsg(eq(GID), any())).thenReturn(777L);
        stubChatSuccess("群答");
        service.handle(groupMsg(21L, true, "群友提问"));
        assertTrue(service.sentMessageTracker.contains(777L));
    }

    @Test
    void handle_privateChat_rateLimitedSecondMessageDropped() {
        // 私聊同样限频（防陌生人刷 LLM token），与群聊同一间隔口径
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, agentService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(31L, "qq:private:" + UID, 5L));
        stubChatSuccess("回");
        service.handle(privateMsg(300L, "第一问"));
        service.handle(privateMsg(301L, "第二问"));
        verify(chatService, times(1)).chat(any());
    }

    @Test
    void handle_privateChat_allowlistBlocksStranger() {
        // 白名单非空时名单外私聊直接丢弃（名单内用户不受影响）
        props.setPrivateAllowUsers("999");
        service = new OneBotEventServiceImpl(chatService, sessionService, agentService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(32L, "qq:private:999", 5L));
        stubChatSuccess("主人好");
        service.handle(privateMsg(UID, 310L, "陌生人私聊"));
        service.handle(privateMsg(999L, 311L, "白名单用户私聊"));
        verify(chatService, times(1)).chat(any());
        verify(apiClient, never()).sendPrivateMsg(eq(UID), any());
    }

    @Test
    void handle_resetCommand_clearsBindingAndRepliesWithoutChat() {
        service.handle(privateMsg(320L, "/reset"));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OneBotSessionBinding>> wc =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(bindingMapper).delete(wc.capture());
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), segCaptor.capture());
        assertTrue(segCaptor.getValue().get(0).text().contains("新对话"));
    }

    @Test
    void handle_helpCommand_listsCommandsWithoutChat() {
        service.handle(privateMsg(400L, "/help"));
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("/history"));
        assertTrue(captor.getValue().get(0).text().contains("/recall"), "帮助文案应列出 /recall");
    }

    @Test
    void handle_newCommand_clearsBindingLikeReset() {
        service.handle(privateMsg(401L, "/new"));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OneBotSessionBinding>> wc =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(bindingMapper).delete(wc.capture());
        verify(chatService, never()).chat(any());
    }

    @Test
    void handle_historyCommand_repliesWithRecentMessages() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(50L, "qq:private:" + UID, 5L));
        when(sessionService.listMessages("5")).thenReturn(List.of(
                new com.dark.javaHarness.domain.dto.SessionMessagesView.Item("user", "你好", 1L),
                new com.dark.javaHarness.domain.dto.SessionMessagesView.Item("assistant", "你好呀", 2L)));
        service.handle(privateMsg(500L, "/history"));
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), captor.capture());
        String text = captor.getValue().get(0).text();
        assertTrue(text.contains("你：") && text.contains("我："), text);
    }

    @Test
    void handle_historyCommand_noSessionRepliesHint() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        service.handle(privateMsg(501L, "/history"));
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("聊天记录"));
    }

    @Test
    void handle_agentCommand_listsAgents() {
        when(agentService.agentItems()).thenReturn(List.of(
                new com.dark.javaHarness.domain.dto.AgentItemView(1L, "general"),
                new com.dark.javaHarness.domain.dto.AgentItemView(2L, "writer")));
        service.handle(privateMsg(600L, "/agent"));
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), captor.capture());
        String text = captor.getValue().get(0).text();
        assertTrue(text.contains("general") && text.contains("writer"), text);
    }

    @Test
    void handle_agentCommand_switchesByName() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(60L, "qq:private:" + UID, 5L));
        when(agentService.agentItems()).thenReturn(List.of(
                new com.dark.javaHarness.domain.dto.AgentItemView(1L, "general"),
                new com.dark.javaHarness.domain.dto.AgentItemView(2L, "writer")));
        service.handle(privateMsg(601L, "/agent writer"));
        verify(sessionService).switchAgent("5", 2L);
        verify(chatService, never()).chat(any());
    }

    @Test
    void handle_agentCommand_unknownNameDoesNotSwitch() {
        // 绑定查询在此分支不会被触发（未知名称先于绑定解析返回），用 lenient 保留「已有绑定也不切换」的场景
        lenient().when(bindingMapper.selectOne(any())).thenReturn(binding(61L, "qq:private:" + UID, 5L));
        when(agentService.agentItems()).thenReturn(List.of(
                new com.dark.javaHarness.domain.dto.AgentItemView(1L, "general")));
        service.handle(privateMsg(602L, "/agent 不存在"));
        verify(sessionService, never()).switchAgent(any(), anyLong());
    }

    @Test
    void handle_unknownSlashCommand_fallsThroughToChat() {
        // 未识别指令不拦截：仍走正常聊天
        when(bindingMapper.selectOne(any())).thenReturn(binding(42L, "qq:private:" + UID, 5L));
        stubChatSuccess("嗯");
        service.handle(privateMsg(402L, "/foo bar"));
        verify(chatService).chat(new ChatRequest("/foo bar", "5", null));
    }

    @Test
    void handle_chatTimeout_givesUpSilently() {
        // 超时放弃回复且静默：防 LLM 卡死占满处理线程池
        props.setChatTimeoutSeconds(1);
        when(bindingMapper.selectOne(any())).thenReturn(binding(34L, "qq:private:" + UID, 5L));
        when(chatService.chat(any())).thenAnswer(inv -> {
            Thread.sleep(2500);
            return ChatResponse.success("1", false, null, "迟到的回复", null, null,
                    java.util.Set.of(), null);
        });
        long start = System.currentTimeMillis();
        service.handle(privateMsg(330L, "慢问题"));
        assertTrue(System.currentTimeMillis() - start < 2000, "超时后应尽快返回而不是等聊天结束");
        verifyNoInteractions(apiClient);
    }

    @Test
    void handle_chatFailed_staysSilent() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(4L, "qq:private:" + UID, 5L));
        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("5", false, null, "FAILED", null, "boom", null, null,
                        java.util.Set.of(), null));
        service.handle(privateMsg(40L, "触发失败"));
        verifyNoInteractions(apiClient);
    }

    @Test
    void handle_overlongReplyTruncatedSingleMessage() {
        // 超长回复：整段单条发送且被截断（trim 后取前 max 字 + …），不再分段
        props.getReply().setMaxLength(5);
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("aaa\n\nbbb");
        service.handle(privateMsg(50L, "长回复"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(1)).sendPrivateMsg(eq(UID), captor.capture());
        String text = captor.getValue().get(0).text();
        assertEquals("aaa\n\n…", text);
        assertTrue(text.endsWith("…"), text);
    }

    @Test
    void handle_handlesServiceExceptionWithoutThrowing() {
        when(bindingMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        service.handle(privateMsg(60L, "任何异常都不外抛"));
        verify(chatService, never()).chat(any());
    }

    // ==================== 表情包发送钩子（napcat.emoji） ====================

    /**
     * 开启表情模块：写真实图片与映射 JSON、配置 napcat.emoji 后重建 service
     * （EmojiReplies 在构造时加载映射，必须先配置再建 service）；send-delay-ms=0 免停顿。
     */
    private void enableEmoji(double probability) throws IOException {
        Path emojis = Files.createDirectories(tempDir.resolve("emojis"));
        Files.writeString(emojis.resolve("开心转圈.gif"), "gif");
        Files.writeString(emojis.resolve("流泪猫猫头.png"), "png");
        Files.writeString(emojis.resolve("戳一戳.gif"), "gif");
        Path index = tempDir.resolve("emoji-index.json");
        Files.writeString(index, """
                {
                  "happy_01": { "path": "开心转圈.gif", "tags": ["开心", "激动", "好耶", "太棒了"] },
                  "sad_02": { "path": "流泪猫猫头.png", "tags": ["伤心", "委屈", "难过", "呜呜"] },
                  "poke_04": { "path": "戳一戳.gif", "tags": ["在吗", "理我", "戳", "出来"] }
                }
                """, StandardCharsets.UTF_8);
        props.getEmoji().setEnabled(true);
        props.getEmoji().setDir(emojis.toString());
        props.getEmoji().setIndexFile(index.toString());
        props.getEmoji().setProbability(probability);
        props.getEmoji().setSendDelayMs(0);
        props.getEmoji().setCheerEmoji("happy_01");
        service = new OneBotEventServiceImpl(chatService, sessionService, agentService, bindingMapper, apiClient, props);
    }

    @Test
    void handle_emojiSentAfterTextInOrder() throws IOException {
        enableEmoji(1.0);
        service.pauseHook = millis -> true; // 注入免真 sleep（send-delay-ms=0，仍会调用钩子）
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("太激动了！");
        service.handle(privateMsg(80L, "问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), captor.capture());
        List<List<MessageSegment>> calls = captor.getAllValues();
        // 发送顺序：整段 text → image（命中 tag「激动」）
        assertEquals("text", calls.get(0).get(0).type());
        assertEquals("太激动了！", calls.get(0).get(0).text());
        assertEquals("image", calls.get(1).get(0).type());
        String file = String.valueOf(calls.get(1).get(0).data().get("file"));
        assertEquals("base64://" + Base64.getEncoder().encodeToString("gif".getBytes(StandardCharsets.UTF_8)),
                file);
    }

    @Test
    void handle_emojiProbabilityZero_neverSent() throws IOException {
        enableEmoji(0.0);
        when(bindingMapper.selectOne(any())).thenReturn(binding(7L, "qq:private:" + UID, 5L));
        stubChatSuccess("好开心呀。");
        service.handle(privateMsg(82L, "问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(1)).sendPrivateMsg(eq(UID), captor.capture());
        assertEquals("text", captor.getValue().get(0).type());
    }

    @Test
    void handle_groupChat_emojiSentViaGroupApi() throws IOException {
        enableEmoji(1.0);
        service.pauseHook = millis -> true;
        when(bindingMapper.selectOne(any())).thenReturn(binding(9L, "qq:group:" + GID + ":" + UID, 5L));
        stubChatSuccess("好开心呀。");
        service.handle(groupMsg(90L, true, "群问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(2)).sendGroupMsg(eq(GID), captor.capture());
        // 首条：reply 引用 + text；第二条：image（与文本同走群聊 API）
        assertEquals("reply", captor.getAllValues().get(0).get(0).type());
        assertEquals("text", captor.getAllValues().get(0).get(1).type());
        assertEquals("image", captor.getAllValues().get(1).get(0).type());
        verify(apiClient, never()).sendPrivateMsg(anyLong(), any());
    }

    @Test
    void handle_emojiHookFailure_doesNotAffectTextSending() throws IOException {
        enableEmoji(1.0);
        // 表情模块整体异常：钩子吞掉，文本照常发出（整段单条）
        service.emojiReplies = new EmojiReplies(props) {
            @Override
            public MessageSegment pickFor(String chunkText) {
                throw new IllegalStateException("表情模块炸了");
            }
        };
        when(bindingMapper.selectOne(any())).thenReturn(binding(8L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。");
        service.handle(privateMsg(83L, "问"));
        verify(apiClient, times(1)).sendPrivateMsg(eq(UID), any());
    }

    // ==================== /recall 撤回（napcat.recall 配置已随分段移除） ====================

    @Test
    void handle_recallCommand_deletesLastSentMessage() {
        service.sentMessageTracker.record("private:" + UID, 222L);
        service.handle(privateMsg(800L, "/recall"));
        verify(apiClient).deleteMsg(222L);
        verify(chatService, never()).chat(any());
    }

    @Test
    void handle_recallCommand_nothingToRecall() {
        service.handle(privateMsg(801L, "/recall"));
        verify(apiClient, never()).deleteMsg(anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("撤回"));
    }

    @Test
    void handle_recallCommand_secondRecallHasNothingLeft() {
        // 撤回成功后应从 tracker 移除该 id：连续第二次不再对同一 id delete，改回「没有可撤回的消息。」
        // （回复发送桩为 null：避免「已撤回。」这条确认消息自身被记为新 id 而污染场景）
        when(apiClient.sendPrivateMsg(eq(UID), any())).thenReturn((Long) null);
        service.sentMessageTracker.record("private:" + UID, 223L);
        service.handle(privateMsg(810L, "/recall"));
        service.handle(privateMsg(811L, "/recall"));
        verify(apiClient, times(1)).deleteMsg(223L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), captor.capture());
        assertTrue(captor.getAllValues().get(0).get(0).text().contains("已撤回"));
        assertTrue(captor.getAllValues().get(1).get(0).text().contains("没有可撤回"));
    }
}
