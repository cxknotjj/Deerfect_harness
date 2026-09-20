package com.dark.javaHarness.domain.dto;

/**
 * Agent 列表条目（GET /api/harness/agents）：id 即 agent 表主键，
 * web 端下拉直接以真实 id 绑定会话（替代旧的「下标 + 1」约定）。
 */
public record AgentItemView(Long id, String name) {
}
