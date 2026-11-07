package com.dark.javaHarness.advisor;

import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.service.SessionSummaryPort;
import com.dark.javaHarness.tool.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 执行上下文组装拦截器（Spring AI Advisor）。
 *
 * <p>职责：对一次 ChatClient 调用中已注入的 messages 序列做
 * 「执行上下文组装」——过滤空/系统噪声、保证 role 交替顺序、按 token 预算从旧丢弃。
 * 它是纯横切增强：不负责"从哪里加载历史"（历史加载由 Agent 侧 SessionService 完成，
 * 与本 Advisor 解耦），只负责"把已给到的消息裁剪成一个 LLM 友好、受控长度的上下文"。
 *
 * <p>对应 HARNESS_TODO ①（执行上下文组装）：
 * - 按 token 预算裁剪（保留 system + 最近 N 轮）
 * - 过滤空/系统噪声消息
 * - 保证 role 顺序（system → user/assistant 交替）
 */
public class ContextAssemblingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblingAdvisor.class);

    private final int tokenBudget;

    /** 会话标识（摘要读写键；null = 无会话场景，摘要机制零行为） */
    private final String sessionId;

    /** 长期记忆摘要端口（P2-3）：注入已有摘要 + 投递被裁消息；null = 单测/无摘要场景 */
    private final SessionSummaryPort summaryPort;

    /**
     * @param tokenBudget 会话历史 token 预算（生产由 GeneralAssistantAgent 从
     *                    app.context.history-budget 注入，yaml 为唯一数值源）；≤ 0 = 不裁剪
     * @param sessionId   会话标识（非空且 summaryPort 非空时启用滚动摘要）
     * @param summaryPort 长期记忆摘要端口（可 null）
     */
    public ContextAssemblingAdvisor(int tokenBudget, String sessionId, SessionSummaryPort summaryPort) {
        this.tokenBudget = tokenBudget;
        this.sessionId = sessionId;
        this.summaryPort = summaryPort;
    }

    @Override
    public String getName() {
        return "context-assembling-advisor";
    }

    @Override
    public int getOrder() {
        // 早于默认内存 advisor 执行，确保组装先于记忆注入等
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt assembled = assemble(request.prompt());
        ChatClientRequest rebuilt = ChatClientRequest.builder()
                .prompt(assembled)
                .context(request.context())
                .build();
        return chain.nextCall(rebuilt);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {
        Prompt assembled = assemble(request.prompt());
        ChatClientRequest rebuilt = ChatClientRequest.builder()
                .prompt(assembled)
                .context(request.context())
                .build();
        return chain.nextStream(rebuilt);
    }

    /* ---------------- 组装纯函数（便于单测） ---------------- */

    /**
     * 对 Prompt 内的消息序列做上下文组装：
     * 1. 过滤空内容 / 系统噪声（空白内容消息丢弃）；
     * 2. 归一化 role 顺序（以 system 开头，之后 user/assistant 交替压制连续同类）；
     * 3. 从最旧丢弃直至总 token 估算不超过预算（system 始终保留）。
     * <p>重建 Prompt 时保留原 options（含 model 等 ChatOptions），避免丢失模型参数。
     */
    public Prompt assemble(Prompt prompt) {
        List<Message> raw = prompt.getInstructions();
        List<Message> assembled = assemble(raw);
        return new Prompt(assembled, prompt.getOptions());
    }

    /** 组装消息列表（核心逻辑，纯函数可测）。 */
    public List<Message> assemble(List<Message> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? new ArrayList<>() : new ArrayList<>(raw);
        }
        // 1. 过滤空消息 / 系统噪声
        List<Message> cleaned = filterNoise(raw);
        // 2. 归一化 role 顺序
        List<Message> normalized = normalizeRoles(cleaned);
        // 3. 注入长期记忆摘要（system 段，参与预算；摘要本身不会被裁掉）
        injectSummary(normalized);
        // 4. token 预算从旧丢弃（保留 system + 最近消息）；预算 ≤ 0 = 不限制，跳过裁剪
        if (tokenBudget <= 0) {
            return normalized;
        }
        return trimToBudget(normalized);
    }

    /** 过滤空白内容与纯系统占位消息。 */
    private List<Message> filterNoise(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            String text = m.getText();
            if (text == null || text.isBlank()) {
                continue; // 空内容丢弃
            }
            if (m instanceof SystemMessage && text.isBlank()) {
                continue; // 系统占位噪声丢弃
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 归一化 role 顺序：以 system 开头（若有）；之后 user/assistant 交替，
     * 压制任意连续同类消息（保留最后一个，system 除外）。
     */
    private List<Message> normalizeRoles(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof SystemMessage) {
                // system 统一放在最前（去重多余 system）
                out.removeIf(x -> x instanceof SystemMessage);
                out.add(0, m);
                continue;
            }
            String role = roleOf(m);
            if (!out.isEmpty()) {
                String lastRole = roleOf(out.get(out.size() - 1));
                // 连续同类且非 system：替换为当前（保留最新）
                if (lastRole.equals(role) && !"system".equals(lastRole)) {
                    out.set(out.size() - 1, m);
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 按 token 预算从最旧丢弃：保留 system 与最近的 user/assistant，直至估算 token ≤ 预算。 */
    private List<Message> trimToBudget(List<Message> messages) {
        if (messages.size() <= 1) {
            return messages;
        }
        long total = messages.stream().mapToLong(this::estimateTokens).sum();
        if (total <= tokenBudget) {
            return messages;
        }
        List<Message> out = new ArrayList<>(messages);
        // 从最旧逐步丢弃（Skip system，予以保留）；记录被丢消息的 ID 与原文，供归档引用与摘要
        List<String> droppedIds = new ArrayList<>();
        List<Message> dropped = new ArrayList<>();
        while (out.size() > 1 && total > tokenBudget) {
            int dropIdx = -1;
            for (int i = 0; i < out.size(); i++) {
                if (!(out.get(i) instanceof SystemMessage)) {
                    dropIdx = i;
                    break;
                }
            }
            if (dropIdx < 0) {
                break; // 全为 system，无法再丢
            }
            Message victim = out.get(dropIdx);
            String messageId = messageId(victim);
            if (messageId != null) {
                droppedIds.add(messageId);
            }
            dropped.add(victim);
            total -= estimateTokens(victim);
            out.remove(dropIdx);
        }
        if (!droppedIds.isEmpty()) {
            // 窗口外还有历史：告诉模型归档入口，否则它不会主动召回
            String ref = "【历史引用】更早 " + droppedIds.size()
                    + " 条消息已移出当前窗口并归档（消息 ID 示例：" + droppedIds.get(0)
                    + "），可用 recall_history 工具按关键词或消息 ID 检索。";
            appendToSystem(out, ref);
        }
        requestSummary(dropped);
        log.info("[context-assembler] 裁剪后消息数={}, 估算token={}/{}", out.size(), total, tokenBudget);
        return out;
    }

    /**
     * 注入已有滚动摘要：裁剪前先放入 system 段（system 永不参与丢弃，摘要不会丢）。
     * 读取失败按无摘要继续（降级到现状「丢最旧轮次」）。
     */
    private void injectSummary(List<Message> messages) {
        if (summaryPort == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String summary;
        try {
            summary = summaryPort.summaryOf(sessionId);
        } catch (Exception e) {
            log.debug("[context-assembler] 摘要读取失败（按无摘要继续）: {}", e.getClass().getSimpleName());
            return;
        }
        if (summary == null || summary.isBlank()) {
            return;
        }
        appendToSystem(messages, "【历史摘要】" + summary.trim());
    }

    /** 投递本次被裁消息（异步 best-effort，任何异常不得影响组装结果） */
    private void requestSummary(List<Message> dropped) {
        if (summaryPort == null || sessionId == null || sessionId.isBlank() || dropped.isEmpty()) {
            return;
        }
        try {
            summaryPort.requestSummary(sessionId, dropped);
        } catch (Exception e) {
            log.debug("[context-assembler] 摘要投递失败（忽略）: {}", e.getClass().getSimpleName());
        }
    }

    /** 消息 ID（快照读取时写入 metadata）；缺失（旧快照/非会话消息）返回 null */
    private static String messageId(Message message) {
        Object value = message.getMetadata().get(ChatMessageMetadata.MESSAGE_ID);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** 摘要/归档引用追加到 system 消息（保持单条 system，避免 role 归一化被二次破坏） */
    private static void appendToSystem(List<Message> messages, String ref) {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage system) {
            messages.set(0, new SystemMessage(system.getText() + "\n\n" + ref));
            return;
        }
        messages.add(0, new SystemMessage(ref));
    }

    /** token 估算统一走 {@link TokenEstimator}（全项目唯一口径，与观测/工具预算可比对） */
    private long estimateTokens(Message m) {
        return TokenEstimator.estimateTokens(m.getText());
    }

    private String roleOf(Message m) {
        if (m instanceof SystemMessage) {
            return "system";
        }
        if (m instanceof AssistantMessage) {
            return "assistant";
        }
        return "user";
    }
}