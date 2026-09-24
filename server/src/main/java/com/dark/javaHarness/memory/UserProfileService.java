package com.dark.javaHarness.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 用户偏好画像提取服务（跨会话长期记忆）。
 *
 * <p>机制：定时扫描「已静默超阈值且未提炼」的会话 → 读取会话快照 →
 * LLM 将对话中的偏好事实合并进全局单份画像文件（{@code user-profile/user-profile.md}，
 * Markdown 条目化全文）→ 原子落盘成功后标记 {@code profile_extracted=1}（一次会话只提炼一次）。
 * 画像作为 system 段（【用户偏好画像】）注入 general/lead——常驻小数据全量注入，不走检索。
 *
 * <p>降级原则：任何失败（LLM 异常 / 空输出 / 文件写失败）都不标记，warn 单行日志，
 * 下轮扫描自动重试；绝不因画像失败影响聊天主流程，也绝不因空输出抹掉既有画像。
 *
 * <p>LLM 通道与 RouteJudge 同款：轻量模型（请求级显式 model 参数）+ 重试 +
 * llm_call_log 落库口径（agent_name='memory-profile'）。
 */
@Service
@ConditionalOnProperty(name = "app.memory.profile-enabled", havingValue = "true")
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    /** 提取用轻量模型（与 route-judge 同款约定：注册表命中则提速，未命中走默认兜底） */
    private static final String PROFILE_MODEL = "qwen3.8-27b";

    /** 单轮扫描批处理上限（防一次唤醒雪崩：每会话一次 LLM 调用） */
    private static final int SCAN_BATCH_LIMIT = 10;

    /** 会话快照注入 LLM 的字符上限（超长取尾部：提取偏好只需近期对话信号） */
    private static final int SNAPSHOT_MAX_CHARS = 8000;

    private static final String SYSTEM_PROMPT =
            "你是 Harness 的用户偏好画像维护器。根据一段对话内容，输出更新后的完整用户偏好画像。\n"
            + "画像格式：Markdown 无序列表，每行一条以「- 」开头的事实条目。\n"
            + "规则：\n"
            + "1. 只记录稳定偏好与事实（语言习惯、技术栈、沟通风格、环境、工作方式），不记录一次性任务细节。\n"
            + "2. 保留既有画像中与本次对话无关的条目，绝不丢失。\n"
            + "3. 与既有条目语义重复的合并为一条；语义冲突以本次对话为准。\n"
            + "4. 本次对话没有新偏好时，原样输出既有画像。\n"
            + "5. 只输出画像正文，不要任何解释、标题或代码块包裹。";

    private final SessionMapper sessionMapper;
    /** 复用会话快照读取（loadContext 解析 JSON 还原 Message 列表），不直接碰 session_messages 表 */
    private final SessionService sessionService;
    private final ChatClientRegistry clientRegistry;
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
    private final com.dark.javaHarness.agent.LlmRetry retry = new com.dark.javaHarness.agent.LlmRetry();

    public UserProfileService(SessionMapper sessionMapper,
                              SessionService sessionService,
                              ChatClientRegistry clientRegistry,
                              @org.springframework.lang.Nullable LlmCallRecorder recorder,
                              @Value("${app.memory.profile-file:user-profile/user-profile.md}") String profileFile,
                              @Value("${app.memory.profile-idle-minutes:30}") int idleMinutes,
                              @Value("${app.memory.profile-max-chars:4000}") int maxChars,
                              @Value("${app.memory.profile-scan-interval-ms:300000}") long scanIntervalMs) {
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
        this.clientRegistry = clientRegistry;
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

    /** 扫描待提炼会话：软删除由 @TableLogic 自动过滤；NULL last_active_at（存量会话）不命中 → 不回溯 */
    List<SessionEntity> scanStale() {
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.eq("profile_extracted", 0)
                .lt("last_active_at", LocalDateTime.now().minusMinutes(idleMinutes))
                .orderByAsc("last_active_at")
                .last("LIMIT " + SCAN_BATCH_LIMIT);
        return sessionMapper.selectList(qw);
    }

    /** 处理单个会话：读快照 → LLM 合并 → 原子落盘 → 标记；任何失败不标记（下轮重试） */
    void processOne(SessionEntity session) {
        String sessionId = String.valueOf(session.getSessionId());
        try {
            String snapshot = renderSnapshot(sessionService.loadContext(sessionId));
            if (snapshot.isBlank()) {
                // 建档后从未写回（无内容可提炼），直接标记避免空转重扫
                markExtracted(session);
                return;
            }
            String current = currentProfile();
            String merged = mergeProfile(current, snapshot);
            if (merged == null || merged.isBlank()) {
                // 防抹掉画像：LLM 输出为空不落盘不标记
                log.warn("[memory-profile] 会话 {} 提炼输出为空，跳过（下轮重试）", sessionId);
                return;
            }
            if (merged.length() > maxChars) {
                merged = merged.substring(0, maxChars);
            }
            writeProfile(merged);
            markExtracted(session);
            log.info("[memory-profile] 会话 {} 画像提炼完成（{} 字符）", sessionId, merged.length());
        } catch (Exception e) {
            log.warn("[memory-profile] 会话 {} 提炼失败（下轮重试）：{}", sessionId,
                    LlmCallRecorder.describeError(e));
        }
    }

    /** 会话快照渲染为对话文本（user/assistant 逐行）；超长取尾部保留最近对话 */
    private String renderSnapshot(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m instanceof org.springframework.ai.chat.messages.AssistantMessage ? "assistant" : "user")
                    .append(": ").append(m.getText()).append('\n');
        }
        String text = sb.toString();
        return text.length() <= SNAPSHOT_MAX_CHARS
                ? text
                : text.substring(text.length() - SNAPSHOT_MAX_CHARS);
    }

    /** 读当前画像全文；文件不存在视为空画像（注入侧同源，保证所见即所提炼） */
    public String currentProfile() {
        Path path = Path.of(profileFile);
        return Files.exists(path) ? readString(path) : "";
    }

    private String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取画像文件失败: " + profileFile, e);
        }
    }

    /** LLM 合并画像（含重试与落库）；重试耗尽抛异常（processOne 统一 warn，失败原因可见）/ 输出为空返回 null */
    private String mergeProfile(String current, String snapshot) {
        String userPrompt = "【当前画像】\n" + (current.isBlank() ? "（空，首次生成）" : current)
                + "\n\n【对话内容】\n" + snapshot;
        return retry.executeWithRetry(() -> doCall(userPrompt),
                (attempt, durationMs, err) -> record(durationMs, err == null, err, userPrompt));
    }

    /** 单次 LLM 合并调用（不含重试语义，可被重试）；返回模型原始输出 */
    private String doCall(String userPrompt) {
        ChatClient client = clientRegistry.getByModel(PROFILE_MODEL);
        // 请求级显式携带 model：与 AgentChatCaller/RouteJudge 同款约定（缺 model DashScope 返回 400）
        return client.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(PROFILE_MODEL).build())
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }

    /** 画像落盘：临时文件 + 原子替换（防写一半崩溃留半个文件）；失败抛异常（调用方不标记） */
    private void writeProfile(String content) throws Exception {
        Path target = Path.of(profileFile);
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "user-profile-", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 提炼成功后标记（一次会话只提炼一次；活跃重置为独立 TODO 本期不做） */
    private void markExtracted(SessionEntity session) {
        UpdateWrapper<SessionEntity> uw = new UpdateWrapper<>();
        uw.eq("session_id", session.getSessionId())
                .set("profile_extracted", 1);
        sessionMapper.update(null, uw);
    }

    /** 提炼调用观测落库（无会话上下文口径：sessionId 空；轨迹标识全 NULL——不属于任何轮次与执行树） */
    private void record(long durationMs, boolean ok, Throwable e, String userPrompt) {
        if (recorder == null) {
            return;
        }
        int promptTokens = LlmCallRecorder.estimateTokens(SYSTEM_PROMPT)
                + LlmCallRecorder.estimateTokens(userPrompt);
        recorder.record(new LlmCallLog(null, "memory-profile", PROFILE_MODEL, false, ok,
                promptTokens, null, null, true,
                durationMs, LlmCallRecorder.describeError(e),
                null, null, null, null, null, null, null, null,
                null, null, null, null));
    }
}
