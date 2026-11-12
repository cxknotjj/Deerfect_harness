-- llm_call_log 扩展：记录实际命中的路由与成员，支持还原失败切换路径
ALTER TABLE llm_call_log
  ADD COLUMN route_id BIGINT NULL COMMENT '命中的路由 id' AFTER agent_name,
  ADD COLUMN route_code VARCHAR(64) NULL COMMENT '命中的路由代码' AFTER route_id,
  ADD COLUMN model_id BIGINT NULL COMMENT '实际使用的模型部署 id' AFTER route_code,
  ADD COLUMN provider_code VARCHAR(32) NULL COMMENT '实际供应商代码' AFTER model_id,
  ADD COLUMN upstream_name VARCHAR(128) NULL COMMENT '实际请求下发的模型名' AFTER provider_code,
  ADD COLUMN fallback_from_model_id BIGINT NULL COMMENT '从哪个模型切换而来' AFTER upstream_name,
  ADD COLUMN error_class VARCHAR(32) NULL COMMENT '错误分类：timeout/connection/4xx/5xx/cancel/other' AFTER error_msg;
