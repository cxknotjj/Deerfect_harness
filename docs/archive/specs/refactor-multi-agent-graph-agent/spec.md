# Spec: MultiAgentGraphAgent 编排组件提取——行数与关注点控制

## Why

MultiAgentGraphAgent 756 行、7 个构造重载（最长 13 参），与 AgentChatCaller 重构前同一失控模式。
与 Caller 不同的是：本类名字与职责**本来就相符**（多 Agent 图编排器），因此本次目标不是名实修正，
而是行数控制与独立关注点提取——把「非编排本体」的纯函数/策略块挪出，让编排主线
（图构建 → 三节点 → 执行/续跑 → 熔断/取消接线）读起来一条线。

## Why NOT 拆三节点 / 检查点回放（否决理由）

- **三节点 lambda（lead/subtask/aggregate）**：闭包捕获编排共享字段（caller/budget/cancelFlag/
  pipeline hooks 等 6 组依赖），抽类需打包传递制造「上下文上帝参数」反模式；节点语义与编排
  状态耦合深，属编排本体。
- **检查点回放/恢复点选择**：与 StateGraph 编译产物、checkpoint saver API 深耦合，抽出后
  参数爆炸，且续跑语义（final 有效零调用回放 vs 回退聚合前）必须在图上下文里才成立。
- **状态键常量（K_*）与 SKIPPED_RESULT**：图状态词汇表，被 Pipeline 钩子与节点两端共用，
  挪出反而割裂读写两端。

## What Changes

1. **`OrchestrationPrompts` 新建**（编排提示词构建，纯函数/常量）：
   `LEAD_FALLBACK_PROMPT`、`AGGREGATOR_FALLBACK_PROMPT` 两个兜底常量 +
   `aggregateUserPrompt`（聚合用户 prompt 拼接：结果 →【子任务N】节 + 预算上限等份额截断，
   `AGG_SECTION_HEADER` Pattern 随迁）。零依赖纯函数，Agent 改为调用。
2. **`AggregateStreamGuard` 新建**（流式聚合护栏）：`predictAggregateStreaming` 整体挪出——
   首 token 前失败或 0 内容 token → 流式重试一次；已推 token 后失败不重试（防内容重复），
   以已收内容为准；取消异常永不重试原样上抛。依赖 caller 与 cancelled 标志，经参数注入。
3. **构造链收敛**：7 个重载 → 5 个。删除无人使用的 9 参（L165）与 11 参（L184）中间重载；
   保留测试在用的 5/6/7/8 参便捷链与生产 13 参全参。
4. **类 javadoc 更新**：说明收窄后职责与组件委派关系。

## Impact

- `agent/MultiAgentGraphAgent.java`：756 行预计降至 ~560 行
- 新增 `agent/OrchestrationPrompts.java`、`agent/AggregateStreamGuard.java`
- 单测：MultiAgentGraphAgentTest（24 处构造）断言与构造调用零改动；行为零变化
- 全量 `mvn test` 回归绿（当前基线 488）

## ADDED Requirements

### Requirement: 外部行为零变化
护栏重试时序、聚合截断口径、兜底提示词文本 SHALL 逐字节不变；构造链删除的重载
SHALL 经 grep 确认无人使用。

#### Scenario: 黑盒单测不改断言
- **WHEN** 重构完成后运行 MultiAgentGraphAgentTest 全部用例
- **THEN** 全绿且断言未改（构造调用未改）

### Requirement: 编排主线可读
Agent 类 SHALL 只保留：图构建、三节点、执行/续跑入口、熔断/取消接线、节点→caller 桥接
（predictLead/predictSubtask/predictAggregate）；提示词文本与聚合护栏 SHALL 位于独立组件。

#### Scenario: grep 验收
- **WHEN** grep `LEAD_FALLBACK_PROMPT|AGGREGATOR_FALLBACK_PROMPT|predictAggregateStreaming` 于 MultiAgentGraphAgent
- **THEN** 仅剩 import 与调用点，无定义

## 范围外（防蔓延）
- 不拆三节点 lambda / 检查点回放 / 状态键常量（见否决理由）
- 不动 MultiAgentStreamPipeline（174 行尚可控）与 GeneralAssistantAgent
- 不改执行语义：重试时序、截断口径、占位与降级说明文本零变化
