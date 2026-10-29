package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 聊天接口响应体（POST /api/chat）。
 *
 * <p>{@code sources} 为 RAG 知识库最近一次命中的出处（docName/title/score）；
 * 知识库未启用/无命中时为 null 或空列表。
 *
 * <p>{@code agent} 为实际使用的 agent 名（回退/分流可观察）：显式 agentId 未命中回退默认、
 * 智能分流结果都从这里透出，调用方无需猜「这条回答是谁生成的」。
 *
 * <p>{@code flags} 为渠道无关的通用标志集合（如 "voice"）：由 core 的
 * {@link com.dark.javaHarness.service.ReplyPostProcessor} 等扩展点声明而来，无实现时为空集。
 *
 * <p>{@code route} 为本轮路由决策快照（路径/置信度/工作流/来源）。
 */
public record ChatResponse(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String reply,
        String error,
        List<KnowledgeSource> sources,
        String agent,
        java.util.Set<String> flags,
        RouteInfo route) {

    /** 成功响应：携带来出处、实际 Agent、渠道通用标志与路由决策。 */
    public static ChatResponse success(String sessionId, boolean newSession, String goalId, String reply,
                                       List<KnowledgeSource> sources, String agent,
                                       java.util.Set<String> flags, RouteInfo route) {
        return new ChatResponse(sessionId, newSession, goalId, "SUCCEEDED", reply, null, sources, agent,
                flags, route);
    }

    /** 失败响应：透出实际 Agent 与路由决策。 */
    public static ChatResponse failure(String sessionId, boolean newSession, String goalId, String error,
                                       String agent, RouteInfo route) {
        return new ChatResponse(sessionId, newSession, goalId, "FAILED", null, error, null, agent,
                java.util.Set.of(), route);
    }
}
