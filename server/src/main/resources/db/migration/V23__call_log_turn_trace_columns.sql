-- 轮次与调用链轨迹标识(全 NULL 兼容,存量行不回填):
-- turn_id     一条用户消息触发的完整处理(ChatService 入口生成;/submit 直发为 NULL)
-- trace_id    一次 Agent 执行链(创建 Goal 时生成;route-judge/画像等非执行树调用为 NULL)
-- span_id     单次 LLM 调用(发起前生成;观测行必有)
-- parent_span 父调用的 span_id(根调用为 NULL)
ALTER TABLE llm_call_log ADD COLUMN turn_id VARCHAR(32) NULL;
ALTER TABLE llm_call_log ADD COLUMN trace_id VARCHAR(32) NULL;
ALTER TABLE llm_call_log ADD COLUMN span_id VARCHAR(32) NULL;
ALTER TABLE llm_call_log ADD COLUMN parent_span VARCHAR(32) NULL;
-- 工具行经 ToolContext 随调用关联(读取与塞值接线见工具侧任务)
ALTER TABLE tool_call_log ADD COLUMN turn_id VARCHAR(32) NULL;
ALTER TABLE tool_call_log ADD COLUMN trace_id VARCHAR(32) NULL;
ALTER TABLE tool_call_log ADD COLUMN parent_span VARCHAR(32) NULL;
