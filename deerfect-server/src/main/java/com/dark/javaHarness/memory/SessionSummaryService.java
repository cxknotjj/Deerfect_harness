package com.dark.javaHarness.memory;

import com.dark.javaHarness.agent.LlmRetry;
import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.SessionSummaryEntity;
import com.dark.javaHarness.mapper.SessionSummaryMapper;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.contract.api.SessionSummaryPort;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 会话长期记忆滚动摘要（P2-3）：上下文预算裁剪丢弃旧轮次时，把丢弃内容异步压缩成
 * 一段 ≤{@code app.context.summary-max-chars} 的摘要，落 session_summary 表；下一轮
 * 由 ContextAssemblingAdvisor 作为 system 段注入，回答「开头说过的约束」。
 *
 * <p><b>增量与滚动合并</b>：covered_message_id 记录已并入摘要的最大快照消息 ID；
 * 只有更新的丢弃内容才触发 LLM，输入为「已有摘要 + 新增对话」，输出整体替换（滚动合并）。
 *
 * <p><b>降级</b>：读取失败返回 null（组装侧退回无摘要现状）；生成失败只 WARN、不写库、
 * 不推进游标（下次裁剪自动重试）；整个过程在独立守护线程池执行，绝不阻塞聊天主链路。
 *
 * <p>LLM 通道与 UserProfileService / RouteJudge 同款：轻量模型（请求级显式 model）+ 重试 +
 * llm_call_log 落库口径（agent_name='memory-summary'）。
 */
@Service
public class SessionSummaryService implements SessionSummaryPort {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    /** 单次喂给模型的对话字符上限（超长取尾部：旧事实已在既有摘要里） */
    private static final int MAX_INPUT_CHARS = 4000;

