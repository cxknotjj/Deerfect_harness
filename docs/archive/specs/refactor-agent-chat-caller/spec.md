# Spec: 重构 AgentChatCaller——按内聚组件拆分，名实相符

## Why

AgentChatCaller（754 行）名义上是「LLM 调用器」，实际承载 9 项职责：调用执行（名字承诺的）、
重试循环、取消语义、预算账本（接口+异常+记账都嵌在类内）、观测记录、错误分类转换、
幻觉工具降级、流空闲超时、角色策略装配。失控的直接证据是构造器 12 参重载链——
每加一个横切关注点就加一参，超时参数化又加一参。继续堆会让执行层核心无法维护。

## Why NOT 装饰器链（对齐工具装饰链先例的否决理由）

工具装饰链（ToolCallbackDecorator）适用是因为工具执行是**包围式**语义（装饰器 try/finally
包住整个执行）。LLM 流管道的横切关注点是**插入式**算子：取消的 takeUntil 必须排在
doOnNext 收集之前、预算记账嵌在 usage 帧处理内、空闲超时必须包住整条流——包围式装饰器
包装会打乱算子顺序引入取消/记账竞态。因此本次按**内聚组件提取**分层，Caller 保留
「调用生命周期编排」，纯函数/独立关注点挪出为顶级类型。

## What Changes

1. **`BudgetLedger` 顶级化**：接口 + 嵌套 `BudgetExceededException` 从 Caller 内部挪出为
   `agent/BudgetLedger.java`（纯搬移，语义零变化）。外部引用（OrchestrationBudget、
   MultiAgentGraphAgent、AgentChatCallerTest 共 10 处）改为顶级类引用。
2. **`CallCancellation` 顶级化**：`CANCELLED_MSG` 常量 + `cancelException()` 工厂挪出为
   `agent/CallCancellation.java`（纯搬移）。外部引用（MultiAgentGraphAgent 2 处）更新。
3. **`LlmErrorClassifier` 新建**：错误分类纯函数收敛——Quota/Auth 的 matches+from 调度、
   isUnknownToolCall 判定；提供 `translate(e, model)` 供 call/stream 两处复用（消除两处
   重复的分类→转换逻辑）。
4. **`LlmCallObserver` 新建**：观测记录封装——持有 LlmCallRecorder，提供 okStream/error
   记录方法，tokensEstimated 口径（stream && completionTokens==null）随迁。Caller 持有
   Observer 而非直接持有 Recorder。
5. **构造链收敛**：10 个重载 → 4 个（4 参单测便捷 / 5 参 / 6 参 / 12 参全参）。删除 5 个中间
   重载（5(budgets) 变体 / 7 / 8 / 9 / 10 参，已 grep 确认无人使用；6 参被 AgentChatCallerTest
   使用故保留并改为直托 12 参全参构造）。
6. **Caller 保留**（收窄后名实相符）：tokenStream/streamCore/contentOf（流管道核）、
   重试循环（call 的 executeWithRetry 包裹 + stream 手动循环）、取消拦截、装配策略
   （assemblyForRole/maxTokensForRole/MemoryPolicy）、buildSpec、captureUsageAndAccount
   （流帧算子，与管道算子顺序强耦合，不挪）。

## Impact

- `agent/AgentChatCaller.java`：754 行预计降至 ~450 行
- 新增 `agent/BudgetLedger.java`、`agent/CallCancellation.java`、`agent/LlmErrorClassifier.java`、`agent/LlmCallObserver.java`
- 引用更新：`OrchestrationBudget.java`（2 处）、`MultiAgentGraphAgent.java`（~10 处含 javadoc）、
  `AgentChatCallerTest.java`（4 处类型前缀）
- 单测：AgentChatCallerTest（18 例）/ AgentChatCallerRetryTest（4 例）断言不改（黑盒行为不变），
  仅嵌套类引用前缀替换；新组件按需补小单测
- 全量 `mvn test` 回归绿（当前基线 488）

## ADDED Requirements

### Requirement: 外部行为零变化
重构 SHALL NOT 改变任何外部可观察行为：call/stream 方法签名不变、llm_call_log 记录口径
不变、异常类型语义不变（BudgetExceededException 仍为 RuntimeException、消息文案不变）、
重试/取消/熔断时序不变。

#### Scenario: 黑盒单测不改断言
- **WHEN** 重构完成后运行 AgentChatCallerTest 与 AgentChatCallerRetryTest
- **THEN** 全部用例通过，断言仅允许「AgentChatCaller.X」→「X」的引用前缀替换

### Requirement: 编译期引用完整
嵌套类型挪出后，所有外部引用 SHALL 改为顶级类引用，全项目无 `AgentChatCaller.Budget*`
残留。

#### Scenario: grep 验收
- **WHEN** 重构完成后 grep `AgentChatCaller\.(BudgetLedger|BudgetExceededException|cancelException)`
- **THEN** 零命中

## 范围外（防蔓延）
- 不改类名（收窄职责后 AgentChatCaller 语义成立；改名波及 blame/javadoc 多处）
- 不做 Spring bean 化 / 装饰器链化（见否决理由）
- 不动重试/取消/熔断/装配的算法语义与流算子顺序
- tokenStream/streamCore 不挪出（流管道核就是「发起调用」的组成部分）
