package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 会话历史归档检索：只读 recall_history 工具的数据源。
 *
 * <p>两种范围：{@code current}（默认）按会话强制过滤，模型无法越权；{@code allSessions}
 * 跨全部历史会话召回——「上个月那次怎么解决的」只能靠后者。跨会话结果的 {@code sessionId}
 * 随记录返回，工具层据此标注来源，用户才能对上「是哪次会话」。
 *
 * <p>单条内容在 SQL 侧截到 {@link #CONTENT_PREVIEW_CHARS} 字符，先控制结果体量，再交给
 * ToolCallBudget 做 token 预算。跨会话检索目前是「时间倒序 + 关键词」；语料再大需要向量化
 * 召回（与滚动摘要共用 embedding 工件），本层接口不变。
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

    /** 一条归档消息（内容为预览截断后的文本；sessionId 用于跨会话结果标注来源） */
    public record ArchivedMessage(Long archiveId, String sessionId, String messageId,
                                  String role, String content, Long ts) {
    }

    /** 会话内检索（recall_history 的默认范围，行为与历史版本一致） */
    public List<ArchivedMessage> search(String sessionId, String messageId, String query, int limit) {
        return search(sessionId, messageId, query, limit, false);
    }

    /**
     * 检索归档消息：messageId 非空按精确 ID，否则按关键词匹配正文；
     * 结果按归档时间倒序，最多 limit 条（上限 {@link #MAX_LIMIT}）。
     *
     * @param allSessions true = 跨全部会话检索（纯关键词或精确 ID，仍按时间倒序）；
     *                    false = 限定 sessionId，sessionId 为空时直接返回空表
     */
    public List<ArchivedMessage> search(String sessionId, String messageId, String query, int limit,
                                        boolean allSessions) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        QueryWrapper<SessionMessageArchiveEntity> qw = new QueryWrapper<>();
        qw.select("id", "session_id", "message_id", "role", "ts", "archived_at",
                        "LEFT(content, " + CONTENT_PREVIEW_CHARS + ") AS content");
        if (!allSessions) {
            if (sessionId == null || sessionId.isBlank()) {
                return List.of();
            }
            qw.eq("session_id", sessionId);
        }
        if (messageId != null && !messageId.isBlank()) {
            qw.eq("message_id", messageId);
        } else {
            qw.like("content", query);
        }
        qw.orderByDesc("archived_at", "id").last("LIMIT " + cap);
        return mapper.selectList(qw).stream()
                .map(e -> new ArchivedMessage(e.getId(), e.getSessionId(), e.getMessageId(),
                        e.getRole(), e.getContent(), e.getTs()))
                .toList();
    }
}
