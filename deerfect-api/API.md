# deerfect-api 方法手册

本文件逐个说明 `deerfect-api` 的端口、SPI 与关键类型：**做什么、参数是什么、会不会阻塞、
失败时是什么行为**。它描述能力，不规定调用顺序——怎么组合由消费者决定。

## 阅读约定

- **线程**：端口实现都是 Spring 单例，可被多线程调用；标注「阻塞」的方法不要放在事件回调线程
  （飞书/QQ 的事件线程有 3 秒应答约束）。
- **失败**：业务性错误用 `IllegalArgumentException`（参数/状态不合法）；不抛受检异常。
  渠道实现型 SPI 的约定相反——**不得抛异常**，失败折成返回值。
- **稳定性**：见 [README.md](./README.md) 的能力目录。「稳定」级签名变更必须同步本文件与
  `PortSignatureGuardTest`。

---

## 一、调用型端口（server 实现，消费者调用）

### ChatService

对话用例入口：建档、检索、Agent 执行、响应组装都在实现内部完成。

| 方法 | 说明 | 阻塞 | 失败 |
| --- | --- | --- | --- |
| `ChatResponse chat(ChatRequest request)` | 同步完成一轮对话 | 是（数十秒级） | 参数非法抛 `IllegalArgumentException`；模型失败折成 `status=FAILED` + `error` |
| `Flux<String> streamReactive(ChatRequest request)` | 流式对话，元素是 SSE 文本事件（`data: <token>` / `data: [DONE]` / `event: meta`） | 否（订阅后异步） | 流内 `event: error` |
| `Flux<String> resume(String goalId)` | 按 goalId 从检查点续跑 multi-agent 编排 | 否 | goal 不存在抛 `IllegalArgumentException`；仍在 RUNNING 抛冲突异常（HTTP 层映射 409） |

要点：

- `chat` 与 `streamReactive` 都会写会话记忆。写回本身用 `FOR UPDATE` 锁会话行，
  **不会丢消息**；但整轮没有串行化——同一会话并发提问时，两轮都可能基于旧上下文生成回答，
  历史顺序也会按完成时间而非提问时间排列。消费者需自行保证同一会话串行。
- `streamReactive` 的元素已带 SSE 前缀，HTTP 层只负责补换行；非 HTTP 渠道若不需要流式，
  直接用 `chat` 更简单。

### SessionService

多轮会话与消息快照。继承 Spring AI 的 `ChatMemory` 与 `SessionSummaryPort`。

| 方法 | 说明 | 返回/失败 |
| --- | --- | --- |
| `String createSession(String creator, String firstQuestion)` | 新建会话，会话名取首条提问（截断） | 返回 sessionId 字符串；`creator` 建议写 `渠道:用户标识` |
| `List<Message> loadContext(String sessionId)` | 读取完整上下文（Spring AI Message 列表） | 无会话返回空列表 |
| `List<SessionMessagesView.Item> listMessages(String sessionId)` | 历史消息（role/content/ts），供回显 | 无历史返回空列表 |
| `List<SessionSnapshotItem> snapshotItems(String sessionId)` | 快照条目（带稳定 id，增量消费用） | 无历史返回空列表 |
| `void saveContext(String sessionId, Message message)` | 追加一条消息，ts 取当前时刻 | —— |
| `void saveContext(String sessionId, Message message, Long ts)` | 追加一条消息并指定真实时刻 | —— |
| `void saveContextBatch(String sessionId, List<SessionMessageEntry> entries)` | 一轮对话批量写入（推荐） | —— |
| `void touchSession(String sessionId, String lastQuestion)` | 更新「最近提问」 | —— |
| `SessionInfo getSession(String sessionId)` | 查会话（软删除不返回） | 不存在或 id 非法返回 **null** |
| `void switchAgent(String sessionId, Long agentId)` | 会话内切换 Agent（幂等） | 会话/Agent 不存在抛 `IllegalArgumentException` |
| `PageResult<SessionInfo> page(long current, long size)` | 会话分页（id 降序） | 页数由调用方按 `total / size` 计算 |
| `void deleteSession(String sessionId)` | 软删会话 + 删快照 + 通知渠道清绑定 | 幂等，不存在也成功 |

继承自 `ChatMemory`（一般不需要渠道直接调用）：`add(String, List<Message>)`、
`add(String, Message)`、`get(String)`、`clear(String)`。

继承自 `SessionSummaryPort`：`summaryOf` / `requestSummary` / `deleteBySessionId`，见下文。

> `getSession` / `page` 返回契约自己的 `SessionInfo`，与 `session` 表结构解耦；
> 逻辑删除标记、画像提炼游标等持久化字段不会出现在返回值里。

### AgentService

Agent 列表与目标执行。目标（Goal）是「一次执行」的台账，`submit` 异步、`executeSync` 同步、
`executeStreamReactive` 流式。

