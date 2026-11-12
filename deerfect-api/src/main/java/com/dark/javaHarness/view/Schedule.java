package com.dark.javaHarness.view;

import java.time.LocalDateTime;

/**
 * 一条定时任务：按 cron 到点把 objective 提交给指定 Agent。
 *
 * <p>与 Goal 的分工：Schedule 是「什么时候做」，Goal 是「这一次做得怎么样」。
 * 每次触发产生一个新的 Goal，Schedule 只记录最近一次的 goalId，不参与 Goal 生命周期。
 *
 * @param notifyChannel 执行完成后推送目标（channel:target）；null = 不推送
 */
public record Schedule(
        String id,
        String objective,
        String agentName,
        String cron,
        String notifyChannel,
        boolean enabled,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        String lastGoalId,
        LocalDateTime createdAt) {
}
