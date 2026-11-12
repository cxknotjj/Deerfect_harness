-- 完成 LLM 配置切换：保留思考开关语义后删除旧 model_provider 路径
-- 旧端点若配置 disable_thinking=1，转换为模型默认参数 thinking_mode=off，
-- 由 LlmCallOptions 在请求级下发 enable_thinking=false（ChatClient 与 AgentScope 同口径）。

UPDATE llm_model m
JOIN model_provider mp ON mp.id = m.id
SET m.default_params = JSON_SET(COALESCE(m.default_params, JSON_OBJECT()), '$.thinking_mode', 'off')
WHERE mp.disable_thinking = 1;

ALTER TABLE agent DROP COLUMN model_provider_id;

DROP TABLE model_provider;