| 方法 | 说明 | 阻塞 |
| --- | --- | --- |
| `Goal submit(String agentName, String objective)` | 创建目标并异步派发，立即返回 PENDING 的 Goal | 否 |
| `Goal executeSync(String agentName, String objective)` | 创建并同步执行 | 是 |
| `Goal executeSync(String agentName, String objective, String sessionId, String turnId)` | 带会话记忆与轮次标识 | 是 |
| `Goal executeSync(..., String traceId)` | 复用调用链标识 | 是 |
| `Goal executeSync(..., String traceId, String channel)` | 指定来源渠道（注入信道规范段） | 是 |
| `Flux<String> executeStreamReactive(String agentName, String objective, String sessionId, String turnId)` | 流式执行 | 否 |
| `Flux<String> executeStreamReactive(..., String traceId)` | 同上，复用 traceId | 否 |
| `Flux<String> executeStreamReactive(..., String traceId, String channel)` | 同上，指定渠道 | 否 |
| `Flux<String> resumeStreamReactive(Goal goal)` | 按已有 goal 的检查点续跑（固定 multi-agent） | 否 |
| `boolean retry(String goalId)` | 重投失败的 goal（同一条记录，不新建） | 否 |
| `Set<String> agentNames()` | 全部 Agent 名 | 否 |
| `List<AgentItemView> agentItems()` | Agent 列表（id + name），供下拉/绑定 | 否 |
| `Optional<String> findAgentNameById(Long agentId)` | id → 名称 | 否 |
| `Optional<AgentConfig> getAgentConfig(String agentName)` | 读取模型与提示词配置 | 否 |

要点：

- `submit` 返回的 Goal 只是「已入队」；执行结果要查 `Goal.status()`，或订阅流式输出。
- `retry` 仅在 goal 为 FAILED、仍有 agentName 且未超重投上限时返回 true。

### SemanticTriggerJudge

对「程序层判定为候选」的渠道事件做语义层判断（Slow Path）。

```java
boolean shouldRespond(String channel, String conversationKey, String message);
```

- `channel`：渠道标识（`qq` / `feishu`）。
- `conversationKey`：渠道内会话键，用于限频与上下文（如 `qq:group:gid:uid`）。
- 返回 `true` 表示值得主动介入；实现异常或无实现时，调用方应按「不响应」处理。

### HarnessStatusPort

只读状态：渠道 / 沙箱 / MCP 当前是否可用。给 IM 渠道的 `/status` 类指令用。

```java
List<ComponentStatus> components();
```

`ComponentStatus(name, enabled, available, detail)`：`enabled` 是配置层面是否启用，
`available` 是当前是否就绪，`detail` 为展示文案（不含密钥与 URL query）。

实现约定：**只读快照，不得触发新的探活或连接**——一条聊天指令不该变成一次对外部系统的探测。
实现可选，渠道侧用 `ObjectProvider` 注入；缺位时指令应明确说「不可用」而不是编数据。

### SessionSummaryPort

上下文裁剪时的滚动摘要。一般由 core 的上下文组装调用，渠道无需直接使用。

| 方法 | 说明 | 约束 |
| --- | --- | --- |
| `String summaryOf(String sessionId)` | 读取当前摘要 | 同步只读、必须轻量；无摘要/失败返回 null |
| `void requestSummary(String sessionId, List<Message> droppedMessages)` | 把被裁剪消息并入摘要 | 异步 best-effort，失败不抛 |
| `void deleteBySessionId(String sessionId)` | 删除会话摘要 | 幂等，失败不抛 |

---

## 二、实现型 SPI（渠道实现，server 调用）

### MessageSender

主动外发的唯一入口。core 的 outbox 按 `channel:target` 路由到对应实现。

```java
String channel();                                   // qq / email / feishu
boolean send(String target, String text);           // 尽力而为
DeliveryReceipt sendForReceipt(String target, String text); // 带回执（默认实现委托 send）
```

`DeliveryReceipt`：

| 字段 | 含义 |
| --- | --- |
| `sent` | 是否送达 |
| `messageId` | 渠道侧消息 ID（webhook 无 ID 时为 null） |
| `reason` | 失败原因，必须可读且尽量含渠道原始错误码 |
| `retryable` | 失败是否值得重试；配置错/内容问题用 `DeliveryReceipt.fatal(...)` 标为不可重试 |

约定：**实现不得抛异常**，所有失败折成 `DeliveryReceipt`；未知失败默认按可重试处理。

### ChannelHealth

渠道健康快照。core 的监控页与 `ChannelWatchdog` 读取。

```java
String channelName();                 // 展示名，如「飞书 / Webhook」
String channelId();                   // 默认空串；应与 MessageSender.channel() 一致
Snapshot snapshot();
```

`Snapshot(Status status, String detail)`：

| Status | 含义 | 探活告警是否处理 |
| --- | --- | --- |
| `UP` | 已启用且可达 | 恢复通知 |
| `DOWN` | 已启用但确认不可用 | 告警 |
| `UNKNOWN` | 拿不到结论（无近期流量） | 不告警 |
| `DISABLED` | 配置关闭 | 不处理 |

