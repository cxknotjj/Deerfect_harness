package com.dark.javaHarness.tool.builtin.memory;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import com.dark.javaHarness.tool.ToolContextKeys;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 显式记忆工具：记住 / 忘掉 / 查看。
 *
 * <p><b>只在用户明说时写入</b>：工具描述里明确「不要自行把普通对话内容记成记忆」——
 * 否则模型会把每轮闲聊都存一遍，记忆区变成第二份聊天记录。
 *
 * <p><b>忘掉不猜</b>：关键词命中多条时不删除，返回候选让模型向用户确认 id。
 * 误删一条用户珍视的记忆，代价远高于多问一句。
 *
 * <p>记忆是跨会话的，注入走 system 段（{@code ExplicitMemorySectionProviderImpl}），
 * 本类只负责读写，不参与注入。
 */
@Component
public class MemoryTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 保留天数上限：十年，防模型把「记住」写成天文数字 */
    private static final int MAX_EXPIRES_DAYS = 3650;

    private final MemoryService memoryService;

    public MemoryTools(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "remember",
            description = "把用户明确要求长期记住的事实写入记忆（跨会话生效，之后每轮对话都会带上）。"
                    + "只在用户说「记住…」「以后都…」或明确要求长期保留时调用；"
                    + "不要自行把普通对话内容、临时任务细节记成记忆。"
                    + "同一 key 再次写入会覆盖旧条目（用于「我换车牌了」这类更新）。")
    public String remember(
            @ToolParam(description = "要记住的事实正文（一句话，只写事实，不要带「请记住」之类的话术）")
            String content,
            @ToolParam(required = false, description = "可选短标签（如 车牌 / 技术栈 / 生日），便于之后定位与覆盖")
            String key,
            @ToolParam(required = false, description = "保留天数（正整数）；不传表示长期有效")
            Integer expiresInDays,
            ToolContext context) {
        try {
            LocalDateTime expiresAt = null;
            if (expiresInDays != null) {
                int days = Math.max(1, Math.min(expiresInDays, MAX_EXPIRES_DAYS));
                expiresAt = LocalDateTime.now().plusDays(days);
            }
            // 先数同 key 旧条目：remember 会软删它们，回复里要说清楚，否则模型会以为旧条目还在
            boolean replaced = key != null && !key.isBlank() && memoryService.countActiveByKey(key) > 0;
            Memory memory = memoryService.remember(content, key, "user", sessionId(context), expiresAt);
            StringBuilder reply = new StringBuilder("已记住（id=").append(memory.id());
            if (memory.key() != null) {
                reply.append("，标签=").append(memory.key());
            }
            reply.append("）：").append(memory.content());
            if (memory.expiresAt() != null) {
                reply.append("。有效期至 ").append(DATETIME.format(memory.expiresAt()));
            } else {
                reply.append("。长期有效");
            }
            if (replaced) {
                reply.append("（同标签的旧条目已自动替换，不再注入）");
            }
            return reply.append("，之后的对话会自动带上这条。").toString();
        } catch (IllegalArgumentException e) {
            return "没能记住：" + e.getMessage();
        }
    }

    @Tool(name = "forget",
            description = "忘掉一条已记住的事实。优先用 id 精确删除；不确定 id 时传 keyword（标签或正文关键词）定位。"
                    + "keyword 命中多条时不会删除，会返回候选，需让用户确认后再用 id 删除。")
    public String forget(
            @ToolParam(required = false, description = "记忆 id（list_memories 返回的编号）")
            Long id,
            @ToolParam(required = false, description = "标签或正文关键词（与 id 二选一；命中多条时不删除）")
            String keyword) {
        if (id != null) {
            return memoryService.findActive(id)
                    .map(memory -> {
                        memoryService.forget(id);
                        return "已忘掉（id=" + id + "）：" + preview(memory.content());
                    })
                    .orElse("没有找到 id=" + id + " 的有效记忆（可能已被删除或已过期）。");
        }
        if (keyword == null || keyword.isBlank()) {
            return "请提供 id，或提供 keyword（标签/正文关键词）来定位要忘掉的记忆。";
        }
        List<Memory> hits = memoryService.findActiveByKeyword(keyword);
        if (hits.isEmpty()) {
            return "没有匹配「" + keyword.strip() + "」的有效记忆。";
        }
        if (hits.size() == 1) {
            Memory memory = hits.get(0);
            memoryService.forget(memory.id());
            return "已忘掉（id=" + memory.id() + "）：" + preview(memory.content());
        }
        StringBuilder reply = new StringBuilder("「").append(keyword.strip())
                .append("」命中 ").append(hits.size()).append(" 条，未删除——请确认要忘掉哪一条，再用 id 调用 forget：\n");
        for (Memory memory : hits) {
            reply.append("- id=").append(memory.id());
            if (memory.key() != null) {
                reply.append(" [").append(memory.key()).append(']');
            }
            reply.append(' ').append(preview(memory.content())).append('\n');
        }
        return reply.toString().stripTrailing();
    }

    @Tool(name = "list_memories",
            description = "查看当前有效（未删除、未过期）的记忆：id / 标签 / 正文 / 来源 / 记录时间。"
                    + "用户问「你记住我什么了」或要核对记忆时调用。")
    public String listMemories() {
        List<Memory> memories = memoryService.list(Memory.Status.ACTIVE, 50);
        if (memories.isEmpty()) {
            return "目前没有记住任何事。用户明确要求「记住…」时才会写入记忆。";
        }
        StringBuilder reply = new StringBuilder("当前共 ").append(memories.size()).append(" 条有效记忆：\n");
        for (Memory memory : memories) {
            reply.append("- id=").append(memory.id());
            if (memory.key() != null) {
                reply.append(" [").append(memory.key()).append(']');
            }
            reply.append(' ').append(preview(memory.content()))
                    .append("（来源=").append(sourceLabel(memory.source()))
                    .append("，").append(DATE.format(memory.createdAt())).append(" 记，")
                    .append(memory.expiresAt() == null ? "长期" : "有效期至 " + DATE.format(memory.expiresAt()))
                    .append("，状态=有效）\n");
        }
        return reply.toString().stripTrailing();
    }

    private static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "user" -> "对话中明示";
            case "cli" -> "命令行";
            case "manual" -> "管理接口";
            default -> source;
        };
    }

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 60 ? content : content.substring(0, 60) + "…";
    }

    private static String sessionId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(ToolContextKeys.SESSION_ID);
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
