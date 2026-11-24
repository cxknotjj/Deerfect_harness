package com.dark.javaHarness.memory.plugin;

import java.util.Map;

/**
 * 一条召回的记忆：text 为注入 prompt 的正文（按插件配置截断），metadata 仅用于追溯、不注入。
 */
public record MemoryHit(String id, String text, Double score, Map<String, Object> metadata) {
}
