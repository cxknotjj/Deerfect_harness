-- 轨迹观测字段扩展(P0 三列,全 NULL 兼容,存量行不回填):
-- output_summary  LLM 回复文本摘要(截断 200 字符存储,VARCHAR(500) 预留余量)
-- first_token_ms  流式调用首个 token 到达延迟(SYNC 与失败调用为 NULL)
-- cached_tokens   供应商缓存命中 token(原生 usage 无该信息时为 NULL)
ALTER TABLE llm_call_log ADD COLUMN output_summary VARCHAR(500) NULL;
ALTER TABLE llm_call_log ADD COLUMN first_token_ms BIGINT NULL;
ALTER TABLE llm_call_log ADD COLUMN cached_tokens INT NULL;
