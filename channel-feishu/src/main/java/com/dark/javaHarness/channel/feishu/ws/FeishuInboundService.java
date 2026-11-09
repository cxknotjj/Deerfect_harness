package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站第一跳：把 SDK 回调变成「判定 + 入队」，然后立刻返回。
 *
 * <p><b>3 秒约束是这个类存在的理由</b>：飞书要求事件在 3 秒内被确认，而一次 Agent
 * 对话要几十秒。所以这里只做四件轻活——识别单聊、白名单、去重、入有界队列；
 * 调模型与发回复全部交给 {@link FeishuMessageHandler} 在后台线程跑。
 *
 * <p>队列必须有界：无界队列在模型卡住时会把内存吃光，而且越积越久、回复越来越晚。
 * 满了就丢弃并记 warn——对个人 agent 来说，丢一条比拖垮进程好。
 */
public class FeishuInboundService {

    private static final Logger log = LoggerFactory.getLogger(FeishuInboundService.class);

    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 4;

    /** 配对提示的去重集合上限：只是为了防止陌生人群发时刷屏，不需要精确 */
    private static final int MAX_PAIRING_HINTS = 100;

    /** 群聊正文里的 @ 占位符：{@code @_user_1}，可能出现在开头或中间 */
    private static final Pattern MENTION = Pattern.compile("@_user_\\d+");

    private final FeishuProperties props;
    private final FeishuEventDedup dedup;
    private final FeishuMessageHandler handler;
    private final ThreadPoolExecutor executor;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> pairingHints = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadSeq = new AtomicInteger();

    public FeishuInboundService(FeishuProperties props, FeishuEventDedup dedup, FeishuMessageHandler handler) {
        this.props = props;
        this.dedup = dedup;
        this.handler = handler;
        int capacity = Math.max(1, props.getWs().getQueueCapacity());
        this.executor = new ThreadPoolExecutor(CORE_THREADS, MAX_THREADS, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "feishu-inbound-" + threadSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * SDK 线程调用：无论后面多慢，这里必须在毫秒级返回。
     */
    public void onEvent(P2MessageReceiveV1 event) {
        String eventId = null;
        try {
            if (event == null || event.getHeader() == null) {
                return;
            }
            eventId = event.getHeader().getEventId();
            P2MessageReceiveV1Data data = event.getEvent();
            if (data == null || data.getMessage() == null || data.getSender() == null) {
                return;
            }
            EventMessage message = data.getMessage();
            String chatType = message.getChatType();
            boolean group = "group".equals(chatType);
            if (!group && !"p2p".equals(chatType)) {
                log.debug("[feishu] 忽略未知会话类型 chatType={} chatId={}", chatType, message.getChatId());
                return;
            }
            if (group && (message.getMentions() == null || message.getMentions().length == 0)) {
                // 依赖飞书「只推送 @ 机器人的群消息」这一语义；万一将来开了「接收所有群消息」
                // 权限，这里会把这些消息挡掉一半（至少不会对没人 @ 的闲聊开口）
                log.debug("[feishu] 群里没人 @ 机器人，忽略 chatId={}", message.getChatId());
                return;
            }
            UserId senderId = data.getSender().getSenderId();
            String openId = senderId == null ? null : senderId.getOpenId();
            if (openId == null || openId.isBlank()) {
                return;
            }
            if (!allowed(openId)) {
                return;
            }
            if (!dedup.firstSeen(eventId)) {
                log.debug("[feishu] 重复事件丢弃 eventId={}", eventId);
                return;
            }
            if (!"text".equals(message.getMessageType())) {
                log.debug("[feishu] 暂不支持的消息类型 type={} openId={}", message.getMessageType(), openId);
                return;
            }
            String text = textOf(message.getContent());
            if (group && text != null) {
                text = stripMentions(text);
            }
            if (text == null || text.isBlank()) {
                return;
            }
            submit(new FeishuInboundMessage(openId, message.getChatId(), message.getMessageId(), eventId, text, group));
        } catch (Exception e) {
            // 入站解析失败不能让 SDK 线程炸掉，否则后续事件可能一起断
            log.error("[feishu] 事件预处理异常 eventId={}", eventId, e);
        }
    }

    /** 当前排队 + 正在处理的任务数（排障用） */
    public int backlog() {
        return executor.getQueue().size() + executor.getActiveCount();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void submit(FeishuInboundMessage message) {
        try {
            executor.execute(() -> {
                try {
                    handler.handle(message);
                } catch (Throwable t) {
                    // handler 自己应该兜住异常；这里再兜一层，避免一个线程死掉后池里少一个
                    log.error("[feishu] 消息处理失败 messageId={}", message.messageId(), t);
                }
            });
            log.info("[feishu] 收到{}消息 openId={} chatId={} messageId={} 字数={}",
                    message.group() ? "群聊" : "单聊", message.openId(), message.chatId(),
                    message.messageId(), message.text().length());
        } catch (RejectedExecutionException e) {
            log.warn("[feishu] 入站队列已满（容量 {}），丢弃本条 messageId={}",
                    props.getWs().getQueueCapacity(), message.messageId());
        }
    }

    /**
     * 白名单：空 = 配对模式（只打印 open_id，不处理）。
     *
     * <p>不做「空名单放行」：长连接没有回调端点，白名单是机器人被拉进陌生群后
     * 唯一能拦住「对所有人开口」的闸。
     */
    private boolean allowed(String openId) {
        List<String> allowed = props.getWs().getAllowedUsers();
        if (allowed == null || allowed.isEmpty()) {
            if (pairingHints.size() < MAX_PAIRING_HINTS && pairingHints.add(openId)) {
                log.info("[feishu] 配对模式：收到 open_id={}。把它加入 channel.feishu.ws.allowed-users 后重启即可对话",
                        openId);
            }
            return false;
        }
        if (!allowed.contains(openId)) {
            log.debug("[feishu] 非白名单用户丢弃 openId={}", openId);
            return false;
        }
        return true;
    }

    /** 去掉群聊正文里的 @ 占位符（{@code @_user_1}），避免把无意义的 key 送进模型 */
    private String stripMentions(String text) {
        return MENTION.matcher(text).replaceAll("").trim();
    }

    /** 飞书文本消息的 content 是 JSON 字符串：{@code {"text":"..."}} */
    private String textOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content);
            String text = node.path("text").asText("");
            return text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            log.debug("[feishu] 内容不是合法 JSON，按纯文本处理：{}", content);
            return content.trim();
        }
    }
}
