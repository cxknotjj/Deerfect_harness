package com.dark.javaHarness.domain;

import java.time.LocalDateTime;

/**
 * 显式记忆（领域对象）：用户明确要求「记住」的一条事实。
 *
 * @param id        主键
 * @param key       可选短标签（车牌/技术栈），同 key 以最新为准
 * @param content   记忆正文
 * @param source    来源：user=对话中明示 / cli=命令行 / manual=管理接口
 * @param sessionId 来源会话（仅追溯）
 * @param createdAt 创建时间
 * @param expiresAt 过期时间（null=长期）
 * @param status    当前状态（有效 / 已过期 / 已删除）
 */
public record Memory(long id, String key, String content, String source, String sessionId,
                     LocalDateTime createdAt, LocalDateTime expiresAt, Status status) {

    /** 记忆在某一时刻的状态（list 时按 now 判定，不落库） */
    public enum Status {
        /** 未删除且未过期，会注入 system */
        ACTIVE,
        /** 未删除但已过 expires_at，不再注入 */
        EXPIRED,
        /** 已软删，保留审计但不再注入 */
        DELETED
    }
}
