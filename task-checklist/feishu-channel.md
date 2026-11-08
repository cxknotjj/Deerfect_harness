# 飞书渠道（M3a webhook / M3b 事件订阅）

- 范围：新增 `channel-feishu` 模块；M3a 只做出站推送，M3b 才有入站对话
- 创建：2026-11-08
- 状态：共 9 项，已完成 0 项；其中 **M3a 5 项待办**、**M3b 4 项待评估**（等 M3a 跑一两周再决定）
- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 5.3 / 8 节
- 关系：依赖 `delivery-outbox.md`（错误码要能落到 `markFailed(retryable)`）；与 `email-channel.md` 结构对称，登记方式相同

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| M3a 出站推送 | 0 | 5 | 5 |
| M3b 事件订阅（未启动） | 0 | 4 | 4 |
| 合计 | 0 | 9 | 9 |

## 背景

判据只有一句：**投递可靠性只需要「发得出去」，不需要「收得进来」。** 这两件事在飞书上是两套接入方式，成本差 6 倍。

- **M3a 群机器人 webhook**：0.5 人日。不建应用、不申请权限、不需要审核、不需要公网回调、零 SDK。拿到 `code 0/9499/11232` 三种错误码就够 outbox 分流
- **M3b 事件订阅**：3~4 人日。才有对话入口；回调路线需域名 + HTTPS，长连接路线需 SDK 在 Java 25 上能跑

M3a 做完，「主通道可换、失败原因可分类」就成立了。M3b 是产品升级，不是可靠性修复。

## M3a：群机器人 webhook

- [ ] F2-1 模块骨架与三处登记
  - 作用：飞书作为可选渠道独立打包，不启用时 core 依赖里没有任何 lark / http 相关库
  - 位置：新增 `channel-feishu/pom.xml`；改根 `pom.xml`、`server/pom.xml`
  - 做法：与 `channel-email` 同一套登记动作（根 pom `<modules>` / server pom `runtime` 依赖 + enforcer `bannedDependencies` 排除 / 自身 pom 的 `jacoco.line.minimum`）。M3a **只依赖 `harness-api` + JDK `HttpClient`**，不要引入 `spring-boot-starter-web`（那是 M3b 回调才需要）
  - 验收：`mvn -o -B verify` 通过；`dependency:tree -pl channel-feishu` 里没有 web starter、没有 `lark-oapi`

- [ ] F2-2 FeishuProperties 与 webhook 配置
  - 作用：让 `notify_channel` 只写别名，凭证不进业务表
  - 位置：`channel-feishu/.../FeishuProperties.java`、`server/src/main/resources/application-channel.yaml`
  - 做法：`@ConfigurationProperties(prefix = "channel.feishu")`，M3a 字段 `enabled / webhookAlias / webhookUrl / webhookSecret`；`webhook-url` 与 `webhook-secret` 从环境变量注入（`FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET`）；`@ConditionalOnProperty` 默认关闭
  - 验收：`enabled=false` 时无 Bean；`enabled=true` 且缺 secret 时启动即报配置缺失（不要静默按无签名发送）；`notify_channel` 里出现的是 `feishu:main` 而不是 URL

- [ ] F2-3 FeishuWebhookSender（签名 + 错误映射）
  - 作用：把飞书的结构化错误码翻译成「可重试 / 终态」，让 outbox 能自动补救
  - 位置：`channel-feishu/.../FeishuWebhookSender.java`
  - 做法：`channel()` 返回 `"feishu"`；`target` 是配置里的别名（如 `main`），不是 URL；签名 `sign = Base64(HMAC-SHA256(key = timestamp + "\n" + secret, data = ""))`，**timestamp 用秒级**；请求体 `{"msg_type":"text","content":{"text":"..."}}`。映射：`code=0` → 成功；`code=9499` → 终态（请求体非法，含超 20 KB）；`code=11232` → 可重试；返回体提示 sign / timestamp → 终态并提示查时钟
  - 验收：单测覆盖四类分支；签名用固定 `timestamp + secret` 能算出与飞书文档一致的 base64；本机时钟偏 10 分钟时给出可读错误而不是笼统失败

