-- ============================================================
-- V24 - 画像扫描索引 + LLM 调用表清理依据索引
--
-- 优化审查(2026-09-25)两项 DB 问题的收敛修复：
-- 1) 画像扫描每 5 分钟按 (profile_extracted=0 AND last_active_at<X) 全表过滤，
--    V19 只加列未加索引，会话量增长后为周期性全表扫 → 组合索引一步命中；
-- 2) llm_call_log 无界增长，保留期清理按 created_at 删除 → 补 created_at 索引，
--    删除条件走索引而非全表扫（tool_call_log 在 V15 已有 idx_created_at，无需重复）。
-- 索引只加速，不改任何查询语义；存量数据由 MySQL 在线建索引回填。
-- ============================================================
ALTER TABLE session
    ADD INDEX idx_profile_scan (profile_extracted, last_active_at);

ALTER TABLE llm_call_log
    ADD INDEX idx_created_at (created_at);
