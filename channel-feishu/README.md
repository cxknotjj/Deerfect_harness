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
    │   └── ws/           (13)         # M3b 长连接入站（含指令与表情）
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
| `FeishuChatHandler` | **按会话串行** → 绑定会话 → 调 `ChatService` → 回复；指令在入队前拦掉 |
| `FeishuCommandParser` | `/name arg` 解析（纯函数）；只认白名单指令名，其余回落成普通消息 |
| `FeishuEmojiCatalog` | `[emoji:分类]` 标记剥离 + 按分类从 `emojis/` 取图（永不抛出） |
| `FeishuApiClient` | `im/v1/messages` 发送，`tenant_access_token` 缓存到过期前 60 秒 |
| `FeishuInboundMessage` / `FeishuMessageHandler` | 入站消息值对象 / 处理器接口 |

**两个必须知道的坑**（`docs/dev.md` 有详细版）：

- **SDK 无法停止**：oapi-sdk 2.4.19 的 `Client` 没有公开 stop，`pingLoop` 死循环且吞中断，
  OkHttp 线程非守护——JVM 不会自然退出。`FeishuWsClient.stop()` 断开后延迟 3 秒
  `Runtime.halt(0)`。升级 SDK 后必须重跑一次「启动 → Ctrl+C」。
- **按会话串行**：同一 `sessionId` 同时只跑一轮，第二条排队（容量 1），第三条回
  「上一条还在处理」。理由是写回虽有行锁，但「读上下文」在模型调用前、无锁，
  并发两条会各自基于旧上下文回答。

## 指令（仅授权人可用）

`FeishuChatHandler` 在入队之前拦指令：不建会话、不进会话队列、不调模型——`/help` 不该为了
回一段固定文案先建一个会话，也不该排在一条 30 秒的生成任务后面。

| 指令 | 作用 |
| --- | --- |
| `/new`、`/reset` | 删掉 `(open_id, chat_id)` 的绑定，下条消息建新会话（旧会话行保留，Web 仍可查） |
| `/history [n]` | 最近 n 条对话，默认 5、上限 20 |
| `/agent` | 列出可用 Agent，标出当前绑定的那个 |
| `/agent <名>` | 切换本会话的 Agent（需要已有会话） |
| `/help` | 上面这份清单 |

**为什么只有一个人能用**：指令在群里也生效，而 `/new` 重置的是「这个人 + 这个群」的上下文。
放开给所有人，群里任何成员都能打散别人的对话。授权口径是单个 open_id：

```bash
export FEISHU_WS_COMMAND_USER=ou_xxx
```

留空时回退 `allowed-users` 首位（启动日志会注明回退了）；两者都空时指令不可用。
**注意 `command-user` 必须在 `allowed-users` 里**，否则入站白名单会先把消息挡掉，
表现为「指令发了没反应」，启动日志会有一条 warn。

非授权用户发指令会收到一句「指令只有管理员能用」，**不交给模型**——模型会顺着斜杠编出
一份并不存在的命令清单，比不回复更糟。不认识的 `/xxx` 不拦截，照常走聊天。

## 表情包

模型在回复末尾输出 `[emoji:分类]`（`happy` / `angry` / `shock` / `chill` / `social` /
`abstract` / `festive`，`none` = 不发），渠道剥离标记后追加一张同类图片：

```text
回复 "好耶！\n[emoji:happy]"
  → 发送文本 "好耶！"
  → 按 happy_ 前缀从 emojis/ 随机取一张
  → POST /im/v1/images 换 image_key
  → msg_type=image 发送
```

| 配置（`channel.feishu.ws.emoji.*`） | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭时**仍会剥离标记**（不让 `[emoji:happy]` 露给用户），只是不选图不发图 |
| `dir` | `emojis` | 与 QQ 共用同一份图片目录 |
| `index-file` | `emojis/emoji-index.json` | 缺失时自动创建空映射，日志打印绝对路径 |
| `probability` | `1.0` | 分类命中后的发送概率 |

与 QQ 的差异只有两处：QQ 把图片内联成 OneBot 的 `base64://` 段，飞书必须先上传换
`image_key`（飞书不接受内联字节）；配置键各自独立，因为渠道模块之间不能互相依赖，
共享的是**数据**（`emojis/` 目录与索引）而不是代码。

触发依赖提示词：`PromptAssembler` 的飞书信道规范段会告诉模型这个约定。换 Agent 时，
如果它的提示词明令「只输出正文」，标记就不会出现——那不是渠道的问题。

## 边界

- 支持的消息类型：`text`、`post`（富文本，拼成纯文本）、`image`（作为多模态附件交给模型）。
  `file` / `audio` / `sticker` 仍记 debug 丢弃。
- 群聊接收**所有**用户消息（需 `im:message.group_msg`，敏感权限），不要求 @ 机器人；
  回复用 `receive_id_type=chat_id`。机器人自己的消息按 `sender_type` 过滤，避免自回环。
- 图片需要 `im:resource` 权限下载（`GET /im/v1/messages/{message_id}/resources/{file_key}?type=image`），
  单张上限 8 MB；下载失败只跳过该图，文本照常回复。
- 发图（表情）需要 `im:resource`——与下载图片同一个权限；缺权限时上传返回 403，只在日志里 warn，文本照常发出。
- 图片只对 Agentic 路径生效：多 Agent 编排（`MultiAgentGraphAgent`）走 `Agent.execute` 的默认实现，
  附件会被丢弃。图片也不进会话快照，下一轮看不到上一轮的图。
- 长连接没有回调端点，因此没有验签三关：安全边界是「App 凭据握手 + 可选 encrypt-key +
  `allowed-users` 白名单」。白名单为空时进入**配对模式**：只打印 open_id，不回复。
- 指令只有 `command-user` 一个 open_id 能用，且不提供 `/recall`：撤回需要记住每条发出消息的
  id 并调用删除接口，是唯一带新增状态的功能，暂未做。
