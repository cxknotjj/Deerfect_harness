# channel-email

邮件渠道：**只出不进**。把定时任务结果、渠道故障告警这类「没人开口时」的消息投到邮箱，
也是超长正文（超过飞书 20 KB 上限）的默认落点。

契约实现两个：

| 契约 | 位置 | 作用 |
| --- | --- | --- |
| `MessageSender` | `contract.spi` | `channel()` 返回 `email`，`target` 直接就是收件地址 |
| `ChannelHealth` | `contract.spi` | 把「最近一次投递结果」当探针，7 天无投递报 `UNKNOWN` 而不是 `DOWN` |

`UNKNOWN` 很关键：邮件渠道没有主动探活（不会为了探活往你邮箱发测试信），所以「很久没发过」
和「发不出去」必须分开，否则每周都会收到一条假警报。

## 目录结构

```text
channel-email/
├── pom.xml
└── src/
    ├── main/java/com/dark/javaHarness/channel/email/
    │   ├── EmailProperties.java       # channel.email.* 配置绑定（enabled/host/port/from/ssl/timeout）
    │   ├── EmailChannelConfig.java    # 装配：@ConditionalOnProperty + JavaMailSender 构建
    │   ├── EmailMessageSender.java    # 发信 + 错误翻译（认证失败/发件人拒绝/超时）
    │   └── EmailChannelHealth.java    # 健康快照（基于最近一次投递时间与结果）
    └── test/java/com/dark/javaHarness/channel/email/
        ├── EmailMessageSenderTest.java
        └── EmailChannelHealthTest.java
```

这个模块**没有** `src/main/resources`：配置写在 server 的 `application-channel.yaml`，
凭证走环境变量（`MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_HOST` / `MAIL_FROM`）。

## 边界

- 不处理入站：收信需要 IMAP 与轮询，属于另一个量级的工作，当前没有需求。
- 不支持 STARTTLS（587）：只实现 465/SSL。要加需要设置 `mail.smtp.starttls.enable`，
  并补一个「明文连接必须被拒绝」的测试。
- 不是 runtime 必需：`deerfect-server/pom.xml` 里是 `runtime` 依赖，`enabled=false` 时依赖树里
  没有 mail starter，注册表里也不会出现 `email`。

## 启用（环境变量与首次检查）

配置在 server 的 `application-channel.yaml` 的 `channel.email.*`，占位符已接好，**不用改 yaml**；
关闭时 `deerfect-server` 的依赖树里没有 mail starter（渠道模块是 runtime 依赖）。

`.env.local`：

```bash
export CHANNEL_EMAIL_ENABLED=true
export MAIL_HOST=smtp.qq.com          # QQ 邮箱；其它服务商换成自己的
export MAIL_USERNAME=you@qq.com       # 完整发件地址
export MAIL_PASSWORD=xxxxxxxx         # 授权码，不是登录密码
export MAIL_FROM=you@qq.com           # 可省略，默认等于 MAIL_USERNAME
```

首次启用按这个顺序检查，别跳过第 3 步：

1. `MAIL_FROM` 不设时自动等于 `MAIL_USERNAME`；若单独设置，两者必须一致（多数服务商强制要求，不一致会被拒信）
2. 发件人加入服务商白名单；主题避免「中奖 / 免费」这类词
3. **发一封测试邮件到你手机上的邮箱客户端，确认它不在垃圾箱**——「邮件能发出去」和
   「你会看到它」是两件事，后者才是告警的意义
4. 打开监控页确认渠道状态：从未投递时会显示「未知（尚无投递记录）」，这是预期行为，不是故障

## 测试方式

单元测试不连真实 SMTP：`EmailMessageSenderTest` 用假的 `JavaMailSender` 验证错误码翻译，
`EmailChannelHealthTest` 用可变时钟验证「7 天无投递 → UNKNOWN」。

真实通路需要手工验证一次（E1-5）：配好授权码后往 `delivery_outbox` 插一条 `email:<地址>`，
确认 `status=SENT` 且邮件落在**收件箱**而非垃圾箱。
