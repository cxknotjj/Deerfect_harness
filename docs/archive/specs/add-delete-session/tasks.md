# Tasks

- [x] Task 1: 后端删除会话服务与端点
  - [x] 1.1 `SessionService` 新增 `deleteSession(String sessionId)`；`SessionServiceImpl` 实现：软删 session 行、物理删 session_messages 快照行、删 onebot_session_binding 绑定行（幂等，不存在不报错；不对 llm_call_log / tool_call_log / goal 做任何写操作）
  - [x] 1.2 `HarnessController` 新增 `DELETE /api/harness/sessions/{sessionId}`，成功返回空体 2xx
  - [x] 1.3 单测：SessionServiceImplTest（软删 + 快照清理 + 绑定清理 + 幂等）、HarnessControllerTest（委托与透传）
- [x] Task 2: saveContext 防复活守卫
  - [x] 2.1 `SessionServiceImpl.saveContext` 写回前校验会话未软删，已删则跳过写回与 touch（不抛异常）
  - [x] 2.2 单测：已软删会话触发 saveContext 时不产生快照行
- [x] Task 3: 前端 API 层与 mock
  - [x] 3.1 `client.ts` 新增 `deleteSession(sessionId)`（DELETE 封装）并挂入 `Api` 接口与 `realApi`
  - [x] 3.2 `mock/mock.ts` 同步实现 `deleteSession`（内存态移除会话与转录）
- [x] Task 4: 前端会话删除交互与联动
  - [x] 4.1 `useSessions` 新增 `remove(id)`：调用 API 成功后从本地列表移除并修正 total；失败抛错由调用方处理
  - [x] 4.2 `SessionList.vue` 会话项 hover 显示删除按钮，confirm 确认后 emit 删除事件
  - [x] 4.3 `App.vue` 联动：删除的是当前会话时 → `chat.stop()` 终止流、`showChat('')` 切回草稿、清 useChat 消息桶与 chatCache 缓存；非当前会话仅移除列表项
  - [x] 4.4 `main.css` 补删除按钮样式（与既有 hover 操作风格一致，双主题 token）
- [x] Task 5: 验证
  - [x] 5.1 后端 `mvn -pl shared,server -am test` 全量通过
  - [x] 5.2 web `npm run typecheck` + `npm run build` 通过
  - [x] 5.3 浏览器端到端：mock 模式删除非当前/当前会话；真模式删除会话后列表消失、localStorage 缓存清理、再次进入该会话 id 返回空历史

# Task Dependencies

- Task 1 与 Task 2 相互独立，可并行；Task 3 依赖 Task 1（接口形状）；Task 4 依赖 Task 3；Task 5 依赖 Task 1-4 全部完成。
