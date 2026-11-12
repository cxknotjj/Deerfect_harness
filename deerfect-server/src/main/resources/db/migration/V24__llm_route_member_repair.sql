-- ---------------------------------------------------------------------------
-- 修复：全新数据库上 main 路由无成员，导致启动绑定校验失败
--
-- 根因：V20 的路由成员回填按旧表 model_provider 的行 id 硬编码
--       （main -> m.id = 12），但 V1 种子只插入 id 1-8、11（没有 12）。
--       存量库因人工补过 id=12 的模型行而侥幸可用；全新库（CI、首次部署）
--       上 main 路由成员为 0，BindingValidator 启动校验直接失败。
-- 处理：任何"没有启用成员"的路由，补入 id 最小的启用模型作为兜底成员；
--       已有启用成员的路由不受影响，可重复执行。
-- ---------------------------------------------------------------------------
INSERT INTO llm_route_member (route_id, model_id, priority, enabled)
SELECT r.id,
       m.id,
       COALESCE((SELECT MAX(rm.priority) FROM llm_route_member rm WHERE rm.route_id = r.id), 0) + 10,
       1
FROM llm_route r
JOIN (SELECT id FROM llm_model WHERE enabled = 1 ORDER BY id LIMIT 1) m
WHERE NOT EXISTS (
    SELECT 1 FROM llm_route_member rm
    WHERE rm.route_id = r.id AND rm.enabled = 1
);
