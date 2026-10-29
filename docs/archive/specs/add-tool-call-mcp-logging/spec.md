# 工具调用与 MCP 日志记录 Spec

## Why

工具调用目前只在带 SSE emitter 的流式链路可见（`ToolCallTracer` 发进度行），且不落库——`llm_call_log` 只记 LLM 调用，工具侧无成本/审计账本；QQ 渠道、API 直调等无 emitter 链路的工具执行完全不可见。MCP server 连接/发现失败只有 warn 单行日志，无 server 维度可查询记录。

## What Changes

- 新增 `tool_call_log` 表（V15）+ `ToolCallLog`/`ToolCallLogEntity`/`ToolCallLogMapper`/`ToolCallRecorder`：仿照 `llm_call_log` 四件套，记录每次真实工具执行（工具名/参数摘要/耗时/成败/所属 agent 与 sessionId/来源 server）
- 新增 `mcp_server_log` 表（V16）+ `McpServerLog`/`McpServerLogEntity`/`McpServerLogMapper`/`McpServerRecorder`：记录每个 MCP server 的连接/发现事件（成功带工具数，失败带原因），按 server 维度可查询
- `ToolCallTracer` 改为「无 emitter 也装饰」：新增带 agentName/sessionId/recorder 的重载，emitter 为 null 但 recorder 存在时仍装饰（持久化-only 模式），补齐无 SSE 链路的观测盲区；SSE 进度行行为不变
- MCP 工具标注来源 server：`McpToolProvider` 用 `ServerTaggedCallback` 包装各 server 发现的回调，tracer 据此填充 `tool_call_log.server_name` 列（非 MCP 工具为 NULL）
- `McpToolProvider.connectAndDiscover` 接入 `McpServerRecorder`：连接/发现成功与失败均结构化落库（recorder 为 null 时跳过，兼容既有直连测试）
- `AgentRequestSpecFactory.build` 接入 `ToolCallRecorder`：把 `forAgent`/`sessionId` 透传给 tracer，工具装饰从「仅 emitter 非空」改为「emitter 或 recorder 任一非空」
- 既有行为保持：SSE 进度行格式不变、`expand_tool`/`load_skill` 元工具不经 tracer（不落库）、`ToolCallBudget` 拦截的调用未真实执行故不落库

**BREAKING**：无（全部为新增观测面，不改既有接口语义）

## Impact

- Affected specs: 无既有 spec 受影响（全新观测能力）
- Affected code:
  - `src/main/resources/db/migration/V15__add_tool_call_log.sql`（新增）
  - `src/main/resources/db/migration/V16__add_mcp_server_log.sql`（新增）
  - `src/main/java/com/dark/javaHarness/domain/ToolCallLog.java`、`McpServerLog.java`（新增）
  - `src/main/java/com/dark/javaHarness/domain/entity/ToolCallLogEntity.java`、`McpServerLogEntity.java`（新增）
  - `src/main/java/com/dark/javaHarness/mapper/ToolCallLogMapper.java`、`McpServerLogMapper.java`（新增）
  - `src/main/java/com/dark/javaHarness/service/impl/ToolCallRecorder.java`、`McpServerRecorder.java`（新增）
  - `src/main/java/com/dark/javaHarness/tool/ToolCallTracer.java`（修改：装饰条件 + 持久化 + server 标注）
  - `src/main/java/com/dark/javaHarness/tool/McpToolProvider.java`（修改：ServerTaggedCallback + 连接事件落库）
  - `src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java`（修改：注入 recorder，透传 agent/session）
  - 上述改动类的既有单测（构造参数同步）

## ADDED Requirements

### Requirement: 工具调用全链路落库
系统 SHALL 在每次真实工具执行结束后，将一条记录异步写入 `tool_call_log`（工具名、参数摘要、耗时毫秒、成败、错误摘要、所属 agent 名、sessionId、来源 server），不区分是否有 SSE emitter。

#### Scenario: 带 emitter 的流式链路工具调用
- **WHEN** 编排路径工具执行成功（有 SSE emitter）
- **THEN** `tool`/`tool-done` 进度行照常发射，且 `tool_call_log` 新增一条 status=OK 记录（server_name 视工具来源）

#### Scenario: 无 emitter 的链路（QQ 渠道/API 直调）工具调用
- **WHEN** `assembly.toolEmitter()` 为 null 且工具执行成功
- **THEN** 无 SSE 进度行（行为不变），但 `tool_call_log` 新增一条记录——观测盲区消除

#### Scenario: 工具执行失败
- **WHEN** 工具抛出 RuntimeException
- **THEN** 异常照原样向上抛（行为不变），`tool_call_log` 记录 status=ERROR 与错误摘要（超长截断 500 字符）

#### Scenario: 观测落库失败
- **WHEN** `tool_call_log` 写入发生异常
- **THEN** 仅记 warn 日志，主链路（工具执行结果、SSE）完全不受影响（与 LlmCallRecorder 同口径）

### Requirement: MCP 工具来源 server 标注
系统 SHALL 让 MCP server 发现的工具回调携带来源 server 名，`tool_call_log.server_name` 据此填充；非 MCP 工具该列为 NULL。

#### Scenario: MCP 工具调用落库带 server 名
- **WHEN** 来自 server `browser` 的工具被真实执行
- **THEN** 对应 `tool_call_log.server_name` = `browser`

### Requirement: MCP server 连接事件结构化落库
系统 SHALL 在每个 MCP server 连接/发现完成时写入一条 `mcp_server_log`（server 名、transport、CONNECTED/FAILED、工具数、错误摘要），后台预热与请求期懒连接两条路径均覆盖。

#### Scenario: 单 server 连接失败（失败隔离语义保持）
- **WHEN** server `browser` 握手失败
- **THEN** 该 server 工具面仍为空、其余 server 不受影响（既有行为不变），且 `mcp_server_log` 新增一条 event=FAILED 记录（含错误摘要），可按 server 维度查询

#### Scenario: 连接成功
- **WHEN** server `browser` 连接并发现 5 个工具
- **THEN** `mcp_server_log` 新增一条 event=CONNECTED、tool_count=5 记录

### Requirement: 元工具与预算拦截不落库
系统 SHALL 保持 `expand_tool`/`load_skill` 元工具不经 tracer 装饰（不产生进度行、不落库）；`ToolCallBudget` 拦截（未真实执行、返回引导文本）不落库。

#### Scenario: 预算拦截
- **WHEN** 工具执行次数超限，`ToolCallBudget` 返回引导文本
- **THEN** `tool_call_log` 无该次记录（未发生真实执行）
