-- V22 - agent 表注册 route-judge 内部行（路由判定表驱动化，改库即生效）
--
-- route-judge 是每条聊天请求的前置判定环节（SIMPLE/COMPLEX 分流），此前模型与提示词
-- 硬编码在 LlmRouteJudge 常量里；现与 multi-agent/lead/aggregator 同轨注册为内部角色行
-- （is_internal=1：不进 web AgentSelect 下拉、不注册为对话 Agent、不可被会话绑定）。
--
-- 数据依赖：model_provider 表的 qwen3.8-27b 行（V1 种子数据，uk_model 唯一）。
-- 若该行不存在则 SELECT 无结果、本迁移不插行——代码侧回退内置常量，行为不变。
-- prompt 为 LlmRouteJudge.SYSTEM_PROMPT 常量原文（\n 为实际换行），改库即生效。
-- INSERT IGNORE 依赖 uk_agent_name 唯一键保证幂等。
INSERT IGNORE INTO `agent` (agent_name, description, model_provider_id, prompt, status, is_internal)
SELECT 'route-judge',
       '主路由判定器：LLM 判定请求走 SIMPLE（单模型直答）还是 COMPLEX（多 Agent 编排）',
       mp.id,
       '你是 Harness 的主路由判断器。判断一条用户请求应该走「简单」还是「复杂」路径。\n只输出一行 JSON，不要任何解释、前后缀。格式严格为：{"route":"simple"} 或 {"route":"complex"}\n- simple：无需工具、无需拆分子任务，单次回答即可（如问候、闲聊、简短问答、讲笑话、简单解释）。\n- complex：需联网搜索、需执行代码、多步骤处理、需拆分为多个子任务（如调研竞品并输出报告、规划并执行一个完整项目）。',
       1,
       1
FROM model_provider mp
WHERE mp.model = 'qwen3.8-27b';
