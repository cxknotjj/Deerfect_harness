# Tasks

- [x] Task 1: 装饰器接口与上下文（TDD）
  - [x] 1.1 新建 `tool/ToolCallbackDecorator` 接口（`decorate(ctx, tools)`，extends Ordered，javadoc 写明顺序契约与禁止返回 null 约定）
  - [x] 1.2 新建 `tool/ToolDecorationContext` record（agentName / sessionId / emitter / callBudgetEnabled；disableTools 路径工厂提前返回，不进 Context）
  - [x] 1.3 单测：上下文构造与字段可见性（record 自带），确认编译通过
- [x] Task 2: ToolObservationDecorator（Order 100，TDD）
  - [x] 2.1 新建组件，构造注入 `LlmCallRecorder`；`emitter == null && recorder == null` 直通，否则委托 `ToolCallTracer.trace(tools, emitter, agentName, sessionId, recorder::recordToolCall)` 装饰
  - [x] 2.2 单测：适用/不适用两分支；serverName 提取（ServerTaggedCallback 包装）走既有 TracerTest 兜底
- [x] Task 3: ToolBudgetDecorator（Order 200，TDD）
  - [x] 3.1 新建组件，构造注入 `ContextBudgetProperties`；`!callBudgetEnabled` 直通，否则委托 `ToolCallBudget.limit(tools, getToolCallLimit(), getToolResultBudget())`
  - [x] 3.2 单测：开关直通 / 委托参数正确（预算内核由既有 ToolCallBudget 测试兜底）
- [x] Task 4: ToolLazyLoadDecorator（Order 300，TDD）
  - [x] 4.1 新建组件，构造注入 `ToolLazyManager`；无条件委托 `process(sessionId, tools)`（开关在 manager 内部）
  - [x] 4.2 单测：委托透传正确（mock manager）
- [x] Task 5: SkillMetaToolDecorator（Order 400，追加型，TDD）
  - [x] 5.1 新建组件，构造注入 `SkillManager`；`loadSkillTool(agentName)` 存在则追加新列表，不存在直通
  - [x] 5.2 单测：有技能追加 / 无技能直通 / 不修改原列表（防御性拷贝）
- [x] Task 6: DefaultToolDecorators 默认链（TDD）
  - [x] 6.1 新建 `tool/DefaultToolDecorators`，`static List<ToolCallbackDecorator> defaults(ToolLazyManager, SkillManager, ContextBudgetProperties, LlmCallRecorder)` 按 Order 升序返回
  - [x] 6.2 单测：顺序断言（100→200→300→400，文档化顺序契约防漂移）；空依赖场景各装饰器直通
- [x] Task 7: AgentRequestSpecFactory 接线 + 全量回归
  - [x] 7.1 新增第 10 参全参构造（`List<ToolCallbackDecorator> decorators`）；现有 7/8/9 参三个构造签名保留为兼容构造（内部调 `DefaultToolDecorators.defaults(...)` 自建默认链）
  - [x] 7.2 `build()` 工具注入段替换为：`disableTools=true` 提前 return（保持现状）→ 构造 Context → `for (d : decorators) tools = Objects.requireNonNull(d.decorate(ctx, tools), ...)` → 移除四段硬编码调用
  - [x] 7.3 编译主代码 + 跑 `AgentChatCallerTest` / `GeneralAssistantAgentTest` / `MultiAgentGraphAgentTest` 等受影响测试
  - [x] 7.4 全量回归 `mvn test` 全绿（427 用例 = 412 基线 + 15 新增，0 失败 0 错误）
- [x] Task 8: 收尾
  - [x] 8.1 检查 README / docs 是否涉及工具装饰描述需同步（已更新 README.md 结构清单与 data-flow.md 装饰链描述）
  - [x] 8.2 更新项目记忆（新架构约定：新增装饰器的登记方式与顺序契约）

# Task Dependencies

- Task 1 是 Task 2/3/4/5 的前置（接口与上下文定义）
- Task 2/3/4/5 相互独立，可并行
- Task 6 依赖 Task 2/3/4/5（组装默认链）
- Task 7 依赖 Task 6（接线默认链）
- Task 8 依赖 Task 7（回归通过后收尾）
