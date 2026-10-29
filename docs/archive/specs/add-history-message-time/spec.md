# 会话历史消息显示真实时间 Spec

## Why
前端会话历史（切回旧会话/刷新后从服务端拉回）显示的时间不是真实时间：上下文快照 JSON
只存 `role/content` 两个字段没存时间，后端 messages 接口无法返回时间戳，前端历史消息
`ts=0`（不展示时间或显示错误）。实时聊天的消息时间是真的（`Date.now()`），两处不一致。

## What Changes
- **后端快照存时间**：`session_messages.content` 快照 JSON 的每条消息增加 `ts` 字段
  （epoch 毫秒，字符串形态存储，保持现有 `Map<String,String>` 反序列化不变）
- **用户消息记录真实发送时间**：`ChatServiceImpl` 在请求入口捕获发送时刻并透传到写回处
  （当前 user/assistant 都在轮次结束时落库，用户消息时间会等于回复完成时间，不真实）
- **接口透出**：`SessionMessagesView.Item` 增加 `ts` 字段（Long 可空）；旧快照无 `ts`
  → 返回 null（兼容，不回填历史数据）
- **前端消费**：`SessionMessageView` 类型加 `ts`，`useChat.toItem` 用服务端 ts 填充
  `MessageItem.ts`（无则维持 0 = 不展示时间）；localStorage 缓存已持久化 ts，无需改动
- 无 **BREAKING**：`saveContext` 保留原 2 参签名（委托新重载，时间取当前），既有调用方
  与测试不受影响；旧快照数据不迁移

## Impact
- Affected specs: 无（独立功能项，关联 web/TODO.md 待办）
- Affected code:
  - `SessionService.java` / `SessionServiceImpl.java`（saveContext 重载 + 快照 ts 读写）
  - `SessionMessagesView.java`（Item 加 ts）
  - `ChatServiceImpl.java`（请求入口捕获发送时刻，写回时透传）
  - `web/src/api/types.ts` / `web/src/composables/useChat.ts`（ts 消费）
  - 测试：`SessionServiceImplTest`、`ChatServiceImplTest` 补充用例

## ADDED Requirements

### Requirement: 快照记录消息时间戳
`SessionServiceImpl.saveContext` SHALL 支持携带时间戳（epoch 毫秒）写快照：新重载
`saveContext(String sessionId, Message message, Long ts)`，快照 item 以字符串形态写入
`ts` 键；未提供 ts 的原 2 参签名 SHALL 保持现有行为（ts 取当前时刻），既有调用方零改动。
`loadContext` SHALL 忽略 `ts` 键（仅读 role/content），带 ts 的快照对上下文还原完全兼容。

#### Scenario: 新消息带时间落库
- **WHEN** 任一入口完成一轮对话并写回上下文
- **THEN** 快照 JSON 的 user 与 assistant item 均含 `ts`（毫秒字符串），loadContext 还原正常

#### Scenario: 旧数据兼容
- **WHEN** 读取不含 `ts` 的历史快照
- **THEN** loadContext 与 listMessages 均正常，listMessages 返回的 `ts` 为 null

### Requirement: 用户消息记录真实发送时刻
`ChatServiceImpl` SHALL 在请求入口（sync `chat` 与流式 `streamReactive` 两入口）捕获消息
发送时刻，写回 user 消息快照时使用该时刻；assistant 消息使用回复完成时刻。落库守卫不变
（同步路径仍仅 SUCCEEDED 写回，失败轮次不落库的行为不改变）。

#### Scenario: 用户消息时间与发送时刻一致
- **WHEN** 用户发送消息（生成耗时较长时）
- **THEN** 历史回显中该条 user 消息的时间 ≈ 点击发送的时刻，而非回复完成时刻

### Requirement: messages 接口透出时间戳
`GET /api/harness/sessions/{id}/messages` 的 `SessionMessageView` SHALL 增加 `ts` 字段
（Long 可空，epoch 毫秒）；前端 `useChat.toItem` SHALL 以服务端 `ts` 填充 `MessageItem.ts`
（缺失时维持 0 = 不展示时间），历史消息时间与实时消息共用同一展示格式。

#### Scenario: 前端历史回显真实时间
- **WHEN** 切回旧会话或刷新页面触发服务端历史拉取
- **THEN** 快照中带 ts 的消息按真实时间展示；无 ts 的旧消息不展示时间（不显示错误时间）
