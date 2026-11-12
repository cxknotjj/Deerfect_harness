-- 飞书单聊会话绑定（M3b）：open_id + chat_id → 本项目的 session_id。
--
-- 为什么单独一张表而不是复用 onebot_session_binding：渠道私有数据跟渠道模块走，
-- 表结构也不同（QQ 有群号，飞书是 chat_id）。共用一张表会让「删 QQ 渠道」变成
-- 「改 core 的表」，与渠道模块化的前提相悖。
CREATE TABLE `feishu_session_binding` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `open_id` VARCHAR(64) NOT NULL COMMENT '飞书用户 open_id',
  `chat_id` VARCHAR(64) NOT NULL COMMENT '飞书会话 ID（单聊与 open_id 一一对应）',
  `session_id` BIGINT NOT NULL COMMENT '本项目会话 ID',
  `create_time` DATETIME(3) NOT NULL COMMENT '创建时刻',
  `update_time` DATETIME(3) NOT NULL COMMENT '更新时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feishu_binding` (`open_id`, `chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞书单聊会话绑定';
