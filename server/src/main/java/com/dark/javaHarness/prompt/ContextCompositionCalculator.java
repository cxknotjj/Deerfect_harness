package com.dark.javaHarness.prompt;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.tool.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 上下文构成计算器（Path A / Path B 共用的纯函数）：
 * system 分段 + RAG + 工具 Schema + 会话消息 → {@link ContextComposition}。
 * 占比分母为各组成项之和；总数为 0 时不生成 parts。
 */
public final class ContextCompositionCalculator {

    /** system 段落名 → 固定 part 口径（顺序声明即 part 输出顺序，角色 → 画像 → 索引 → 纪律 → 约定 → 技能） */
    private static final Map<String, SectionSpec> SECTION_SPECS = Map.of(
            "角色", new SectionSpec("system.role", "角色提示词"),
            "用户偏好画像", new SectionSpec("system.profile", "用户画像"),
            "工具索引", new SectionSpec("system.toolIndex", "工具索引"),
            "工具纪律", new SectionSpec("system.discipline", "工具纪律"),
            "输出约定", new SectionSpec("system.output", "输出约定"),
            "skill", new SectionSpec("system.skillIndex", "技能索引"));

    private ContextCompositionCalculator() {
    }

    /**
     * 构建快照。finalMessages 为预算裁剪后的最终消息列表（system 消息不参与消息类统计）；
     * 最后一个 {@link UserMessage} 计为当前 user，其余 user/assistant 分别汇总为历史。
     */
    public static ContextComposition build(String sessionId, String agentName, String model, String turnId,
                                           SystemAssembly system, int toolSchemaTokens,
                                           List<Message> finalMessages) {
        List<ContextComposition.Part> parts = new ArrayList<>();
        int knownSectionTokens = 0;
        for (PromptAssembler.RenderedSection section : system.sections()) {
            SectionSpec spec = SECTION_SPECS.get(section.name());
            if (spec == null) {
                // 未登记段落（扩展段）自然落入 system.other 兜底，不单独成项
                continue;
            }
            knownSectionTokens += section.tokens();
            if (section.tokens() > 0) {
                parts.add(new ContextComposition.Part(spec.key(), spec.label(), "system",
                        section.tokens(), 0));
            }
        }
        // 拼接开销（段间空行）与未登记段落都归入 system.other；仅正数时成项
        int otherTokens = TokenEstimator.estimateTokens(system.text())
                - knownSectionTokens - system.ragTokens();
        if (otherTokens > 0) {
            parts.add(new ContextComposition.Part("system.other", "系统拼接", "system",
                    otherTokens, 0));
        }
        if (system.ragTokens() > 0) {
            parts.add(new ContextComposition.Part("retrieval.rag", "RAG 知识块", "retrieval",
                    system.ragTokens(), 0));
        }
        if (toolSchemaTokens > 0) {
            parts.add(new ContextComposition.Part("tools.schema", "工具 Schema", "tools",
                    toolSchemaTokens, 0));
        }

        ConversationTokens conversation = conversationTokens(finalMessages);
        if (conversation.historyUser() > 0) {
            parts.add(new ContextComposition.Part("conversation.history.user", "历史 user 消息",
                    "conversation", conversation.historyUser(), 0));
        }
        if (conversation.historyAssistant() > 0) {
            parts.add(new ContextComposition.Part("conversation.history.assistant", "历史 assistant 消息",
                    "conversation", conversation.historyAssistant(), 0));
        }
        if (conversation.currentUser() > 0) {
            parts.add(new ContextComposition.Part("conversation.currentUser", "当前 user 消息",
                    "conversation", conversation.currentUser(), 0));
        }

        int total = parts.stream().mapToInt(ContextComposition.Part::tokens).sum();
        List<ContextComposition.Part> withPercent = total == 0 ? List.of()
                : parts.stream()
                        .map(part -> new ContextComposition.Part(part.key(), part.label(), part.group(),
                                part.tokens(), percentOf(part.tokens(), total)))
                        .toList();
        return new ContextComposition(sessionId, agentName, model, turnId,
                System.currentTimeMillis(), total, withPercent);
    }

    /** 会话消息拆分：最后一个 user 为当前消息，其余 user/assistant 汇总为历史（system 排除）。 */
    private static ConversationTokens conversationTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return ConversationTokens.EMPTY;
        }
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
            }
        }
        int historyUser = 0;
        int historyAssistant = 0;
        int currentUser = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof SystemMessage) {
                continue;
            }
            int tokens = TokenEstimator.estimateTokens(message.getText());
            if (i == lastUser) {
                currentUser = tokens;
            } else if (message instanceof AssistantMessage) {
                historyAssistant += tokens;
            } else if (message instanceof UserMessage) {
                historyUser += tokens;
            }
            // 其他角色（如工具消息）不计入会话上下文占比
        }
        return new ConversationTokens(historyUser, historyAssistant, currentUser);
    }

    /** 占比：tokens / total，四舍五入保留 1 位小数。 */
    private static double percentOf(int tokens, int total) {
        return Math.round(tokens * 1000.0 / total) / 10.0;
    }

    private record SectionSpec(String key, String label) {
    }

    private record ConversationTokens(int historyUser, int historyAssistant, int currentUser) {
        static final ConversationTokens EMPTY = new ConversationTokens(0, 0, 0);
    }
}
