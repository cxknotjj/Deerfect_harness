package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
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

    /** 纯图片消息没有正文，用占位符过 @NotBlank 校验，同时让模型知道这轮带了图 */
    private static final String IMAGE_PLACEHOLDER = "[图片]";

    private final FeishuProperties props;
    private final FeishuEventDedup dedup;
    private final FeishuMessageHandler handler;
    private final ThreadPoolExecutor executor;

    /** 最近一次收到事件的时间（毫秒）；0 = 自启动以来没有收到过。只给健康快照读 */
    private volatile long lastEventAt;
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
        // 收到即记账：即使后面判定丢弃，也证明长连接活着——健康快照只看这个时间
        lastEventAt = System.currentTimeMillis();
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
            // 机器人自己的消息不能进：开了 group_msg 后事件若带上 bot 消息，会形成自回环
            String senderType = data.getSender().getSenderType();
            if (senderType != null && !"user".equals(senderType)) {
                log.debug("[feishu] 忽略非用户消息 senderType={} chatId={}", senderType, message.getChatId());
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
            Parsed parsed = parse(message);
            if (parsed == null) {
                return;
            }
            String text = parsed.text();
            if (group && text != null) {
                text = stripMentions(text);
            }
            if (text == null || text.isBlank()) {
                // 纯图片消息没有正文，给模型一个占位符，否则会因 @NotBlank 被请求校验拦掉
                if (parsed.imageKeys().isEmpty()) {
                    return;
                }
                text = IMAGE_PLACEHOLDER;
            }
            submit(new FeishuInboundMessage(openId, message.getChatId(), message.getMessageId(), eventId,
                    text, group, parsed.imageKeys().stream().map(FeishuAttachment::new).toList()));
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
    /** 按消息类型解析：text 原文 / post 结构化正文 / image 资源键；其它类型返回 null 表示不支持 */
    private Parsed parse(EventMessage message) {
        String type = message.getMessageType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "text" -> parsedText(message.getContent());
            case "post" -> parsedPost(message.getContent());
            case "image" -> parsedImage(message.getContent());
            default -> {
                log.debug("[feishu] 暂不支持的消息类型 type={}", type);
                yield null;
            }
        };
    }

    private Parsed parsedText(String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(null, List.of());
        }
        try {
            JsonNode node = json.readTree(content);
            String text = node.path("text").asText("");
            return new Parsed(text.isBlank() ? null : text.trim(), List.of());
        } catch (Exception e) {
            log.debug("[feishu] 内容不是合法 JSON，按纯文本处理：{}", content);
            return new Parsed(content.trim(), List.of());
        }
    }

    /**
     * 富文本：{@code {"title":"...","content":[[{"tag":"text","text":"..."},...]]}}，
     * 旧版多语言包装为 {@code {"zh_cn":{...}}}，两种都接受。
     *
     * <p>图片元素收集 image_key，正文里留占位符；表情/文件这类暂时只记占位，不下载。
     */
    private Parsed parsedPost(String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(null, List.of());
        }
        try {
            JsonNode root = json.readTree(content);
            JsonNode body = root.has("content") ? root : root.path("zh_cn");
            StringBuilder text = new StringBuilder();
            String title = body.path("title").asText("").trim();
            if (!title.isEmpty()) {
                text.append(title).append('\n');
            }
            List<String> imageKeys = new ArrayList<>();
            for (JsonNode paragraph : body.path("content")) {
                StringBuilder line = new StringBuilder();
                for (JsonNode element : paragraph) {
                    appendPostElement(element, line, imageKeys);
                }
                String value = line.toString().trim();
                if (!value.isEmpty()) {
                    text.append(value).append('\n');
                }
            }
            String out = text.toString().trim();
            return new Parsed(out.isEmpty() ? null : out, imageKeys);
        } catch (Exception e) {
            log.debug("[feishu] 富文本解析失败，按纯文本处理：{}", content);
            return new Parsed(content.trim(), List.of());
        }
    }

    private void appendPostElement(JsonNode element, StringBuilder line, List<String> imageKeys) {
        switch (element.path("tag").asText("")) {
            case "text", "code_block", "a" -> line.append(element.path("text").asText(""));
            case "at" -> {
                String name = element.path("user_name").asText("");
                if (!name.isBlank()) {
                    line.append('@').append(name).append(' ');
                }
            }
            case "img" -> {
                String key = element.path("image_key").asText("");
                if (!key.isBlank()) {
                    imageKeys.add(key);
                    line.append(IMAGE_PLACEHOLDER);
                }
            }
            case "emotion" -> line.append("[表情]");
            case "media" -> line.append("[文件]");
            case "hr" -> line.append("---");
            default -> {
                // 未知元素忽略：宁可少一段，也不要把结构 JSON 塞进提示词
            }
        }
    }

    private Parsed parsedImage(String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(null, List.of());
        }
        try {
            String key = json.readTree(content).path("image_key").asText("");
            return key.isBlank()
                    ? new Parsed(null, List.of())
                    : new Parsed(IMAGE_PLACEHOLDER, List.of(key));
        } catch (Exception e) {
            log.debug("[feishu] 图片消息解析失败：{}", content);
            return new Parsed(null, List.of());
        }
    }

    /** 解析结果：正文 + 待下载的资源键 */
    private record Parsed(String text, List<String> imageKeys) {
    }
    /** 最近一次收到事件的时间（毫秒）；0 = 从未收到 */
    public long lastEventAtMillis() {
        return lastEventAt;
    }

}
