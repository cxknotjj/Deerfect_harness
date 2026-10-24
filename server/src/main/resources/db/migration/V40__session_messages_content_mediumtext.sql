-- 会话上下文快照列升级为 MEDIUMTEXT：
-- TEXT 上限 64KB（中文 UTF-8 约 2 万字符），长会话或单条超长回复会撑爆列，
-- 导致 updateById 失败、本轮上下文静默丢失。业务层同时用
-- app.session.snapshot-max-messages 限制保留条数，避免快照无界增长。
-- 注意：TEXT→MEDIUMTEXT 在大表上可能触发表重建/元数据锁，生产升级建议择低峰执行。
ALTER TABLE session_messages
    MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT '消息正文内容（完整会话上下文的 JSON 快照）';