- [ ] F2-4 FeishuHealth
  - 作用：让 `ChannelWatchdog` 能发现飞书不可达
  - 位置：`channel-feishu/.../FeishuHealth.java`
  - 做法：实现 `ChannelHealth`，读最近一次投递结果与时间（与邮件同一策略：不发主动探活，避免为了探活往群里发测试消息）；超 7 天无投递报「未知」
  - 验收：`channelName()` 为「飞书 / Webhook」；从未投递 → `available=false` + 「未知」；`detail` 不含 webhook URL 与 secret

- [ ] F2-5 真机验证（含真实群与限流实验）
  - 作用：证明「换个通道就能拿到可分类的失败原因」不是纸面结论
  - 位置：飞书客户端 + 本机运行中的服务
  - 做法：建一个只有自己的群 → 群设置 → 群机器人 → 添加自定义机器人 → 开启签名校验 → 拿到 URL 与 secret；配进 `application-channel.yaml`；把某条定时任务的 `notify_channel` 改成 `feishu:main|email:...`
  - 验收：① 正常推送在飞书群可见；② 故意写错 secret → outbox 该行 `DEAD` 且 `last_error` 提示签名；③ 停 NepCat 后任务结果从飞书收到（`chain_index=0`）；④ 20 KB 以上正文走邮件（D1-8）

## M3b：事件订阅（未启动，等 M3a 跑一两周再决定）

- [ ] F2-6 路线选型与前置验证
  - 作用：先确认「有没有域名」，再决定走回调还是长连接——两条路的前置完全不同
  - 位置：`docs/dev.md` 的渠道说明；`channel-feishu/pom.xml`
  - 做法：有域名 + 有效证书（Let's Encrypt）→ 走 Webhook 回调，零 SDK；没有域名 → 只能长连接，**先花 15 分钟验证 `lark-oapi` 在 Java 25 上能建 WS**，跑不通就停在这里（不值得为它降级 JDK）
  - 验收：选型结论与依据写进 `docs/dev.md`；若选长连接，SDK 连通性验证有明确结论

- [ ] F2-7 入站结构（3 秒硬约束）
  - 作用：一次 agent 对话几十秒，而飞书要求 3 秒内应答，超时会重推
  - 位置：`channel-feishu/.../event/FeishuEventController.java`（回调）或 `ws/FeishuWsClient.java`（长连接）
  - 做法：收到事件 → 验签 → 去重 → **入有界队列 → 立即返回 200**；后台线程池消费。**复用 `OneBotEventServiceImpl` 的「有界队列 + 背压丢弃 + 超时 `cancel(true)` + 失败静默」结构**，不重新发明
  - 验收：下发一条需要 30 秒生成的消息，飞书侧无「超时重推」；队列满时回「忙，稍等」而不是静默丢

- [ ] F2-8 验签三关与去重
  - 作用：回调端点暴露在公网，任何人知道 URL 就能伪造事件
  - 位置：`channel-feishu/.../event/`（验签与去重）
  - 做法：三关缺一不可——校验 `verification_token`、用 `encrypt_key` 解密、`allowed_users` 白名单（`open_id` 不在名单直接丢弃）；`event_id` 用 `LinkedHashMap` + 5 分钟 TTL 去重（复用 `MessageDedupCache` 写法）。**不为「先跑通」临时关掉验签**
  - 验收：伪造请求被拒（无验签 / 错 token 两例）；同一 `event_id` 第二次到达不处理；不在白名单的用户消息被丢弃且只记 debug

- [ ] F2-9 对话入口接 ChatService
  - 作用：给「随手可用」这个入口补上 1:1 对话
  - 位置：`channel-feishu/.../reply/FeishuReplyService.java`
  - 做法：从事件取 `open_id` 与文本，复用 core 的 `ChatService`，会话按 `open_id` 绑定（与 QQ 按用户绑定同构）；回复走 `im/v1/messages`；首次对接需要配对——用户先发一句话，日志打印 `open_id`，复制进 `notify_channel`，**不做自动发现**
  - 验收：私聊一句话能收到 agent 回复；同一 `open_id` 多轮上下文连续；未配对用户的消息被白名单丢弃

## 安全前提

webhook URL 本身就是凭证（泄露即被冒名发消息），所以 F2-2 强制要 secret、F2-3 强制签名。M3b 的回调端点还要额外做 F2-8 的三关。这条不是可选项——「先跑通再补安全」在这类端点上通常意味着永远不补。
