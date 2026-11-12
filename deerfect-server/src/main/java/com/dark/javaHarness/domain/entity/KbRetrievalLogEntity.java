package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * RAG 检索轨迹日志实体，对应表 kb_retrieval_log（V34）。
 * 记录每次知识检索的来源（预取三提交点/现查/缓存短路）、命中条数、耗时与降级原因。
 */
@Data
@TableName("kb_retrieval_log")
public class KbRetrievalLogEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话ID（预取为发起会话） */
    private String sessionId;

    /** 调用方角色（lead/researcher/general 等；预取为被预取的 agent） */
    private String agentName;

    /** 来源：entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit */
    private String source;

    /** 检索查询文本（超长截断 512） */
    private String query;

    /** 检索的知识库列表（CSV） */
    private String kbs;

    /** 注入条数（预算截留后；cache_hit 行为 NULL；无命中为 0） */
    private Integer hitCount;

    /** 检索耗时（毫秒；cache_hit ≈ 0） */
    private Long durationMs;

    /** 是否成功：1-成功 0-降级/失败（error_msg 有效） */
    private Integer ok;

    /** 降级原因（超时/异常/池拒绝；成功为 NULL） */
    private String errorMsg;

    /** 轮次标识（执行期检索携带；预取为 NULL） */
    private String turnId;

    /** 执行链标识（执行期检索携带；预取为 NULL） */
    private String traceId;

    /** 检索发起时刻（排序键） */
    private LocalDateTime startedAt;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
