package com.dark.javaHarness.channel.qq.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.qq.client.NapCatApiClient;
import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.channel.qq.reply.EmojiReplies;
import com.dark.javaHarness.channel.qq.reply.UserRateLimiter;
import com.dark.javaHarness.channel.qq.persistence.OneBotSessionBinding;
import com.dark.javaHarness.channel.qq.persistence.OneBotSessionBindingMapper;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.SemanticTriggerJudge;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上报事件处理实现：过滤 → 白名单/触发/限频 → 会话绑定 → 直调 ChatService → 整段回复。
 *
 * <p>会话键：私聊 {@code qq:private:<uid>}，群聊 {@code qq:group:<gid>:<uid>}
 * （群内按用户独立上下文）；binding 命中复用 harness 会话（多轮记忆跨消息延续），
 * 未命中经 {@code SessionService.createSession(creator=qq:<uid>, 首问)} 建档落库。
 * {@code /reset} 指令删除绑定行，下条消息开启新会话（多轮记忆清空）。
 *
 * <p>失败语义：任何异常/FAILED/超时只记日志不回复（渠道侧静默），不打断主流程、
 * 不向 QQ 侧泄错误细节；聊天超时中断底层调用并放弃回复（防僵尸任务占满聊天池），
 * 队列满背压丢弃本条消息。
 *
 * <p><b>体量说明（约 580 行，不再拆；2026-11-07 体检复核）</b>：整条链路是「一条上报消息的
 * 顺序处理」——过滤 → 白名单/触发 → 限频 → 绑定 → 调用 → 回复。若拆成 pipeline，每一步都要
 * 搬运同一组上下文（事件、用户、群、绑定行、限频器、客户端、超时配置），跳转与参数转手的成本
 * 高于单文件阅读成本；更关键的是**顺序本身即语义**（限频必须先于调用、绑定必须先于建档、
 * 超时清理必须先于回复），拆开后这条顺序不再一眼可见。若将来要复用同一条链路到其他渠道，
 * 再按「渠道无关的处理器」抽出，而不是现在按行数切。
 */
