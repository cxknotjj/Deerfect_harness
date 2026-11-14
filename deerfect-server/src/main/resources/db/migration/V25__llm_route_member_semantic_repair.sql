-- ---------------------------------------------------------------------------
-- 修复：全新库的路由成员按硬编码 id 回填，语义错位
--
-- 根因：V20 用旧表 model_provider 的行 id 硬编码成员（main=12 / fast=11 /
--       judge=5 / multi=4），但全新库里 llm_model 是自增 id（V1 种子只有
--       9 个模型，id 1–9）。结果 main / fast 匹配不到成员，V24 用「id 最小的
--       模型」兜底，导致 main 与 fast 同时指向 qwen-plus，语义错误。
--
-- 策略：不改已发布的 V20/V24（Flyway checksum 不可变），新增本迁移做保守修复：
--       仅当路由「只有 1 个成员，且该成员就是 id 最小的启用模型」时，判定为
--       V24 兜底产物并替换；手工配置过成员（成员数 > 1 或成员不是最小 id）的
--       库保持原样。可重复执行。
--
-- 语义目标（与 V20 注释一致，按 upstream_name 定位；不存在时回退同系列）：
--   main → deepseek-v4.1-flash-1017（缺失时 deepseek-v4-flash）
--   fast → qwen3.8-flash（缺失时 qwen-turbo）
--   judge / multi 在全新库本就命中（id 5 / 4），无需处理
-- ---------------------------------------------------------------------------

-- 1) 删掉 V24 兜底产生的单成员（只匹配 main / fast）
-- 用派生表先物化成员数：MySQL 不允许 DELETE 的子查询直接读同一张目标表
DELETE rm
FROM llm_route_member rm
JOIN llm_route r ON r.id = rm.route_id
JOIN (SELECT route_id, COUNT(*) AS member_count FROM llm_route_member GROUP BY route_id) x
  ON x.route_id = r.id AND x.member_count = 1
WHERE r.code IN ('main', 'fast')
  AND rm.model_id = (SELECT id FROM llm_model WHERE enabled = 1 ORDER BY id LIMIT 1);

-- 2) main：优先 4.1 flash，缺失时退到 deepseek-v4-flash
INSERT INTO llm_route_member (route_id, model_id, priority, enabled)
SELECT r.id, m.id, 10, 1
FROM llm_route r
JOIN llm_model m
  ON m.upstream_name IN ('deepseek-v4.1-flash-1017', 'deepseek-v4-flash')
 AND m.enabled = 1
WHERE r.code = 'main'
  AND NOT EXISTS (
    SELECT 1 FROM llm_route_member rm WHERE rm.route_id = r.id AND rm.enabled = 1
  )
ORDER BY FIELD(m.upstream_name, 'deepseek-v4.1-flash-1017', 'deepseek-v4-flash')
LIMIT 1;

-- 3) fast：优先 qwen3.8-flash，缺失时退到 qwen-turbo
INSERT INTO llm_route_member (route_id, model_id, priority, enabled)
SELECT r.id, m.id, 10, 1
FROM llm_route r
JOIN llm_model m
  ON m.upstream_name IN ('qwen3.8-flash', 'qwen-turbo')
 AND m.enabled = 1
WHERE r.code = 'fast'
  AND NOT EXISTS (
    SELECT 1 FROM llm_route_member rm WHERE rm.route_id = r.id AND rm.enabled = 1
  )
ORDER BY FIELD(m.upstream_name, 'qwen3.8-flash', 'qwen-turbo')
LIMIT 1;
