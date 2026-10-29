# Tasks

- [x] Task 1: 迁移与实体加列（后端观测存储层）
  - [ ] 1.1 新增 `V23__call_log_turn_trace_columns.sql`：llm_call_log 加
        turn_id/trace_id/span_id/parent_span，tool_call_log 加
        turn_id/trace_id/parent_span，均 VARCHAR(32) NULL
  - [ ] 1.2 `LlmCallLog` / `ToolCallLog` record 加字段；`LlmCallLogEntity` /
        `ToolCallLogEntity` 加列映射；`LlmCallRecorder.doInsert` /
        `doInsertToolCall` 写入新列
  - [ ] 1.3 `LlmCallRecorderTest` 补新列写入断言（含 NULL 场景）
- [x] Task 2: turnId 生成与贯通（LLM 侧主链）
  - [ ] 2.1 `ChatServiceImpl` chat/toSseBody 入口生成 turnId（32hex UUID），
        显式参数传至 AgentService；`AgentService` 接口与实现、`Goal` 加
        turnId/traceId 字段（Goal 创建处生成 traceId）
  - [ ] 2.2 `AgentChatCaller.CallContext` 加 turnId/traceId/spanId/parentSpan
        （spanId 发起前生成）；`LlmCallObserver` 与 5 处 `new LlmCallLog(`
        调用点机械适配（route-judge 仅 turnId，trace/span 落 NULL；
        UserProfileService 全 NULL）
- [x] Task 3: 编排树贯通（MultiAgentGraphAgent）
  - [ ] 3.1 lead 节点调用的 spanId 写入 OverAllState 状态键；subtask/
        aggregate 节点构造调用时读出作 parent_span；complex-fallback 重答
        作为根调用（parent_span NULL）挂同一 trace_id
- [x] Task 4: 工具侧 ToolContext 关联
  - [ ] 4.1 `AgentRequestSpecFactory` 发起调用时 `.toolContext(Map.of(...))`
        塞 turnId/traceId/parentSpan（缺省不塞，Map 留空）
  - [ ] 4.2 `ToolCallTracer` 从 ToolContext 读取三标识（缺/异常静默 NULL）
        → `ToolCallLog` 落库
- [x] Task 5: 前端轨迹分段与缩进
  - [ ] 5.1 `web/src/api/types.ts`：LlmCallItem +4 字段、ToolCallItem +3 字段
  - [ ] 5.2 `TraceView.vue`：按 turn_id 分段（「第 N 轮 · M 步」，N=首现顺序
        纯前端推导）；按 parent_span 链缩进；无 turn_id 平铺如旧
- [x] Task 6: 全量验证
  - [ ] 6.1 后端 `mvn -pl server -am test` 0 失败 0 错误；前端
        `cd web && npm run build` 通过
  - [ ] 6.2 服务重启后浏览器实测：发一轮 SIMPLE 与一轮 COMPLEX（或带工具），
        轨迹页核对分段、步数、缩进树与落库标识一致性

# Task Dependencies
- Task 2 依赖 Task 1（列先行）
- Task 3 依赖 Task 2（CallContext/span 机制就绪）
- Task 4 依赖 Task 2（CallContext 持有标识值）；与 Task 3 可并行
- Task 5 依赖 Task 1-4 字段定形；Task 6 收尾依赖全部
