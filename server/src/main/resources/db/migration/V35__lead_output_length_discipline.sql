-- lead 输出字数纪律（e2e 实测 2026-10-01）：brief 四要素使 lead 输出变长，2000 token 封顶
-- 曾把 JSON 截断在 3105 字符处丢失 agent 字段（指派回退 general）。除服务端 max-tokens-lead
-- 上调 4000 外，提示词层再约束长度——任务书宁短勿长，短 brief 同样可执行且省 token。
-- REPLACE 幂等：锚点为 V32 写入的 brief 说明句尾，重复执行时目标子串已变更则不再命中。
UPDATE `agent`
SET prompt = REPLACE(
        prompt,
        '执行专家看不到会话历史',
        '执行专家看不到会话历史。输出纪律：严格控制长度，单条任务书（brief）不超过 300 字（背景与约束各一两句即可，禁止罗列细节），全部子任务的 JSON 总输出不超过 1500 字；宁短勿长——输出超限会被截断，导致专家指派失效。'
    )
WHERE agent_name = 'lead'
  AND prompt LIKE '%执行专家看不到会话历史%'
  AND prompt NOT LIKE '%输出纪律：严格控制长度%';
