package com.dark.javaHarness.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.agent.LlmRetry;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.model.SessionSnapshotItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 用户偏好画像提取服务（跨会话长期记忆）。
 *
 * <p>机制：定时扫描「静默超阈值且活跃时间晚于上次提炼时间」的会话 → 只取快照中
 * <b>游标之后</b>的新消息 → LLM 输出「新增/变更条目」补丁 → 按 key 合并进全局单份画像
 * （{@code user-profile/user-profile.md}）→ 原子落盘 + 备份 → 记录游标与提炼时刻。
 *
 * <p><b>为什么是增量而不是全量重提炼</b>：旧口径用 {@code profile_extracted} 做一次性锁定，
 * 长青会话（QQ 渠道按用户固定 sessionId）提炼一次后，后面聊的内容永远进不了画像——用户
 * 越常用，画像越停在第一次。改增量后每轮只喂新消息，token 随「新增对话」增长而不是随
 * 「会话总长度」增长；且 LLM 只输出变更条目而不是重写整份画像，既有条目由代码保证不丢，
 * 不再依赖提示词自觉。
 *
 * <p><b>待提炼判定</b>用 {@code last_active_at > profile_extracted_at}，不引入新的布尔标记：
 * 写回路径不需要维护开关，提炼期间又来了新消息也不会被误标为已处理（记录的是开始处理那一刻
 * 的活跃时间）。
 *
 * <p>降级原则：任何失败（LLM 异常 / 空输出 / 文件写失败）都不推进游标，warn 单行日志，
 * 下轮扫描自动重试；绝不因画像失败影响聊天主流程，也绝不因空输出抹掉既有画像。
 *
 * <p>LLM 通道与 RouteJudge 同款：轻量模型（请求级显式 model 参数）+ 重试 +
 * llm_call_log 落库口径（agent_name='memory-profile'）。
 */
