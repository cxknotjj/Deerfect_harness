-- V3 - 会话历史归档表：快照裁剪（条数/字符上限）移除的消息先落归档，供 recall_history 按需召回。
CREATE TABLE `session_message_archive` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_id` varchar(64) NOT NULL COMMENT '所属会话ID',
  `message_id` varchar(64) NOT NULL COMMENT '快照 item 的稳定 ID',
  `role` varchar(20) NOT NULL COMMENT '消息角色：user/assistant',
  `content` mediumtext NOT NULL COMMENT '消息原文（part=tail 时为被截断的尾部）',
  `part` varchar(16) NOT NULL DEFAULT 'full' COMMENT '归档分段：full-整条 / tail-单条超限截断的尾部',
  `ts` bigint DEFAULT NULL COMMENT '消息时间戳（epoch 毫秒）',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户隔离ID',
  `archived_at` datetime NOT NULL COMMENT '归档时间',
  PRIMARY KEY (`id`),
  KEY `idx_archive_session_message` (`session_id`,`message_id`),
  KEY `idx_archive_session_time` (`session_id`,`archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话历史归档表（裁剪转归档）';
