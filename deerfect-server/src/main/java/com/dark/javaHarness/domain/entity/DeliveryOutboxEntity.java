package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 主动投递 outbox，对应表 delivery_outbox（MySQL 主库）。
 *
 * <p>一条外发消息从入队到终态的全部状态都在这一行里：`PENDING` → `SENT` / `DEAD`。
 * `next_retry_at` 兼作领取租约——发送前先把它推到未来，进程在发送途中被杀也不会卡死，
 * 租约到期后自动重试。
 *
 * <p>状态与业务含义的对应：`SENT` 表示渠道确认收下（不代表用户已读），`DEAD` 表示
 * 重投耗尽或遇到不可重试错误（配置错 / 内容问题），`last_error` 是唯一的事后线索。
 */
@Data
@TableName("delivery_outbox")
public class DeliveryOutboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户（当前单用户固定 default） */
    private String tenantId;

    /** 来源：schedule / alert / manual */
    private String source;

    /** 业务指针（schedule.id 或 goal.id），仅追溯用 */
    private String refId;

    /** 降级链原文，如 {@code feishu:main|email:me@example.com} */
    private String channelChain;

    /** 当前尝试到链上第几个通道（0 起） */
    private Integer chainIndex;

    /** 消息正文（重投时不再回查业务表） */
    private String content;

    /** PENDING / SENT / DEAD */
    private String status;

    /** 总尝试次数（跨通道累加，发送前递增，仅供审计） */
    private Integer attempts;

    /** 当前跳的尝试次数（决定退避与链推进；换跳时归零） */
    private Integer hopAttempts;

    /** 最后一次失败原因（含渠道原始错误码） */
    private String lastError;

    /** 下次可尝试时刻，兼作领取租约 */
    private LocalDateTime nextRetryAt;

    /** 送达时刻 */
    private LocalDateTime sentAt;

    /** 入队时刻 */
    private LocalDateTime createdAt;
}
