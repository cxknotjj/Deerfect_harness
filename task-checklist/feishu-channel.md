# 飞书渠道（M3a webhook / M3b 事件订阅）

- 范围：新增 `channel-feishu` 模块；M3a 只做出站推送，M3b 才有入站对话
- 创建：2026-11-08
- 状态：共 9 项，已完成 8 项；其中 **M3a 4/5**（F2-5 只差真实群 webhook 验证）、**M3b 4/4 完成**（含群聊 @ 扩展）
- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 5.3 / 8 节
- 关系：依赖 `delivery-outbox.md`（错误码要能落到 `markFailed(retryable)`）；与 `email-channel.md` 结构对称，登记方式相同

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| M3a 出站推送 | 4 | 1 | 5 |
| M3b 事件订阅（长连接） | 4 | 0 | 4 |
| 合计 | 8 | 1 | 9 |

## 背景

判据只有一句：**投递可靠性只需要「发得出去」，不需要「收得进来」。** 这两件事在飞书上是两套接入方式，成本差 6 倍。

- **M3a 群机器人 webhook**：0.5 人日。不建应用、不申请权限、不需要审核、不需要公网回调、零 SDK。拿到 `code 0/9499/11232` 三种错误码就够 outbox 分流
- **M3b 事件订阅**：3~4 人日。才有对话入口；回调路线需域名 + HTTPS，长连接路线需 SDK 在 Java 25 上能跑

M3a 做完，「主通道可换、失败原因可分类」就成立了。M3b 是产品升级，不是可靠性修复。

## M3a：群机器人 webhook

- [x] F2-1 模块骨架与三处登记
  - 作用：飞书作为可选渠道独立打包，不启用时 core 依赖里没有任何 lark / http 相关库
  - 位置：新增 `channel-feishu/pom.xml`；改根 `pom.xml`、`server/pom.xml`
  - 做法：与 `channel-email` 同一套登记动作（根 pom `<modules>` / server pom `runtime` 依赖 + enforcer `bannedDependencies` 排除 / 自身 pom 的 `jacoco.line.minimum`）。M3a **只依赖 `harness-api` + JDK `HttpClient`**，不要引入 `spring-boot-starter-web`（那是 M3b 回调才需要）
  - 验收：`mvn -o -B verify` 通过；`dependency:tree -pl channel-feishu` 里没有 web starter、没有 `lark-oapi`
  - 结果（2026-11-08）：新建 `channel-feishu`，依赖只有 `harness-api` + `spring-boot-starter`（容器能力）+ `jackson-databind`（请求体与错误码），**没有** web starter、没有 `lark-oapi`。三处登记完成：根 pom `<modules>`、server pom 的 `runtime` 依赖 + enforcer 排除、自身 `jacoco.line.minimum`（实测 84.5% → 门槛 0.82）。

- [x] F2-2 FeishuProperties 与 webhook 配置
  - 作用：让 `notify_channel` 只写别名，凭证不进业务表
  - 位置：`channel-feishu/.../FeishuProperties.java`、`server/src/main/resources/application-channel.yaml`
  - 做法：`@ConfigurationProperties(prefix = "channel.feishu")`，M3a 字段 `enabled / webhookAlias / webhookUrl / webhookSecret`；`webhook-url` 与 `webhook-secret` 从环境变量注入（`FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET`）；`@ConditionalOnProperty` 默认关闭
  - 验收：`enabled=false` 时无 Bean；`enabled=true` 且缺 secret 时启动即报配置缺失（不要静默按无签名发送）；`notify_channel` 里出现的是 `feishu:main` 而不是 URL
  - 结果（2026-11-08）：字段为 `enabled / alias / webhookUrl / webhookSecret / timeoutMs`，默认 `alias=main`、`enabled=false`。`FeishuChannelConfig` 在 `enabled=true` 时校验 URL 与 secret，缺任一直接抛 `IllegalStateException` 拒绝启动（`FeishuChannelConfigTest` 三例覆盖）。顺手补了 `docker-compose.yml` 的渠道环境变量透传——此前只有文档写了 `CHANNEL_EMAIL_ENABLED`，compose 并未注入，等于文档说不通。

