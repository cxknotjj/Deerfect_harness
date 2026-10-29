# Tasks
- [ ] Task 1: 后端快照支持时间戳
  - [ ] SubTask 1.1: `SessionService` 增加 `saveContext(String, Message, Long ts)` 重载（原 2 参委托新重载，ts 取当前时刻）
  - [ ] SubTask 1.2: `SessionServiceImpl` 实现重载——快照 item 写入 `ts`（字符串毫秒）；`loadContext` 确认忽略 `ts` 键（还原逻辑不变）
  - [ ] SubTask 1.3: `SessionMessagesView.Item` 增加 `ts` 字段；`listMessages` 解析快照中的 `ts`（缺失返回 null）
- [ ] Task 2: ChatServiceImpl 透传真实发送时刻
  - [ ] SubTask 2.1: sync `chat()` 入口捕获发送时刻，`writeBackContext`/saveContext 对 user 消息使用该时刻（assistant 用完成时刻）
  - [ ] SubTask 2.2: 流式 `streamReactive` 入口捕获发送时刻，收尾写回 user 消息使用该时刻（落库守卫不变）
- [ ] Task 3: 前端消费服务端时间戳
  - [ ] SubTask 3.1: `web/src/api/types.ts` 的 `SessionMessageView` 增加 `ts?: number | null`
  - [ ] SubTask 3.2: `useChat.toItem` 以 `m.ts ?? 0` 填充 `MessageItem.ts`（确认 MessageBubble 对 ts>0 的时间展示格式复用现状）
- [ ] Task 4: 测试与验证
  - [ ] SubTask 4.1: `SessionServiceImplTest` 补用例（带 ts 落库/读取；旧快照无 ts → null；loadContext 兼容）
  - [ ] SubTask 4.2: `ChatServiceImplTest` 验证既有用例不回归（2 参 saveContext 委托行为不变）
  - [ ] SubTask 4.3: 全量 `mvn test` + `npm run build` 绿；浏览器实测：新对话→刷新→历史时间与发送时刻一致（旧会话旧消息不显示时间）

# Task Dependencies
- Task 2 依赖 Task 1（重载签名）
- Task 3 依赖 Task 1 的接口字段（可与 Task 2 并行）
- Task 4 依赖 Task 1-3 全部完成
