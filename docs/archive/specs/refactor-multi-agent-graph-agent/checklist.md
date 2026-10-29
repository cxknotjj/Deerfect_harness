# Checklist

## 外部行为零变化
- [x] 兜底提示词文本逐字节不变；聚合截断口径（等份额、节边界，PromptBudgetAdvisor.sections 未动）不变；护栏重试时序（首 token 前/0 token 重试一次、已推 token 不重试、取消直抛）不变
- [x] MultiAgentGraphAgentTest（24 处构造点）断言与构造调用零改动且全绿（23 例）

## 结构收敛
- [x] OrchestrationPrompts（42 行）：2 个兜底常量 + aggregateUserPrompt + AGG_SECTION_HEADER，零依赖纯函数
- [x] AggregateStreamGuard（122 行）：护栏重试策略独立，caller/角色/提示词/advisor 供给构造注入
- [x] 构造重载 7 → 5（删除的 9/11 参重载 grep 确认无人使用；8 参委托改直托 13 参）
- [x] Agent 保留：图构建 + 三节点 + 执行/续跑 + 熔断/取消接线 + 节点→caller 桥接；行数 756 → 614

## 验收
- [x] grep `LEAD_FALLBACK_PROMPT|AGGREGATOR_FALLBACK_PROMPT|predictAggregateStreaming` 在 Agent 内仅剩 OrchestrationPrompts.* 调用点，无定义
- [x] `mvn test` 全量回归绿（488 例，0 失败 0 错误，1 skip 既有基线）
- [x] 类 javadoc 更新；checklist / tasks 勾选
