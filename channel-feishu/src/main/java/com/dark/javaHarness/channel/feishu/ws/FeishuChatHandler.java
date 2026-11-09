package com.dark.javaHarness.channel.feishu.ws;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBinding;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;

/**
 * 单聊 / 群聊消息 → 项目会话 → 回复。
 *
 * <p>一条消息经过四步，任何一步失败都只影响这一条：
 * <ol>
 *   <li><b>绑定会话</b>：open_id + chat_id 查 {@code feishu_session_binding}，没有就建会话；
 *       这样重启后多轮上下文仍然连续。</li>
 *   <li><b>按会话排队</b>：同一 sessionId 同时只跑一轮，避免连发两条各自基于旧上下文回答。</li>
 *   <li><b>调模型</b>：复用 core 的 {@link ChatService}，channel="feishu" 让 system prompt
 *       注入对应的信道规范；带超时，超时中断线程（不中断会一直占着聊天池）。</li>
 *   <li><b>回复</b>：走应用 API（不是群 webhook）——webhook 只能发到固定群，发不回单聊。</li>
 * </ol>
 *
 * <p><b>为什么要按会话串行</b>：{@code SessionSnapshotStore.appendBatch} 用 {@code FOR UPDATE}
 * 锁会话行，写回本身不会丢消息；但整轮没有串行化——两条并发消息各自读到同一份旧上下文，
 * 后完成的把历史覆盖成「B 问、B 答、A 问、A 答」，且互相看不到对方的提问。
 * 锁必须加在「读上下文之前」，而那个时机只有渠道层掌握。
 *
 * <p><b>线程模型</b>（改过一次，别退回）：入站线程只做「绑定 + 入队」就返回，不再阻塞，
 * 否则一个慢会话会占住入站池。真正的执行在虚拟线程上驱动，模型调用仍提交到
 * {@link #chatExecutor}——聊天池是全局并发上限，虚拟线程只负责等待与超时。
 * <b>不能在 chatExecutor 内部再 submit 到 chatExecutor 然后 get</b>：池满时两个线程
 * 都在等自己提交的任务，没有线程去执行，直接死锁。
 */
