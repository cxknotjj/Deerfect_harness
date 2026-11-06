-- 画像提炼从「一次会话只提炼一次」改为「增量提炼」。
--
-- 原口径：profile_extracted=0/1 一次性锁定。长青会话（QQ 按用户固定 sessionId）
-- 静默提炼一次后，后面聊的所有内容都不会再进画像——用户越常用，画像越停在第一次。
--
-- 新口径：
--   profile_extracted_at   上次提炼完成时对应的 last_active_at；last_active_at 超过它
--                          = 有新增对话待提炼（写回路径无需额外维护标记）
--   last_extracted_message_id
--                          增量游标：已并入画像的最后一条快照消息 id（快照 item 的 id，
--                          由 SessionService 写回时生成，裁剪后仍可定位）
ALTER TABLE `session`
  ADD COLUMN `last_extracted_message_id` varchar(64) DEFAULT NULL
    COMMENT '增量提炼游标：已并入画像的最后一条快照消息 id（NULL=从未提炼）',
  ADD COLUMN `profile_extracted_at` datetime DEFAULT NULL
    COMMENT '上次提炼完成时的 last_active_at（活跃时间超过它即视为有待提炼内容）';

ALTER TABLE `session` DROP INDEX `idx_profile_scan`;

-- 存量不回溯：把已有会话一律视为「已处理到当时的活跃时刻」。
-- 否则升级后首轮扫描会把全部历史会话（本人库里 88 个）逐批喂给模型，
-- 既烧 token 又把陈旧/测试内容混进画像。新会话 profile_extracted_at 为 NULL，
-- 照常进入首轮提炼。
UPDATE `session` SET `profile_extracted_at` = `last_active_at` WHERE `last_active_at` IS NOT NULL;

ALTER TABLE `session`
  ADD INDEX `idx_profile_scan` (`profile_extracted_at`, `last_active_at`),
  DROP COLUMN `profile_extracted`;
