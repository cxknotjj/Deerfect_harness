-- V6 - Goal 记录消息来源渠道：system prompt 的信道规范段据此生成（qq/cli/web/api）。
ALTER TABLE `goal`
  ADD COLUMN `channel` varchar(16) DEFAULT NULL COMMENT '消息来源渠道（qq/cli/web/api；空=不注入信道段）' AFTER `session_id`;