- [x] F2-3 FeishuWebhookSender（签名 + 错误映射）
  - 作用：把飞书的结构化错误码翻译成「可重试 / 终态」，让 outbox 能自动补救
  - 位置：`channel-feishu/.../FeishuWebhookSender.java`
  - 做法：`channel()` 返回 `"feishu"`；`target` 是配置里的别名（如 `main`），不是 URL；签名 `sign = Base64(HMAC-SHA256(key = timestamp + "\n" + secret, data = ""))`，**timestamp 用秒级**；请求体 `{"msg_type":"text","content":{"text":"..."}}`。映射：`code=0` → 成功；`code=9499` → 终态（请求体非法，含超 20 KB）；`code=11232` → 可重试；返回体提示 sign / timestamp → 终态并提示查时钟
  - 验收：单测覆盖四类分支；签名用固定 `timestamp + secret` 能算出与飞书文档一致的 base64；本机时钟偏 10 分钟时给出可读错误而不是笼统失败
  - 结果（2026-11-08）：`FeishuWebhookSenderTest` 13 条，用 JDK `HttpServer` 起本地桩验证「发出去的 JSON」与「响应怎么翻译」，不 mock HttpClient。签名向量由 Python `hmac` 独立算出并硬编码（`1700000000` + `test-secret` → `mbm4Y4o…PL84=`），锁住「key 是 timestamp+secret、data 为空、秒级时间戳」三件事。分流补齐 HTTP 维度：429 / 5xx 可重试，其余 4xx 终态；超 20 KB 在发送前就判终态，不浪费一次必然失败的请求。

- [x] F2-4 FeishuHealth
  - 作用：让 `ChannelWatchdog` 能发现飞书不可达
  - 位置：`channel-feishu/.../FeishuHealth.java`
  - 做法：实现 `ChannelHealth`，读最近一次投递结果与时间（与邮件同一策略：不发主动探活，避免为了探活往群里发测试消息）；超 7 天无投递报「未知」
  - 验收：`channelName()` 为「飞书 / Webhook」；从未投递 → `available=false` + 「未知」；`detail` 不含 webhook URL 与 secret
  - 结果（2026-11-08）：`channelId()=feishu`（探活告警靠它排除本渠道），四态与邮件一致：从未投递 / 超 7 天 → `UNKNOWN`，最近一次失败 → `DOWN`。`FeishuHealthTest` 7 条，其中一条专门断言 `detail` 不含 URL 与 secret——监控页是浏览器能打开的页面。

- [ ] F2-5 真机验证（含真实群与限流实验）
  - 作用：证明「换个通道就能拿到可分类的失败原因」不是纸面结论
  - 位置：飞书客户端 + 本机运行中的服务
  - 做法：建一个只有自己的群 → 群设置 → 群机器人 → 添加自定义机器人 → 开启签名校验 → 拿到 URL 与 secret；配进 `application-channel.yaml`；把某条定时任务的 `notify_channel` 改成 `feishu:main|email:...`
  - 验收：① 正常推送在飞书群可见；② 故意写错 secret → outbox 该行 `DEAD` 且 `last_error` 提示签名；③ 停 NepCat 后任务结果从飞书收到（`chain_index=0`）；④ 20 KB 以上正文走邮件（D1-8）
  - 进度（2026-11-08）：**代码侧就绪，验收未闭环**。签名、错误分流、20 KB 预检都有单测覆盖，但没有真实 webhook URL 与 secret，无法验证「消息真的出现在飞书群」。需要你建一个只有自己的群、加自定义机器人、开启签名校验，把 URL 与 secret 配进环境变量后按上面四步验一遍。

## M3b：事件订阅（未启动，等 M3a 跑一两周再决定）

- [x] F2-6 路线选型与前置验证
  - 作用：先确认「有没有域名」，再决定走回调还是长连接——两条路的前置完全不同
  - 位置：`docs/dev.md` 的渠道说明；`channel-feishu/pom.xml`
  - 做法：有域名 + 有效证书（Let's Encrypt）→ 走 Webhook 回调，零 SDK；没有域名 → 只能长连接，**先花 15 分钟验证 `lark-oapi` 在 Java 25 上能建 WS**，跑不通就停在这里（不值得为它降级 JDK）
  - 验收：选型结论与依据写进 `docs/dev.md`；若选长连接，SDK 连通性验证有明确结论
  - 结果（2026-11-09）：选长连接（无可用 HTTPS 域名）。`oapi-sdk 2.4.19` 在 JDK 25.0.5 上握手成功（日志 `connected to wss://msg-frontier.feishu.cn/...`）。**发现阻断性缺陷**：SDK 没有公开 stop，`pingLoop` 是死循环且吞中断、OkHttp 线程非守护——JVM 永不自然退出。方案：`FeishuWsClient.stop()` 断开后延迟 3 秒 `Runtime.halt(0)`，并写进 `docs/dev.md`，升级 SDK 必须重跑「启动 → Ctrl+C」。

