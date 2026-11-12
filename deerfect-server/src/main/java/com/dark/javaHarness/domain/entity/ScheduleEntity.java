package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 定时任务实体，对应表 agent_schedule。
 *
 * <p>与 goal 的关系：一次触发产生一个新 goal，本表只保留最近一次的 goalId，
 * 不跟踪其生命周期（状态查询走 /api/harness/goals）。
 */
@Data
@TableName("agent_schedule")
public class ScheduleEntity {

    /** 任务ID（sched-UUID，服务生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 触发时提交给 Agent 的目标 */
    private String objective;

    /** 执行的 Agent 名（须在 agent 表存在） */
    private String agentName;

    /** cron 表达式（6 段：秒 分 时 日 月 周） */
    private String cron;

    /** 1=启用 0=暂停 */
    private Integer enabled;

    /** 执行后推送目标（channel:target）；空=不推送 */
    private String notifyChannel;

    /** 最近一次触发时间 */
    private LocalDateTime lastRunAt;

    /** 下次触发时间（扫描依据，触发后按 cron 重算；无后续时间时置空并停用） */
    private LocalDateTime nextRunAt;

    /** 最近一次触发产生的 goal id（触发失败为空） */
    private String lastGoalId;

    /** 执行结果推送时间；空 = 待推送（幂等标记，防每轮重复推送） */
    private LocalDateTime notifiedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
