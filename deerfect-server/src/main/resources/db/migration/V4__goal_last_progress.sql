-- V4 - Goal 进展心跳：Watchdog 据此发现「RUNNING 但长时间无进展」的僵尸目标。
ALTER TABLE `goal`
  ADD COLUMN `last_progress_at` datetime DEFAULT NULL COMMENT '最近进展时间（RUNNING 期间由执行链路刷新）' AFTER `finished_at`,
  ADD KEY `idx_status_progress` (`status`,`last_progress_at`);
