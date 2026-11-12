package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话长期记忆滚动摘要，对应表 session_summary（MySQL 主库）。
 * 上下文预算裁剪时把被丢弃的轮次异步压缩进 summary；covered_message_id 记录已并入的
 * 最大快照消息 ID，下一轮只处理更新的丢弃内容（滚动合并）。
 */
@Data
@TableName("session_summary")
public class SessionSummaryEntity {

    /** 会话主键（与 session.session_id 对应，手工指定不依赖自增） */
    @TableId(type = IdType.INPUT)
    private Long sessionId;

    /** 摘要正文（已覆盖 coveredMessageId 及更早的历史） */
    private String summary;

    /** 已纳入摘要的最大快照消息 ID（增量游标；为空表示尚未记录） */
    private String coveredMessageId;

    /** 最近一次摘要生成时间 */
    private LocalDateTime updateTime;
}
