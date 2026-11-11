package com.dark.javaHarness.channel.feishu.ws;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBinding;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.HarnessStatusPort;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.api.FileArtifactStore;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import com.dark.javaHarness.contract.model.ChatAttachment;
import com.dark.javaHarness.contract.model.ComponentStatus;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.view.AgentItemView;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import com.dark.javaHarness.view.SessionMessagesView;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
import java.util.Locale;
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

    /** 聊天池满了：这条根本没进队列，必须让用户知道，否则和「已读不回」没区别 */
    private static final String POOL_BUSY_NOTICE = "消息有点多，这条没能排上，稍后再发一次。";

    /** 指令帮助文案：只列真正支持的，别让模型或用户以为有 /recall */
    private static final String HELP_TEXT = """
            可用指令（仅限管理员）：
            /new 开启新对话（清空当前会话记忆）
            /reset 同 /new
            /history [n] 查看最近 n 条对话（默认 5）
            /agent 查看可用 Agent
            /agent <名> 切换当前会话使用的 Agent
            /status 查看会话与渠道/组件状态
            /recall 撤回机器人上一条消息""";

    /** /status 最多展示的组件行数与单行详情长度：超出只给省略号，避免刷屏 */
    private static final int STATUS_MAX_LINES = 8;
    private static final int STATUS_DETAIL_MAX = 80;

    /** /history 默认条数与上限：上限只为避免脚本刷屏把回复撑成几千字 */
    private static final int HISTORY_DEFAULT = 5;
    private static final int HISTORY_MAX = 20;
    private static final int HISTORY_ITEM_MAX = 60;

    /** 单张图片上限：飞书本身限 10 MB，这里留余量，避免 base64 后请求体过大 */
    private static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;

    /** 表情 key 缓存上限：超了就不再写新条目，避免长期运行后无界增长 */
    private static final int EMOJI_KEY_CACHE_MAX = 256;

    /** /recall 记录表上限：超出直接清空，召回不是关键数据，不值得为它设计 LRU */
    private static final int LAST_SENT_MAX = 512;

    /** 群聊回复模式；配置里写字符串，解析失败按 all 处理 */
    private enum ReplyMode { ALL, MENTION, PROBABILITY }

    private final ChatService chatService;
    private final SessionService sessionService;
    private final AgentService agentService;
    /** 只读状态端口：可能缺位（未实现时 /status 降级为一句提示） */
    private final HarnessStatusPort statusPort;
    private final FeishuSessionBindingMapper bindingMapper;
    private final FeishuApiClient api;
    /** 表情包：识别 [emoji:分类] 标记，按分类取图并上传发送 */
    private final FeishuEmojiCatalog emoji;
    /** 唯一允许使用指令的 open_id（配置优先，留空回退白名单首位；空串 = 指令不可用） */
    private final String commandUser;
    private final int timeoutSeconds;
    /** 回复正文上限（字符）；<=0 = 不限制 */
    private final int replyMaxChars;
    /** 长任务回执：开关、延迟阈值与表情类型 */
    private final boolean receiptEnabled;
    private final long receiptDelayMs;
    private final String receiptEmoji;
    /** 群聊回复模式与 @ 提问者、引用回复 */
    private final ReplyMode replyMode;
    private final double replyProbability;
    private final boolean atAsker;
    private final boolean quoteReply;
    private final boolean replyInThread;
    /** 语音回复：core 的 voice:<id> 工件库、mp3→opus 转码器、开关 */
    private final VoiceArtifactStore voiceArtifacts;
    /** 文件工件库：send_file 的产物，按 file:<id> flag 上传发送 */
    private final FileArtifactStore fileArtifacts;
    private final FeishuAudioTranscoder audioTranscoder;
    private final boolean voiceEnabled;
    /** 每个会话最近一条机器人消息（/recall 用），key = openId:chatId */
    private final ConcurrentHashMap<String, String> lastSent = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final ThreadPoolExecutor chatExecutor;
    private final ExecutorService driver;
    private final ConcurrentHashMap<Long, Slot> slots = new ConcurrentHashMap<>();
    /**
     * 表情 image_key 缓存（索引名 → image_key）。
     *
     * <p>飞书的 image_key 在租户内可复用，不必每发一张就上传一次。键用索引里的表情名
     * （与文件名一一对应）；换图后需要重启，这与「索引启动时加载」的既有约束一致。
     */
    private final ConcurrentHashMap<String, String> emojiKeys = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();

    public FeishuChatHandler(ChatService chatService,
                             SessionService sessionService,
                             AgentService agentService,
                             HarnessStatusPort statusPort,
                             FeishuSessionBindingMapper bindingMapper,
                             FeishuApiClient api,
                             VoiceArtifactStore voiceArtifacts,
                             FileArtifactStore fileArtifacts,
                             FeishuAudioTranscoder audioTranscoder,
                             FeishuProperties props) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.statusPort = statusPort;
        this.bindingMapper = bindingMapper;
        this.api = api;
        this.voiceArtifacts = voiceArtifacts;
        this.fileArtifacts = fileArtifacts;
        this.audioTranscoder = audioTranscoder;
        this.voiceEnabled = props.getWs().getVoice().isEnabled();
        this.emoji = new FeishuEmojiCatalog(props.getWs().getEmoji());
        this.commandUser = resolveCommandUser(props);
        this.timeoutSeconds = Math.max(1, props.getWs().getChatTimeoutSeconds());
        this.replyMaxChars = props.getWs().getReplyMaxChars();
        this.receiptEnabled = props.getWs().isReceiptReactionEnabled();
        this.receiptDelayMs = props.getWs().getReceiptDelayMs();
        this.receiptEmoji = props.getWs().getReceiptEmoji();
        this.replyMode = parseReplyMode(props.getWs().getReplyMode());
        this.replyProbability = props.getWs().getReplyProbability();
        this.atAsker = props.getWs().isAtAsker();
        this.quoteReply = props.getWs().isQuoteReply();
        this.replyInThread = props.getWs().isReplyInThread();
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
        // 指令在入队之前拦掉：/help 不该为了回一段固定文案先建一个会话，
        // 也不该排在一条 30 秒的生成任务后面等
        FeishuCommandParser.Parsed command = FeishuCommandParser.parse(message.text());
        if (command != null) {
            return handleCommand(message, command);
        }
        if (!shouldReply(message)) {
            // 不在回复范围内：静默丢弃，连会话都不建
            log.debug("[feishu] reply-mode={} 不回复本条 chatId={} mentioned={}",
                    replyMode, message.chatId(), message.mentioned());
            return CompletableFuture.completedFuture(null);
        }

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

    /**
     * 指令处理：不建会话、不进会话队列、不调模型，跑在虚拟线程上。
     *
     * <p><b>为什么只有一个人能用</b>：指令能在群里执行，而 {@code /new} 会把「这个人 + 这个群」
     * 的上下文重置。若放开给群里所有人，任何一个成员都能把别人正在进行的对话打散。
     * 授权口径是单个 open_id，不是白名单——白名单回答的是「谁能和机器人说话」。
     *
     * <p>非授权用户发指令得到一句明确提示，而不是交给模型：模型会顺着斜杠编出一份
     * 并不存在的命令清单，比不回复更糟。
     */
    private CompletableFuture<Void> handleCommand(FeishuInboundMessage message,
                                                  FeishuCommandParser.Parsed command) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (!commandUser.equals(message.openId())) {
            log.info("[feishu] 非授权用户尝试指令 openId={} command={}", message.openId(), command.name());
            submitTurn(() -> {
                reply(message, "指令只有管理员能用。直接说话就行，我照样会回答。");
                done.complete(null);
            });
            return done;
        }
        log.info("[feishu] 执行指令 /{} arg='{}' openId={} chatId={}",
                command.name(), command.arg(), message.openId(), message.chatId());
        submitTurn(() -> {
            try {
                doCommand(message, command);
            } catch (Exception e) {
                log.error("[feishu] 指令执行失败 command={} openId={}", command.name(), message.openId(), e);
                reply(message, "指令执行失败：" + e.getMessage());
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    private void doCommand(FeishuInboundMessage message, FeishuCommandParser.Parsed command) {
        switch (command.name()) {
            case "new", "reset" -> resetSession(message);
            case "help" -> reply(message, HELP_TEXT);
            case "history" -> history(message, command.arg());
            case "agent" -> agent(message, command.arg());
            case "status" -> status(message);
            case "recall" -> recall(message);
            // 解析器只放行白名单指令名，走不到这里；留着防御未来加命令忘了加分支
            default -> reply(message, "不认识的指令，发 /help 看看能做什么。");
        }
    }

    /**
     * /new 与 /reset：只删绑定，不删会话行——历史仍能在 Web 控制台查到，
     * 下一条消息会建一个新会话。（与 QQ 的 resetSession 同语义）
     */
    private void resetSession(FeishuInboundMessage message) {
        int deleted = bindingMapper.delete(new QueryWrapper<FeishuSessionBinding>()
                .eq("open_id", message.openId())
                .eq("chat_id", message.chatId()));
        log.info("[feishu] 会话重置 openId={} chatId={}（删除 {} 行绑定）",
                message.openId(), message.chatId(), deleted);
        reply(message, deleted > 0
                ? "已开启新对话，之前的记忆不会带过来。"
                : "当前还没有进行中的对话，直接说就行。");
    }

    /**
     * /status：当前会话 + 组件可用性。
     *
     * <p>只读快照，不触发探活——一条聊天指令不该变成一次对外部系统的探测。
     * 状态端口可能缺位（单独部署渠道模块时），此时明确说「不可用」而不是编数据。
     */
    private void status(FeishuInboundMessage message) {
        StringBuilder sb = new StringBuilder();
        FeishuSessionBinding binding = find(message);
        if (binding == null) {
            sb.append("当前没有会话（发一句话就会建）");
        } else {
            Long agentId = currentAgentId(binding);
            String agent = agentId == null
                    ? "未绑定"
                    : agentService.findAgentNameById(agentId).orElse("未知");
            sb.append("会话 ").append(binding.getSessionId()).append(" · Agent ").append(agent);
        }
        if (statusPort == null) {
            reply(message, sb.append("\n状态接口不可用（服务端未提供 HarnessStatusPort）").toString());
            return;
        }
        sb.append("\n组件：");
        int shown = 0;
        for (ComponentStatus item : statusPort.components()) {
            if (shown++ >= STATUS_MAX_LINES) {
                sb.append("\n…");
                break;
            }
            sb.append('\n').append(item.available() ? "● " : "○ ").append(item.name());
            if (!item.enabled()) {
                sb.append("（未启用）");
            }
            sb.append("：").append(truncate(item.detail(), STATUS_DETAIL_MAX));
        }
        reply(message, sb.toString());
    }

    /** /history [n]：只读当前绑定的会话，没有绑定就说明还没聊过 */
    private void history(FeishuInboundMessage message, String arg) {
        FeishuSessionBinding binding = find(message);
        if (binding == null) {
            reply(message, "我们还没有聊天记录哦。");
            return;
        }
        List<SessionMessagesView.Item> messages =
                sessionService.listMessages(String.valueOf(binding.getSessionId()));
        if (messages == null || messages.isEmpty()) {
            reply(message, "我们还没有聊天记录哦。");
            return;
        }
        int n = parsePositiveInt(arg, HISTORY_DEFAULT);
        List<SessionMessagesView.Item> tail =
                messages.subList(Math.max(0, messages.size() - n), messages.size());
        StringBuilder sb = new StringBuilder("最近 " + tail.size() + " 条：");
        for (SessionMessagesView.Item item : tail) {
            sb.append('\n').append("user".equals(item.role()) ? "你：" : "我：")
                    .append(truncate(item.content(), HISTORY_ITEM_MAX));
        }
        reply(message, sb.toString());
    }

    /** /agent 无参列出（标记当前），带参切换；切换需要已有会话 */
    private void agent(FeishuInboundMessage message, String arg) {
        List<AgentItemView> agents = agentService.agentItems();
        FeishuSessionBinding binding = find(message);
        Long currentId = currentAgentId(binding);
        if (arg == null || arg.isBlank()) {
            reply(message, "可用 Agent：\n" + formatAgents(agents, currentId));
            return;
        }
        String name = arg.trim();
        Long targetId = agents.stream()
                .filter(item -> item.name() != null && item.name().equalsIgnoreCase(name))
                .map(AgentItemView::id)
                .findFirst().orElse(null);
        if (targetId == null) {
            reply(message, "没找到这个 Agent，可用：\n" + formatAgents(agents, currentId));
            return;
        }
        if (binding == null) {
            reply(message, "先和我说句话建立会话，再切换 Agent 吧。");
            return;
        }
        try {
            sessionService.switchAgent(String.valueOf(binding.getSessionId()), targetId);
            reply(message, "已切换到 Agent：" + name);
        } catch (IllegalArgumentException e) {
            reply(message, "切换失败：" + e.getMessage());
        }
    }

    private Long currentAgentId(FeishuSessionBinding binding) {
        if (binding == null) {
            return null;
        }
        SessionInfo session = sessionService.getSession(String.valueOf(binding.getSessionId()));
        return session == null || session.agentId() == null ? null : session.agentId().longValue();
    }

    private static String formatAgents(List<AgentItemView> agents, Long currentId) {
        StringBuilder sb = new StringBuilder();
        for (AgentItemView item : agents) {
            boolean current = item.id() != null && item.id().equals(currentId);
            sb.append(current ? "→ " : "  ").append(item.name());
            if (current) {
                sb.append("（当前）");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value <= 0 ? fallback : Math.min(value, HISTORY_MAX);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 群聊回复模式判断。单聊总是回；指令在调用本方法之前已经处理，不受概率影响。
     */
    private boolean shouldReply(FeishuInboundMessage message) {
        if (!message.group()) {
            return true;
        }
        return switch (replyMode) {
            case MENTION -> message.mentioned();
            case PROBABILITY -> random.nextDouble() < replyProbability;
            case ALL -> true;
        };
    }

    /** 配置里的字符串 → 枚举；写错按 all 处理并告警，不让一个拼写错误把机器人变哑巴 */
    private static ReplyMode parseReplyMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReplyMode.ALL;
        }
        try {
            return ReplyMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[feishu] 未知 reply-mode='{}'，按 all 处理", raw);
            return ReplyMode.ALL;
        }
    }

    /**
     * 统一文本出口：优先引用回复（挂到原消息下），失败退回普通发送；群聊按配置 @ 提问者。
     *
     * <p>为什么不直接用 reply：原消息被撤回/删除时接口会报错，此时宁可丢引用也不能丢回复。
     */
    private FeishuApiClient.Result sendText(FeishuInboundMessage message, String text) {
        String body = decorate(message, text);
        if (quoteReply && message.messageId() != null && !message.messageId().isBlank()) {
            FeishuApiClient.Result quoted = api.replyText(message.messageId(), body, replyInThread);
            if (quoted.sent()) {
                return quoted;
            }
            log.debug("[feishu] 引用回复失败，退回普通发送：{}", quoted.reason());
        }
        return message.group()
                ? api.sendTextToChat(message.chatId(), body)
                : api.sendText(message.openId(), body);
    }

    /** 统一图片出口：引用规则同上（图片不参与 @） */
    private FeishuApiClient.Result sendImage(FeishuInboundMessage message, String imageKey) {
        if (quoteReply && message.messageId() != null && !message.messageId().isBlank()) {
            FeishuApiClient.Result quoted = api.replyImage(message.messageId(), imageKey, replyInThread);
            if (quoted.sent()) {
                return quoted;
            }
            log.debug("[feishu] 引用发图失败，退回普通发送：{}", quoted.reason());
        }
        return message.group()
                ? api.sendImageToChat(message.chatId(), imageKey)
                : api.sendImage(message.openId(), imageKey);
    }

    /** 群聊 @ 提问者：多人群里一眼看出这句话在回谁 */
    private String decorate(FeishuInboundMessage message, String text) {
        if (!message.group() || !atAsker) {
            return text;
        }
        return "<at user_id=\"" + message.openId() + "\"></at> " + text;
    }

    /** 记下本会话最近一条机器人消息，供 /recall 使用 */
    private void rememberSent(FeishuInboundMessage message, FeishuApiClient.Result result) {
        if (!result.sent() || result.messageId() == null || result.messageId().isBlank()) {
            return;
        }
        String key = sendKey(message);
        if (lastSent.size() >= LAST_SENT_MAX && !lastSent.containsKey(key)) {
            lastSent.clear(); // 极端情况：宁可丢掉召回能力，也不让这个表无界增长
        }
        lastSent.put(key, result.messageId());
    }

    private static String sendKey(FeishuInboundMessage message) {
        return message.openId() + ":" + message.chatId();
    }

    /** /recall：撤回本会话最近一条机器人消息 */
    private void recall(FeishuInboundMessage message) {
        String last = lastSent.remove(sendKey(message));
        if (last == null) {
            reply(message, "没有可撤回的消息。");
            return;
        }
        FeishuApiClient.Result result = api.deleteMessage(last);
        reply(message, result.sent() ? "已撤回上一条。" : "撤回失败：" + result.reason());
    }

    /** 从渠道无关 flags 里取 {@code prefix<id>}（voice: / file:）；没有该 flag 返回 null */
    private static String artifactId(Set<String> flags, String prefix) {
        if (flags == null) {
            return null;
        }
        for (String flag : flags) {
            if (flag != null && flag.startsWith(prefix)) {
                String id = flag.substring(prefix.length()).trim();
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }

    /**
     * 语音回复：取工件 → 转 opus → 上传 → 发语音条。
     *
     * <p>飞书语音条只认 opus（TTS 出的是 mp3），上传还必须带时长，所以中间必然有一次转码。
     * 任一步失败返回 false，调用方退回文字——语音是加分项，不该让整条回复失败。
     */
    private boolean sendVoice(FeishuInboundMessage message, String voiceId) {
        if (!voiceEnabled) {
            return false;
        }
        Optional<VoiceArtifactStore.VoiceArtifact> artifact = voiceArtifacts.take(voiceId);
        if (artifact.isEmpty()) {
            log.warn("[feishu] 语音工件缺失，回退文字（id={}）", voiceId);
            return false;
        }
        Optional<FeishuAudioTranscoder.Encoded> encoded = audioTranscoder.toOpus(artifact.get().audio());
        if (encoded.isEmpty()) {
            return false;
        }
        FeishuApiClient.Upload upload = api.uploadFile("opus", "voice.opus",
                encoded.get().audio(), encoded.get().durationMs());
        if (!upload.ok()) {
            log.warn("[feishu] 语音上传失败，回退文字：{}", upload.reason());
            return false;
        }
        FeishuApiClient.Result result = sendAudio(message, upload.key());
        if (!result.sent()) {
            log.warn("[feishu] 语音发送失败，回退文字：{}", result.reason());
            return false;
        }
        rememberSent(message, result);
        log.info("[feishu] 已发语音 {}={} 时长={}ms messageId={}",
                message.group() ? "chatId" : "openId", message.replyId(),
                encoded.get().durationMs(), result.messageId());
        return true;
    }

    /**
     * 文件附件：{@code file:<id>} flag → 取工件 → 上传（file_type=stream）→ msg_type=file。
     *
     * <p>与语音不同，附件失败不改变文字的命运：文字已经发出去了，这里只记日志。
     */
    private void sendAttachments(FeishuInboundMessage message, Set<String> flags) {
        String fileId = artifactId(flags, "file:");
        if (fileId == null) {
            return;
        }
        Optional<FileArtifactStore.FileArtifact> artifact = fileArtifacts.take(fileId);
        if (artifact.isEmpty()) {
            log.warn("[feishu] 文件工件缺失（id={}）", fileId);
            return;
        }
        FeishuApiClient.Upload upload = api.uploadFile("stream",
                artifact.get().fileName(), artifact.get().content(), null);
        if (!upload.ok()) {
            log.warn("[feishu] 文件上传失败 name={}：{}", artifact.get().fileName(), upload.reason());
            return;
        }
        FeishuApiClient.Result result = sendFile(message, upload.key());
        if (!result.sent()) {
            log.warn("[feishu] 文件发送失败 name={}：{}", artifact.get().fileName(), result.reason());
            return;
        }
        rememberSent(message, result);
        log.info("[feishu] 已发文件 {}={} name={} bytes={}",
                message.group() ? "chatId" : "openId", message.replyId(),
                artifact.get().fileName(), artifact.get().content().length);
    }

    /** 文件出口：引用规则与文本一致 */
    private FeishuApiClient.Result sendFile(FeishuInboundMessage message, String fileKey) {
        if (quoteReply && message.messageId() != null && !message.messageId().isBlank()) {
            FeishuApiClient.Result quoted = api.replyFile(message.messageId(), fileKey, replyInThread);
            if (quoted.sent()) {
                return quoted;
            }
            log.debug("[feishu] 引用发文件失败，退回普通发送：{}", quoted.reason());
        }
        return message.group()
                ? api.sendFileToChat(message.chatId(), fileKey)
                : api.sendFile(message.openId(), fileKey);
    }

    /** 语音出口：引用规则与文本一致（原消息不可引用时退回普通发送） */
    private FeishuApiClient.Result sendAudio(FeishuInboundMessage message, String fileKey) {
        if (quoteReply && message.messageId() != null && !message.messageId().isBlank()) {
            FeishuApiClient.Result quoted = api.replyAudio(message.messageId(), fileKey, replyInThread);
            if (quoted.sent()) {
                return quoted;
            }
            log.debug("[feishu] 引用发语音失败，退回普通发送：{}", quoted.reason());
        }
        return message.group()
                ? api.sendAudioToChat(message.chatId(), fileKey)
                : api.sendAudio(message.openId(), fileKey);
    }

    /** 超长截断：整条发不出去比截断更糟，末尾标明已截断避免误会 */
    private static String capReply(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（已截断）";
    }

    /** 单行截断：历史回显里换行会把一条消息撑成多行，读起来分不清谁说的 */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("[\\r\\n]+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /** 指令回复出口：与正常回复同一条路径（同样支持引用与 @） */
    private void reply(FeishuInboundMessage message, String text) {
        FeishuApiClient.Result result = sendText(message, text);
        if (!result.sent()) {
            log.warn("[feishu] 指令回复失败 {}={} 原因={}",
                    message.group() ? "chatId" : "openId", message.replyId(), result.reason());
        }
    }

    /**
     * 生效的指令授权：{@code command-user} 优先，留空回退 {@code allowed-users} 首位。
     * 两者都没有时返回空串——那个状态下入站白名单本身就挡掉了所有消息，指令不可达。
     */
    private static String resolveCommandUser(FeishuProperties props) {
        List<String> allowed = props.getWs().getAllowedUsers();
        String configured = props.getWs().getCommandUser();
        if (configured != null && !configured.isBlank()) {
            String value = configured.trim();
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(value)) {
                log.warn("[feishu] command-user={} 不在 allowed-users 内：入站闸门会先挡掉它的消息，指令永远不会执行",
                        value);
            }
            return value;
        }
        if (allowed != null && !allowed.isEmpty()) {
            log.info("[feishu] 未配置 command-user，指令授权回退到 allowed-users 首位：{}", allowed.get(0));
            return allowed.get(0);
        }
        log.warn("[feishu] 未配置 command-user 且 allowed-users 为空：指令对所有人不可用");
        return "";
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
        // 慢回复才贴「处理中」回执；finally 里撤销，成功/失败/超时都不会留下卡住的表情
        FeishuReceipt receipt = receiptEnabled
                ? new FeishuReceipt(driver, api, message.messageId(), receiptEmoji, receiptDelayMs)
                : null;
        try {
            ChatResponse response = chat(message, sessionId);
            if (response == null) {
                return;
            }
            if (!"SUCCEEDED".equals(response.status()) || response.reply() == null || response.reply().isBlank()) {
                log.warn("[feishu] 聊天未产出回复（status={}，messageId={}）",
                        response.status(), message.messageId());
                return;
            }

            // 表情标记一律剥离：模型按提示词在末尾追加 [emoji:分类]，露给用户就是乱码。
            // 即使表情模块关闭也要剥——关掉开关不该让正文变脏。
            FeishuEmojiCatalog.ParsedReply parsed = FeishuEmojiCatalog.stripMarker(response.reply());
            String text = capReply(parsed.text(), replyMaxChars);
            // 语音优先：core 产出 voice:<id> 时先试着发语音条，成功即本轮结束（与 QQ 一致）
            String voiceId = artifactId(response.flags(), "voice:");
            if (voiceId != null && sendVoice(message, voiceId)) {
                return;
            }
            if (voiceId != null && text.isBlank()) {
                text = "语音发送失败，请稍后再试";
            }
            if (!text.isBlank()) {
                FeishuApiClient.Result result = sendText(message, text);
                if (result.sent()) {
                    rememberSent(message, result);
                    log.info("[feishu] 已回复 {}={} messageId={} 回复字数={}",
                            message.group() ? "chatId" : "openId", message.replyId(),
                            result.messageId(), text.length());
                } else {
                    log.warn("[feishu] 回复失败 {}={} 原因={}",
                            message.group() ? "chatId" : "openId", message.replyId(), result.reason());
                    return; // 文本都没发出去，别再追发表情
                }
            }
            // 附件在文字之后发：文件是补充说明，文字仍然是主体
            sendAttachments(message, response.flags());
            sendEmoji(message, parsed.category());
        } finally {
            if (receipt != null) {
                receipt.clear();
            }
        }
    }

    /**
     * 表情钩子：按分类取一张本地图片 → 上传换 image_key → 发送。
     *
     * <p>任何失败只记日志：文本这时已经发出去了，表情是增强项，不该影响主链路。
     * 只在模型真的输出了非 none 分类时才走这条，避免每轮都多两次 API 调用。
     */
    private void sendEmoji(FeishuInboundMessage message, String category) {
        if (category == null || "none".equals(category)) {
            return;
        }
        try {
            FeishuEmojiCatalog.Picked picked = emoji.pick(category);
            if (picked == null) {
                return;
            }
            String imageKey = emojiKeys.get(picked.name());
            if (imageKey == null) {
                FeishuApiClient.Upload upload = api.uploadImage(picked.name(), picked.bytes());
                if (!upload.ok()) {
                    log.warn("[feishu] 表情上传失败 category={} 原因={}", category, upload.reason());
                    return;
                }
                imageKey = upload.key();
                if (emojiKeys.size() < EMOJI_KEY_CACHE_MAX) {
                    emojiKeys.put(picked.name(), imageKey);
                }
            }
            FeishuApiClient.Result result = sendImage(message, imageKey);
            if (result.sent()) {
                rememberSent(message, result);
                log.info("[feishu] 已发表情 {}={} category={} messageId={}",
                        message.group() ? "chatId" : "openId", message.replyId(), category,
                        result.messageId());
            } else {
                log.warn("[feishu] 表情发送失败 category={} 原因={}", category, result.reason());
            }
        } catch (Exception e) {
            log.warn("[feishu] 表情钩子异常，跳过本次表情（文本回复不受影响）", e);
        }
    }

    private ChatResponse chat(FeishuInboundMessage message, long sessionId) {
        ChatRequest request = new ChatRequest(message.text(), String.valueOf(sessionId), null, null,
                "feishu", downloadAttachments(message));
        Future<ChatResponse> future;
        try {
            future = chatExecutor.submit(() -> chatService.chat(request));
        } catch (RejectedExecutionException e) {
            log.warn("[feishu] 聊天池已满，丢弃本条 messageId={}", message.messageId());
            // 这条没进队列，必须回一句：否则用户看到的是永久静默
            reply(message, POOL_BUSY_NOTICE);
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
     * 把消息里的资源下载成附件。下载失败/超限只跳过这一张，文本仍照常回复——
     * 图片是增强项，不该拖垮整条消息。
     */
    private List<ChatAttachment> downloadAttachments(FeishuInboundMessage message) {
        if (message.attachments().isEmpty()) {
            return List.of();
        }
        List<ChatAttachment> attachments = new ArrayList<>(message.attachments().size());
        for (FeishuAttachment attachment : message.attachments()) {
            FeishuApiClient.Download download = api.downloadImage(message.messageId(), attachment.fileKey());
            if (!download.ok()) {
                log.warn("[feishu] 图片下载失败 messageId={} key={} 原因={}",
                        message.messageId(), attachment.fileKey(), download.reason());
                continue;
            }
            if (download.data().length > MAX_ATTACHMENT_BYTES) {
                log.warn("[feishu] 图片超过 {} MB，跳过 messageId={} 实际={} 字节",
                        MAX_ATTACHMENT_BYTES / 1024 / 1024, message.messageId(), download.data().length);
                continue;
            }
            attachments.add(new ChatAttachment(download.mimeType(), download.data()));
        }
        return attachments;
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
                FeishuApiClient.Result result = sendText(message, BUSY_NOTICE);
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
