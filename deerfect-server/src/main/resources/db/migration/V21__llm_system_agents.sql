-- 系统 Agent：内部 LLM 调用统一走 Agent → 路由 → 模型
-- profile / summary 绑定 fast 路由；trigger-judge 绑定 judge 路由
INSERT INTO agent (agent_name, description, route_id, prompt, tools, knowledge, thinking, status, is_internal)
SELECT 'profile', '用户画像提炼（内部）', r.id, NULL, NULL, NULL, 0, 1, 1
FROM llm_route r
WHERE r.code = 'fast'
  AND NOT EXISTS (SELECT 1 FROM agent a WHERE a.agent_name = 'profile');

INSERT INTO agent (agent_name, description, route_id, prompt, tools, knowledge, thinking, status, is_internal)
SELECT 'summary', '会话摘要（内部）', r.id, NULL, NULL, NULL, 0, 1, 1
FROM llm_route r
WHERE r.code = 'fast'
  AND NOT EXISTS (SELECT 1 FROM agent a WHERE a.agent_name = 'summary');

INSERT INTO agent (agent_name, description, route_id, prompt, tools, knowledge, thinking, status, is_internal)
SELECT 'trigger-judge', '渠道语义触发判断（内部）', r.id, NULL, NULL, NULL, 0, 1, 1
FROM llm_route r
WHERE r.code = 'judge'
  AND NOT EXISTS (SELECT 1 FROM agent a WHERE a.agent_name = 'trigger-judge');
