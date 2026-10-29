package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 流式聊天的 SSE meta 事件负载（末尾发送）。
 *
 * <p>{@code sources} 为 RAG 知识库本次会话最近命中的出处（docName/title/score）；
 * 知识库未启用/无命中时为 null。
 * <p>{@code route} 为本轮路由决策快照（路径/置信度/工作流/来源），供 UI 展示与排障。
 */
public record SseMeta(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String error,
        List<KnowledgeSource> sources,
        RouteInfo route) {
}
