package com.dark.javaHarness.memory.plugin;

/**
 * 记忆检索请求：query 为当前用户消息，topK 来自插件配置。
 */
public record MemoryQuery(String query, MemoryScope scope, int topK) {
}
