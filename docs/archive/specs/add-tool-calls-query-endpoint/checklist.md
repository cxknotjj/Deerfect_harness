# Checklist

- [x] `GET /api/tool-calls` 可用:sessionId 可选、serverName 可选、limit 默认 50 上限 200,按 id 倒序
- [x] 响应含 serverName 字段,MCP 故障场景可按 server 过滤定位
- [x] 实现与 `/api/llm-calls` 同风格(无额外 service 层,直接 mapper 查询)
- [x] `ToolCallControllerTest` 覆盖 sessionId/serverName 过滤与 limit 收敛
- [x] `mvn -pl server test` 全绿;`clean package` 全量回归通过
- [x] HARNESS_TODO.md 对应条目已勾选
- [x] 在 `feat/tool-calls-query-endpoint` 分支上完成,未直接改 master
