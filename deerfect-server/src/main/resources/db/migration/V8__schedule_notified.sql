-- 主动推送幂等标记：定时任务触发后按 last_goal_id 轮询 goal 状态，完成后置该时间，
-- 避免每轮扫描重复推送（失败也置位：重试交给下一轮任务，不在这里死循环）
ALTER TABLE `agent_schedule`
  ADD COLUMN `notified_at` datetime DEFAULT NULL COMMENT '执行结果推送时间；空=待推送' AFTER `last_goal_id`;
