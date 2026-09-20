package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 会话历史消息响应（GET /api/harness/sessions/{sessionId}/messages）。
 * 把会话上下文快照（session_messages.content 的全量 JSON）按时间顺序还原为 role/content 列表，
 * 供前端切回旧会话时回显。会话快照只存 user / assistant 两类角色（system 提示词不入库）。
 */
public record SessionMessagesView(String sessionId, List<Item> messages) {

    /** 单条历史消息（role: user / assistant） */
    public record Item(String role, String content) {
    }
}