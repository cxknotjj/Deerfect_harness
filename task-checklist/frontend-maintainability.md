# 前端可维护性清单

- 范围：`web` 的 API 客户端拆分、SSE 解析测试、复杂组件收敛
- 创建：2026-11-05
- 来源：知识图谱分析报告（`issue.md`）
- 状态：共 3 项，已完成 3 项，待办 0 项

## 背景

`web` 生产文件 47 个，仅 5 个有文件级测试关系（10.6%），是全项目覆盖最低的模块；同时 `client.ts` 一个文件承载 18 个符号（会话、Agent、监控、知识库、SSE 全在这里），`web` 还有 12 个 `complex` 文件。

## P1 API 客户端拆分

- [x] P1-1 `client.ts` 按领域拆分
  - 作用：所有 HTTP + SSE 逻辑集中在一个文件，任何端点变化都触碰同一文件，前端改动冲突率最高
  - 位置：[client.ts](../web/src/api/client.ts#L48-L360)
  - 做法：按领域拆为 `api/auth.ts`、`api/session.ts`、`api/agent.ts`、`api/monitor.ts`、`api/knowledge.ts`、`api/chatStream.ts`；保留 `realApi` 聚合入口，保持现有调用方不变
  - 验收：`client.ts` 只做聚合与导出，单文件符号数 ≤6；`npm run typecheck` + `npm run build` 通过；调用方零改动
  - 结果（2026-11-05）：拆为 `http.ts`（请求头/401 钩子/请求封装）、`auth.ts`、`sessions.ts`、`observability.ts`、`chatStream.ts`；`client.ts` 仅保留 `Api` 与 `realApi` 聚合及兼容导出；`npm run typecheck`、`npm test` 25 条、`npm run build` 全绿。

- [x] P1-2 SSE 流式解析独立并补测试
  - 作用：`/api/chat/stream` 的 event/data 逐帧解析是前端最关键也最易坏的逻辑，目前混在 `streamChat` 里
  - 位置：[client.ts streamChat](../web/src/api/client.ts#L238-L336)、[useChat.ts](../web/src/composables/useChat.ts)
  - 做法：抽出 `parseSseFrame` / `SseStreamReader` 纯函数模块；补正常帧、多行 data、注释行、半帧截断、异常中止用例
  - 验收：解析模块单测覆盖上述 5 类场景；现有聊天流程行为不变；`npm test` 通过
  - 结果（2026-11-05）：抽出 `sse.ts`（`SseLineParser` + `dataPayload` + `unescapeLineBreaks`），`chatStream.ts` 只负责流读取与回调分发；新增 6 条解析用例 + 2 条流式分发用例（含跨 chunk 半帧、CRLF、注释行、进度降级）；`npm test` 33 条全绿。

## P2 测试与复杂度

- [x] P2-1 `web` 测试覆盖与复杂文件收敛
  - 作用：12 个 `complex` 文件集中在聊天窗口、轨迹页与格式化工具，覆盖不足时前端回归只能靠手测
  - 位置：[ChatWindow.vue](../web/src/components/ChatWindow.vue)、[TraceView.vue](../web/src/components/TraceView.vue)、[monitorFormat.ts](../web/src/utils/monitorFormat.ts)、[chatCache.ts](../web/src/composables/chatCache.ts)
  - 做法：优先补 `monitorFormat` / `chatCache` 的纯函数测试；`ChatWindow` 拆出消息列表与工具行子组件；`TraceView` 拆出筛选与详情面板
  - 验收：`web` 文件级覆盖 ≥40%；`ChatWindow` / `TraceView` 单文件符号数下降 ≥30%；`npm test` + `npm run test:e2e` 通过
  - 结果（2026-11-05）：TraceView 348 → 121 行（派生逻辑全部下沉到 `utils/traceFormat.ts`，单行渲染抽出 `trace/TraceRow.vue`）；ChatWindow 172 → 63 行（滚动区/空态抽 `chat/ChatMessageList.vue`，状态栏与 token/耗时统计抽 `chat/ChatStatusBar.vue`）；新增 `traceFormat` / `sessionMtime` / `endpoints`（覆盖 http/auth/sessions/observability）与 3 个组件测试，`@vue/test-utils` 作为组件回归工具引入；`npm test` 71 条全绿、`npm run typecheck` / `build` 通过、`npm run test:e2e` 4 条通过（本机无 Playwright headless shell，按配置回退到已安装的 Chrome）；有测试关联的生产文件 15/37 ≈ 40.5%。
