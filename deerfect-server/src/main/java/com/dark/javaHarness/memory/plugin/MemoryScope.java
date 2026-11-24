package com.dark.javaHarness.memory.plugin;

/**
 * 记忆作用域：决定一份记忆属于谁、由哪个 Agent 使用。
 *
 * @param userId    稳定用户标识（取 {@code session.creator}；禁止用 default/anonymous 以外的占位）
 * @param agentName 当前 Agent 名（跨用户共享同一记忆库时按 Agent 隔离）
 * @param sessionId 会话 id，仅用于追溯（可空）
 */
public record MemoryScope(String userId, String agentName, String sessionId) {

    public MemoryScope {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("记忆作用域缺少 userId");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("记忆作用域缺少 agentName");
        }
    }
}
