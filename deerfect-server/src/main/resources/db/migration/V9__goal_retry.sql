-- 失败重投所需的三列：执行 Agent（重投必须知道用谁跑）、重投次数、下次重投时间
ALTER TABLE `goal`
  ADD COLUMN `agent_name` varchar(64) DEFAULT NULL COMMENT '执行该目标的 Agent 名（失败重投依据）' AFTER `channel`,
  ADD COLUMN `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重投次数（上限见 AgentServiceImpl.MAX_RETRIES）' AFTER `agent_name`,
  ADD COLUMN `next_retry_at` datetime DEFAULT NULL COMMENT '下次重投时间（指数退避）；空=不再重投' AFTER `retry_count`;
