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
├── README.md                            # 本文件
├── data-flow.md                         # 入站/出站链路、静默丢弃点、线程模型（改链路时同步）
├── todo_list.md                         # channel 侧待办；已完成项留档
└── src/
    ├── main/java/com/dark/javaHarness/channel/qq/
    │   ├── client/    (6)  NapCat HTTP 客户端与装配
    │   ├── dto/       (4)  OneBot 11 报文模型
    │   ├── event/     (8)  入站：接收、准入、解析、去重、派发
    │   ├── persistence/(3) 会话绑定（session ↔ QQ 用户/群）
    │   └── reply/     (2)  回复侧：限频与表情包
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

## 远程联调（SSH 双隧道）

NapCat 跑在远程 Docker 时，用两条隧道打通「本机 server ↔ 远程 NapCat」。

```powershell
# 终端 A（本机，保持不关）：拉回 NapCat API/WebUI，并把远程上报反向隧道回本机 8080
# 主机地址只存仓库根 .env.local（已 gitignore），文档与提交里不出现真实 IP
$env:NAPCAT_REMOTE_HOST = (Select-String -Path .env.local -Pattern '^NAPCAT_REMOTE_HOST=').Line -replace '^NAPCAT_REMOTE_HOST=',''
ssh -i "C:\Users\23220\id_remote_ali_service" -N -L 3000:127.0.0.1:3000 -L 6099:127.0.0.1:6099 -R 8080:127.0.0.1:8080 "root@$env:NAPCAT_REMOTE_HOST"
```

```bash
# 终端 B（ssh 登录远程主机后执行，保持常驻）：把 Docker 网桥网关的 8080 转到反向隧道落点
socat TCP-LISTEN:8080,bind=172.17.0.1,fork,reuseaddr TCP:127.0.0.1:8080
```

- `NAPCAT_REMOTE_HOST`：远程 NapCat 主机地址，只写仓库根 `.env.local`（已 gitignore），
  文档/提交里用环境变量引用，不落真实 IP；私钥路径同理按本机实际填写。
- `-L 3000` / `-L 6099`：本机访问 `127.0.0.1:3000`（NapCat HTTP API）与 `127.0.0.1:6099`（WebUI）
  实际连到远程；`application-qq.yaml` 的默认地址无需改。
- `-R 8080`：远程 `127.0.0.1:8080` 回连本机 harness；NapCat 在容器内只能访问宿主机 Docker
  网桥网关，故用 socat 监听 `172.17.0.1:8080` 再转发到反向隧道落点。
- NapCat 的 OneBot HTTP 上报地址填 `http://172.17.0.1:8080/onebot/event`；本机 harness 需先启动
  （8080 在监听），否则上报失败。
- `172.17.0.1` 是默认 bridge 网关；使用自定义 Docker 网络时按 `docker network inspect` 实际网关调整。

## 边界

- 只支持 OneBot 11 的 HTTP 上报，不支持 WebSocket 正/反向连接。
- `NapCatHealth` 是唯一会主动打 NapCat 的地方；其余路径都是「有消息才动」。
- 渠道实现不得抛异常给 core：`QqMessageSender` 的失败折成 `DeliveryReceipt`。
