package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 显式记忆，对应表 memory（MySQL 主库）。
 * 用户明说「记住这个」时写入，跨会话注入 system；与画像（模型推断）来源不同、互不覆盖。
 */
@Data
@TableName("memory")
public class MemoryEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户（当前单用户固定 default） */
    private String tenantId;

    /** 可选短标签（车牌/技术栈），同 key 以最新为准 */
    private String memoryKey;

    /** 记忆正文 */
    private String content;

    /** 来源：user=对话中明示 / cli=命令行 / manual=管理接口 */
    private String source;

    /** 来源会话（仅追溯，不参与过滤） */
    private Long sessionId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /** 过期时间（NULL=长期） */
    private LocalDateTime expiresAt;

    /** 软删时间（NULL=有效） */
    private LocalDateTime deletedAt;
}
