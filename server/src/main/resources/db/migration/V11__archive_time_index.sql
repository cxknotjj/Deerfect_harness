-- 跨会话归档召回按「归档时间倒序 + LIMIT N」取数：没有该索引时 MySQL 会对匹配集整体
-- filesort（关键词 LIKE 无法用索引过滤），数据量上来后每次召回都是全表排序。
-- 单列 idx_archive_time 只服务跨会话路径；会话内检索继续走 (session_id, archived_at)。
ALTER TABLE `session_message_archive` ADD INDEX `idx_archive_time` (`archived_at`, `id`);
