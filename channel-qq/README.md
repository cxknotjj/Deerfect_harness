# channel-qq

QQ 渠道（NapCat / OneBot 11），**双向**：既能被动接消息对话，也能主动推送。

| 方向 | 入口 | 说明 |
| --- | --- | --- |
| 入站 | `OneBotEventController`（`POST /onebot/event`） | NapCat 通过 `httpClients` 上报，约 4~10s 超时，必须先 ACK 再处理 |
| 出站 | `QqMessageSender`（实现 `MessageSender`） | 定时任务结果、告警走这里；`target` 是群号或 QQ 号 |

## 目录结构

```text
channel-qq/
├── pom.xml
└── src/
    ├── main/java/com/dark/javaHarness/channel/
    │   ├── data-flow.md                 # 入站/出站链路的设计说明（放在源码树里，改链路时同步）
    │   └── qq/
    │       ├── client/    (6)  NapCat HTTP 客户端与装配
    │       ├── dto/       (4)  OneBot 11 报文模型
    │       ├── event/     (8)  入站：接收、准入、解析、去重、派发
    │       ├── persistence/(3) 会话绑定（session ↔ QQ 用户/群）
    │       └── reply/     (2)  回复侧：限频与表情包
    └── test/java/com/dark/javaHarness/channel/qq/...
```

### `client/` —— 与 NapCat 说话

| 类 | 作用 |
| --- | --- |
| `NapCatApiClient` / `NapCatApiClientImpl` | 调 NapCat 的 HTTP API：发消息、取登录信息 |
| `NapCatProperties` | `napcat.*` 配置（base-url / token / 事件密钥 / 限频 / 表情索引） |
| `NapCatChannelConfig` | 装配：按开关创建客户端、`MessageSender`、`ChannelHealth` |
| `NapCatHealth` | 健康快照：`get_login_info` 探活，结果缓存 30s（监控页 15s 刷新不会打爆 NapCat） |
| `QqMessageSender` | `MessageSender` 实现：把文本拆成 OneBot 段发出 |

### `dto/` —— OneBot 11 报文

`OneBotEvent`（上报事件）、`MessageSegment`（消息段，text/image/at）、`SendMsgRequest`、
`ApiResult`。纯数据，不含逻辑，方便单测直接构造报文。

### `event/` —— 入站链路（最关键的一段）

| 类 | 作用 |
| --- | --- |
| `OneBotEventController` | 接收端点。纪律：验签 → `message_id` 去重 → **立即 200** → 异步处理 |
| `OneBotEventService` / `Impl` | 事件处理与调度：有界队列 + 8 线程聊天池 + 会话绑定 |
| `ChannelEventPolicy` | 程序层准入（Fast Path）：来源/群/用户/触发方式 → RESPOND / CANDIDATE / IGNORE |
| `CommandParser` | `/name arg` 指令解析（纯函数） |
| `EventTextParser` | CQ 码 → 纯文本，剥 @ 与图片段 |
| `MessageDedupCache` | 按 `message_id` 幂等去重 |
| `SentMessageTracker` | 记住自己发出的消息，避免把机器人自己的回显当成用户输入 |

**为什么必须先 ACK**：NapCat 上报超时约 4~10 秒，同步做 LLM 调用必然超时重发，
重发又会产生重复回复。所以解析、过滤、模型调用全部发生在 ACK 之后。

### `persistence/` —— 会话绑定

`OneBotSessionBinding`（表实体）、`OneBotSessionBindingMapper`、`OneBotSessionBindingCleaner`
（实现 `SessionBindingCleaner`，删除 harness 会话时清掉 QQ 侧绑定，避免孤儿会话）。

### `reply/` —— 回复侧的两个护栏

- `UserRateLimiter`：同一用户两次回复的最小间隔（内存滑动窗），防 QQ 风控。
  **限频不等于串行**：限频防刷屏，串行防「同一会话两条消息互相看不见」，后者目前只有飞书做了。
- `EmojiReplies`：按 LLM 输出的分类标记随机取一张表情包，产出内联 `base64://` 图片段。

## 边界

- 只支持 OneBot 11 的 HTTP 上报，不支持 WebSocket 正/反向连接。
- `NapCatHealth` 是唯一会主动打 NapCat 的地方；其余路径都是「有消息才动」。
- 渠道实现不得抛异常给 core：`QqMessageSender` 的失败折成 `DeliveryReceipt`。