    /** 会话串行：同一会话同一时刻只允许一个摘要任务，避免并发覆盖写 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final SessionSummaryMapper mapper;
    @Nullable
    private final LlmCallRecorder recorder;
    private final com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry;
    private final com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog;
    private final com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver;
    private final int maxChars;
    private final LlmRetry retry = new LlmRetry();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "session-summary");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public SessionSummaryService(SessionSummaryMapper mapper,
                                 com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry,
                                 com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog,
                                 com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver,
                                 @Nullable LlmCallRecorder recorder,
                                 @Value("${app.context.summary-max-chars:600}") int maxChars) {
        this.mapper = mapper;
        this.llmClientRegistry = llmClientRegistry;
        this.llmAgentCatalog = llmAgentCatalog;
        this.llmModelResolver = llmModelResolver;
        this.recorder = recorder;
        this.maxChars = Math.max(120, maxChars);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public String summaryOf(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return null;
        }
        try {
            SessionSummaryEntity row = mapper.selectById(sid);
            String summary = row == null ? null : row.getSummary();
            return summary == null || summary.isBlank() ? null : summary;
        } catch (Exception e) {
            // 读失败降级：组装侧按「无摘要」继续，不影响主链路
            log.debug("[memory-summary] 会话 {} 摘要读取失败（按无摘要继续）：{}", sessionId, describe(e));
            return null;
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        try {
            mapper.deleteById(sid);
        } catch (Exception e) {
            // 清理是旁路：失败只告警，不阻断会话删除
            log.warn("[memory-summary] 会话 {} 摘要删除失败（忽略）：{}", sessionId, describe(e));
        }
    }

    @Override
    public void requestSummary(String sessionId, List<Message> droppedMessages) {
        if (sessionId == null || sessionId.isBlank() || droppedMessages == null) {
            return;
        }
        List<Message> usable = droppedMessages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .filter(m -> m.getText() != null && !m.getText().isBlank())
                .toList();
        if (usable.isEmpty() || parseSessionId(sessionId) == null) {
            return;
        }
        if (!inFlight.add(sessionId)) {
            log.debug("[memory-summary] 会话 {} 已有摘要任务在跑，跳过本轮投递", sessionId);
            return;
        }
        try {
            pool.execute(() -> {
                try {
                    summarizeNow(sessionId, usable);
                } catch (Exception e) {
                    // 失败不写库不推进游标：下次裁剪带同一批消息自动重试
                    log.warn("[memory-summary] 会话 {} 摘要生成失败（下次重试）：{}", sessionId, describe(e));
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(sessionId);
            log.debug("[memory-summary] 摘要队列已满，跳过本轮（不阻塞主链）");
        }
    }

    /** 同步生成并落库（异步任务的实体；包内可见便于单测直接驱动） */
    void summarizeNow(String sessionId, List<Message> droppedMessages) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        SessionSummaryEntity row = mapper.selectById(sid);
        String covered = row == null ? null : row.getCoveredMessageId();
        String lastId = lastMessageId(droppedMessages);
        if (lastId != null && covered != null && compareIds(lastId, covered) <= 0) {
            log.debug("[memory-summary] 会话 {} 摘要游标已覆盖 {}，跳过", sessionId, lastId);
            return;
        }
        String existing = row == null || row.getSummary() == null ? "" : row.getSummary();
        String dialog = render(droppedMessages);
        String merged = merge(existing, dialog);
        if (merged == null || merged.isBlank()) {
            log.warn("[memory-summary] 会话 {} 模型输出为空，跳过（不推进游标）", sessionId);
            return;
        }
        String summary = clamp(merged.trim());
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            SessionSummaryEntity created = new SessionSummaryEntity();
            created.setSessionId(sid);
            created.setSummary(summary);
            created.setCoveredMessageId(lastId);
            created.setUpdateTime(now);
            mapper.insert(created);
        } else {
            row.setSummary(summary);
            if (lastId != null) {
                row.setCoveredMessageId(lastId);
            }
            row.setUpdateTime(now);
            mapper.updateById(row);
        }
        log.info("[memory-summary] 会话 {} 摘要已更新（{} 字符，覆盖至 {}）", sessionId, summary.length(),
                lastId == null ? covered : lastId);
    }

    /** LLM 滚动合并（含重试与观测），任何异常上抛给异步包装统一降级 */
    private String merge(String existing, String dialog) {
        String userPrompt = "【已有摘要】\n" + (existing.isBlank() ? "（空，首次生成）" : existing)
                + "\n\n【新增对话】\n" + dialog;
        com.dark.javaHarness.llm.routing.RouteAttempt route = routeAttempt();
        return retry.executeWithRetry(() -> doCall(route, userPrompt),
                (attempt, durationMs, err) -> record(durationMs, err == null, err, userPrompt, route));
    }

    /** summary 系统 Agent → 路由首选成员（观测与调用使用同一成员） */
    private com.dark.javaHarness.llm.routing.RouteAttempt routeAttempt() {
        com.dark.javaHarness.llm.routing.RoutePlan plan = llmModelResolver.resolve(
                llmAgentCatalog.require("summary").getRouteId());
        return com.dark.javaHarness.llm.routing.RouteAttempt.primary(plan, plan.members().get(0));
    }

    private String doCall(com.dark.javaHarness.llm.routing.RouteAttempt route, String userPrompt) {
        ChatClient client = llmClientRegistry.get(route.modelId());
        // 请求级显式携带 model：与 AgentChatCaller / RouteJudge / memory-profile 同款约定
        return client.prompt()
                .system(systemPrompt())
                .user(userPrompt)
                .options(OpenAiChatOptions.builder().model(route.upstreamName()))
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }

    private String systemPrompt() {
        return "你是会话记忆压缩器。输入是【已有摘要】（可能为空）和【新增对话】。\n"
                + "请把两者合并为一段供后续对话使用的历史摘要：\n"
                + "1. 保留用户的目标、约束、偏好、关键事实、已确认结论与未完成事项；\n"
                + "2. 删除寒暄、重复内容和过程性细节；\n"
                + "3. 按时间先后叙述，第三人称，不超过 " + maxChars + " 字；\n"
                + "4. 只输出摘要正文，不要标题、不要解释、不要代码块包裹。";
    }

    /** 被丢弃消息渲染为对话文本（超长取尾部：更早的内容已在既有摘要里） */
    private static String render(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m instanceof org.springframework.ai.chat.messages.AssistantMessage
                            ? "assistant" : "user")
                    .append(": ").append(m.getText()).append('\n');
        }
        String text = sb.toString();
        return text.length() <= MAX_INPUT_CHARS
                ? text
                : text.substring(text.length() - MAX_INPUT_CHARS);
    }

    /** 最后一条消息的快照 ID（metadata 缺失返回 null：此时不参与游标比较） */
    private static String lastMessageId(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object value = messages.get(i).getMetadata().get(ChatMessageMetadata.MESSAGE_ID);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** 快照消息 ID 为数值字符串：按数值比较；任一方非数值时退回字符串比较 */
    private static int compareIds(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private String clamp(String text) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static Long parseSessionId(String sessionId) {
        try {
            return Long.valueOf(sessionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 摘要调用观测落库（无会话上下文口径：sessionId 空；不属于任何轮次与执行树） */
    private void record(long durationMs, boolean ok, Throwable e, String userPrompt,
                        com.dark.javaHarness.llm.routing.RouteAttempt route) {
        if (recorder == null) {
            return;
        }
        int promptTokens = LlmCallRecorder.estimateTokens(systemPrompt())
                + LlmCallRecorder.estimateTokens(userPrompt);
        long start = System.currentTimeMillis() - durationMs;
        recorder.record(new LlmCallLog(null, "memory-summary", route.upstreamName(), false, ok,
                promptTokens, null, null, true,
                durationMs, LlmCallRecorder.describeError(e),
                null, null, null, null, null, null, null, null,
                null, null, null, null, LlmCallRecorder.startedAt(start),
                route.routeId(), route.routeCode(), route.modelId(), route.providerCode(),
                route.upstreamName(), route.fallbackFromModelId(),
                ok ? null : com.dark.javaHarness.llm.routing.FailurePolicy.errorClass(e)));
    }

    private static String describe(Throwable e) {
        return LlmCallRecorder.describeError(e);
    }
}
