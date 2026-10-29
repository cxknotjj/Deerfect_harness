# Tasks

- [x] Task 1: OrchestrationPrompts 提取
  - [x] 1.1 新建 `agent/OrchestrationPrompts.java`：LEAD_FALLBACK_PROMPT / AGGREGATOR_FALLBACK_PROMPT 常量 + aggregateUserPrompt + AGG_SECTION_HEADER 随迁（42 行；等份额截断已内化于 PromptBudgetAdvisor.sections，随迁的只有 Pattern）
  - [x] 1.2 Agent 引用改为 OrchestrationPrompts.*（5 处），删除类内定义
  - 验证：编译通过；提示词文本逐字节不变
- [x] Task 2: AggregateStreamGuard 提取
  - [x] 2.1 新建 `agent/AggregateStreamGuard.java`（122 行）：predictStreaming + streamOnce 挪出（重试时序逐字节保持），caller/aggregatorRole/aggregatorPrompt/advisor 供给经构造注入
  - [x] 2.2 Agent 调用点改 streamGuard.predictStreaming，删除类内定义；顺带清理死代码（CancellationException import、safe()）
  - 验证：编译通过；MultiAgentGraphAgentTest 23 例全绿
- [x] Task 3: 构造链收敛
  - [x] 3.1 删除无人使用的 9 参与参重载，保留 5/6/7/8 参 + 13 参全参；**8 参委托改直托 13 参**（原委托已删的 9 参，曾致源码损坏）
  - [x] 3.2 全参构造 javadoc 保留原委派说明
  - 验证：ChatAgentConfig 与测试构造点编译通过
- [x] Task 4: 回归收尾
  - [x] 4.1 `mvn test` 全量回归绿（488 例，0 失败 0 错误，1 skip 既有基线）
  - [x] 4.2 grep 验收：Agent 内 3 处命中均为 OrchestrationPrompts.* 调用点，无定义；行数 756 → 614
  - [x] 4.3 类 javadoc 更新（组件委派关系 + 编排本体范围）；checklist 勾选

# Task Dependencies
- Task 1/2/3 相互独立可并行（同文件顺序编辑避免交错，建议 1→2→3 串行）
- Task 4 依赖全部
