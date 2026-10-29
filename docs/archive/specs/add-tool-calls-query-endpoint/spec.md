# 工具调用查询端点 Spec

## Why
工具调用观测数据已完整落库(`tool_call_log` 表,V15 起由 LlmCallRecorder 异步写入),但没有任何查询入口,只能进数据库手工查。需补 `/api/tool-calls` 查询端点,与 `/api/llm-calls` 同风格暴露,支持编排排障(某 MCP server 故障时从日志定位到该 server)。

## What Changes
- 新增 `ToolCallController`:`GET /api/tool-calls?sessionId=&serverName=&limit=`,返回 `List<ToolCallLogEntity>`,按 id 倒序
  - `sessionId` 可选——查全量或单会话
  - `serverName` 可选——按来源 MCP server 过滤(非 MCP 工具该字段为空,不匹配任何 serverName 过滤)
  - `limit` 默认 50、上限 200,与 `/api/llm-calls` 同规则
- 新增 `ToolCallControllerTest` 单测(照 LlmCallControllerTest 风格,mock mapper 验证查询条件与 limit 边界)
- HARNESS_TODO.md 对应条目勾选
- server 侧零表结构变更、零新依赖

## Impact
- Affected specs: 无(纯新增查询面,消费既有 tool_call_log 数据)
- Affected code: 新增 `server/src/main/java/com/dark/javaHarness/controller/ToolCallController.java` 与对应测试;不改 LlmCallRecorder/装饰器链/表结构

## ADDED Requirements

### Requirement: 工具调用查询端点
系统 SHALL 提供 `GET /api/tool-calls`,按 id 倒序返回 `tool_call_log` 记录,响应字段含 sessionId/agentName/toolName/serverName/argsSummary/status/durationMs/errorMsg/createdAt。

#### Scenario: 按会话查询一次编排
- **WHEN** 请求 `GET /api/tool-calls?sessionId=s1&limit=50`
- **THEN** 返回该会话全部工具调用记录(含 MCP 工具及其来源 serverName),倒序排列

#### Scenario: 按来源 server 定位故障
- **WHEN** 请求 `GET /api/tool-calls?serverName=tavily`
- **THEN** 仅返回来源 server 为 tavily 的调用记录,可从中识别连续 ERROR 条目

#### Scenario: limit 边界
- **WHEN** 请求 `limit=0` 或 `limit=10000`
- **THEN** 按默认/上限规则收敛(1~200),不发生全表拖取

### Requirement: 与 llm-calls 同风格
新端点 SHALL 复用 `LlmCallController` 的实现风格(直接 mapper 查询、无额外 service 层、同 limit 规则),并配同风格单测(mock mapper,覆盖 sessionId/serverName 过滤与 limit 收敛)。

#### Scenario: 单测回归
- **WHEN** 运行 `mvn -pl server test`
- **THEN** 新增测试与服务端既有测试全部通过
