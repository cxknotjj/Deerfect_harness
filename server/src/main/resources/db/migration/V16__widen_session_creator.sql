-- 放宽 session.creator：飞书 open_id 是 35 字符（ou_ + 32 位十六进制），
-- 加 "feishu:" 前缀共 42 字符，原 varchar(30) 装不下，建会话直接报
-- "Data too long for column 'creator'"。
--
-- 30 是给 "qq:123456" 这类短标识留的。渠道越多，标识越长，这里一次放宽到 64，
-- 给飞书群、企业微信等后续渠道留同样余量。
ALTER TABLE `session`
  MODIFY COLUMN `creator` VARCHAR(64) NOT NULL COMMENT '会话创建者（渠道:用户标识）';
