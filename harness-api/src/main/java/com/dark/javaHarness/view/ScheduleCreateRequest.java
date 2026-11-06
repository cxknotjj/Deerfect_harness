package com.dark.javaHarness.view;

/**
 * 创建定时任务入参（POST /api/schedules）。
 *
 * @param objective     触发时提交给 Agent 的目标，必填
 * @param agentName     执行的 Agent 名，必填且须在 agent 表存在
 * @param cron          cron 表达式（6 段：秒 分 时 日 月 周），必填
 * @param notifyChannel 执行后推送目标（channel:target），可空
 */
public record ScheduleCreateRequest(
        String objective,
        String agentName,
        String cron,
        String notifyChannel) {
}
