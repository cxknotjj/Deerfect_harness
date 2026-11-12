package com.dark.javaHarness.view;

import java.time.LocalDateTime;

/**
 * 新增记忆请求（POST /api/memories，CLI / 管理接口通道）。
 *
 * @param content   正文（必填）
 * @param key       可选短标签；同 key 会覆盖旧记忆
 * @param expiresAt 过期时间（null=长期）
 */
public record MemoryCreateRequest(String content, String key, LocalDateTime expiresAt) {
}
