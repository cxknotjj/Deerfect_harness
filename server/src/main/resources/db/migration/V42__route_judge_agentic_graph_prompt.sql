-- V42 - route-judge 提示词切换为「Agentic / Graph 编排」路径语义
--
-- V22 注册的 prompt 仍按「简单 / 复杂」组织，会让判定模型以难度为锚点，而不是以
-- 「是否存在可独立并行的工作流 / 多份交付物」这一任务形态为锚点。本迁移把库中 prompt
-- 更新为与 LlmRouteJudge.SYSTEM_PROMPT 一致的两路径语义（输出契约由代码统一追加，
-- 无论库中 prompt 新旧都保证富 JSON 形状：route/confidence/workstreams/deliverables/reason）。
--
-- 仅更新内部行 route-judge（is_internal=1），不影响对话 Agent。
UPDATE `agent`
SET `description` = '主路径判断器：判断请求走 Agentic（单工作流自主执行）还是 Graph（多工作流并行编排）路径',
    `prompt` = '你是 Harness 的主路径判断器。判断一条用户请求应走「Agentic」还是「Graph 编排」路径。
Agentic：单一工作流，可由一个 Agent 自主推理-行动、按需多步调用工具。
Graph：存在至少 2 个可独立并行推进的工作流，或多份独立交付物；只有并行收益大于协调成本时才选。
不要按消息长短或主观难度判断。'
WHERE `agent_name` = 'route-judge' AND `is_internal` = 1;
