# 邮件渠道模块（M1 后半）

- 范围：新增 `channel-email` 模块 + 根 `pom.xml` / `server/pom.xml` 登记 + `application-channel.yaml`
- 创建：2026-11-08
- 状态：共 6 项，已完成 0 项，待办 6 项
- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 6.3 节
- 关系：依赖 `delivery-outbox.md` 的 D1-1（通道链）才能被降级链调用；本清单只做渠道实现，不含投递层

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| 模块骨架 | 0 | 1 | 1 |
| 实现 | 0 | 3 | 3 |
| 部署与测试 | 0 | 2 | 2 |
| 合计 | 0 | 6 | 6 |

## 背景

邮件在运行时就是一个渠道：实现 `MessageSender`、`channel()` 返回 `email`、被 `notify_channel` 按 `email:` 前缀指名。所以它是**独立模块**，不是 `server` 里的一个实现类——判据不是「有没有入站」，而是「外部依赖会不会被 core 继承」与「实现会不会被替换」。

放 `server` 里，`spring-boot-starter-mail` 就进了 core 的依赖树，此后所有部署都带着它，哪怕只跑 QQ。模块化换来的是「不启用时 core 依赖里没有任何 mail 相关库」。

邮件有双用途：**兜底告警**与**长文投递**（IM 里长文会被截断或折叠）。后者让它不只是「失败时才用」的通道。

## 条目

- [ ] E1-1 模块骨架与三处登记
  - 作用：让邮件作为可选渠道独立打包、独立启停，不污染 core 依赖树
  - 位置：新增 `channel-email/pom.xml`；改根 `pom.xml` 的 `<modules>`、`server/pom.xml`
  - 做法：三处同步——① 根 `pom.xml` 加 `<module>channel-email</module>`；② `server/pom.xml` 加 `channel-email` 的 `runtime` 依赖，并在 enforcer `bannedDependencies` 里补 `com.dark:channel-email:*:*:compile` 排除（现有规则禁止 core 编译期依赖渠道模块）；③ 模块自身 pom 声明 `harness-api` + `spring-boot-starter-mail`，并设 `jacoco.line.minimum`（父 pom 默认 0.00 只出报告，初值可设 0.5，实测后按「实测下方约 2 点」调整）
  - 验收：`mvn -o -B verify` 通过；`mvn dependency:tree -pl server` 里 `channel-email` 是 runtime、`spring-boot-starter-mail` 不出现；`ArchitectureTest` 全绿

- [ ] E1-2 EmailProperties 与渠道配置
  - 作用：邮件配置归到 `channel.*` 命名空间，与 `channel.feishu.*` 对称；避免 `MailSenderAutoConfiguration` 的隐式装配
  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailProperties.java`、`server/src/main/resources/application-channel.yaml`
  - 做法：`@ConfigurationProperties(prefix = "channel.email")`，字段 `enabled / host / port / from / username / password / ssl / connectTimeoutMs`；`@ConditionalOnProperty(prefix="channel.email", name="enabled", havingValue="true")` 控制装配，默认关闭
  - 验收：`enabled=false` 时容器里没有 `EmailMessageSender`，`MessageSenderRegistry` 启动日志的渠道列表不含 `email`；`enabled=true` 且配置齐全时注册成功

- [ ] E1-3 EmailMessageSender（发送 + 错误分流）
  - 作用：把「发出去」和「为什么没发出去」都变成可判定的事实
  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailMessageSender.java`
  - 做法：自建 `JavaMailSenderImpl`（不依赖自动配置），`channel()` 返回 `"email"`；覆盖 `sendForReceipt`；正文纯文本 + 固定头部（来源 / 目标 / 时间 / 错误原因），不做 HTML。失败分流：认证失败（`AuthenticationFailedException`）→ 终态，重试只会一直失败且可能触发服务商风控；连接超时 → 可重试；收件人拒绝（`SendFailedException` 的 invalid addresses）→ 终态
  - 验收：三类失败分别产生「终态 / 可重试 / 终态」三种回执，`reason` 含原始异常摘要；成功时 `sent=true`

- [ ] E1-4 EmailChannelHealth（真实流量当探针）
  - 作用：让监控页能看到邮件渠道状态，同时不付出 SMTP 建连 + 认证的成本
  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailChannelHealth.java`
  - 做法：实现 `ChannelHealth`，`snapshot()` **只读最近一次投递结果与时间**，不发探活请求。超过 7 天无投递报「未知（无近期投递）」而不是假装健康
  - 验收：从未投递过 → `available=false` + `detail` 为「未知」；刚成功过 → `available=true`；`detail` 不含用户名密码

- [ ] E1-5 环境变量与首次部署检查
  - 作用：凭据不进仓库；「邮件进垃圾箱」这件事在首次部署时就被确认，而不是等告警丢了才发现
  - 位置：`application-channel.yaml` 的占位符、`docs/dev.md` 的部署说明、`docs/guides/docker-deploy.md` 的环境变量清单
  - 做法：`username` / `password` 从 `MAIL_USERNAME` / `MAIL_PASSWORD` 注入；文档写清 SMTP 服务商选择（QQ 邮箱授权码 / 阿里云邮件推送等）与「发件人白名单」；首次部署检查清单加一条：**发一封测试邮件到你手机上的邮箱客户端，确认不在垃圾箱**
  - 验收：`grep -r "MAIL_PASSWORD" docs/` 能查到说明；仓库里无明文密码；首次部署后测试邮件在收件箱

- [ ] E1-6 单元测试
  - 作用：避免邮件模块成为「写完就没人动、坏了不知道」的一角
  - 位置：`channel-email/src/test/java/com/dark/javaHarness/channel/email/`
  - 做法：`EmailMessageSenderTest`（成功 / 认证失败 / 超时 / 无效收件人 四类），`EmailChannelHealthTest`（无投递 / 近期成功 / 超 7 天）
  - 验收：两个测试类全绿；`channel-email` 行覆盖率不低于 `jacoco.line.minimum`

## 与投递层的接口约定

- 目标格式：`email:me@example.com`（`target` 就是收件地址，不做别名映射）
- 回执：`DeliveryReceipt.reason` 必须能区分「配置错」与「瞬时故障」，否则 D1-4 的 `markFailed(retryable)` 无法判断
- 邮件正文不超过 20 KB 不做限制（这里不是 webhook），但长文由 D1-8 决定走哪个通道