public class OneBotEventServiceImpl implements OneBotEventService {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventServiceImpl.class);

    /** 聊天执行池线程名前缀序号 */
    private static final AtomicLong CHAT_THREAD_SEQ = new AtomicLong();

    /** 聊天执行池队列上限：满即拒绝（背压丢弃单条消息，与限频丢弃同语义），防无界堆积 */
    private static final int CHAT_QUEUE_CAPACITY = 100;

    /** LLM 回复末尾的情绪标记（[emoji:happy] 等）；解析后从正文剥离，按分类取表情 */
    private static final Pattern EMOJI_MARKER =
            Pattern.compile("\\[emoji:([a-zA-Z]+)\\]", Pattern.CASE_INSENSITIVE);

    /** 语音 flag 前缀：core 产生的 voice:&lt;artifactId&gt;，仅本渠道消费 */
    private static final String VOICE_FLAG_PREFIX = "voice:";

    /** /help 文案（无 LLM 消耗） */
    private static final String HELP_TEXT = """
            可用指令：
            /new 开启新对话（清空当前会话记忆）
            /reset 同 /new
            /history [n] 查看最近 n 条对话（默认 5）
            /agent 查看可用 Agent
            /agent <名> 切换当前会话使用的 Agent
            /recall 撤回机器人上一条消息""";

    private final ChatService chatService;
    private final SessionService sessionService;
    /** /agent 指令用：只读使用 core 的 AgentService 接口（列表 + 名称→id），由本轮指令授权 */
    private final AgentService agentService;
    private final OneBotSessionBindingMapper bindingMapper;
    private final NapCatApiClient apiClient;
    private final NapCatProperties props;
    /** 语音工件库（core 工具按 turnId 写入）：本渠道按 voice:&lt;id&gt; flag 取走音频发送 */
    private final VoiceArtifactStore voiceArtifacts;
    /** 语义层触发判断（Slow Path，可 null：core 未提供实现时候选事件一律忽略） */
    private final SemanticTriggerJudge semanticJudge;
    private final UserRateLimiter rateLimiter;
    /** 入站文本提取与触发判定（超长类拆分 2026-09-25 拆出） */
    private final EventTextParser textParser;
    /** 程序层事件准入策略（规则表 → 响应/候选/忽略） */
    private final ChannelEventPolicy eventPolicy;
    /** 聊天执行池：有界队列 + Abort 拒绝 + @PreDestroy 关闭（优化审查 2026-09-25 收口项） */
    private final ThreadPoolExecutor chatExecutor;

    /** 表情包匹配器（构造内创建，签名不变；包内可见：测试可替换注入异常桩） */
    EmojiReplies emojiReplies;

    /** 已发消息记录（回复触发判定 + /recall 共用）；包内可见，测试可直接预置 */
    SentMessageTracker sentMessageTracker = new SentMessageTracker();

    public OneBotEventServiceImpl(ChatService chatService,
                                  SessionService sessionService,
                                  AgentService agentService,
                                  OneBotSessionBindingMapper bindingMapper,
                                  NapCatApiClient apiClient,
                                  NapCatProperties props,
                                  VoiceArtifactStore voiceArtifacts,
                                  SemanticTriggerJudge semanticJudge) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.bindingMapper = bindingMapper;
        this.apiClient = apiClient;
        this.props = props;
        this.voiceArtifacts = voiceArtifacts;
        this.semanticJudge = semanticJudge;
        this.emojiReplies = new EmojiReplies(props);
        this.textParser = new EventTextParser(props, sentMessageTracker);
        this.eventPolicy = new ChannelEventPolicy(props, textParser);
        this.rateLimiter = new UserRateLimiter(props.getRateLimit().getPerUserSeconds() * 1000L);
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
        // 程序层准入（Fast Path）：规则表决定 响应/候选/忽略；未配置规则时等价既有语义
        ChannelEventPolicy.Evaluation evaluation = eventPolicy.evaluate(event);
        if (evaluation.decision() == ChannelEventPolicy.Decision.IGNORE) {
            log.debug("[napcat] 事件被策略忽略 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        String text = evaluation.text();
        if (text == null || text.isBlank()) {
            return;
        }
        boolean isGroup = "group".equals(event.messageType());
        // 候选事件（CANDIDATE）交给语义层（Slow Path）：未启用/无实现/判断失败一律保持安静
        if (evaluation.decision() == ChannelEventPolicy.Decision.CANDIDATE
                && !semanticApproves(event, text)) {
            log.debug("[napcat] 候选事件未通过语义层，保持安静 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        // 同用户限频（群聊防风控 + 私聊防刷）：间隔内的消息直接丢弃
        if (!rateLimiter.tryAcquire(event.userId())) {
            log.debug("[napcat] 限频丢弃 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        // 指令路由：识别 "/xxx"（/reset /new /help /history /agent /recall）；未识别返回 false 交由正常聊天
        CommandParser.Parsed cmd = CommandParser.parse(text);
        if (cmd != null && handleCommand(event, isGroup, cmd)) {
            return;
        }
        BoundSession session = bindSession(event, text);
        ChatResponse resp;
        try {
            // 仅本会话「首次建档」那条下发配置 Agent（触发 core 绑定到 session.agent_id）；
            // 复用旧绑定的后续消息传 null，避免覆盖 /agent 切换的会话 Agent（core 在 agentId!=null 时会重绑）
            resp = chatWithTimeout(event, text, session.sessionId(),
                    session.created() ? props.getAgentId() : null);
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
        sendReply(event, isGroup, resp.reply(), resp.flags());
    }

    /** 语义层判断：开关关闭/无实现/异常/超时一律按「不响应」处理（渠道侧静默） */
    private boolean semanticApproves(OneBotEvent event, String text) {
        if (!props.getSemanticTrigger().isEnabled() || semanticJudge == null) {
            return false;
        }
        try {
            return semanticJudge.shouldRespond("qq", sessionKey(event), text);
        } catch (Exception e) {
            log.warn("[napcat] 语义层判断失败，保持安静：{}", e.getMessage());
            return false;
        }
    }

    /** 聊天调用（带超时）：超时放弃回复且静默，后台任务跑完仅落会话记忆；超时秒数 0 = 不限制 */
    private ChatResponse chatWithTimeout(OneBotEvent event, String text, long sessionId, Long agentIdOverride)
            throws InterruptedException {
        ChatRequest request = new ChatRequest(text, String.valueOf(sessionId), agentIdOverride, null, "qq");
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

    /**
     * 指令分发：命中并已回复返回 true（调用方据此跳过聊天）；未识别返回 false（交由正常聊天）。
     */
    private boolean handleCommand(OneBotEvent event, boolean isGroup, CommandParser.Parsed cmd) {
        switch (cmd.name()) {
            case "reset", "new" -> {
                resetSession(event);
                return true;
            }
            case "help" -> {
                sendReply(event, isGroup, HELP_TEXT, Set.of());
                return true;
            }
            case "history" -> {
                handleHistory(event, isGroup, cmd.arg());
                return true;
            }
            case "agent" -> {
                handleAgent(event, isGroup, cmd.arg());
                return true;
            }
            case "recall" -> {
                handleRecall(event, isGroup);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** /recall：撤回本会话/本群最近一条机器人消息 */
    private void handleRecall(OneBotEvent event, boolean isGroup) {
        Long last = sentMessageTracker.lastId(sendTargetKey(event));
        if (last == null) {
            sendReply(event, isGroup, "没有可撤回的消息。", Set.of());
            return;
        }
        apiClient.deleteMsg(last);
        sentMessageTracker.forget(last);
        sendReply(event, isGroup, "已撤回。", Set.of());
    }

    /** /reset：删除会话绑定行，下条消息重新建档开启新会话，并回复确认 */
    private void resetSession(OneBotEvent event) {
        String key = sessionKey(event);
        int deleted = bindingMapper.delete(new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        log.info("[napcat] 会话重置 key={}（删除 {} 行绑定）", key, deleted);
        sendReply(event, "group".equals(event.messageType()), "已开启新对话，之前的记忆已清空。", Set.of());
    }

    /** /history [n]：回显当前会话最近 n 条（默认 5），不消耗 LLM */
    private void handleHistory(OneBotEvent event, boolean isGroup, String arg) {
        Long sid = boundSessionId(event);
        if (sid == null) {
            sendReply(event, isGroup, "我们还没有聊天记录哦。", Set.of());
            return;
        }
        List<com.dark.javaHarness.view.SessionMessagesView.Item> msgs =
                sessionService.listMessages(String.valueOf(sid));
        if (msgs == null || msgs.isEmpty()) {
            sendReply(event, isGroup, "我们还没有聊天记录哦。", Set.of());
            return;
        }
        int n = parsePositiveInt(arg, 5);
        List<com.dark.javaHarness.view.SessionMessagesView.Item> tail =
                msgs.subList(Math.max(0, msgs.size() - n), msgs.size());
        StringBuilder sb = new StringBuilder("最近 " + tail.size() + " 条：");
        for (var it : tail) {
            sb.append('\n').append("user".equals(it.role()) ? "你： " : "我： ").append(truncate(it.content(), 60));
        }
        sendReply(event, isGroup, sb.toString(), Set.of());
    }

    /** /agent：无参列出 Agent；有参按名称切换（需已有会话绑定） */
    private void handleAgent(OneBotEvent event, boolean isGroup, String arg) {
        List<com.dark.javaHarness.view.AgentItemView> agents = agentService.agentItems();
        if (arg == null || arg.isBlank()) {
            sendReply(event, isGroup, "可用 Agent：\n" + formatAgentList(agents), Set.of());
            return;
        }
        Long targetId = agents.stream()
                .filter(a -> a.name() != null && a.name().equalsIgnoreCase(arg.trim()))
                .map(com.dark.javaHarness.view.AgentItemView::id)
                .findFirst().orElse(null);
        if (targetId == null) {
            sendReply(event, isGroup, "没找到这个 Agent，可用：\n" + formatAgentList(agents), Set.of());
            return;
        }
        Long sid = boundSessionId(event);
        if (sid == null) {
            sendReply(event, isGroup, "先和我说句话建立会话，再切换 Agent 吧。", Set.of());
            return;
        }
        try {
            sessionService.switchAgent(String.valueOf(sid), targetId);
            sendReply(event, isGroup, "已切换到 Agent：" + arg.trim(), Set.of());
        } catch (IllegalArgumentException e) {
            sendReply(event, isGroup, "切换失败：" + e.getMessage(), Set.of());
        }
    }

    private static String formatAgentList(List<com.dark.javaHarness.view.AgentItemView> agents) {
        StringBuilder sb = new StringBuilder();
        for (var a : agents) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("· ").append(a.name());
        }
        return sb.length() == 0 ? "（暂无）" : sb.toString();
    }

    /** 会话绑定查询：未建档返回 null */
    private Long boundSessionId(OneBotEvent event) {
        OneBotSessionBinding row = bindingMapper.selectOne(
                new QueryWrapper<OneBotSessionBinding>().eq("session_key", sessionKey(event)));
        return row == null ? null : row.getSessionId();
    }

    /** 解析正整数参数（非法/空回默认值） */
    private static int parsePositiveInt(String arg, int fallback) {
        if (arg == null || arg.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(arg.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 单行截断（保留可读性） */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 会话绑定结果：sessionId + 是否本次新建（created=true 表示首次建档落库） */
    private record BoundSession(long sessionId, boolean created) {
    }

    /** binding 命中复用（created=false），未命中建 harness 会话并落库（created=true） */
    private BoundSession bindSession(OneBotEvent event, String text) {
        String key = sessionKey(event);
        OneBotSessionBinding row = bindingMapper.selectOne(
                new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        if (row != null) {
            return new BoundSession(row.getSessionId(), false);
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
        return new BoundSession(sessionId, true);
    }

    /**
     * 回复发送：有 voice:&lt;id&gt; flag 时先发语音，成功即跳过文字与表情；
     * 否则剥离 [emoji:分类] 标记后整段单条发送文字（超长按 max-length 截断），
     * 群聊可带 reply 段与 @提问者段，文本发送成功后按分类追加一张表情。
     */
    private void sendReply(OneBotEvent event, boolean isGroup, String reply, Set<String> flags) {
        NapCatProperties.Reply replyCfg = props.getReply();
        NapCatProperties.Emoji emojiCfg = props.getEmoji();
        ParsedReply parsed = stripEmojiMarker(reply);
        String text = capLength(parsed.text(), replyCfg.getMaxLength());
        String voiceId = voiceId(flags);
        if (voiceId != null && sendVoice(event, isGroup, voiceId)) {
            return; // 语音发送成功：本轮不再发文字与表情
        }
        if (voiceId != null && text.isBlank()) {
            text = "语音发送失败，请稍后再试";
        }
        List<MessageSegment> segments = new ArrayList<>();
        if (isGroup) {
            if (event.messageId() != null) {
                segments.add(new MessageSegment("reply", Map.of("message_id", event.messageId())));
            }
            if (replyCfg.isAtAsker()) {
                segments.add(new MessageSegment("at", Map.of("qq", String.valueOf(event.userId()))));
            }
        }
        segments.add(new MessageSegment("text", Map.of("text", text)));
        Long id = sendSegments(event, isGroup, segments);
        if (id == null) {
            log.warn("[napcat] 回复发送失败（{}）",
                    isGroup ? "group=" + event.groupId() : "user=" + event.userId());
            return;
        }
        // 表情钩子：仅当 LLM 输出了非 none 的分类标记时追加；停顿（模拟「找图」）可注入；
        // 任何异常只记日志不阻断文本
        if (parsed.emojiCategory() == null || "none".equals(parsed.emojiCategory())) {
            return;
        }
        try {
            MessageSegment emoji = emojiReplies.pickByCategory(parsed.emojiCategory());
            if (emoji != null) {
                if (!pauseHook.pause(Math.max(0, emojiCfg.getSendDelayMs()))) {
                    return; // 停顿被中断（executor 停机）：不再发表情
                }
                sendSegments(event, isGroup, List.of(emoji));
            }
        } catch (Exception e) {
            log.warn("[napcat] 表情发送钩子异常，跳过本次表情（文本回复不受影响）", e);
        }
    }

    /** 从渠道无关 flags 中取 voice:&lt;id&gt;；无该 flag 返回 null。 */
    private static String voiceId(Set<String> flags) {
        if (flags == null) {
            return null;
        }
        for (String flag : flags) {
            if (flag != null && flag.startsWith(VOICE_FLAG_PREFIX)) {
                String id = flag.substring(VOICE_FLAG_PREFIX.length()).trim();
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }

    /** 取走语音工件并发送 record 段；工件缺失或发送失败返回 false（调用方回退文字）。 */
    private boolean sendVoice(OneBotEvent event, boolean isGroup, String id) {
        Optional<VoiceArtifactStore.VoiceArtifact> artifact = voiceArtifacts.take(id);
        if (artifact.isEmpty()) {
            log.warn("[napcat] 语音工件缺失，回退文字（id={}）", id);
            return false;
        }
        String file = "base64://" + Base64.getEncoder().encodeToString(artifact.get().audio());
        Long messageId = sendSegments(event, isGroup,
                List.of(new MessageSegment("record", Map.of("file", file))));
        if (messageId == null) {
            log.warn("[napcat] 语音发送失败，回退文字（id={}）", id);
            return false;
        }
        return true;
    }

    /** 剥离回复中的情绪标记：取最后一个 [emoji:分类]，返回正文与该分类（无标记时分类为 null） */
    static ParsedReply stripEmojiMarker(String reply) {
        if (reply == null) {
            return new ParsedReply("", null);
        }
        Matcher m = EMOJI_MARKER.matcher(reply);
        int start = -1;
        int end = -1;
        String category = null;
        while (m.find()) {
            category = m.group(1).toLowerCase(Locale.ROOT);
            start = m.start();
            end = m.end();
        }
        if (start < 0) {
            return new ParsedReply(reply.trim(), null);
        }
        String text = (reply.substring(0, start) + reply.substring(end)).trim();
        return new ParsedReply(text, category);
    }

    /** 文本正文与末尾情绪分类（分类 null = 无标记） */
    record ParsedReply(String text, String emojiCategory) {
    }

    /** 超长截断（保留原文换行与空白；max <= 0 = 不限制） */
    private static String capLength(String s, int max) {
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    /** 按目标类型发送消息段；成功返回 message_id 并记入 tracker，失败返回 null（尽力而为） */
    private Long sendSegments(OneBotEvent event, boolean isGroup, List<MessageSegment> segments) {
        Long id = isGroup
                ? apiClient.sendGroupMsg(event.groupId(), segments)
                : apiClient.sendPrivateMsg(event.userId(), segments);
        if (id != null) {
            sentMessageTracker.record(sendTargetKey(event), id);
        }
        return id;
    }

    /** 已发消息记录的目标键：私聊/群聊分桶（与 /recall 取用口径一致） */
    private String sendTargetKey(OneBotEvent event) {
        return "group".equals(event.messageType())
                ? "group:" + event.groupId()
                : "private:" + event.userId();
    }

    /** 发送前停顿钩子（包内可注入：测试替换为记录器避免真 sleep）；返回 false = 被中断 */
    interface Pause {
        boolean pause(long millis);
    }

    /** 生产实现：Thread.sleep，中断时恢复中断标志并返回 false */
    Pause pauseHook = millis -> {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    };

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
