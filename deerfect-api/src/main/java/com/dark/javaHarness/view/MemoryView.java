package com.dark.javaHarness.view;

import java.time.LocalDateTime;

/**
 * 一条显式记忆的视图。
 *
 * @param id        主键
 * @param key       可选短标签（车牌/技术栈）
 * @param content   记忆正文
 * @param source    来源：user=对话中明示 / cli=命令行 / manual=管理接口
 * @param sessionId 来源会话（仅追溯，可空）
 * @param createdAt 记录时间
 * @param expiresAt 过期时间（null=长期）
 * @param status    状态：ACTIVE / EXPIRED / DELETED
 */
public record MemoryView(long id, String key, String content, String source, String sessionId,
                         LocalDateTime createdAt, LocalDateTime expiresAt, String status) {
}