public class FeishuChatHandler implements FeishuMessageHandler, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatHandler.class);

    /** 每个会话最多再排一条：连发两条通常是补充说明，再多就是刷屏 */
    private static final int PENDING_CAPACITY = 1;

    /** 同一会话的「还在处理」提示最短间隔，避免刷屏时回一长串同样的提示 */
    private static final long BUSY_NOTICE_INTERVAL_MS = 30_000L;

    private static final String BUSY_NOTICE = "上一条还在处理，稍等片刻再发。";

    private final ChatService chatService;
    private final SessionService sessionService;
    private final FeishuSessionBindingMapper bindingMapper;
    private final FeishuApiClient api;
    private final int timeoutSeconds;
    private final ThreadPoolExecutor chatExecutor;
    private final ExecutorService driver;
    private final ConcurrentHashMap<Long, Slot> slots = new ConcurrentHashMap<>();
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
        // 虚拟线程只做「等聊天池 + 超时」：阻塞它们的代价近似为零，也不会占用聊天池
        this.driver = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void handle(FeishuInboundMessage message) {
        handleAsync(message);
    }

    /**
     * 入队并立即返回；返回的 future 在<b>这一条真正处理完</b>时完成（排队中的消息要等前一条结束）。
     * 被拒收的消息立即完成，不会悬挂调用方。
     */
    CompletableFuture<Void> handleAsync(FeishuInboundMessage message) {
        long sessionId;
        try {
            sessionId = sessionIdOf(message);
        } catch (Exception e) {
            log.error("[feishu] 会话绑定失败 openId={} messageId={}", message.openId(), message.messageId(), e);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> done = new CompletableFuture<>();
        Pending pending = new Pending(message, done);
        AtomicReference<Runnable> dispatch = new AtomicReference<>();
        AtomicBoolean queued = new AtomicBoolean(false);
        AtomicBoolean notifyBusy = new AtomicBoolean(false);

        // compute 是按 key 原子的：入口与收尾都在这里改 Slot，不需要额外加锁
        slots.compute(sessionId, (key, slot) -> {
            if (slot == null) {
                Slot fresh = new Slot();
                fresh.running = true;
                dispatch.set(() -> runTurn(key, fresh, pending));
                return fresh;
            }
            if (!slot.running) {
                slot.running = true;
                dispatch.set(() -> runTurn(key, slot, pending));
                return slot;
            }
            if (slot.pending == null) {
                slot.pending = pending;
                queued.set(true);
                log.info("[feishu] 会话正忙，排队一条 session={} messageId={}", key, message.messageId());
                return slot;
            }
            long now = System.currentTimeMillis();
            if (now - slot.lastBusyNoticeAt >= BUSY_NOTICE_INTERVAL_MS) {
                slot.lastBusyNoticeAt = now;
                notifyBusy.set(true);
            }
            return slot;
        });

        if (dispatch.get() != null) {
            submitTurn(dispatch.get());
            return done;
        }
        if (!queued.get()) {
            // 积压：通知由独立虚拟线程发出，不能占用入站线程，也不能再占聊天池
            if (notifyBusy.get()) {
                sendBusyNotice(sessionId, message);
            } else {
                log.warn("[feishu] 会话积压，丢弃本条（提示已限频）session={} messageId={}",
                        sessionId, message.messageId());
            }
            done.complete(null);
        }
        return done;
    }

    @Override
    public void destroy() {
        driver.shutdownNow();
        chatExecutor.shutdownNow();
    }

    /** 真正处理一条消息；由虚拟线程驱动，模型调用仍在聊天池上 */
    private void runTurn(long sessionId, Slot slot, Pending pending) {
        try {
            doTurn(pending.message(), sessionId);
        } catch (Exception e) {
            log.error("[feishu] 处理消息失败 session={} messageId={}",
                    sessionId, pending.message().messageId(), e);
        } finally {
            // 无论成功、失败还是超时，都必须推进队头：漏了这一步，一次异常会让该会话永久卡死
            Pending next = advance(sessionId, slot);
            if (next != null) {
                submitTurn(() -> runTurn(sessionId, slot, next));
            }
            pending.done().complete(null);
        }
    }

    /** 取队头或标记空闲；返回 null 表示没有排队条目（槽位已从 map 移除） */
    private Pending advance(long sessionId, Slot slot) {
        AtomicReference<Pending> next = new AtomicReference<>();
        slots.compute(sessionId, (key, current) -> {
            if (current != slot) {
                // 槽位已被替换，说明本轮的收尾与新建发生了竞态；保留新槽位不动
                return current;
            }
            if (slot.pending != null) {
                next.set(slot.pending);
                slot.pending = null;
                return slot; // 保持 running=true，由下一条接棒
            }
            return null;      // 空闲：移除，下一条消息会重建
        });
        return next.get();
    }

    private void doTurn(FeishuInboundMessage message, long sessionId) {
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

    private void submitTurn(Runnable task) {
        try {
            driver.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[feishu] 调度器已关闭，丢弃待执行的一轮");
        }
    }

    private void sendBusyNotice(long sessionId, FeishuInboundMessage message) {
        log.warn("[feishu] 会话积压（已排队 {} 条），丢弃本条 session={} messageId={}",
                PENDING_CAPACITY, sessionId, message.messageId());
        try {
            driver.execute(() -> {
                FeishuApiClient.Result result = message.group()
                        ? api.sendTextToChat(message.chatId(), BUSY_NOTICE)
                        : api.sendText(message.openId(), BUSY_NOTICE);
                if (!result.sent()) {
                    log.warn("[feishu] 忙提示发送失败 messageId={} 原因={}", message.messageId(), result.reason());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[feishu] 调度器已关闭，忙提示未发送");
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

    /**
     * 一个会话的调度槽位。字段只允许在 {@link ConcurrentHashMap#compute} 里读写——
     * 那是按 key 原子的，等价于一把极短命的锁；在此之外读会看到中间态。
     */
    private static final class Slot {
        boolean running;
        Pending pending;
        long lastBusyNoticeAt;
    }

    private record Pending(FeishuInboundMessage message, CompletableFuture<Void> done) {
    }
}
