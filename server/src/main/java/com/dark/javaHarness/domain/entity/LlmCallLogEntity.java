package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * LLM 调用观测实体，对应表 llm_call_log。
 * 由 LlmCallRecorder 在每次 LLM 调用结束后异步写入。
 */
@Data
@TableName("llm_call_log")
public class LlmCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话ID（路由判断等无会话场景为空） */
    private String sessionId;

    /** 调用方角色（lead/researcher/aggregator/general/route-judge 等） */
    private String agentName;

    /** 实际使用的模型名 */
    private String model;

    /** 调用形态：SYNC-阻塞 / STREAM-流式 */
    private String callKind;

    /** 结果：OK / ERROR */
    private String status;

    /** 输入 token 数（流式为近似估算） */
    private Integer promptTokens;

    /** 输出 token 数（流式为近似估算） */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** token 是否为近似估算：1-是 */
    private Integer tokensEstimated;

    /** 调用耗时（毫秒） */
    private Long durationMs;

    /** 失败原因（status=ERROR 时） */
    private String errorMsg;

    /** prompt 装配的技能名单（CSV，空为 NULL） */
    private String skillNames;

    /** prompt 装配的工具名单（CSV，空为 NULL） */
    private String toolNames;

    /** prompt 装配的 MCP 工具名单（tool_names 子集，CSV，空为 NULL） */
    private String mcpToolNames;

    /** LLM 回复文本摘要（成功调用截断 200 字符；失败为 NULL） */
    private String outputSummary;

    /** 首 token 到达延迟（毫秒；流式成功调用有值，SYNC 与失败为 NULL） */
    private Long firstTokenMs;

    /** 供应商缓存命中 token（原生 usage 无该信息为 NULL） */
    private Integer cachedTokens;

    /** 调用结束时间 */
    private LocalDateTime createdAt;
}
