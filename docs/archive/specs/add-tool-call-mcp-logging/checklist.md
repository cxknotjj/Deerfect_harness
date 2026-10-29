# Checklist

- [ ] V15/V16 迁移在 MySQL 上成功执行（Flyway 启动自动应用），表结构与 spec 字段一致
- [ ] 带 emitter 链路：SSE 进度行行为与格式不变，且 tool_call_log 同步新增记录
- [ ] 无 emitter 链路（emitter=null、recorder 非空）：工具仍被装饰，执行后 tool_call_log 新增记录，无 SSE 行
- [ ] 工具执行失败：异常原样上抛（行为不变），tool_call_log 记录 status=ERROR 与截断错误摘要
- [ ] tool_call_log.error_msg / args_summary 超 500 字符截断，写入不失败
- [ ] 观测落库异常仅 warn，不影响工具执行结果与主链路（与 LlmCallRecorder 同口径）
- [ ] MCP 工具落库 server_name 正确填充；非 MCP 工具该列为 NULL
- [ ] MCP server 连接成功/失败均落 mcp_server_log（CONNECTED 带 tool_count / FAILED 带 error）；失败隔离语义不变（单 server 失败不拖垮其他 server、工具面为空）
- [ ] expand_tool / load_skill 元工具不经 tracer（不产生进度行、不落库）
- [ ] ToolCallBudget 拦截（未真实执行）不落库
- [ ] 全量 `mvn test` 通过（0 失败），HARNESS_TODO.md L91-93 对应 TODO 已勾选
