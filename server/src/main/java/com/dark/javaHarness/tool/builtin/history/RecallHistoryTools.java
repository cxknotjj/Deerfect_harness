package com.dark.javaHarness.tool.builtin.history;

import com.dark.javaHarness.service.impl.SessionHistoryArchiveService;
import com.dark.javaHarness.service.impl.SessionHistoryArchiveService.ArchivedMessage;
import com.dark.javaHarness.tool.ToolContextKeys;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * recall_history 工具：按关键词或消息 ID 检索本会话已归档的更早消息。
 *
 * <p>会话 ID 经 ToolContext 由服务端注入（模型不可见、不可改），跨会话检索被服务端过滤杜绝。
 * 结果由既有 ToolCallBudget 装饰链做工具结果 token 预算，本类只控制单条预览长度与条数。
 */
@Component
public class RecallHistoryTools {

    /** 默认返回条数 */
    private static final int DEFAULT_LIMIT = 3;

    private final SessionHistoryArchiveService archiveService;

    public RecallHistoryTools(SessionHistoryArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Tool(name = "recall_history",
            description = "检索本会话已被上下文裁剪归档的更早消息。"
                    + "当用户提到当前对话窗口之外的历史、需要找回早期细节时使用；"
                    + "按关键词（query）或精确消息 ID（messageId）二选一检索。")
    public String recallHistory(
            @ToolParam(required = false, description = "关键词，匹配归档消息正文（与 messageId 二选一）")
            String query,
            @ToolParam(required = false, description = "精确消息 ID（历史引用行中给出的 m- 开头 ID）")
            String messageId,
            @ToolParam(required = false, description = "返回条数，默认 3，最大 10")
            Integer limit,
            ToolContext context) {
        String sessionId = sessionId(context);
        if (sessionId == null) {
            return "无法定位当前会话，不能检索归档历史。";
        }
        if (isBlank(query) && isBlank(messageId)) {
            return "请提供关键词 query 或消息 ID messageId 后再检索归档历史。";
        }
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, SessionHistoryArchiveService.MAX_LIMIT));
        List<ArchivedMessage> hits = archiveService.search(sessionId, messageId, query, cap);
        if (hits.isEmpty()) {
            return "未命中归档消息（可能未超出上下文窗口，或关键词不匹配）。";
        }
        StringBuilder sb = new StringBuilder("命中 ").append(hits.size()).append(" 条归档消息：");
        for (int i = 0; i < hits.size(); i++) {
            ArchivedMessage m = hits.get(i);
            sb.append("\n\n[").append(i + 1).append("] ").append(m.role())
                    .append("（messageId=").append(m.messageId()).append("）\n")
                    .append(m.content() == null ? "" : m.content());
        }
        return sb.toString();
    }

    /** 从 ToolContext 取服务端注入的会话 ID；缺失/类型不符返回 null（工具不应越权推断） */
    private static String sessionId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(ToolContextKeys.SESSION_ID);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