兼容访问器：`enabled()` = 非 DISABLED；`available()` = UP。三参数构造
`Snapshot(boolean enabled, boolean available, String detail)` 仍可用，会映射到四态。
`detail` 不得包含 token / URL / 密钥。

### ReplyPostProcessor

回复文本的后处理扩展点：剥离渠道控制标记、声明通用 flag。

```java
Processed process(String rawReply);
record Processed(String text, Set<String> flags) {}
```

约定：**无副作用、幂等、不得抛异常**（core 会捕获并跳过）。入参非 null；
返回的 `text` 用于写记忆与出参，`flags` 透出到 `ChatResponse.flags`。

### SessionBindingCleaner

删除 harness 会话时，通知渠道清理自己的绑定行。

```java
void deleteBySessionId(long sessionId);
```

实现可选（渠道未启用时无实现，core 静默跳过）；无绑定静默，不得抛异常。

---

## 三、共享桥接

### VoiceArtifactStore

语音工件：工具把 TTS 音频放进来，渠道按 flag 取走发送。工件是短生命周期内存数据。

| 方法 | 说明 |
| --- | --- |
| `String put(String turnId, String text, byte[] audio, String format)` | 存入音频，返回工件 id；同 turnId 覆盖 |
| `Optional<VoiceArtifact> findByTurn(String turnId)` | 按轮次查找（只读，不删除） |
| `Optional<VoiceArtifact> take(String id)` | 取走并删除（渠道发送前调用） |

`VoiceArtifact(id, turnId, text, audio, format, createdAt)`。

---

## 四、关键类型

### ChatRequest

```java
record ChatRequest(String message, String sessionId, Long agentId, String routing, String channel)
```

| 字段 | 说明 |
| --- | --- |
| `message` | 用户输入，必填 |
| `sessionId` | 为空时由实现新建会话，并在响应里返回 |
| `agentId` | 指定 Agent；为空走默认/路由 |
| `routing` | `auto`（默认）/ `agentic` / `graph` |
| `channel` | 来源渠道（`qq` / `feishu` / `cli` / `web` / `api`），用于注入信道规范；空 = 不注入 |

### ChatResponse

```java
record ChatResponse(String sessionId, boolean newSession, String goalId, String status,
                    String reply, String error, List<KnowledgeSource> sources,
                    String agent, Set<String> flags, RouteInfo route)
```

- `status`：`SUCCEEDED` / `FAILED`（大写字符串）。
- `sources`：RAG 命中出处；未启用/无命中为 null 或空列表。
- `agent`：实际执行的 Agent 名（回退与分流可观察）。
- `flags`：渠道无关标志，如 `voice:<id>`。
- `route`：路由决策快照（path/confidence/workstreams/reason/source）。

### Goal 与 GoalStatus

`Goal` 是一次执行的台账：`id`、`objective`、`sessionId`、`turnId`、`traceId`、`channel`、
`agentName`、`status`、`createdAt`、`finishedAt`、`summary`、`lastProgressAt`、
`retryCount`、`nextRetryAt`。

`GoalStatus`：`PENDING`（已创建待执行）、`RUNNING`、`SUCCEEDED`、`FAILED`、
`ACTIVE`（长期目标，不进入执行链）。

### AgentConfig 与 AgentItemView

```java
record AgentConfig(Long modelProviderId, String model, String prompt, String knowledge, Boolean thinking)
record AgentItemView(Long id, String name)
```

`AgentConfig` 来自 agent 表 JOIN model_provider；`knowledge` 是逗号分隔的知识库标识，
`thinking` 控制思考内容是否透传（null/0 = 不透传）。

### 会话相关记录

```java
record SessionInfo(Long id, String name, String creator, String lastQuestion,
                   Integer agentId, LocalDateTime lastActiveAt)
record SessionSnapshotItem(String id, String role, String content, Long ts)
record SessionMessageEntry(Message message, Long ts)
record SessionMessagesView.Item(String role, String content, Long ts)
```

`ts` 为 epoch 毫秒；user 是发送时刻、assistant 是完成时刻；旧快照可能为 null。
`SessionSnapshotItem.id` 是快照条目的稳定 id，用于增量消费定位游标。
`SessionInfo.agentId` 为 null 表示会话未绑定 Agent；`lastActiveAt` 旧数据可能为 null。

---

## 五、一个调用序列示例（示例，不是规定）

```java
// 渠道收到一条消息后，可以这样组合；也可以只用其中一部分
String sessionId = sessionService.createSession("feishu:" + openId, text);
ChatResponse response = chatService.chat(
        new ChatRequest(text, sessionId, null, null, "feishu"));
if ("SUCCEEDED".equals(response.status())) {
    // flags 里可能有 voice:<id>，由渠道决定是否消费
    sender.send(openId, response.reply());
}
```

渠道可以只用 `ChatService`，也可以同时用 `AgentService` 派长任务、用 `SessionService` 管理会话；
这些选择不影响本模块的契约。
