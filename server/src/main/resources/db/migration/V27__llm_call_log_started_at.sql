-- 轨迹时序排序键(可空,存量行不回填):
-- started_at  LLM 调用发起时刻(调用开始时的时钟读数;区别于 created_at 落库时刻)。
--             llm_call_log 行落库晚于其触发的工具行,TraceView 时序排序若用落库
--             时刻,决策 LLM 调用(先发起)会排到工具调用之后;排序须用发起时刻。
--             NULL=历史行/未采集,前端排序与展示回退 created_at。
ALTER TABLE llm_call_log ADD COLUMN started_at DATETIME NULL;
