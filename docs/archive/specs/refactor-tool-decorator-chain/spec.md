# 工具装饰链插件化（Tool Decorator Chain）Spec

## Why

当前工具装饰链硬编码在 `AgentRequestSpecFactory.build()` 里：装饰顺序、开关条件散落在组装代码中，`ToolCallTracer`/`ToolCallBudget` 是 final 静态工具类（私有构造 + static 方法），无法注册、替换、排序；顺序约束（预算包观测外、懒加载最外、元工具绕过全部装饰）仅靠注释和代码位置隐式保证。新增一层装饰必须修改组装工厂及其全部测试构造。需要将装饰链改造为独立可插拔组件：与业务工具类只通过 `ToolCallback` 接口绑定，支持动态挂载。

## What Changes

- 新增 `tool/ToolCallbackDecorator` 接口（extends Ordered）与 `tool/ToolDecorationContext` 上下文 record
- 新增 4 个装饰器组件（tool 包，自判适用条件）：
  - `ToolObservationDecorator`（Order 100，最内层）——迁移 `ToolCallTracer` 的 SSE 进度行 + `tool_call_log` 落库装饰逻辑
  - `ToolBudgetDecorator`（Order 200）——迁移次数/结果硬预算调用
  - `ToolLazyLoadDecorator`（Order 300，最外层）——委托 `ToolLazyManager.process`
  - `SkillMetaToolDecorator`（Order 400，追加型）——`load_skill` 元工具追加
- 新增 `DefaultToolDecorators` 默认链工厂（单一事实来源）+ `AgentRequestSpecFactory` 兼容构造（现有 9 参签名保留，内部自建默认链），规避装饰器列表穿透 5 层手动装配链
- **REMOVED** `AgentRequestSpecFactory.build()` 中的硬编码装饰调用（tracer → budget → lazy → load_skill 四段直调），改为统一应用循环
- 范围边界：MCP `ServerTaggedCallback`（工具发现期标注）不在本次范围；`ToolCallTracer.traceAnnotated`（仅测试引用）保留；既有 `tool → agent` 包反向依赖（`ToolCallTracer` import `ProgressLine`）保持现状不收敛
- 行为等价承诺：SSE 进度行、`tool_call_log` 落库、预算截断、懒加载展开、`load_skill` 注册的外部行为全部不变

## Impact

- Affected specs: 无既有 spec 受影响（`add-tool-call-mcp-logging` 的 tool_call_log 落库行为保持不变，仅调用载体从 factory 直调变为装饰器组件）
- Affected code:
  - 新增：`tool/ToolCallbackDecorator.java`、`tool/ToolDecorationContext.java`、`tool/ToolObservationDecorator.java`、`tool/ToolBudgetDecorator.java`、`tool/ToolLazyLoadDecorator.java`、`tool/SkillMetaToolDecorator.java`、`tool/DefaultToolDecorators.java`
  - 修改：`agent/AgentRequestSpecFactory.java`（build() 应用循环 + 新增全参构造）
  - 不动：`GeneralAssistantAgent`、`AgentChatCaller`、`MultiAgentGraphAgent`、`ChatAgentConfig`、`ToolCallTracer`/`ToolCallBudget` 内核、`ToolLazyManager`、`SkillManager`、全部业务工具类、MCP 相关

## ADDED Requirements

### Requirement: 装饰器接口与上下文

系统 SHALL 提供 `ToolCallbackDecorator` 接口（`List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools)`，extends Ordered），与 `ToolDecorationContext` record（agentName、sessionId、emitter、callBudgetEnabled 字段）。装饰器与业务工具 SHALL 只通过 `ToolCallback` 接口交互，不依赖任何具体工具类。

#### Scenario: 装饰器不适用时零开销直通

- **WHEN** 装饰器的适用条件不满足（如 emitter 与 recorder 均为空）
- **THEN** `decorate` 原样返回传入的工具列表（同一引用），不产生任何包装

#### Scenario: 接口契约快速失败

- **WHEN** 任一装饰器 `decorate` 返回 null
- **THEN** 工厂侧 `Objects.requireNonNull` 抛出异常（组装期编程错误快速失败，不静默丢装饰）

### Requirement: 默认链与动态挂载

系统 SHALL 提供 `DefaultToolDecorators.defaults(...)` 静态工厂作为默认装饰链的单一事实来源，按 Order 升序返回装饰器列表。`AgentRequestSpecFactory` SHALL 保留现有构造签名（内部自建默认链）并提供接收 `List<ToolCallbackDecorator>` 的全参构造。

#### Scenario: 新增装饰器不触碰组装工厂

- **WHEN** 需要新增一层工具装饰（如审计、缓存）
- **THEN** 只需新增一个实现 `ToolCallbackDecorator` 的类并在 `DefaultToolDecorators.defaults()` 登记一行（未来 factory bean 化后改为 Spring List 自动收集），`AgentRequestSpecFactory` 与业务工具类零改动

#### Scenario: 顺序契约显式化

- **WHEN** 默认链被构建
- **THEN** 装饰器按 Order 升序排列：观测(100) → 预算(200) → 懒加载(300) → 元工具(400)，且有测试断言该顺序防止漂移

### Requirement: 顺序契约

装饰链 SHALL 满足以下顺序语义（数值小 = 先执行 = 更贴近真实工具）：

1. **观测最内层**：只有真实工具执行才产生 SSE 进度行与 `tool_call_log` 记录
2. **预算包观测外**：预算截断的引导文本不产生假观测记录，执行计数只统计真实执行
3. **懒加载最外层**：未展开的轻量 schema 不进观测与预算
4. **元工具最后追加**：`expand_tool`/`load_skill` 不经观测与预算额度

#### Scenario: 预算截断不产生假记录

- **WHEN** 工具次数超限、预算装饰器返回引导文本
- **THEN** 该次调用不产生 `tool_call_log` 记录、不发 SSE 工具事件

## MODIFIED Requirements

### Requirement: AgentRequestSpecFactory 工具注入组装

`AgentRequestSpecFactory.build()` 的工具注入段 SHALL 改为：构造 `ToolDecorationContext` 一次 → 按序应用全部装饰器 → `spec.toolCallbacks(tools)`。`disableTools=true`（幻觉降级路径）SHALL 在装饰链之前直接返回（装饰器不执行），与现状一致。工具双通道归一（`@Tool` 注解对象经 `ToolCallbacks.from` 转回调）保持在装饰链之前完成。

#### Scenario: 无 SSE 链路的观测盲区保持覆盖

- **WHEN** QQ 渠道/API 直调链路（emitter 为 null）且 recorder 非空
- **THEN** 观测装饰器仍装饰工具，执行后落库 `tool_call_log`（行为与改造前一致）

#### Scenario: 编排路径预算保持启用

- **WHEN** 编排路径（`toolCallBudget=true` 且 emitter 非空）
- **THEN** 预算装饰器按 `budgets.getToolCallLimit()`/`getToolResultBudget()` 生效，行为与改造前一致

## REMOVED Requirements

### Requirement: factory 内硬编码装饰调用

**Reason**: 装饰顺序与开关条件散落在 `AgentRequestSpecFactory.build()` 中，违反开闭原则；静态工具类形态无法注册/替换/排序。
**Migration**: 装饰逻辑下沉到 4 个装饰器组件（内核纯函数与装饰器实现保留在 `ToolCallTracer`/`ToolCallBudget` 中，组件委托调用）；factory 侧以统一应用循环替代。外部行为不变（见行为等价承诺），无需数据或配置迁移。