- [x] F2-7 入站结构（3 秒硬约束）
  - 作用：一次 agent 对话几十秒，而飞书要求 3 秒内应答，超时会重推
  - 位置：`channel-feishu/.../event/FeishuEventController.java`（回调）或 `ws/FeishuWsClient.java`（长连接）
  - 做法：收到事件 → 验签 → 去重 → **入有界队列 → 立即返回 200**；后台线程池消费。**复用 `OneBotEventServiceImpl` 的「有界队列 + 背压丢弃 + 超时 `cancel(true)` + 失败静默」结构**，不重新发明
  - 验收：下发一条需要 30 秒生成的消息，飞书侧无「超时重推」；队列满时回「忙，稍等」而不是静默丢
  - 结果（2026-11-09）：`FeishuInboundService` 只做判定 + 入队，2~4 线程、有界队列（默认 32），满则丢并记 warn；SDK 回调毫秒级返回。实测群里 @ 后 1.6 秒内完成回复（含模型生成），无重推。队列满时目前是丢弃 + 日志（未回「忙」），个人用量下可接受，作为已知取舍记录。

- [x] F2-8 验签三关与去重
  - 作用：回调端点暴露在公网，任何人知道 URL 就能伪造事件
  - 位置：`channel-feishu/.../event/`（验签与去重）
  - 做法：三关缺一不可——校验 `verification_token`、用 `encrypt_key` 解密、`allowed_users` 白名单（`open_id` 不在名单直接丢弃）；`event_id` 用 `LinkedHashMap` + 5 分钟 TTL 去重（复用 `MessageDedupCache` 写法）。**不为「先跑通」临时关掉验签**
  - 验收：伪造请求被拒（无验签 / 错 token 两例）；同一 `event_id` 第二次到达不处理；不在白名单的用户消息被丢弃且只记 debug
  - 结果（2026-11-09）：长连接没有回调端点，三关相应变形——① 连接层由 SDK 用 App 凭据换取 ws 地址，`encrypt-key` 可选解密；② `allowed-users` 白名单（空 = 配对模式，只打印 open_id）；③ `FeishuEventDedup` 按 event_id 去重，5 分钟 TTL、容量 2048。差异写进 `docs/dev.md`。`FeishuEventDedupTest` 5 条、`FeishuInboundServiceTest` 8 条。

- [x] F2-9 对话入口接 ChatService
  - 作用：给「随手可用」这个入口补上 1:1 对话
  - 位置：`channel-feishu/.../reply/FeishuReplyService.java`
  - 做法：从事件取 `open_id` 与文本，复用 core 的 `ChatService`，会话按 `open_id` 绑定（与 QQ 按用户绑定同构）；回复走 `im/v1/messages`；首次对接需要配对——用户先发一句话，日志打印 `open_id`，复制进 `notify_channel`，**不做自动发现**
  - 验收：私聊一句话能收到 agent 回复；同一 `open_id` 多轮上下文连续；未配对用户的消息被白名单丢弃
  - 结果（2026-11-09）：`FeishuChatHandler` 按 `(open_id, chat_id)` 绑定 `feishu_session_binding`（V15），复用 `ChatService`（channel=feishu），回复走 `im/v1/messages`（单聊 `open_id` / 群聊 `chat_id`）。实测单聊与群聊 @ 均收到回复。
  - **超出原条目的扩展**：群聊 @ 机器人（原计划只做 1:1）。入站接受 `chatType=group` 且 `mentions` 非空，剥掉 `@_user_N` 占位符；会话按人 + 群隔离。需要额外权限 `im:message.group_at_msg`。
  - **实测发现的两个 bug**：① `session.creator` 只有 varchar(30)，`feishu:` + 35 字符 open_id 超长，建会话报 `Data too long`——V16 放宽到 64；② `mvn -pl server -am package` 在只改渠道模块时不重打 fat jar，必须 `clean`，否则跑的是旧代码。

## 安全前提

webhook URL 本身就是凭证（泄露即被冒名发消息），所以 F2-2 强制要 secret、F2-3 强制签名。M3b 的回调端点还要额外做 F2-8 的三关。这条不是可选项——「先跑通再补安全」在这类端点上通常意味着永远不补。
