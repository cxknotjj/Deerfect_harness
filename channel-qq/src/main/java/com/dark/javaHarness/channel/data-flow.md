# channel 数据流与时序图

> QQ（NapCat / OneBot 11 纯 HTTP）通道专属数据流文档。主链路（API → ChatService → Agent → LLM）见 `docs/data-flow.md`，本文档只画 channel 侧：从 NapCat 上报到回复落地的完整时序、全部静默丢弃点、线程模型与发送细节。

> **2026-10 增强要点**（分支 `feat/qq-channel-enhancements`）：① 群聊「回复机器人消息」也触发；② 指令化 `/new` `/reset` `/help` `/history` `/agent` `/recall`；③ 群聊回复首条可 `@提问者`；④ `/recall` 撤回；⑤ 已发消息 id 记录（`SentMessageTracker`）；⑥ 回复整段单条发送（超长按上限截断，不再分段）。触发判据与指令明细见第四节，撤回与消息记录见第五节。

## 一、线程模型（三条线程链）

| 线程链 | 归属 | 职责 | 语义 |
|---|---|---|---|
| `nio-8080-exec-*` | Tomcat | 接收上报、验签、解析、去重 | **立即 ACK**，不做任何慢事（NapCat 上报超时约 4~10s，同步处理会导致重发风暴） |
| `onebot-exec-*` | onebotExecutor（有界队列） | handle 全流程：过滤/提取/限频/建绑/调聊天/发回复 | 队列满 → log.error「异步池拒绝事件」 |
| `onebot-chat-*` | chatExecutor（8 线程） | 仅承载 `ChatService.chat` 阻塞调用 | 被 `chatWithTimeout` 120s（可配）看管；超时后 future.cancel(false)，任务继续跑完只落会话记忆 |

## 二、端到端时序图

```mermaid
sequenceDiagram
    autonumber
    participant N as NapCat (QQ)
    participant C as OneBotEventController<br/>(nio-8080-exec)
    participant S as OneBotEventServiceImpl<br/>(onebot-exec)
    participant CH as ChatService → LLM<br/>(onebot-chat)
    participant A as NapCatApiClient<br/>(OkHttp)
    participant E as EmojiReplies

    N->>C: POST /onebot/event（message 事件）
    C->>C: X-Signature 验签（HMAC-SHA1，secret 未配置跳过）
    C->>C: Jackson 解析 → messageId 幂等去重
    C->>C: INFO「收到上报 type/user/群名片/group/text 预览」
    C-->>N: 200 ACK "{}"（立即，快速操作空对象）
    C->>S: onebotExecutor.execute(handle(event))

    rect rgb(255, 245, 230)
        note over S: 过滤与丢弃（任一命中即静默返回，见第三节）
        S->>S: 自消息过滤（userId == self-id）
        S->>S: 私聊白名单 / 文本提取（群聊 at|prefix|all 三模式；at 模式下含 @机器人 / 回复机器人）
        S->>S: 限频 tryAcquire（per-user-seconds，10s 默认）
    end

    alt 指令路由（/new /reset /help /history /agent /recall）
        S->>S: CommandParser 解析 → handleCommand 路由（会话重置/帮助/历史/Agent/撤回）
        S->>A: sendGroupMsg/sendPrivateMsg（确认语 / 帮助 / 历史 / Agent / 撤回）
        A-->>N: 已开启新对话 / 可用指令 等
    else 正常消息
        S->>S: bindSession（binding 命中复用，未命中建 harness 会话并落库）
        S->>CH: chatWithTimeout → chatExecutor.submit(chat)
        CH-->>S: ChatResponse（status=FAILED/SUCCEEDED）
        S->>S: capLength 超长截断（加 …）；群聊段序 reply→at(可选)→text
        S->>A: sendGroupMsg/sendPrivateMsg（整段单条 text）
        A->>N: POST /send_group_msg（IO/5xx 重试 1 次）
        A-->>S: ApiResult；retcode=0 → INFO「已发送…（messageId）」
        S->>S: 成功返回 id → SentMessageTracker.record(目标键, id)
        S->>E: pickByCategory(category)
        alt 表情分类命中（[emoji:happy] 等；none/无标记不发）
            S->>S: pauseHook.pause(send-delay-ms)
            S->>A: 发送 image 段（base64:// 内联图片）
        end
    end
```

## 三、静默丢弃点一览（「接到请求后没反应」排查表）

