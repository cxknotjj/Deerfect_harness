package com.dark.javaHarness.channel.feishu.ws;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBinding;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;

/**
 * 单聊消息 → 项目会话 → 回复。
 *
 * <p>三件事按顺序发生，任何一件失败都只影响这一条消息：
 * <ol>
 *   <li><b>绑定会话</b>：open_id + chat_id 查 {@code feishu_session_binding}，没有就建会话；
 *       这样重启后多轮上下文仍然连续。</li>
 *   <li><b>调模型</b>：复用 core 的 {@link ChatService}，channel="feishu" 让 system prompt
 *       注入对应的信道规范；带超时，超时中断线程（不中断会一直占着聊天池）。</li>
 *   <li><b>回复</b>：走应用 API（不是群 webhook）——webhook 只能发到固定群，发不回单聊。</li>
 * </ol>
 *
 * <p>聊天池独立于入站池：模型慢不能堵住「收消息」这一步，否则队列很快满、事件被丢。
 */
public class FeishuChatHandler implements FeishuMessageHandler, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatHandler.class);

    private final ChatService chatService;
    private final SessionService sessionService;
    private final FeishuSessionBindingMapper bindingMapper;
    private final FeishuApiClient api;
    private final int timeoutSeconds;
    private final ThreadPoolExecutor chatExecutor;
    private final AtomicInteger threadSeq = new AtomicInteger();

    public FeishuChatHandler(ChatService chatService,
                             SessionService sessionService,
                             FeishuSessionBindingMapper bindingMapper,
                             FeishuApiClient api,
                             FeishuProperties props) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.bindingMapper = bindingMapper;
        this.api = api;
        this.timeoutSeconds = Math.max(1, props.getWs().getChatTimeoutSeconds());
        this.chatExecutor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "feishu-chat-" + threadSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void handle(FeishuInboundMessage message) {
        long sessionId;
        try {
            sessionId = sessionIdOf(message);
        } catch (Exception e) {
            log.error("[feishu] 会话绑定失败 openId={} messageId={}", message.openId(), message.messageId(), e);
            return;
        }

        ChatResponse response = chat(message, sessionId);
        if (response == null) {
            return;
        }
        if (!"SUCCEEDED".equals(response.status()) || response.reply() == null || response.reply().isBlank()) {
            log.warn("[feishu] 聊天未产出回复（status={}，messageId={}）", response.status(), message.messageId());
            return;
        }

        FeishuApiClient.Result result = message.group()
                ? api.sendTextToChat(message.chatId(), response.reply())
                : api.sendText(message.openId(), response.reply());
        if (result.sent()) {
            log.info("[feishu] 已回复 {}={} messageId={} 回复字数={}",
                    message.group() ? "chatId" : "openId", message.replyId(),
                    result.messageId(), response.reply().length());
        } else {
            log.warn("[feishu] 回复失败 {}={} 原因={}",
                    message.group() ? "chatId" : "openId", message.replyId(), result.reason());
        }
    }

    @Override
    public void destroy() {
        chatExecutor.shutdownNow();
    }

    private ChatResponse chat(FeishuInboundMessage message, long sessionId) {
        ChatRequest request = new ChatRequest(message.text(), String.valueOf(sessionId), null, null, "feishu");
        Future<ChatResponse> future;
        try {
            future = chatExecutor.submit(() -> chatService.chat(request));
        } catch (RejectedExecutionException e) {
            log.warn("[feishu] 聊天池已满，丢弃本条 messageId={}", message.messageId());
            return null;
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 中断底层调用：不取消的话线程会继续跑，占着聊天池直到模型自己结束
            future.cancel(true);
            log.warn("[feishu] 聊天超时（>{}s），放弃回复（messageId={}）", timeoutSeconds, message.messageId());
            return null;
        } catch (ExecutionException e) {
            log.warn("[feishu] 聊天执行异常（messageId={}）", message.messageId(), e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 查绑定，没有就建会话再写绑定。
     *
     * <p>并发首次消息可能同时插入：唯一键会拒绝第二条，此时回查一次拿先写入的会话，
     * 避免两轮对话落在两个 session 里。
     */
    private long sessionIdOf(FeishuInboundMessage message) {
        FeishuSessionBinding existing = find(message);
        if (existing != null) {
            return existing.getSessionId();
        }
        long sessionId = Long.parseLong(sessionService.createSession("feishu:" + message.openId(), message.text()));
        LocalDateTime now = LocalDateTime.now();
        FeishuSessionBinding entity = new FeishuSessionBinding();
        entity.setOpenId(message.openId());
        entity.setChatId(message.chatId());
        entity.setSessionId(sessionId);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            bindingMapper.insert(entity);
            log.info("[feishu] 新建会话绑定 openId={} -> session={}", message.openId(), sessionId);
            return sessionId;
        } catch (DuplicateKeyException e) {
            FeishuSessionBinding concurrent = find(message);
            if (concurrent != null) {
                log.info("[feishu] 并发首次消息，复用已存在绑定 openId={} -> session={}",
                        message.openId(), concurrent.getSessionId());
                return concurrent.getSessionId();
            }
            throw e;
        }
    }

    private FeishuSessionBinding find(FeishuInboundMessage message) {
        return bindingMapper.selectOne(new QueryWrapper<FeishuSessionBinding>()
                .eq("open_id", message.openId())
                .eq("chat_id", message.chatId()));
    }
}
