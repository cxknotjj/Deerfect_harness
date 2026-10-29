package com.dark.javaHarness.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求体（POST /api/chat、/api/chat/stream）。
 * message 必填，sessionId 可选（为空时自动创建新会话并在响应中返回）。
 * agentId 可选：指定要调用的 Agent（对应 agent 表主键），为空时走默认 Agent。
 * routing 可选：{@code auto}（默认）/ {@code agentic} / {@code graph}，显式指定执行路径。
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        String sessionId,
        Long agentId,
        String routing) {

    /** 不指定执行路径（auto，由服务端判定）的常用形态。 */
    public ChatRequest(String message, String sessionId, Long agentId) {
        this(message, sessionId, agentId, null);
    }
}