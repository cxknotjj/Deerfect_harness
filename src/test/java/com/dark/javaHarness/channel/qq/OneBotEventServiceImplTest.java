package com.dark.javaHarness.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 限频丢弃、会话绑定复用/新建、FAILED 静默、超长分段。
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
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
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
                .thenReturn(ChatResponse.success("1", false, null, reply));
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
    void handle_groupChat_rateLimitedSecondMessageDropped() {
        // 限频间隔在服务构造时快照：必须先设间隔再重建 service（setUp 默认 0 = 不限频）
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(3L, "qq:group:" + GID + ":" + UID, 5L));
        stubChatSuccess("回");
        service.handle(groupMsg(30L, true, "第一问"));
        service.handle(groupMsg(31L, true, "第二问"));
        verify(chatService, times(1)).chat(any());
    }

    @Test
    void handle_privateChat_rateLimitedSecondMessageDropped() {
        // 私聊同样限频（防陌生人刷 LLM token），与群聊同一间隔口径
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
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
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
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
    void handle_chatTimeout_givesUpSilently() {
        // 超时放弃回复且静默：防 LLM 卡死占满处理线程池
        props.setChatTimeoutSeconds(1);
        when(bindingMapper.selectOne(any())).thenReturn(binding(34L, "qq:private:" + UID, 5L));
        when(chatService.chat(any())).thenAnswer(inv -> {
            Thread.sleep(2500);
            return ChatResponse.success("1", false, null, "迟到的回复");
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
                .thenReturn(new ChatResponse("5", false, null, "FAILED", null, "boom", null, null));
        service.handle(privateMsg(40L, "触发失败"));
        verifyNoInteractions(apiClient);
    }

    @Test
    void handle_longReplySplitByParagraphBoundary() {
        props.getReply().setMaxLength(5);
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("aaa\n\nbbb");
        service.handle(privateMsg(50L, "长回复"));
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), any());
    }

    @Test
    void splitReply_unsplittableParagraphSentWhole() {
        // 无换行无标点：层级切不动 → 原样单条发送（不再字符硬切）
        assertEquals(List.of("12345678"), OneBotEventServiceImpl.splitReply("12345678", 5));
    }

    @Test
    void splitOversizedBlock_descendsLineThenSentence() {
        // 换行层：整块超长 → 按行拆
        assertEquals(List.of("aaa", "bbb"), OneBotEventServiceImpl.splitOversizedBlock("aaa\nbbb", 5));
        // 句子层：无换行但有句末标点 → 按句拆，标点保留在句尾
        assertEquals(List.of("你好。", "世界！"), OneBotEventServiceImpl.splitOversizedBlock("你好。世界！", 5));
        // 切不动：无换行无标点 → 原样（不硬切）
        assertEquals(List.of("12345678"), OneBotEventServiceImpl.splitOversizedBlock("12345678", 5));
        // 未超长原样
        assertEquals(List.of("abc"), OneBotEventServiceImpl.splitOversizedBlock("abc", 5));
    }

    @Test
    void splitProgressive_oversizedParagraphDescendsWithoutHardCut() {
        // 单段超长：句末标点断句，标点留在句尾；两句各自成条（即使单句仍超 max 也不硬切）
        assertEquals(List.of("今天天气真好。", "明天会更好！"),
                OneBotEventServiceImpl.splitProgressive("今天天气真好。明天会更好！", 5, 0));
        // 无标点切不动 → 整块单条
        assertEquals(List.of("12345678"), OneBotEventServiceImpl.splitProgressive("12345678", 5, 0));
    }

    @Test
    void splitProgressive_fineGranularity_descendsAllBoundaries() {
        // 40 字粒度（贴近生产配置）：无空行的多行多句回答也按 \n / 。！？逐条
        String reply = "今天天气真不错呀，适合出门玩耍。\n下午我们要一起去公园吗？\n晚上记得早点回来吃饭！";
        assertEquals(List.of("今天天气真不错呀，适合出门玩耍。", "下午我们要一起去公园吗？", "晚上记得早点回来吃饭！"),
                OneBotEventServiceImpl.splitProgressive(reply, 20, 0));
        // 单句 15 字 ≤ 粒度阈值 → 原样单条
        assertEquals(List.of("这是一句没有换行的短话。"), OneBotEventServiceImpl.splitProgressive("这是一句没有换行的短话。", 20, 0));
        // 无空行无换行但超阈值 → 按句末标点切
        assertEquals(List.of("先说第一句话。", "然后是第二句话，带个逗号但不影响。"),
                OneBotEventServiceImpl.splitProgressive("先说第一句话。然后是第二句话，带个逗号但不影响。", 10, 0));
    }

    @Test
    void splitReply_noLimitWhenZero() {
        assertEquals(List.of("一整段不切"), OneBotEventServiceImpl.splitReply("一整段不切", 0));
        assertTrue(OneBotEventServiceImpl.splitReply(null, 100).get(0).isEmpty());
    }

    @Test
    void handle_handlesServiceExceptionWithoutThrowing() {
        when(bindingMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        service.handle(privateMsg(60L, "任何异常都不外抛"));
        verify(chatService, never()).chat(any());
    }

    @Test
    void splitProgressive_multiParagraph_alwaysSplitsByParagraph() {
        // 渐进拆分不设总长门槛：正常长度的多段回答也按段逐条（区别于 splitReply 的超长门槛）
        assertEquals(List.of("第一段", "第二段", "第三段"),
                OneBotEventServiceImpl.splitProgressive("第一段\n\n第二段\n\n第三段", 3000, 4));
        // 单段回答 = 单条（与关闭渐进一致）
        assertEquals(List.of("只有一段"), OneBotEventServiceImpl.splitProgressive("只有一段", 3000, 4));
        // 超过 maxChunks：尾部段落合并为最后一条
        assertEquals(List.of("一", "二", "三\n\n四\n\n五"),
                OneBotEventServiceImpl.splitProgressive("一\n\n二\n\n三\n\n四\n\n五", 3000, 3));
        // maxChunks = 0 不限制
        assertEquals(List.of("一", "二", "三"),
                OneBotEventServiceImpl.splitProgressive("一\n\n二\n\n三", 3000, 0));
    }

    @Test
    void handle_progressiveMultiParagraph_sendsChunksWithDelayBetween() {
        props.getReply().setProgressive(true);
        props.getReply().setInterChunkDelayMs(500); // 动态延迟基础值
        props.getReply().setDelayFactor(0.1);       // 0.1 秒/字
        props.getReply().setJitterRangeMs(0);
        props.getReply().setMaxChunks(4);
        List<Long> pauses = new ArrayList<>();
        service.chunkPause = pauses::add; // 注入记录器避免真 sleep
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。\n\n第二段。\n\n第三段。");
        service.handle(privateMsg(70L, "问"));
        verify(apiClient, times(3)).sendPrivateMsg(eq(UID), any());
        // 首条不等待；条间动态延迟 = 基础 500ms + 字数×0.1s（每条 4 字 → 900ms，无扰动）
        assertEquals(List.of(900L, 900L), pauses);
    }

    @Test
    void handle_progressiveDisabled_singleMessageNoDelay() {
        // 关闭渐进 = 原行为：多段短回答整包一条发送，零等待（动态延迟只在渐进路径生效）
        props.getReply().setProgressive(false);
        props.getReply().setInterChunkDelayMs(1200);
        props.getReply().setDelayFactor(0.1);
        props.getReply().setJitterRangeMs(300);
        List<Long> pauses = new ArrayList<>();
        service.chunkPause = pauses::add;
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。\n\n第二段。");
        service.handle(privateMsg(71L, "问"));
        verify(apiClient, times(1)).sendPrivateMsg(eq(UID), any());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void handle_progressive_dynamicDelayJitterStaysInFormulaRange() {
        // 扰动 ±300ms：4 字条延迟 = 500 + 400 ± 300 → 每次等待都应落在 [600, 1200]
        props.getReply().setProgressive(true);
        props.getReply().setInterChunkDelayMs(500);
        props.getReply().setDelayFactor(0.1);
        props.getReply().setJitterRangeMs(300);
        List<Long> pauses = new ArrayList<>();
        service.chunkPause = pauses::add;
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。\n\n第二段。\n\n第三段。");
        service.handle(privateMsg(72L, "问"));
        assertEquals(2, pauses.size());
        for (long p : pauses) {
            assertTrue(p >= 600 && p <= 1200, "延迟应落在公式区间内: " + p);
        }
    }

    @Test
    void handle_progressive_dynamicDelayCappedByMaxDelayMs() {
        // 超长无标点块（层级切不动原样成片，2000 字）：字数延迟巨大 → 被 max-delay-ms 截断，防分钟级停顿
        props.getReply().setProgressive(true);
        props.getReply().setInterChunkDelayMs(500);
        props.getReply().setDelayFactor(0.1);
        props.getReply().setJitterRangeMs(0);
        props.getReply().setMaxDelayMs(1000);
        List<Long> pauses = new ArrayList<>();
        service.chunkPause = pauses::add;
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("短。\n\n" + "长".repeat(2000));
        service.handle(privateMsg(73L, "问"));
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), any());
        assertEquals(List.of(1000L), pauses);
    }

    // ==================== 表情包发送钩子（napcat.emoji） ====================

    /**
     * 开启表情模块：写真实图片与映射 JSON、配置 napcat.emoji 后重建 service
     * （EmojiReplies 在构造时加载映射，必须先配置再建 service）。
     */
    private void enableEmoji(int maxPerReply, double probability) throws IOException {
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
        props.getEmoji().setMaxPerReply(maxPerReply);
        props.getEmoji().setProbability(probability);
        props.getEmoji().setSendDelayMs(800);
        props.getEmoji().setCheerEmoji("happy_01");
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
    }

    /** 渐进条间零等待（聚焦表情断言，动态延迟另有 4 用例覆盖） */
    private void disableHumanDelays() {
        props.getReply().setProgressive(true);
        props.getReply().setInterChunkDelayMs(0);
        props.getReply().setDelayFactor(0);
        props.getReply().setJitterRangeMs(0);
        service.chunkPause = millis -> true;
    }

    @Test
    void handle_progressive_emojiSentAfterHittingChunkInOrder() throws IOException {
        // 动态延迟保留公式值，用于断言「表情前停顿 = send-delay-ms」被记录在正确位置
        props.getReply().setProgressive(true);
        props.getReply().setInterChunkDelayMs(500);
        props.getReply().setDelayFactor(0.1);
        props.getReply().setJitterRangeMs(0);
        enableEmoji(0, 1.0);
        List<Long> pauses = new ArrayList<>();
        service.chunkPause = pauses::add; // 必须在重建 service 之后注入（构造会重置为真 sleep 实现）
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。\n\n太激动了！\n\n第三段。");
        service.handle(privateMsg(80L, "问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(4)).sendPrivateMsg(eq(UID), captor.capture());
        List<List<MessageSegment>> calls = captor.getAllValues();
        // 发送顺序：chunk1 → chunk2 → 表情 → chunk3（第 2 条含 tag「激动」）
        assertEquals("text", calls.get(0).get(0).type());
        assertEquals("第一段。", calls.get(0).get(0).text());
        assertEquals("text", calls.get(1).get(0).type());
        assertEquals("太激动了！", calls.get(1).get(0).text());
        assertEquals("image", calls.get(2).get(0).type());
        String file = String.valueOf(calls.get(2).get(0).data().get("file"));
        assertEquals("base64://" + Base64.getEncoder().encodeToString("gif".getBytes(StandardCharsets.UTF_8)),
                file);
        assertEquals("text", calls.get(3).get(0).type());
        assertEquals("第三段。", calls.get(3).get(0).text());
        // 停顿顺序：chunk2 前动态延迟（5 字 → 1000）→ 表情前 send-delay（800）→ chunk3 前动态延迟（4 字 → 900）
        assertEquals(List.of(1000L, 800L, 900L), pauses);
    }

    @Test
    void handle_progressive_emojiLimitedByMaxPerReply() throws IOException {
        disableHumanDelays();
        enableEmoji(1, 1.0);
        when(bindingMapper.selectOne(any())).thenReturn(binding(6L, "qq:private:" + UID, 5L));
        // 3 条 chunk 全部命中 tag，但 max-per-reply=1 → 仅第 1 个命中的 chunk 后发一张
        stubChatSuccess("好开心呀。\n\n好难过呀。\n\n在吗在吗。");
        service.handle(privateMsg(81L, "问"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(4)).sendPrivateMsg(eq(UID), captor.capture());
        List<List<MessageSegment>> calls = captor.getAllValues();
        // 顺序：chunk1 → 表情（限流内第 1 张）→ chunk2 → chunk3（后两个命中被限流跳过）
        assertEquals(List.of("text", "image", "text", "text"),
                calls.stream().map(l -> l.get(0).type()).toList());
    }

    @Test
    void handle_emojiProbabilityZero_neverSent() throws IOException {
        disableHumanDelays();
        enableEmoji(0, 0.0);
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
        disableHumanDelays();
        enableEmoji(0, 1.0);
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
        disableHumanDelays();
        enableEmoji(0, 1.0);
        // 表情模块整体异常：钩子吞掉，文本 chunk 照常全部发出
        service.emojiReplies = new EmojiReplies(props) {
            @Override
            public MessageSegment pickFor(String chunkText) {
                throw new IllegalStateException("表情模块炸了");
            }
        };
        when(bindingMapper.selectOne(any())).thenReturn(binding(8L, "qq:private:" + UID, 5L));
        stubChatSuccess("第一段。\n\n第二段。");
        service.handle(privateMsg(83L, "问"));
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), any());
    }
}
