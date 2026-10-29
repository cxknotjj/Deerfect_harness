# Tasks

- [ ] Task 1: 数据层四件套（tool_call_log）：V15 迁移 + ToolCallLog record + ToolCallLogEntity + ToolCallLogMapper + ToolCallRecorder（仿 LlmCallRecorder：boundedElastic 异步、失败仅 warn、error_msg 截 500、参数摘要截断）
  - [ ] 1.1 V15__add_tool_call_log.sql：字段 id/session_id/agent_name/tool_name/server_name/args_summary/status/duration_ms/error_msg/created_at + 索引（created_at/session_id/agent_name/tool_name/server_name）
  - [ ] 1.2 ToolCallRecorder.record(ToolCallLog) 异步落库 + 截断兜底单测（成功记录、失败记录、超长截断）
- [ ] Task 2: 数据层四件套（mcp_server_log）：V16 迁移 + McpServerLog record + McpServerLogEntity + McpServerLogMapper + McpServerRecorder（同口径：异步、warn-only、截断）
  - [ ] 2.1 V16__add_mcp_server_log.sql：字段 id/server_name/transport/event(CONNECTED|FAILED)/tool_count/error_msg/created_at + 索引（server_name/created_at）
  - [ ] 2.2 McpServerRecorder.record(McpServerLog) 异步落库单测
- [ ] Task 3: ToolCallTracer 改造：新增 trace(callbacks, emitter, agentName, sessionId, recorder) 重载；emitter 为 null 但 recorder 非空时仍装饰（持久化-only）；装饰器执行后落库；识别 ServerTaggedCallback 填充 server_name（旧签名委托新签名保持兼容）
  - [ ] 3.1 TDD 先行：新增持久化-only、成功落库字段、失败落库（异常原样抛）、server 标注四类测试并确认失败
  - [ ] 3.2 实现重载与装饰器落库逻辑，测试转绿；既有 tracer 测试不回归
- [ ] Task 4: McpToolProvider 改造：ServerTaggedCallback 包装（保留 server 名）+ connectAndDiscover 成功/失败接入 McpServerRecorder（null 安全，构造参数追加、既有测试同步传 null/mock）
  - [ ] 4.1 TDD 先行：连接成功发 CONNECTED(tool_count)、失败发 FAILED(error) 且工具面仍为空的测试
  - [ ] 4.2 实现包装与事件落库，测试转绿
- [ ] Task 5: AgentRequestSpecFactory 接线：注入 ToolCallRecorder，trace 调用改为新重载（透传 forAgent/sessionId/records），装饰条件改为 emitter 或 recorder 任一非空；受构造参数影响的既有测试同步
- [ ] Task 6: 全量回归 `mvn test`（含新测试），并按 HARNESS_TODO.md L91-93 勾选该 TODO 项

# Task Dependencies

- Task 3 依赖 Task 1（ToolCallRecorder）与 Task 4 的 ServerTaggedCallback 类型定义
- Task 4 依赖 Task 2（McpServerRecorder）
- Task 5 依赖 Task 3（新 trace 重载）
- Task 6 依赖全部前置任务；Task 1 与 Task 2 可并行
