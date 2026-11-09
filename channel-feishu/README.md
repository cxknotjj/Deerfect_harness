# channel-feishu

飞书渠道，**两条互相独立的链路**，开关也分开：

| 链路 | 开关 | 入口 | 方向 |
| --- | --- | --- | --- |
| 群机器人 webhook（M3a） | `CHANNEL_FEISHU_ENABLED` | `FeishuWebhookSender` | 出站：往固定群发通知 |
| 自建应用长连接（M3b） | `CHANNEL_FEISHU_WS_ENABLED` | `ws/FeishuWsClient` | 双向：群里 @ 机器人对话 |

两者不共用任何代码路径。常见误解是「拉进群就能对话」——拉进群的是应用机器人（M3b），
webhook 是群里的自定义机器人（M3a），进群方式、权限、可发送目标都不同。

## 目录结构

```text
channel-feishu/
├── pom.xml
└── src/
    ├── main/java/com/dark/javaHarness/channel/feishu/
    │   ├── FeishuProperties.java      # channel.feishu.*（含 ws.* 子配置）
    │   ├── FeishuChannelConfig.java   # M3a 装配；缺 URL 或 secret 直接拒绝启动
    │   ├── FeishuWebhookSender.java   # M3a：签名 + 错误码翻译 + 20 KB 预检
    │   ├── FeishuHealth.java          # 健康快照（沿用「真实流量当探针」）
    │   ├── persistence/  (2)          # 会话绑定：open_id + chat_id → session
    │   └── ws/           (10)         # M3b 长连接入站
    └── test/java/com/dark/javaHarness/channel/feishu/...
```

### 根包 —— 出站与公共配置

| 类 | 作用 |
| --- | --- |
| `FeishuProperties` | 配置：`webhook-alias/url/secret` + `ws.*`（appId/appSecret/allowedUsers/chatTimeout） |
| `FeishuChannelConfig` | 装配 `MessageSender` 与 `ChannelHealth`；缺 URL 或 secret 时启动即失败——不签名的 webhook 等于对任何拿到 URL 的人开放 |
| `FeishuWebhookSender` | 签名 `Base64(HMAC-SHA256(key=timestamp+"\n"+secret, data=""))`，**秒级**时间戳；错误分流：`9499` 终态、`11232` 可重试、`19021` 终态并提示查时钟 |
| `FeishuHealth` | 和邮件同一策略：不发主动探活（不往群里发测试消息），7 天无投递报 `UNKNOWN` |

### `persistence/` —— 会话绑定

`FeishuSessionBinding`（表实体）、`FeishuSessionBindingMapper`。唯一键是
`(open_id, chat_id)`：同一个人在单聊和不同群里各自独立上下文。

### `ws/` —— 长连接入站（M3b）

| 类 | 作用 |
| --- | --- |
| `FeishuWsConfig` | 装配：`CHANNEL_FEISHU_WS_ENABLED=true` 且 appId/secret 齐备才创建 |
| `FeishuWsClient` | 生命周期：启动、停止、`Runtime.halt(0)` 兜底 |
| `FeishuWsTransport` / `SdkFeishuWsTransport` | 传输层抽象 / oapi-sdk 实现（拆开是为了让生命周期可单测） |
| `FeishuInboundService` | 入站队列（2~4 线程，有界 32）：只做判定 + 入队，SDK 回调毫秒级返回 |
| `FeishuEventDedup` | `event_id` 5 分钟 TTL 去重（容量 2048） |
| `FeishuChatHandler` | **按会话串行** → 绑定会话 → 调 `ChatService` → 回复 |
| `FeishuApiClient` | `im/v1/messages` 发送，`tenant_access_token` 缓存到过期前 60 秒 |
| `FeishuInboundMessage` / `FeishuMessageHandler` | 入站消息值对象 / 处理器接口 |

**两个必须知道的坑**（`docs/dev.md` 有详细版）：

- **SDK 无法停止**：oapi-sdk 2.4.19 的 `Client` 没有公开 stop，`pingLoop` 死循环且吞中断，
  OkHttp 线程非守护——JVM 不会自然退出。`FeishuWsClient.stop()` 断开后延迟 3 秒
  `Runtime.halt(0)`。升级 SDK 后必须重跑一次「启动 → Ctrl+C」。
- **按会话串行**：同一 `sessionId` 同时只跑一轮，第二条排队（容量 1），第三条回
  「上一条还在处理」。理由是写回虽有行锁，但「读上下文」在模型调用前、无锁，
  并发两条会各自基于旧上下文回答。

## 边界

- M3b 只接受 `text` 消息类型，图片/文件/富文本会被忽略并记 debug。
- 群聊必须 @ 机器人（依赖飞书只推送 @ 消息的语义），回复用 `receive_id_type=chat_id`。
- 长连接没有回调端点，因此没有验签三关：安全边界是「App 凭据握手 + 可选 encrypt-key +
  `allowed-users` 白名单」。白名单为空时进入**配对模式**：只打印 open_id，不回复。
