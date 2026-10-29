# 轮次与调用链轨迹标识（turn_id / trace_id）Spec

## Why
轨迹页当前是纯时间线平铺，无法回答「这轮对话一共跑了几步」「lead 派了哪些专家、
各调了什么」——多轮工具循环与多 agent 编排的调用记录混在一起。本 spec 为
llm_call_log / tool_call_log 加轮次与调用关联标识，支撑「N 轮 M 步」统计、
条带按轮分段、跨 agent 调用树（web/TODO.md 轨迹观测增强条目的实施 spec）。

## 摸底结论（决定方案的关键事实）
1. **工具上下文通道现成**：全部工具装饰层已实现 Spring AI 双参
   `call(String toolInput, ToolContext toolContext)` 并透传
   （ToolCallTracer:202 / ToolCallBudget:96 / ToolLazyManager:150,215 /
   SkillManager:147 / ServerTaggedCallback:25）→ 工具侧传递用 **ToolContext**，
   TODO 原文假设的 ThreadLocal 桥接**不需要**
2. **项目上下文传递惯例是显式参数**（sessionId→executeStreamReactive→Goal 先例），
   全库无 Reactor contextWrite / ThreadLocal 业务先例 → LLM 侧传递走
   **Goal 字段 + AgentChatCaller.CallContext 显式携带**，Reactor context
   也**不需要**
3. LLM 观测链：5 处 `new LlmCallLog(`（AgentChatCaller 经 LlmCallObserver /
   GeneralAssistantAgent / LlmRouteJudge / UserProfileService / 单测），
   record 为不可变字段全扩散（编译器兜底机械改动）
4. 工具观测链：`ToolCallTracer.call` 是 tool_call_log 写入的包裹层，
   已持有 ToolContext
5. LLM 调用的 ChatClient spec 统一在 `AgentRequestSpecFactory`（:223
   `spec.toolCallbacks(...)`）构建 → 同处可 `.toolContext(Map)`
6. 三入口（web/CLI/QQ）统一经 ChatService 走 RouteJudge 判定 → turnId
   一处生成全覆盖；`HarnessController /submit` 直发 Goal 无轮次概念 → NULL
7. MultiAgentGraphAgent 并行子任务经 StateGraph OverAllState 传值
   （AsyncNodeAction，graph-core 调度）→ parent_span 需进图状态键
8. 迁移最新 V22；tool_call_log 表结构在 V15，llm_call_log 扩展风格参考 V18

## 标识语义与生成规则

| 标识 | 生成点 | 语义 | NULL 场景 |
|------|--------|------|-----------|
| turn_id | ChatServiceImpl 入口（chat / toSseBody 各自生成一次） | 一条用户消息触发的完整处理（route-judge + 直答/编排） | /submit 直发 Goal；route-judge 之外的历史数据 |
| trace_id | AgentServiceImpl 创建 Goal 时 | 一次 Agent 执行链（直答或编排整体） | route-judge / memory-profile（不属于执行树） |
| span_id | AgentChatCaller 每次调用发起前 | 单次 LLM 调用（ChatClient 内部工具循环聚合为一行一个 span） | 无（观测行必有） |
| parent_span | 调用构造方显式指定 | 指向父调用的 span_id（32hex） | 根调用：SIMPLE 直答、lead、fallback 重答 |

- **span 树不依赖落库自增 id**：spanId 发起前生成，父调用结束后即知自身
  spanId，构造子调用时可携带——异步落库时序无关
- **口径自洽**：GeneralAssistantAgent 的工具多轮循环发生在 ChatClient 内部，
  llm_call_log 仍是一行一个 span，工具行的 parent_span 指向该调用
- UUID 落库统一 32 位 hex（`UUID.randomUUID().toString().replace("-","")`），
  VARCHAR(32)；前端展示截前 8 位

## What Changes
- **V23 迁移**：llm_call_log + `turn_id`/`trace_id`/`span_id`/`parent_span`
  VARCHAR(32) NULL；tool_call_log + `turn_id`/`trace_id`/`parent_span`
  VARCHAR(32) NULL；存量行不回填，全 NULL 兼容
- **LLM 侧贯通**：ChatServiceImpl 生成 turnId → executeStreamReactive/executeSync
  参数 → Goal 字段；AgentChatCaller.CallContext 加 turnId/traceId/spanId/parentSpan
  → LlmCallLog 4 字段 → Recorder/Entity/doInsert 落库；LlmRouteJudge 只带 turnId
- **编排树**：MultiAgentGraphAgent 编排入口生成 traceId（或复用 Goal.traceId），
  lead 的 spanId 经 OverAllState 状态键传给 subtask/aggregate 节点作 parent_span
- **工具侧**：AgentRequestSpecFactory 发起调用时 `.toolContext(Map.of(...))`
  塞 turnId/traceId/parentSpan → ToolCallTracer 从 ToolContext 读出
  → ToolCallLog 3 字段落库
