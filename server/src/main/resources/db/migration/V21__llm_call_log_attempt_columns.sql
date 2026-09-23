-- 轨迹重试可见性(全 NULL 兼容,存量行不回填):
-- attempt       本次记录为第几次尝试(1 起;无重试通道记 1;路由判定/画像为 NULL)
-- max_attempts  重试上限(与 LlmRetry.maxAttempts 口径一致;无重试通道记 1)
ALTER TABLE llm_call_log ADD COLUMN attempt INT NULL;
ALTER TABLE llm_call_log ADD COLUMN max_attempts INT NULL;
