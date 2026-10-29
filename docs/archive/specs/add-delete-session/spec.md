# 删除会话功能 Spec

## Why

会话只增不减，列表会被废弃会话填满；web 端没有任何清理入口。需要补齐「删除会话」能力：后端删除端点 + web 端删除交互。

## 实施前影响面评估（已核实）

| 影响对象 | 结论 | 处理方式 |
|---|---|---|
| session 表 | 已有 `@TableLogic isDelete` 软删字段（[SessionEntity.java](file:///home/wsl/development/java-harness/shared/src/main/java/com/dark/javaHarness/domain/entity/SessionEntity.java):33-34），现无删除方法 | 用**软删除**，与现有设计一致 |
| session_messages 快照 | 与 session 一对一，`clear()` 已有物理删除该表行的先例（SessionServiceImpl:257-265） | 删除会话时**物理删除**快照行 |
| llm_call_log / tool_call_log | 以 session_id 为字段（LlmCallLogEntity:21、ToolCallLogLogEntity:21） | **保留不删**：调用日志是可观测性审计记录，不属于会话内容；删除后日志仍在，仅所属会话不再存在 |
| goal（异步目标/断点续跑） | GoalEntity:25 引用 session_id；CLI 的 sessionId 持久化在 `~/.javaHarness_resume_state` | **保留不删**：CLI 恢复旧会话会因软删查不到而失败，属可接受降级（用户自己删的），不做迁移 |
| QQ 绑定 | `onebot_session_binding` 持久化绑定（OneBotSessionBinding:25），QQ 每次查库；若只软删 session，QQ 下条消息会带着旧 id 重建**孤儿快照** | 删除会话时**一并删除**该会话的绑定行，QQ 用户下条消息自动走新建会话 |
| 流式防复活 | 进行中的流结束时 `saveContext` 会无条件重建快照行（ChatServiceImpl:318-322）→ 已删会话「消息复活」 | `saveContext` 前加**存在性守卫**：会话已软删则跳过写回（同时覆盖 QQ 与 web 流两条路径） |
| 用户偏好画像 | 画像从快照提炼（UserProfileService:137），条件 `profile_extracted=0`；已提炼结果存文件不受影响 | 已提炼的不受影响；未提炼即删则**永不提炼**——用户主动删除即放弃，接受 |
| 正在流式输出时删除 | 前端无按会话取消机制 | 不做后端取消：流自然结束，守卫保证不复活数据；前端删除当前会话时先 abort 本地流 |

## 范围（已确认）

**做**（后端仅四件事）：
1. 软删 session 行（`isDelete` 字段与 `@TableLogic` 现成，无需新增列）
2. 物理删除该会话的 session_messages 快照行
3. 删除 onebot_session_binding 中该会话的绑定行
4. `saveContext` 加防复活守卫（否则删完会话，进行中流的收尾会把快照重建出来，产生脏数据）

**不做（Out of Scope）**：
- llm_call_log / tool_call_log 日志数据：**保留不动**（可观测性审计记录，删除后日志仍可按 sessionId 查阅）
- goal 表与 CLI 断点续跑状态：**保留不动**（CLI 恢复已删会话失败属可接受降级，不做迁移）
- 后端按会话取消流式执行的机制（流自然结束，守卫兜底）
- 用户偏好画像迁移

## What Changes

- 后端新增 `DELETE /api/harness/sessions/{sessionId}`：软删 session + 物理删快照行 + 删 QQ 绑定行；幂等（会话不存在也返回成功）
- `SessionService` 新增 `deleteSession(String sessionId)`；`saveContext` 增加防复活守卫
- web 端：`client.ts` 加 `deleteSession`，mock 同步实现；`useSessions` 加 `remove`；`SessionList.vue` 会话项 hover 显示删除按钮（confirm 确认）；删除当前会话时联动清空聊天视图、消息桶与 localStorage 缓存
- **BREAKING**: 无（纯新增能力，不改既有端点与数据结构）

## Impact

- Affected specs: add-self-contained-web-mvp（web 端会话列表能力扩展）
- Affected code:
  - `shared/.../service/SessionService.java`、`server/.../service/impl/SessionServiceImpl.java`
  - `server/.../controller/HarnessController.java`
  - `server/.../service/ChatServiceImpl.java`（仅 saveContext 守卫）
  - `web/src/api/client.ts`、`web/src/api/types.ts`、`web/src/api/mock/mock.ts`
  - `web/src/composables/useSessions.ts`、`web/src/composables/useChat.ts`、`web/src/composables/chatCache.ts`
  - `web/src/components/SessionList.vue`、`web/src/App.vue`

## ADDED Requirements

### Requirement: 删除会话端点
系统 SHALL 提供 `DELETE /api/harness/sessions/{sessionId}`，软删 session 行（复用 `isDelete`），物理删除对应 session_messages 快照行，并删除 onebot_session_binding 中该会话的绑定行。删除不存在的会话 SHALL 幂等返回成功。删除操作 SHALL NOT 对 llm_call_log、tool_call_log、goal 表产生任何写操作。

#### Scenario: 删除存在的会话
- **WHEN** 对存在的会话 id 调用 DELETE
- **THEN** 返回 2xx；session 行 isDelete 置 1；session_messages 与 onebot_session_binding 对应行被删除

#### Scenario: 删除不存在的会话
- **WHEN** 对不存在或已删除的会话 id 调用 DELETE
- **THEN** 返回 2xx（幂等），不抛错

### Requirement: 快照防复活守卫
`saveContext` 在写回前 SHALL 校验会话行存在（未被软删）；已删除会话的流式收尾 SHALL 跳过快照写回与会话 touch，不产生孤儿数据。

#### Scenario: 删除后流收尾不复活
- **WHEN** 会话被删除后，其进行中的流到达收尾并触发 saveContext
- **THEN** 不重建 session_messages 行，不更新 session 行，无异常抛出

### Requirement: web 删除交互
web 端会话列表项 SHALL 在 hover 时显示删除入口；点击后需确认；确认后调用删除 API 并从列表移除。

#### Scenario: 删除非当前会话
- **WHEN** 删除的会话不是当前选中会话
- **THEN** 列表中该项移除，聊天视图不受影响

#### Scenario: 删除当前会话
- **WHEN** 删除的会话是当前选中会话
- **THEN** 终止进行中的流，聊天视图切回草稿空态，该会话的消息桶与 localStorage 缓存一并清除，列表项移除

#### Scenario: 删除失败
- **WHEN** 删除请求失败（网络/服务端错误）
- **THEN** 列表项保留，向用户呈现错误提示，不误删本地数据
