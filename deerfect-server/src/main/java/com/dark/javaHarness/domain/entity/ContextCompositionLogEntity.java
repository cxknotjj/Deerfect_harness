package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上下文构成快照实体，对应表 context_composition_log。
 * 由 ContextCompositionRecorder 在每次真实 LLM 请求发往模型前异步写入。
 */
@Data
@TableName("context_composition_log")
public class ContextCompositionLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private String sessionId;

    /** 调用方角色（general/lead/researcher/aggregator 等） */
    private String agentName;

    /** 实际命中模型的展示名（可空） */
    private String model;

    /** 轮次标识（编排内各节点同轮共用；可空） */
    private String turnId;

    /** 各组成项估算之和 */
    private Integer estimatedTotalTokens;

    /** 组成明细 JSON：[{key,label,group,tokens,percent}] */
    private String parts;

    /** 快照采集时刻 */
    private LocalDateTime createdAt;
}
