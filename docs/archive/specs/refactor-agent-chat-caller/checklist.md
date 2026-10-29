# Checklist

## 外部行为零变化
- [x] call/stream 方法签名不变；llm_call_log 记录口径不变（字段、tokensEstimated 判定、error 描述均一致）
- [x] 异常类型语义不变：BudgetExceededException 消息文案不变、cancelException 消息文案不变、Quota/Auth 转换口径不变
- [x] AgentChatCallerTest（18 例）/ AgentChatCallerRetryTest（4 例）断言不改（仅 `AgentChatCaller.X` → `X` 前缀替换）且全绿

## 编译期引用完整
- [x] grep `AgentChatCaller\.(BudgetLedger|BudgetExceededException|cancelException)` 零命中（实测 0）
- [x] OrchestrationBudget / MultiAgentGraphAgent / GeneralAssistantAgent / AgentChatCallerTest 引用已更新

## 结构收敛
- [x] 4 个新顶级类型各司其职：BudgetLedger（账本契约）、CallCancellation（取消词汇表）、LlmErrorClassifier（错误分类纯函数）、LlmCallObserver（观测封装）
- [x] 构造重载 10 → 4（4 参 / 5 参 / 6 参 / 12 参全参），删除的 5 个中间重载经 grep 确认原本无人使用（6 参因 AgentChatCallerTest 使用而保留，改直托全参）
- [x] Caller 保留职责收窄为：流管道核 + 重试循环 + 取消拦截 + 装配策略 + buildSpec + 流帧记账；行数 754 → 593

## 收尾
- [x] `mvn test` 全量回归绿（488 例，0 失败 0 错误，1 skip 为既有基线）
- [x] Caller 类 javadoc 更新为收窄后职责描述；checklist / tasks 勾选
