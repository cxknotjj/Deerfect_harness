-- Agent 定时任务：到点把 objective 提交给指定 agent，补「它自己开始」这一层
CREATE TABLE `agent_schedule` (
  `id` varchar(64) NOT NULL COMMENT '任务ID（sched-UUID）',
  `objective` text NOT NULL COMMENT '触发时提交给 Agent 的目标',
  `agent_name` varchar(64) NOT NULL COMMENT '执行的 Agent 名（须在 agent 表存在）',
  `cron` varchar(64) NOT NULL COMMENT 'cron 表达式（Spring 6 段：秒 分 时 日 月 周）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用 0=暂停',
  `notify_channel` varchar(160) DEFAULT NULL COMMENT '执行后推送目标（channel:target）；空=不推送',
  `last_run_at` datetime DEFAULT NULL COMMENT '最近一次触发时间',
  `next_run_at` datetime NOT NULL COMMENT '下次触发时间（扫描依据，触发后按 cron 重算）',
  `last_goal_id` varchar(64) DEFAULT NULL COMMENT '最近一次触发产生的 goal id',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_due` (`enabled`, `next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 定时任务表';