- **前端**：types.ts 的 LlmCallItem/ToolCallItem 补字段；TraceView.vue 按
  turn_id 分段（「第 N 轮 · M 步」小节，N = 视图内 turn_id 首现顺序，纯前端
  推导，不加后端序号列），LLM/工具行按 parent_span 链缩进（margin 层级标记，
  不做展开树组件）；旧数据（无 turn_id）保持现状平铺

## Impact
- Affected specs: add-trace-observability-columns（前置三列已落地验收）、
  add-tool-call-mcp-logging（观测写入链路）、add-web-ui-features（轨迹 tab）
- Affected code:
  - `server/.../db/migration/V23__call_log_turn_trace_columns.sql`（新）
  - `LlmCallLog` / `ToolCallLog`（record 加字段）、
    `LlmCallLogEntity` / `ToolCallLogEntity`、`LlmCallRecorder.doInsert*`
  - `ChatServiceImpl`（turnId 生成 + 传参）、`AgentService` 接口与实现、
    `Goal`（traceId/turnId 字段）、`AgentChatCaller.CallContext`、
    `LlmCallObserver`、`LlmRouteJudge`（仅 turnId）
  - `AgentRequestSpecFactory`（toolContext 塞值）、`ToolCallTracer`（读取落库）
  - `MultiAgentGraphAgent`（状态键 + 子节点 parentSpan）
  - `web/src/api/types.ts`、`web/src/components/TraceView.vue`
  - 测试：`LlmCallRecorderTest` 等观测侧测试 + 新增贯通断言

## ADDED Requirements

### Requirement: 轮次标识贯通
系统 SHALL 在 ChatService 入口为每轮对话生成 turnId，并沿
ChatService→AgentService→Goal→Agent→AgentChatCaller 显式参数链传递至
llm_call_log；route-judge 调用 SHALL 携带同一 turnId；/submit 直发路径
SHALL 落 NULL。

#### Scenario: web 流式一轮多步
- **WHEN** 用户在 web 发送一条触发工具循环的消息
- **THEN** 本轮产生的 llm_call_log 行（含 route-judge）turn_id 一致，
  tool_call_log 行 turn_id 一致

#### Scenario: submit 直发无轮次
- **WHEN** 通过 POST /api/harness/submit 直发目标
- **THEN** 产生的调用记录 turn_id 为 NULL，trace_id 照常生成

### Requirement: 调用链树标识
每次 LLM 调用 SHALL 在发起前生成 span_id；编排内派生调用（subtask/aggregate）
SHALL 携带父调用 span_id 作 parent_span 与编排 trace_id；根调用 parent_span
SHALL 为 NULL；树关系 SHALL 不依赖落库自增 id 与异步落库时序。

#### Scenario: 多 agent 编排建树
- **WHEN** 一条 COMPLEX 消息触发 lead→3 个专家子任务→聚合
- **THEN** 5 次 LLM 调用 trace_id 相同，subtask/aggregate 行的 parent_span
  均为 lead 的 span_id，lead 与其他根调用 parent_span 为 NULL

#### Scenario: 简单直答无双层
- **WHEN** SIMPLE 判定走 general 直答
- **THEN** 该次调用 trace_id = Goal 的 trace_id，parent_span 为 NULL

### Requirement: 工具侧经 ToolContext 关联
LLM 调用发起处 SHALL 通过 Spring AI ToolContext 传递 turnId/traceId/
parentSpan，ToolCallTracer SHALL 从 ToolContext 读取并落 tool_call_log；
该传递 SHALL 复用既有双参 call 签名，不新增 ThreadLocal。

#### Scenario: 工具行归属调用
- **WHEN** 子任务专家执行中调用 Tavily 工具
- **THEN** tool_call_log 新行的 turn_id/trace_id 与该专家 LLM 调用一致，
  parent_span = 专家调用的 span_id

### Requirement: 轨迹页分段与缩进
轨迹页 SHALL 对有 turn_id 的记录按轮分段展示（「第 N 轮 · M 步」），并按
parent_span 链对行缩进标识层级；无 turn_id 的存量记录 SHALL 保持时间线平铺。

#### Scenario: 分段统计
- **WHEN** 一个会话轨迹含 2 轮共 5 步
- **THEN** 页面出现两个轮次小节，标题分别为「第 1 轮 · 3 步」「第 2 轮 · 2 步」

#### Scenario: 旧数据兼容
- **WHEN** 查看本轮改造前的历史会话轨迹
- **THEN** 全部行无分段无缩进，与现状渲染一致

### Requirement: 观测零主链路影响
标识生成与传递 SHALL 遵循「观测永不影响主链路」约束：UUID 生成与 Map 构造
均为内存操作，不新增 IO/锁/阻塞；落库失败仅 warn 的既有异步边界不变。

#### Scenario: 传递失败不伤主链路
- **WHEN** ToolContext 值缺失或格式异常
- **THEN** 工具执行与 LLM 调用不受影响，对应观测列落 NULL
