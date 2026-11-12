package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话历史归档实体，对应表 session_message_archive：
 * 快照裁剪移除的消息先落归档，供 recall_history 按会话检索，避免「裁掉即丢」。
 */
@Data
@TableName("session_message_archive")
public class SessionMessageArchiveEntity {

    /** 自增主键（归档顺序） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    private String sessionId;

    /** 快照 item 的稳定消息 ID */
    private String messageId;

    /** 消息角色：user/assistant */
    private String role;

    /** 消息原文（part=tail 时为被截断的尾部） */
    private String content;

    /** 归档分段：full-整条 / tail-单条超限截断的尾部 */
    private String part;

    /** 消息时间戳（epoch 毫秒，可空） */
    private Long ts;

    /** 租户隔离 ID */
    private String tenantId;

    /** 归档时间 */
    private LocalDateTime archivedAt;
}