@Service
@ConditionalOnProperty(name = "app.memory.profile-enabled", havingValue = "true")
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    /** 单轮扫描批处理上限（防一次唤醒雪崩：每会话一次 LLM 调用） */
    private static final int SCAN_BATCH_LIMIT = 10;

    /** 单次提炼最多喂多少条新消息（超长取尾部：偏好信号集中在最近对话） */
    private static final int MAX_NEW_MESSAGES = 40;

    /** 单次提炼正文总字符上限（与画像上限叠加，单次请求约 1 万字符 ≈ 4k tokens） */
    private static final int MAX_NEW_CHARS = 6000;

    /** 画像条目格式：{@code - [key] value}；key 上限与正则一致，防模型输出超长标签 */
    private static final Pattern ENTRY = Pattern.compile("^-\\s*\\[([^\\]]{1,40})]\\s*(.+)$");

    /** 模型明确表示「本次没有新偏好」 */
    private static final String NONE = "NONE";

    private static final String SYSTEM_PROMPT =
            "你是 Harness 的用户偏好画像维护器。画像是一组「- [key] value」条目。\n"
            + "根据【新增对话】，只输出需要新增或有变化的条目，不要输出未变化的既有条目。\n"
            + "规则：\n"
            + "1. 每行一条，严格为「- [key] value」。key 是稳定的短标签（如 语言 / 技术栈 / 沟通风格 / 工作环境），"
            + "同一主题必须复用当前画像里已有的 key。\n"
            + "2. 只记录稳定偏好与事实（语言习惯、技术栈、沟通风格、环境、工作方式），不记录一次性任务细节。\n"
            + "3. 与既有条目冲突时，输出同一 key 加新值即可——框架会用新值覆盖旧值，不需要你重写整份画像。\n"
            + "4. 当前画像中若有「无 key 的旧条目」，在你确实要更新它时，输出带 key 的等价条目即可（框架会替换掉旧行）。\n"
            + "5. 本次对话没有任何值得记录的新内容时，只输出 " + NONE + "。\n"
            + "6. 只输出条目或 " + NONE + "，不要任何解释、标题或代码块包裹。";

    private final SessionMapper sessionMapper;
    /** 复用会话快照读取（含条目 id），不直接碰 session_messages 表 */
    private final SessionService sessionService;
    private final com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry;
    private final com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog;
    private final com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver;
    /** LLM 调用观测记录器：提炼调用耗时/token 也落 llm_call_log（agent_name='memory-profile'）；null 直通 */
    private final LlmCallRecorder recorder;
    /** 画像文件路径（相对工作目录），文件不存在视为空画像 */
    private final String profileFile;
    /** 会话静默多久视为「已结束」（分钟） */
    private final int idleMinutes;
    /** 画像字符上限（合并输出超限截断护栏） */
    private final int maxChars;
    /** 扫描间隔（毫秒，与 @Scheduled 同键）：仅用于装配确认日志打印真实生效值 */
    private final long scanIntervalMs;
    /** 重试策略（最多 3 次、指数退避），与 RouteJudge 同款 */
    private final LlmRetry retry = new LlmRetry();

    public UserProfileService(SessionMapper sessionMapper,
                              SessionService sessionService,
                              com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry,
                              com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog,
                              com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver,
                              @org.springframework.lang.Nullable LlmCallRecorder recorder,
                              @Value("${app.memory.profile-file:user-profile/user-profile.md}") String profileFile,
                              @Value("${app.memory.profile-idle-minutes:30}") int idleMinutes,
                              @Value("${app.memory.profile-max-chars:4000}") int maxChars,
                              @Value("${app.memory.profile-scan-interval-ms:300000}") long scanIntervalMs) {
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
        this.llmClientRegistry = llmClientRegistry;
        this.llmAgentCatalog = llmAgentCatalog;
        this.llmModelResolver = llmModelResolver;
        this.recorder = recorder;
        this.profileFile = profileFile;
        this.idleMinutes = idleMinutes;
        this.maxChars = maxChars;
        this.scanIntervalMs = scanIntervalMs;
    }

    /** 装配确认日志：启动即可见（区分「任务没装配」与「跑了没扫到」）；首扫延迟 = 扫描间隔（fixedDelay 语义） */
    @jakarta.annotation.PostConstruct
    void reportArmed() {
        log.info("[memory-profile] 画像服务已装配：扫描间隔 {}ms（首次扫描在启动后 {}ms），静默阈值 {} 分钟，画像文件 {}",
                scanIntervalMs, scanIntervalMs, idleMinutes, profileFile);
    }

    /** 定时扫描：上一轮完成后间隔 profile-scan-interval-ms（默认 5 分钟）再跑，不堆叠 */
    @Scheduled(fixedDelayString = "${app.memory.profile-scan-interval-ms:300000}")
    public void scanOnce() {
        List<SessionEntity> stale = scanStale();
        if (stale.isEmpty()) {
            log.debug("[memory-profile] 本轮扫描无待提炼会话");
            return;
        }
        log.info("[memory-profile] 扫描到 {} 个待提炼会话", stale.size());
        for (SessionEntity session : stale) {
            // 单会话失败不阻断批内后续会话
            processOne(session);
        }
    }

    /**
     * 扫描待提炼会话：软删除由 @TableLogic 自动过滤；NULL last_active_at（存量会话）不命中 → 不回溯。
     *
     * <p>条件 {@code last_active_at > profile_extracted_at} 即「提炼之后又活跃过」；用它替代
     * 一次性布尔标记，写回路径无需任何配合。
     */
    List<SessionEntity> scanStale() {
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.isNotNull("last_active_at")
                .lt("last_active_at", LocalDateTime.now().minusMinutes(idleMinutes))
                .and(w -> w.isNull("profile_extracted_at").or().apply("last_active_at > profile_extracted_at"))
                .orderByAsc("last_active_at")
                .last("LIMIT " + SCAN_BATCH_LIMIT);
        return sessionMapper.selectList(qw);
    }

    /** 处理单个会话：取新增消息 → LLM 产出补丁 → 按 key 合并落盘 → 推进游标；任何失败不推进（下轮重试） */
    void processOne(SessionEntity session) {
        String sessionId = String.valueOf(session.getSessionId());
        try {
            List<SessionSnapshotItem> items = sessionService.snapshotItems(sessionId);
            List<SessionSnapshotItem> fresh = freshItems(items, session.getLastExtractedMessageId());
            if (fresh.isEmpty()) {
                // 没有新增内容（或快照为空）：推进提炼时刻避免空转重扫
                markUpToDate(session, lastItemId(items));
                return;
            }
            String dialog = renderSnapshot(fresh);
            if (dialog.isBlank()) {
                markUpToDate(session, lastItemId(items));
                return;
            }
            String current = currentProfile();
            String patch = extractPatch(current, dialog);
            if (patch == null || patch.isBlank()) {
                // 防抹掉画像：LLM 输出为空不落盘不推进（下轮重试）
                log.warn("[memory-profile] 会话 {} 提炼输出为空，跳过（下轮重试）", sessionId);
                return;
            }
            if (isNone(patch)) {
                // 明确「本次没有新偏好」：不动文件，但要推进，否则每轮都会重问一次
                log.debug("[memory-profile] 会话 {} 无新偏好", sessionId);
                markUpToDate(session, lastItemId(items));
                return;
            }
            String merged = mergeProfile(current, patch);
            if (merged.isBlank()) {
                log.warn("[memory-profile] 会话 {} 合并结果为空，跳过（防抹掉既有画像）", sessionId);
                return;
            }
            merged = clamp(merged);
            writeProfile(merged);
            markUpToDate(session, lastItemId(items));
            log.info("[memory-profile] 会话 {} 增量提炼完成（喂入 {} 条新消息，画像 {} 字符）",
                    sessionId, fresh.size(), merged.length());
        } catch (Exception e) {
            log.warn("[memory-profile] 会话 {} 提炼失败（下轮重试）：{}", sessionId,
                    LlmCallRecorder.describeError(e));
        }
    }

    /**
     * 取游标之后的新消息（尾部优先）。
     *
     * <p>游标在快照中找不到 = 已被裁剪丢弃。由于裁剪只从头部丢弃、消息只从尾部追加，
     * 此时剩余条目全部晚于游标，按「全量新增」处理是正确的。
     */
    private List<SessionSnapshotItem> freshItems(List<SessionSnapshotItem> items, String cursor) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (cursor.equals(items.get(i).id())) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<SessionSnapshotItem> tail = items.subList(start, items.size());
        if (tail.size() > MAX_NEW_MESSAGES) {
            tail = tail.subList(tail.size() - MAX_NEW_MESSAGES, tail.size());
        }
        return tail;
    }

    /** 新增消息渲染为对话文本（user/assistant 逐行）；超长取尾部保留最近对话 */
    private String renderSnapshot(List<SessionSnapshotItem> messages) {
        StringBuilder sb = new StringBuilder();
        for (SessionSnapshotItem m : messages) {
            sb.append("assistant".equals(m.role()) ? "assistant" : "user")
                    .append(": ").append(m.content()).append('\n');
        }
        String text = sb.toString();
        return text.length() <= MAX_NEW_CHARS
                ? text
                : text.substring(text.length() - MAX_NEW_CHARS);
    }

    /** 快照最后一条的 id（作为新游标）；无条目返回 null */
    private static String lastItemId(List<SessionSnapshotItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String id = items.get(items.size() - 1).id();
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * 读当前画像全文；文件不存在视为空画像（注入侧同源，保证所见即所提炼）。
     *
     * <p>主文件为空但备份非空时从备份恢复：写盘用原子替换，正常不会出现空文件，
     * 一旦出现基本是外部误清空（手工编辑、容器卷异常），不该让画像从此清零。
     */
    public String currentProfile() {
        Path target = Path.of(profileFile);
        Path backup = backupPath();
        try {
            String text = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
            if (!text.isBlank()) {
                return text;
            }
            if (Files.exists(backup)) {
                String restored = Files.readString(backup, StandardCharsets.UTF_8);
                if (!restored.isBlank()) {
                    log.warn("[memory-profile] 主画像文件为空/缺失，已从备份读取 {}", backup);
                    return restored;
                }
            }
            return text;
        } catch (Exception e) {
            throw new IllegalStateException("读取画像文件失败: " + profileFile, e);
        }
    }

    /** LLM 产出补丁（含重试与落库）；重试耗尽抛异常（processOne 统一 warn）/ 输出为空返回 null */
    private String extractPatch(String current, String dialog) {
        String userPrompt = "【当前画像】\n" + (current.isBlank() ? "（空，首次生成）" : current)
                + "\n\n【新增对话】\n" + dialog;
        com.dark.javaHarness.llm.routing.RoutePlan.Member member = primaryMember();
        return retry.executeWithRetry(() -> doCall(member, userPrompt),
                (attempt, durationMs, err) -> record(durationMs, err == null, err, userPrompt, member));
    }

    /** profile 系统 Agent → 路由首选成员（观测与调用使用同一成员，避免日志与实际模型不一致） */
    private com.dark.javaHarness.llm.routing.RoutePlan.Member primaryMember() {
        return llmModelResolver.resolve(llmAgentCatalog.require("profile").getRouteId()).members().get(0);
    }

    /** 单次 LLM 调用（不含重试语义，可被重试）；返回模型原始输出 */
    private String doCall(com.dark.javaHarness.llm.routing.RoutePlan.Member member, String userPrompt) {
        ChatClient client = llmClientRegistry.get(member.modelId());
        // 请求级显式携带 model：与 AgentChatCaller/RouteJudge 同款约定（缺 model DashScope 返回 400）
        return client.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(member.upstreamName()))
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }

    /**
     * 按 key 合并：既有条目原序保留，同 key 覆盖，新 key 追加；无 key 的旧条目原样保留。
     *
     * <p>为什么在代码里合并而不是让模型重写全文：模型重写时「保留无关条目」只能靠提示词
     * 自觉，一次漏写就是永久丢失；改成补丁 + 代码合并后，既有条目的存活是结构保证的。
     * 旧版本画像里的无 key 条目（{@code - 偏好简洁回复}）在模型输出同值带 key 条目时被替换，
     * 避免升级后出现两条同义条目。
     */
    static String mergeProfile(String current, String patch) {
        Map<String, String> keyed = new LinkedHashMap<>();
        List<String> legacy = new ArrayList<>();
        if (current != null) {
            for (String raw : current.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Matcher m = ENTRY.matcher(line);
                if (m.matches()) {
                    keyed.put(normalizeKey(m.group(1)), "- [" + m.group(1).trim() + "] " + m.group(2).trim());
                } else {
                    legacy.add(line);
                }
            }
        }
        if (patch != null) {
            for (String raw : patch.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || isNone(line)) {
                    continue;
                }
                Matcher m = ENTRY.matcher(line);
                if (m.matches()) {
                    String value = m.group(2).trim();
                    // 旧版无 key 条目被带 key 覆盖：同值即视为同一条
                    legacy.remove("- " + value);
                    keyed.put(normalizeKey(m.group(1)), "- [" + m.group(1).trim() + "] " + value);
                } else if (line.startsWith("-")) {
                    String value = line.substring(1).trim();
                    if (!value.isEmpty() && !legacy.contains("- " + value)) {
                        legacy.add("- " + value);
                    }
                }
            }
        }
        List<String> out = new ArrayList<>(legacy);
        out.addAll(keyed.values());
        return String.join("\n", out);
    }

    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNone(String text) {
        return NONE.equalsIgnoreCase(text.trim());
    }

    /** 超上限按行截断（不切半个条目） */
    private String clamp(String text) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf('\n', maxChars);
        return text.substring(0, cut > 0 ? cut : Math.min(maxChars, text.length()));
    }

    /**
     * 画像落盘：先备份上一版 → 临时文件 + 原子替换（防写一半崩溃留半个文件）。
     *
     * <p>备份只在主文件已存在时做；失败抛异常（调用方不推进游标）。
     */
    private void writeProfile(String content) throws Exception {
        Path target = Path.of(profileFile);
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        if (Files.exists(target)) {
            Files.copy(target, backupPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = Files.createTempFile(dir, "user-profile-", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path backupPath() {
        return Path.of(profileFile + ".bak");
    }

    /**
     * 推进提炼进度：记录「处理到了哪条消息」与「处理的是哪个活跃时刻」。
     *
     * <p>{@code profile_extracted_at} 取扫描时读到的 last_active_at，而不是 now：
     * 提炼期间新到的消息会把 last_active_at 推后，从而在下一轮自然重新命中，
     * 不会被这次标记吞掉。
     */
    private void markUpToDate(SessionEntity session, String lastMessageId) {
        LocalDateTime processedActiveAt = session.getLastActiveAt() == null
                ? LocalDateTime.now() : session.getLastActiveAt();
        UpdateWrapper<SessionEntity> uw = new UpdateWrapper<>();
        uw.eq("session_id", session.getSessionId())
                .set("profile_extracted_at", processedActiveAt);
        if (lastMessageId != null) {
            uw.set("last_extracted_message_id", lastMessageId);
        }
        sessionMapper.update(null, uw);
    }

    /** 提炼调用观测落库（无会话上下文口径：sessionId 空；轨迹标识全 NULL——不属于任何轮次与执行树） */
    private void record(long durationMs, boolean ok, Throwable e, String userPrompt,
                        com.dark.javaHarness.llm.routing.RoutePlan.Member member) {
        if (recorder == null) {
            return;
        }
        int promptTokens = LlmCallRecorder.estimateTokens(SYSTEM_PROMPT)
                + LlmCallRecorder.estimateTokens(userPrompt);
        // 发起时刻由耗时反推（重试回调只给耗时；回调与耗时计算同一时钟读数，差值即发起瞬间）
        long start = System.currentTimeMillis() - durationMs;
        recorder.record(new LlmCallLog(null, "memory-profile", member.upstreamName(), false, ok,
                promptTokens, null, null, true,
                durationMs, LlmCallRecorder.describeError(e),
                null, null, null, null, null, null, null, null,
                null, null, null, null, LlmCallRecorder.startedAt(start)));
    }
}
