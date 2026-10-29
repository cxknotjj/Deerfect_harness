# Tasks

- [x] Task 1: BudgetLedger 顶级化
  - [x] 1.1 新建 `agent/BudgetLedger.java`：接口（overBudget/recordUsage）+ 嵌套 `BudgetExceededException`，javadoc 随迁
  - [x] 1.2 Caller 删除内部定义，改 import；`caller.streamWithAssembly` 等内部引用同步
  - [x] 1.3 外部引用更新：OrchestrationBudget（2 处）、MultiAgentGraphAgent（BudgetExceededException 3 处 + BudgetLedger 签名 5 处）、AgentChatCallerTest（4 处）
  - 验证：grep `AgentChatCaller\.(BudgetLedger|BudgetExceededException)` 零命中；编译通过
- [x] Task 2: CallCancellation 顶级化
  - [x] 2.1 新建 `agent/CallCancellation.java`：CANCELLED_MSG + cancelException() 工厂（包级可见性保持）
  - [x] 2.2 Caller 与 MultiAgentGraphAgent（2 处 throw 点）引用更新
  - 验证：grep `AgentChatCaller.cancelException` 零命中；编译通过
- [x] Task 3: LlmErrorClassifier 收敛错误分类
  - [x] 3.1 新建 `agent/LlmErrorClassifier.java`：`translate(e, model)`（Quota/Auth 命中返回转换后异常，否则 null）+ isUnknownToolCall 判定
  - [x] 3.2 call（callWithAssembly）与 stream（streamWithAssembly）两处分类逻辑改走 Classifier（record 时机各自保留）
  - 验证：AgentChatCallerRetryTest 4 例绿（重试/不重试分类语义不变）
- [x] Task 4: LlmCallObserver 封装观测
  - [x] 4.1 新建 `agent/LlmCallObserver.java`：持有 LlmCallRecorder，提供 okStream/error/record；estimateTokens 兜底口径随迁；recorder 为 null 直通
  - [x] 4.2 Caller 的 record/recordOkStream/recordError 改为委托 Observer（okStream 4 处 + error 8 处调用点，safeMsg 内联为 describeError）
  - 验证：AgentChatCallerTest 观测断言不变（recorder mock 仍收到同参数记录）
- [x] Task 5: 构造链收敛
  - [x] 5.1 删除 5 个中间重载（5(budgets)/7/8/9/10 参），保留 4 参（单测便捷）/ 5 参 / 6 参 / 12 参全参；6 参改直托全参
  - [x] 5.2 全参构造 javadoc 更新（说明链上各组件来源与便捷链保留口径）
  - 验证：生产构造点（MultiAgentGraphAgent/GeneralAssistantAgent）与单测（4/5/6 参）编译通过
- [x] Task 6: 回归收尾
  - [x] 6.1 `mvn test` 全量回归绿（488 例，0 失败 0 错误，1 skip 既有基线）
  - [x] 6.2 grep 验收三连：`AgentChatCaller\.(BudgetLedger|BudgetExceededException|cancelException)` 零命中；Caller 行数 754 → 593
  - [x] 6.3 checklist 勾选；Caller 类 javadoc 更新为收窄后的职责描述

# Task Dependencies
- Task 1/2 纯搬移可先行（互不依赖）
- Task 3/4 依赖 1/2 完成（同文件顺序编辑避免交错）
- Task 5 依赖 1-4（构造签名稳定后再收敛）
- Task 6 依赖全部
