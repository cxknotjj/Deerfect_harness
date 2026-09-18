-- ============================================================
-- V19 - session 表增加用户画像提取支持两列
--
-- 跨会话用户画像（全局单份 MD 文件 user-profile/user-profile.md，不入库）
-- 的提取扫描依据与防重复提炼标记，均在会话粒度：
-- - last_active_at：最近活跃时间，由 createSession 初始化、touchSession 每轮写回刷新；
--   扫描条件「静默超 profile-idle-minutes」据此判定会话已结束。
-- - profile_extracted：提炼标记，0-待提炼 1-已提炼；一次会话只提炼一次
--   （长青会话缺口「活跃重置」为独立 TODO，本期不实现）。
-- 存量会话 last_active_at 为 NULL → 扫描条件不命中 → 历史会话不回溯提炼。
-- ============================================================
ALTER TABLE session
    ADD COLUMN last_active_at    DATETIME NULL COMMENT '最近活跃时间（画像提取扫描依据；建档初始化，每轮写回刷新）',
    ADD COLUMN profile_extracted TINYINT NOT NULL DEFAULT 0 COMMENT '画像提取标记：0-待提炼 1-已提炼（一次会话仅提炼一次）';