| # | 丢弃点 | 判定条件 | 日志 | 代码位置 |
|---|---|---|---|---|
| 1 | 上报验签失败 | X-Signature 校验不过（secret 已配置时） | **WARN**（403） | Controller.signatureValid |
| 2 | 重复上报 | messageId 已处理过 | DEBUG | Controller.onEvent |
| 3 | 异步池拒绝 | onebotExecutor 队列满 | **ERROR** | Controller.onEvent |
| 4 | 自消息过滤 | `user_id == napcat.self-id`（防自循环） | **无日志** | doHandle |
| 5 | 私聊白名单外 | `private-allow-users` 非空且不含该 uid | DEBUG | doHandle |
| 6 | 文本提取为空 | message 数组无 text 段且 raw_message 空 | **无日志** | doHandle |
| 7 | 群聊未命中触发 | mode=at 且既无 at 段指向 self-id，也未命中「回复机器人」（`reply-to-bot=false` 或入站 reply 未命中我方已发记录；self-id 未配置时 @ 判据恒假） | **无日志** | extractGroupText → atSelf/replyToSelf |
| 8 | 限频 | 同 uid 间隔 < per-user-seconds | DEBUG | doHandle |
| 9 | 聊天超时 | chatExecutor 超 chat-timeout-seconds（120s 默认） | **WARN**（放弃回复） | chatWithTimeout |
| 10 | 聊天执行异常 | LLM 链路抛异常 | **WARN** | chatWithTimeout |
| 11 | 未产出回复 | status≠SUCCEEDED 或 reply 空 | **WARN** | doHandle |
| 12 | 表情发送异常 | 表情钩子任何异常 | **WARN**（不影响文本） | sendReply |

> 4/6/7 三个点**完全无日志**——排查「收到上报但没调用大模型」时优先怀疑：群聊触发未命中（`napcat.self-id` 配置与实际 at 段 qq 是否一致；开了 `reply-to-bot` 但回复的那条并不在 `SentMessageTracker` 的 TTL 记录内）、文本提取为空。把 `logging.level.com.dark.javaHarness.channel.qq` 调到 DEBUG 可看到 2/5/8；临时排查可临时提升该包日志级别。

## 四、触发判定与指令清单（2026-10 增强）

### 群聊触发：@机器人 与 「回复机器人消息」

`napcat.group-trigger.mode=at`（默认）时，命中**任一**即触发：

1. **@机器人**：message 数组存在 `at` 段且 `data.qq == napcat.self-id`（数组缺失时兜底 `raw_message` 的 `[CQ:at,qq=self-id]`）。
2. **回复机器人消息**（新增）：`napcat.group-trigger.reply-to-bot=true`（默认 `false`）时，入站 message 数组存在 `reply` 段且其引用的消息 id 命中 `SentMessageTracker` 已发记录（TTL 内）。

两判据实现在 `EventTextParser.extractGroupText` / `atSelf` / `replyToSelf`；`prefix`（前缀）/`all`（全量）模式不受影响。

### 指令清单（`CommandParser` 纯函数）

`"/name arg"` 解析，`name` 小写化、`arg` 去首尾空白；**非 "/" 开头或 "/" 后为空 = 普通聊天**。命中指令由 `OneBotEventServiceImpl.handleCommand` 路由：

| 指令 | 行为 | 消耗 LLM |
|---|---|---|
| `/new` `/reset` | 删除当前会话 binding 行，下条消息重新建档开启新会话（记忆清空），并回确认语 | 否 |
| `/help` | 回显指令帮助文本 | 否 |
| `/history [n]` | 回显当前会话最近 n 条（默认 5；n 非法/≤0 回默认），单行截断 60 字 | 否 |
| `/agent` | 列出可用 Agent（`AgentService.agentItems()`） | 否 |
| `/agent <名>` | 按名（忽略大小写）切换当前会话 Agent；需已有会话绑定，未建档回提示 | 否 |
| `/recall` | 撤回本会话/本群最近一条机器人已发消息 | 否 |

未识别指令返回 false，交回正常聊天链路。

### 只读依赖 core `AgentService`

`/agent` 指令需要 Agent 列表与「名称 → id」映射，故 `OneBotEventServiceImpl` 构造新增只读依赖 core 接口 `com.dark.javaHarness.service.AgentService`（仅调用 `agentItems()`；**未修改 core 定义**）。会话切换仍走既有 `SessionService.switchAgent`。构造签名现为 `OneBotEventServiceImpl(ChatService, SessionService, AgentService, OneBotSessionBindingMapper, NapCatApiClient, NapCatProperties)`。

## 五、撤回与已发消息记录

### 已发消息记录 `SentMessageTracker`

- 内存 TTL（30 分钟）：`messageId → 发送时刻` + `目标键 → 最后一条 id`（目标键 `private:<uid>` / `group:<gid>`）。
- 写入：`sendSegments` 成功返回非空 id 时 `record(目标键, id)`；发送失败（id=null）忽略。
- 消费两处：① 群聊回复触发判定（入站 reply 段命中 = 回复机器人）；② `/recall` 取本目标最近一条 id。
- 容量软上限 10000，达阈值按 TTL 清扫一次；无后台线程、零外部依赖。

