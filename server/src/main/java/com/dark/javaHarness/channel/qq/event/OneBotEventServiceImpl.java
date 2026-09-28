package com.dark.javaHarness.channel.qq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上报事件处理实现：过滤 → 白名单/触发/限频 → 会话绑定 → 直调 ChatService → 分段回复。
 *
 * <p>会话键：私聊 {@code qq:private:<uid>}，群聊 {@code qq:group:<gid>:<uid>}
 * （群内按用户独立上下文）；binding 命中复用 harness 会话（多轮记忆跨消息延续），
 * 未命中经 {@code SessionService.createSession(creator=qq:<uid>, 首问)} 建档落库。
 * {@code /reset} 指令删除绑定行，下条消息开启新会话（多轮记忆清空）。
 *
 * <p>失败语义：任何异常/FAILED/超时只记日志不回复（渠道侧静默），不打断主流程、
 * 不向 QQ 侧泄错误细节；聊天超时中断底层调用并放弃回复（防僵尸任务占满聊天池），
 * 队列满背压丢弃本条消息。
 */
public class OneBotEventServiceImpl implements OneBotEventService {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventServiceImpl.class);

    /** 聊天执行池线程名前缀序号 */
    private static final AtomicLong CHAT_THREAD_SEQ = new AtomicLong();

    /** 聊天执行池队列上限：满即拒绝（背压丢弃单条消息，与限频丢弃同语义），防无界堆积 */
    private static final int CHAT_QUEUE_CAPACITY = 100;

    private final ChatService chatService;
    private final SessionService sessionService;
    private final OneBotSessionBindingMapper bindingMapper;
    private final NapCatApiClient apiClient;
    private final NapCatProperties props;
    private final UserRateLimiter rateLimiter;
    private final Set<Long> privateAllowUsers;
    /** 入站文本提取与触发判定（超长类拆分 2026-09-25 拆出） */
    private final EventTextParser textParser;
    /** 聊天执行池：有界队列 + Abort 拒绝 + @PreDestroy 关闭（优化审查 2026-09-25 收口项） */
    private final ThreadPoolExecutor chatExecutor;

    /** 表情包匹配器（构造内创建，签名不变；包内可见：测试可替换注入异常桩） */
    EmojiReplies emojiReplies;

    public OneBotEventServiceImpl(ChatService chatService,
                                  SessionService sessionService,
                                  OneBotSessionBindingMapper bindingMapper,
                                  NapCatApiClient apiClient,
                                  NapCatProperties props) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.bindingMapper = bindingMapper;
        this.apiClient = apiClient;
        this.props = props;
        this.emojiReplies = new EmojiReplies(props);
        this.textParser = new EventTextParser(props);
        this.rateLimiter = new UserRateLimiter(props.getRateLimit().getPerUserSeconds() * 1000L);
        this.privateAllowUsers = parseAllowUsers(props.getPrivateAllowUsers());
        this.chatExecutor = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(CHAT_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "onebot-chat-" + CHAT_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 优雅关闭聊天执行池：中断在跑任务（对齐 cancel(true) 语义），避免拖慢 JVM 退出 */
    @PreDestroy
    void shutdownChatExecutor() {
        chatExecutor.shutdownNow();
    }

    @Override
    public void handle(OneBotEvent event) {
        try {
            doHandle(event);
        } catch (Exception e) {
            log.error("[napcat] 事件处理失败（messageId={}）", event.messageId(), e);
        }
    }

    private void doHandle(OneBotEvent event) {
        // 自消息过滤（防自循环；reportSelfMessage=false 为另一道保险，缺一不可）
        if (Objects.equals(event.userId(), selfIdAsLong())) {
            return;
        }
        boolean isGroup = "group".equals(event.messageType());
        // 私聊白名单：名单非空时名单外用户直接丢弃（防陌生人刷 LLM token）
        if (!isGroup && !privateAllowUsers.isEmpty() && !privateAllowUsers.contains(event.userId())) {
            log.debug("[napcat] 私聊白名单外丢弃 uid={}", event.userId());
            return;
        }
        String text = isGroup ? textParser.extractGroupText(event) : textParser.extractPrivateText(event);
        if (text == null || text.isBlank()) {
            return;
        }
        // 同用户限频（群聊防风控 + 私聊防刷）：间隔内的消息直接丢弃
        if (!rateLimiter.tryAcquire(event.userId())) {
            log.debug("[napcat] 限频丢弃 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        // /reset 指令：删除会话绑定（下条消息开启新会话），不消耗 LLM
        if ("/reset".equalsIgnoreCase(text)) {
            resetSession(event);
            return;
        }
        Long sessionId = bindSession(event, text);
        ChatResponse resp;
        try {
            resp = chatWithTimeout(event, text, sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (resp == null) {
            // 超时/执行异常：chatWithTimeout 内已记日志，静默返回
            return;
        }
        if (!"SUCCEEDED".equals(resp.status()) || resp.reply() == null || resp.reply().isBlank()) {
            log.warn("[napcat] 聊天未产出回复（status={}，messageId={}）", resp.status(), event.messageId());
            return;
        }
        sendReply(event, isGroup, resp.reply());
    }

    /** 聊天调用（带超时）：超时放弃回复且静默，后台任务跑完仅落会话记忆；超时秒数 0 = 不限制 */
    private ChatResponse chatWithTimeout(OneBotEvent event, String text, long sessionId)
            throws InterruptedException {
        ChatRequest request = new ChatRequest(text, String.valueOf(sessionId), props.getAgentId());
        int timeoutSeconds = props.getChatTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return chatService.chat(request);
        }
        Future<ChatResponse> future;
        try {
            future = chatExecutor.submit(() -> chatService.chat(request));
        } catch (RejectedExecutionException e) {
            // 有界队列满：背压丢弃本条（与限频丢弃同语义），防无界堆积拖垮进程
            log.warn("[napcat] 聊天队列已满，丢弃本条消息（messageId={}）", event.messageId());
            return null;
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 中断底层调用（cancel(true)）：防僵尸任务占满聊天池（优化审查 2026-09-25）
            future.cancel(true);
            log.warn("[napcat] 聊天超时（>{}s），中断并放弃回复（messageId={}）",
                    timeoutSeconds, event.messageId());
            return null;
        } catch (ExecutionException e) {
            log.warn("[napcat] 聊天执行异常（messageId={}）", event.messageId(), e.getCause());
            return null;
        }
    }

    /** /reset：删除会话绑定行，下条消息重新建档开启新会话，并回复确认 */
    private void resetSession(OneBotEvent event) {
        String key = sessionKey(event);
        int deleted = bindingMapper.delete(new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        log.info("[napcat] 会话重置 key={}（删除 {} 行绑定）", key, deleted);
        sendReply(event, "group".equals(event.messageType()), "已开启新对话，之前的记忆已清空。");
    }

    /** 解析私聊白名单 CSV → Set&lt;Long&gt;（空串/空白 = 不限制；非法项记 warn 跳过） */
    private static Set<Long> parseAllowUsers(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                log.warn("[napcat] 私聊白名单非法项忽略：{}", t);
            }
        }
        return ids;
    }

    /** binding 命中复用，未命中建 harness 会话并落库 */
    private Long bindSession(OneBotEvent event, String text) {
        String key = sessionKey(event);
        OneBotSessionBinding row = bindingMapper.selectOne(
                new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        if (row != null) {
            return row.getSessionId();
        }
        long sessionId = Long.parseLong(sessionService.createSession("qq:" + event.userId(), text));
        LocalDateTime now = LocalDateTime.now();
        OneBotSessionBinding entity = new OneBotSessionBinding();
        entity.setSessionKey(key);
        entity.setSessionId(sessionId);
        entity.setQqUserId(String.valueOf(event.userId()));
        entity.setGroupId(event.groupId() == null ? null : String.valueOf(event.groupId()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        bindingMapper.insert(entity);
        log.info("[napcat] 新建会话绑定 key={} -> session={}", key, sessionId);
        return sessionId;
    }

    /** 回复发送：超长按段落边界分段；群聊首条带 reply 段引用原消息；每条 chunk 后接表情钩子 */
    private void sendReply(OneBotEvent event, boolean isGroup, String reply) {
        NapCatProperties.Reply replyCfg = props.getReply();
        NapCatProperties.Emoji emojiCfg = props.getEmoji();
        List<String> chunks = replyCfg.isProgressive()
                ? ReplySplitter.splitProgressive(reply, replyCfg.getSplitChars(), replyCfg.getMaxChunks())
                : ReplySplitter.splitReply(reply, replyCfg.getMaxLength());
        int sentEmojis = 0;
        for (int i = 0; i < chunks.size(); i++) {
            // 条间动态拟人延迟：按「即将发送的这条」的字数计算（基础 + 字数×系数 + 扰动），首条不等待
            if (i > 0 && replyCfg.isProgressive() && !pauseBeforeChunk(dynamicDelayMs(chunks.get(i).length()))) {
                return; // 等待被中断（executor 停机）：停止后续发送，已发部分保留
            }
            List<MessageSegment> segments = new ArrayList<>();
            if (isGroup && i == 0 && event.messageId() != null) {
                segments.add(new MessageSegment("reply",
                        Map.of("message_id", event.messageId())));
            }
            segments.add(new MessageSegment("text", Map.of("text", chunks.get(i))));
            sendSegments(event, isGroup, segments);
            // 表情钩子：每条 chunk 发送完成后（含最后一条）检测；顺序 chunk N → send-delay 停顿 → 表情
            // 上限/概率/文件校验都在 EmojiReplies 与下方计数内；任何表情侧异常只记日志不阻断文本
            try {
                MessageSegment emoji = emojiReplies.pickFor(chunks.get(i));
                if (emoji != null
                        && (emojiCfg.getMaxPerReply() <= 0 || sentEmojis < emojiCfg.getMaxPerReply())) {
                    if (!pauseBeforeChunk(Math.max(0, emojiCfg.getSendDelayMs()))) {
                        return; // 停顿被中断（executor 停机）：表情与后续发送一并停止，已发部分保留
                    }
                    sendSegments(event, isGroup, List.of(emoji));
                    sentEmojis++;
                }
            } catch (Exception e) {
                log.warn("[napcat] 表情发送钩子异常，跳过本次表情（文本回复不受影响）", e);
            }
        }
    }

    /** 按目标类型发送消息段：群聊 sendGroupMsg / 私聊 sendPrivateMsg（表情与文本同一 API） */
    private void sendSegments(OneBotEvent event, boolean isGroup, List<MessageSegment> segments) {
        if (isGroup) {
            apiClient.sendGroupMsg(event.groupId(), segments);
        } else {
            apiClient.sendPrivateMsg(event.userId(), segments);
        }
    }

    /**
     * 渐进条间等待（包内可注入：测试替换为记录器避免真 sleep）。
     * 返回 false = 等待被中断（executor 停机），调用方应停止发送后续分段。
     */
    interface ChunkPause {
        boolean pause(long millis);
    }

    /** 生产实现：Thread.sleep，中断时恢复中断标志并返回 false（包内可注入，测试替换为记录器） */
    ChunkPause chunkPause = millis -> {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    };

    /** 渐进条间等待：delay &lt;= 0 不等待；等待被中断返回 false */
    private boolean pauseBeforeChunk(long delayMs) {
        return delayMs <= 0 || chunkPause.pause(delayMs);
    }

    /**
     * 动态拟人延迟（毫秒）：基础延迟（inter-chunk-delay-ms）+ 字数 × 系数（秒/字）× 1000
     * + 随机扰动（±jitter-range-ms），再被 max-delay-ms 截断（0 = 不设上限）。
     * 字数取「即将发送的这条」的长度——模拟真人把这条打完再发出；延迟下限 0。
     */
    private long dynamicDelayMs(int nextChunkChars) {
        NapCatProperties.Reply cfg = props.getReply();
        long base = Math.max(0, cfg.getInterChunkDelayMs());
        long typed = Math.round(Math.max(0, cfg.getDelayFactor()) * nextChunkChars * 1000.0);
        int jitter = Math.max(0, cfg.getJitterRangeMs());
        long wobble = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1L);
        long delay = Math.max(0, base + typed + wobble);
        return cfg.getMaxDelayMs() > 0 ? Math.min(delay, cfg.getMaxDelayMs()) : delay;
    }

    private String sessionKey(OneBotEvent event) {
        return "group".equals(event.messageType())
                ? "qq:group:" + event.groupId() + ":" + event.userId()
                : "qq:private:" + event.userId();
    }

    private Long selfIdAsLong() {
        try {
            return props.getSelfId() == null ? null : Long.valueOf(props.getSelfId());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
