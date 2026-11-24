package com.dark.javaHarness.memory.plugin;

/**
 * 一轮待写入的对话：只在成功轮产生，turnId 用于同轮去重。
 *
 * @param channel 来源渠道（cli/qq/web 等，可空）
 */
public record MemoryTurn(MemoryScope scope, String userMessage, String assistantReply,
                         String turnId, String channel, long occurredAt) {
}
