package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 会话历史归档检索：只读 recall_history 工具的数据源。
 *
 * <p>按会话强制过滤，调用方无法跨会话检索；单条内容在 SQL 侧截到
 * {@link #CONTENT_PREVIEW_CHARS} 字符，先控制结果体量，再交给 ToolCallBudget 做 token 预算。
 */
@Service
public class SessionHistoryArchiveService {

    /** 单条归档消息返回的最大字符数（避免 4MB 快照消息整段进内存） */
    static final int CONTENT_PREVIEW_CHARS = 2000;

    /** 单次检索最多返回条数（工具层还会再钳制，这里是服务层硬上限） */
    public static final int MAX_LIMIT = 10;

    private final SessionMessageArchiveMapper mapper;

    public SessionHistoryArchiveService(SessionMessageArchiveMapper mapper) {
        this.mapper = mapper;
    }

    /** 一条归档消息（内容为预览截断后的文本） */
    public record ArchivedMessage(Long archiveId, String messageId, String role, String content, Long ts) {
    }

    /**
     * 检索归档消息：messageId 非空按精确 ID，否则按关键词匹配正文；
     * 结果按归档时间倒序，最多 limit 条（上限 {@link #MAX_LIMIT}）。
     */
    public List<ArchivedMessage> search(String sessionId, String messageId, String query, int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        QueryWrapper<SessionMessageArchiveEntity> qw = new QueryWrapper<>();
        qw.select("id", "message_id", "role", "ts", "archived_at",
                        "LEFT(content, " + CONTENT_PREVIEW_CHARS + ") AS content")
                .eq("session_id", sessionId);
        if (messageId != null && !messageId.isBlank()) {
            qw.eq("message_id", messageId);
        } else {
            qw.like("content", query);
        }
        qw.orderByDesc("archived_at", "id").last("LIMIT " + cap);
        return mapper.selectList(qw).stream()
                .map(e -> new ArchivedMessage(e.getId(), e.getMessageId(), e.getRole(), e.getContent(), e.getTs()))
                .toList();
    }
}