### 撤回（`/recall`）

- `/recall`：取 `lastId(目标键)`，命中则 `NapCatApiClient.deleteMsg(id)`（OneBot `delete_msg`）并回「已撤回。」；无则回「没有可撤回的消息。」。
- 回复整体单条发送，不存在「部分已发」场景，故无发送失败自动清理逻辑（`napcat.recall.*` 已随分段一并移除）。

### 配置默认值落点

QQ 渠道配置（`napcat.*`）位于 **`application-qq.yaml`**（自 `application.yaml` 经 `spring.config.import: classpath:application-qq.yaml` 引入，不依赖 profile 激活）；相关键已在其中显式声明，`NapCatProperties` 侧零默认值。

| 键 | 类型 | `application-qq.yaml` 显式值 | 说明 |
|---|---|---|---|
| `napcat.group-trigger.reply-to-bot` | boolean | `false` | at 模式下，回复机器人消息是否也触发 |
| `napcat.reply.max-length` | int | `3000` | 回复长度上限（字）：超出部分截断 |
| `napcat.reply.at-asker` | boolean | `false` | 群聊回复首条是否 @ 提问者 |

## 六、发送侧细节

### 整段单条发送 + 超长截断

回复**整段单条发送**，不再分段/渐进弹出：`capLength` 里 `reply.trim()` 后超过 `napcat.reply.max-length` 即截断为前 max 字 + `…`（max ≤ 0 = 不限制）；保留原文换行与空白。

### 群聊段序（reply / @提问者 / text）

同一条消息内段序：`reply`（`event.messageId` 非空即引用原消息）→ `at`（`napcat.reply.at-asker=true` 时 @ 提问者）→ `text`；私聊无 `reply`/`at` 段。@提问者默认关（防刷屏）。

### 表情钩子（文本发送成功后一次）

1. `enabled=false` → 零行为
2. 解析回复末尾 `[emoji:分类]`（取最后一个；`none`/无标记不发），按分类前缀（如 `happy`）从映射中随机取一张
3. probability 摇号（1.0 必发）→ `pauseHook.pause(send-delay-ms)`（包内可注入、可被中断）→ 发 image 段（整段回复最多一张）

映射表：`channel/emoji-index.json`（表情名 → 图片文件，表情名前缀即分类；启动加载一次，缺失/损坏空映射降级）；图片放 `channel/emojis/`（不进 git）。

## 七、持久化与外部交互

| 对象 | 类型 | 说明 |
|---|---|---|
| `onebot_session_binding` | MySQL 表 | session_key（private:uid / group:gid）→ harness session_id；仅 `/reset`、`/new` 删除对应行（过期清理是 todo 缺口） |
| `/onebot/event` | HTTP POST 入站 | NapCat httpClients 推送；恒 200 ACK（防重发风暴） |
| `/send_group_msg` `/send_private_msg` `/get_login_info` | HTTP POST 出站 | OkHttp，3s 连接/10s 读超时；IO/5xx 重试 1 次；成功返回 message_id 并记入 `SentMessageTracker`；一切失败只记日志（尽力而为） |
| `/delete_msg` | HTTP POST 出站 | OneBot 撤回接口（`NapCatApiClient.deleteMsg`）；失败只记日志不抛异常 |
| `channel/emoji-index.json` | 本地文件 | 表情映射表，启动加载一次（改后需重启） |

## 八、组件映射

```
channel/qq/
├── event/
│   ├── OneBotEventController     入站端点：验签/去重/ACK/异步分发
│   ├── OneBotEventService(Impl)  编排：过滤→提取→限频→指令路由/建绑→聊天→回复（含表情钩子）
│   ├── EventTextParser           事件文本提取（群聊 at|prefix|all 三模式）+ @机器人/回复机器人触发判据
│   ├── CommandParser             指令解析纯函数（/name arg，小写化）
│   ├── SentMessageTracker        已发消息 TTL 记录（回复触发判定 + /recall 共用）
│   └── MessageDedupCache         messageId 幂等去重（内存）
├── client/
│   ├── NapCatApiClient(Impl)     出站客户端：发送（回传 message_id）+ delete_msg + 启动自检 + 成功/失败日志
│   ├── NapCatChannelConfig       装配：napcat.enabled 条件装配 + onebotExecutor/chatExecutor
│   └── NapCatProperties          yaml 绑定（reply/emoji/rate-limit/group-trigger 等）
├── reply/
│   ├── EmojiReplies              表情匹配器：JSON 映射加载 + tag/句尾判定 + image 段生成
│   └── UserRateLimiter           同 uid 内存滑动窗限频
└── dto/                          OneBotEvent / MessageSegment / SendMsgRequest / ApiResult
```
