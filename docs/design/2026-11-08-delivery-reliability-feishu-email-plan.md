# 主动投递可靠性改造：飞书 + 邮箱（规划书）

> 状态：待实施（v5，2026-11-08 四次修订：① 邮件改为独立模块 `channel-email`，见 6.3；② 去掉多实例假设，见 6.2；③ 删除进程外心跳，见 7.2；④ M3 拆为 M3a 群机器人 webhook（0.5 天）/ M3b 事件订阅（可选），见 8.1）　|　日期：2026-11-08　|　影响模块：`harness-api` / `server` / 新增 `channel-email` / 新增 `channel-feishu` / `channel-qq`
>
> 前置阅读：[project-structure.md](../project-structure.md) 的 Flyway 迁移表（当前基线 V13，本方案从 V14 起）、[data-flow.md](../data-flow.md) 第九节主动推送链路。

## 1. 问题：不是「渠道不好用」，是「失败没人知道」

现状的三处硬伤，每一处都有代码位置可查：

| 症状 | 位置 | 后果 |
| --- | --- | --- |
| 发送失败**永久丢弃** | `ScheduleServiceImpl.deliver()`：无论 `send()` 返回 true/false 都调 `markNotified()` | 一次网络抖动 = 这条日报永远消失，日志里只有一行 WARN |
| 单通道，无降级 | `agent_schedule.notify_channel` 只能存一个 `channel:target` | QQ 挂了（协议端重启/风控/限流），定时任务结果全部静默丢失 |
| 探活有数据但**无告警** | `ChannelHealth` → `NapCatHealth` 只被 `ComponentStatusService` 读给监控页 | 渠道断了要等你自己打开 Web 页面才发现 |
| 进程死了无人知 | 无外部观察者 | 容器 OOM / 磁盘满 / 主机重启，所有通知一起停，而你以为只是「今天没任务」（**本方案不解决**，见 7.2 取舍记录与 12 节） |

第四点是真实存在的，但**本方案按需跳过**：进程内写的「失败告警」在进程死亡时确实不会执行，要做「沉默检测」必须引入进程外的观察者。本方案只覆盖「进程活着、但渠道发不出去」这一类，第四点作为已知残余风险记录在 12 节，**不假装解决**。

## 2. 目标与边界

### 2.1 目标（三句话，逐条可验收）

1. **每一条主动消息都有记录和终态**：一条推送要么 `SENT`（有渠道确认），要么 `DEAD`（重投耗尽，带最后一次错误原因），不存在「发没发不知道」。
2. **主通道失败能自动走备用通道**：`feishu:main|email:me@x.com` 按序降级，降级本身发生在注册表层，渠道实现互不感知。
3. **渠道不可达能被发现**：渠道连续探活失败时，不依赖你打开监控页，而是通过其它渠道主动告警。（进程死亡不在本方案范围，见 7.2）

### 2.2 明确不做

| 不做 | 理由 |
| --- | --- |
| 不改 `MessageSender.send()` 的签名 | 现有 `QqMessageSender` 与契约守卫都依赖它；回执能力用 `default` 方法扩展（见 5.1） |
| 不把任何渠道实现放进 `server` | 判据不是「有没有入站」，是「外部依赖会不会被 core 继承」+「实现会不会被替换」。邮件两条都成立（见 6.3） |
| 不做「消息已读」的自动追问 | 飞书能力支持，但它属于产品增强不是可靠性修复，M3 列为可选 |
| 不做消息去重的持久化 | 飞书事件重推窗口是分钟级，进程内存 LRU 够用；持久化会让每次接收多一次写库 |
| 不做完整的告警平台 | 目标是自己用。告警抑制用内存计数即可，重启重置可接受 |
| 不引入 ShedLock | 单实例部署，本方案不为多实例做任何设计。outbox 的条件更新只用于「发送前原子计数 + 崩溃恢复」，不是并发控制（见 6.2） |
| 不做 HTML 邮件 | 纯文本更不容易被判垃圾，且不必维护模板 |

## 3. 总体设计：三层各管一段故障

```text
通道层   飞书（主） + 邮件（备）          → 覆盖「单平台故障」
  ↑ 由 MessageSenderRegistry 按链路由，渠道实现互不知道对方存在
投递层   delivery_outbox + 重投 + 降级     → 覆盖「瞬时失败」
  ↑ 每条消息落库，失败可重试、可审计、可追查
存活层   渠道探活告警（进程内）            → 覆盖「渠道不可达」
  ↑ 进程/主机死亡不在本方案范围，见 7.2 取舍记录
```

**三层盖不住的**（写在这里避免自我欺骗）：你自己断网、邮件被判垃圾、邮件在垃圾箱里你没看、以及「你看到了但没处理」。这些不是工程问题。

### 3.1 一条消息的完整生命周期

```text
调度判定 goal 终态
  → 写 delivery_outbox（PENDING，chain_index=0，next_retry_at=now）
  → 标记 schedule.notified_at（语义从「已通知」改为「已入队」）
  → DeliveryRetryScheduler 扫到期行
      → 条件更新领取（attempts+1，next_retry_at=now+60s）
      → 按 channel_chain[chain_index] 发送
          ├ 成功 → SENT，parent_ref 留空
          ├ 可重试失败 → 保持 PENDING，退避后排下一轮
          └ 重试耗尽 → chain_index+1，换下一个通道
                          └ 链走完 → DEAD + 元告警（走独立路径，见 6.4）
```

**关键语义变更**：`schedule.notified_at` 从「已送达」降级为「已入队」。送达状态只由 outbox 表达。这样调度侧不必关心投递成功与否，两个关注点彻底分开。

## 4. 数据模型（V14）

```sql
CREATE TABLE `delivery_outbox` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`     VARCHAR(32)  NOT NULL DEFAULT 'default',
  `source`        VARCHAR(32)  NOT NULL COMMENT '来源：schedule / alert / manual',
  `ref_id`        VARCHAR(64)           DEFAULT NULL COMMENT '业务指针，如 schedule.id 或 goal.id',
  `channel_chain` VARCHAR(512) NOT NULL COMMENT '降级链原文，如 feishu:ou_x|email:a@b.com',
  `chain_index`   INT          NOT NULL DEFAULT 0 COMMENT '当前尝试到链上第几个通道（0 起）',
  `content`       TEXT         NOT NULL,
  `status`        VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT / DEAD',
  `attempts`      INT          NOT NULL DEFAULT 0 COMMENT '总尝试次数（跨通道累加）',
  `last_error`    VARCHAR(512)          DEFAULT NULL COMMENT '最后一次失败原因（含渠道原始错误码）',
  `next_retry_at` DATETIME              DEFAULT NULL COMMENT '下次可尝试时刻，兼作领取租约',
  `sent_at`       DATETIME              DEFAULT NULL,
  `created_at`    DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_due` (`status`, `next_retry_at`),
  KEY `idx_outbox_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

设计说明：

- **`content` 存全文而非摘要**：消息量极小（个人 agent 每天几条到几十条），存全文换来「重投时不需要回查 goal」这个简化，也支持事后核对「上周那条日报原文是什么」。
- **不用 `SENDING` 状态**：用 `next_retry_at` 推到未来 60 秒充当租约，条件更新领取。少一个状态，少一类「卡在 SENDING」的脏数据。
- **`channel_chain` 存原文而不是拆表**：链最多 2–3 跳，拆表会让「看一条消息的完整投递过程」变成 join。原文 + `chain_index` 足够定位。
- **保留期**：`SENT` 行保留 7 天（审计用，正好覆盖「上周的日报发了没」），`DEAD` 保留 30 天。接入已有的 `DataRetentionCleaner`，新增 `app.retention.outbox-sent-days` / `outbox-dead-days`。

## 5. 端口与接口变更

### 5.1 `MessageSender`：用 default 方法扩展回执，不动现有实现

```java
public interface MessageSender {

    String channel();

    /** 保持原签名：失败不抛，返回是否送达 */
    boolean send(String target, String text);

    /**
     * 带回执的发送：需要 message_id 的场景（已读追查、撤回）用它。
     *
     * <p>默认实现把 send() 折成「成功但无 ID」，现有渠道无需改动；
     * 飞书渠道覆盖它返回真实 message_id。
     */
    default DeliveryReceipt sendForReceipt(String target, String text) {
        return send(target, text)
                ? DeliveryReceipt.sent(null)
                : DeliveryReceipt.failed("unspecified");
    }

    /**
     * 投递回执：sent=false 时 reason 必须可读（含渠道错误码），
     * 它是 outbox.last_error 的唯一来源，也是「能不能自动补救」的判断依据。
     */
    record DeliveryReceipt(boolean sent, String messageId, String reason) {
        public static DeliveryReceipt sent(String messageId) {
            return new DeliveryReceipt(true, messageId, null);
        }
        public static DeliveryReceipt failed(String reason) {
            return new DeliveryReceipt(false, null, reason);
        }
    }
}
```

> **契约守卫**：`PortSignatureGuardTest` 的快照需要同步加上 `sendForReceipt` 与 `DeliveryReceipt`。这与 P2-1（`snapshotItems`）、P3-1（`deleteBySessionId`）是同一类变更，测试报错属预期演进。

### 5.2 `MessageSenderRegistry`：从「单地址」到「通道链」

地址语法向后兼容——**单值就是长度 1 的链，存量数据零迁移**：

```text
qq:123456                      # 单通道（现有数据，行为不变）
feishu:main|email:me@x.com     # 两跳降级；feishu 的 target 在 M3a 是别名，M3b 是 open_id
```

`feishu:` 之后的 target 有两种形态，由渠道实现自己解释（注册表不管）：M3a 是配置里的 webhook 别名（如 `main`），M3b 是 `ou_` 开头的 `open_id`。注册表只负责「按 `|` 拆、逐个找渠道、失败换下一个」。

改造点（不新增类，避免又多一层间接）：

| 方法 | 变更 |
| --- | --- |
| `send(channelRef, text)` | 保留，内部改为「只试第一个可用通道」，供 M1 之前的调用方平滑过渡 |
| `sendWithFallback(channelRef, text)` | 新增，按 `\|` 拆分逐跳尝试，返回 `List<Attempt>`（每跳的渠道、结果、错误） |
| `chainOf(channelRef)` | 新增，解析并校验链（空段、未知渠道前缀在入队时就拒绝，而不是发送时才发现） |

关键判断：**校验提前到入队时**。现在 `notify_channel` 写错了（比如 `q:123`），要等真的触发任务才在日志里看到「渠道未注册」。改后在 `ScheduleService.create()` 就拒绝，配置错误当场暴露。

### 5.3 飞书渠道的错误可编程性（本方案的核心价值）

QQ 渠道的失败是 `null`——分不清网络、超长、风控。飞书返回结构化错误码，可以分流。**两种接入方式的错误码不同，要分别映射。**

M3a（群机器人 webhook，出站）：

| 返回 | 含义 | outbox 处置 |
| --- | --- | --- |
| `code=0` | 成功 | `SENT` |
| `code=9499` | 请求体非法（超 20 KB / JSON 错） | `DEAD`（配置或代码 bug，重试无意义），错误信息带「检查正文长度」 |
| `code=11232` | 触发限流 | `PENDING`，退避 1s → 2s → 4s，本轮内重试 |
| 返回体提示 sign / timestamp | 签名或时钟偏移 | `DEAD` + 提示「检查 webhook-secret 与服务器时钟（timestamp 必须秒级）」 |
| 网络异常 / 5xx | 瞬时 | `PENDING`，退避 60s 重投 |

M3b（应用 API，双向，仅第二阶段适用）：

| 返回 | 含义 | outbox 处置 |
| --- | --- | --- |
| 频率限制（99991400 类） | 瞬时 | 退避 1s → 2s → 4s |
| token 失效（99991663 类） | 可自愈 | 刷新 `tenant_access_token` 后立即重试一次 |
| 用户不在可见范围（230002 类） | **配置错** | `DEAD`，提示「检查应用可用范围」 |
| 机器人不在群（230013 类） | **配置错** | `DEAD`，同上 |
| 内容触发审核 | 内容问题 | `DEAD` + 内容存档，不自动重投（重投会再次触发） |

这两张表是「换飞书」的真正收益：**失败原因可分类，因此补救策略可编程**。QQ 渠道只能统一走兜底。

## 6. M1：投递层 + 邮件通道（2 人日）

### 6.1 写入与状态流转

- `ScheduleServiceImpl.deliver()` 改为 `enqueue()`：写 outbox，成功后 `markNotified()`。语义注释同步改写（现在是「已通知」，改后是「已入队」）。
- 入队前校验链（5.2 的 `chainOf`），非法链直接拒绝写库并抛 `IllegalArgumentException`——配置错误要在创建任务时就报。
- 新增 `DeliveryOutboxService`：`enqueue` / `claimDue` / `markSent` / `markFailed` / `advanceChain`。

### 6.2 重投与领取（单实例前提）

**前提先写清：本项目是个人 agent，单实例部署，本方案不为多实例做任何设计**——不引 ShedLock、不加分布式锁、不写并发测试。`project-hardening.md` 的 **H2-3（多实例防护）仍然待办，且与本方案无关**。

那为什么还要一条带条件更新的 `UPDATE`？理由不是并发，是**「发送前计数」**：

```sql
-- 领取：先原子地「记账 + 推迟下次重试」，领取成功才发送
UPDATE delivery_outbox
   SET attempts = attempts + 1,
       next_retry_at = DATE_ADD(NOW(), INTERVAL 60 SECOND)
 WHERE id = #{id} AND status = 'PENDING' AND next_retry_at <= NOW()
```

`attempts` 必须在发送**之前**递增，否则「发送中进程被杀」会永远不计次、无限重试。而「发送前原子递增 + 推迟下次时间」这个动作，本身就写成了一条 `UPDATE`。多出来的 `WHERE` 条件不是为多实例准备的，是为了让这次领取**幂等于「这一行还没被领取」**——它只多一个条件，成本为零，顺带也让「同一行不会被两轮同时处理」变成了数据库层的保证，而不是依赖 `notifying` 那个内存标志。

它实际保护的两个故障，都发生在单实例上：

| 故障 | 条件更新的作用 |
| --- | --- |
| 发送中进程被杀（SMTP 卡住时 `docker restart`） | `attempts` 已递增、`next_retry_at` 在未来；重启后到期自动重试，不会永远卡在「发送中」 |
| 上一轮还没跑完就来了下一轮 tick | `notifying` 标志挡住 apply 层，`WHERE` 挡住行级——两处都不依赖对方 |

- 扫描节拍：`@Scheduled(fixedDelay = 30s)`，每轮 `LIMIT 50`。
- 重试上限：单通道 3 次（60s / 180s / 600s 退避），耗尽后 `chain_index+1`。
- 链耗尽：`status=DEAD`，触发元告警（6.4）。
- **将来若真要扩容**：这套写法天然可用（条件更新本身就是乐观锁），但届时还要处理 `@Scheduled` 的其它任务、事件分发、渠道连接数——那是 H2-3 的范围，不是这里。

### 6.3 邮件通道（独立模块 `channel-email`）

**修正一处初稿判断**：初稿写「邮件没有入站需求，放 `server` 的 `message` 包即可」——这是错的，混淆了**运行时角色**和**部署单元**。邮件在运行时就是一个渠道：实现 `MessageSender`、`channel()` 返回 `email`、被注册表按 `email:` 前缀路由、可被 `notify_channel` 指名。既然 `channel-qq` 是模块，它就该是模块。

真正的判据不是「有没有入站」，是这两条：

1. **外部依赖是否会被 core 继承**：`spring-boot-starter-mail` 放在 `server/pom.xml`，等于此后所有部署都带着 mail 依赖，哪怕只跑 QQ。
2. **实现是否会变**：邮件服务商可换（QQ 邮箱授权码 / 阿里云邮件推送 / SendGrid / SES），换的时候不该动 core 的依赖树。

「可选功能」这个理由本身不足——`@ConditionalOnProperty` 放在 core 里也能关。但**依赖隔离**关不掉，只有模块能做到。

```text
channel-email/src/main/java/com/dark/javaHarness/channel/email/
├── EmailProperties.java          channel.email.*（host/port/from/username/password/enabled）
├── EmailMessageSender.java       MessageSender，channel() = "email"
└── EmailChannelHealth.java       ChannelHealth（只读最近一次投递结果，不做 SMTP 探活）

channel-email/pom.xml             spring-boot-starter-mail + harness-api（不依赖 core）
```

**分界线（重要）**：编排在 core，实现在模块。

| 留在 `server` 的 `message` 包 | 去 `channel-email` |
| --- | --- |
| `MessageSenderRegistry`（改造：通道链） | `EmailMessageSender` |
| `DeliveryOutboxService` / `DeliveryRetryScheduler` | `EmailProperties` |
| `AlertGate`（告警抑制） | `EmailChannelHealth` |

理由：注册表、重投、抑制都是**跨渠道的编排**，它们只认 `MessageSender` 接口，与「发的是邮件还是飞书」无关。一旦某个渠道的实现细节渗进这些类，加第三个渠道时就要改两处。

**模块登记的三处同步点**（漏一处就编译不过或守卫不过）：

```text
pom.xml          <modules> 增加 channel-email
server/pom.xml   runtime 依赖 + enforcer 的 bannedDependencies 排除
                 （现有规则：禁止 core 以 compile scope 依赖渠道模块）
channel-email/pom.xml  jacoco.line.minimum（父 pom 默认 0.00 只出报告，不设门槛也可，但建议设）
```

**配置走 `channel.email.*` 而非 `spring.mail.*`**：

- 渠道配置统一在 `channel.*` 命名空间下，和 `channel.feishu.*` 对称。
- 用 `spring.mail.*` 会触发 `MailSenderAutoConfiguration` 的隐式装配——「哪个 Bean 从哪来」变得不好追。自己 new `JavaMailSenderImpl` 多 15 行，换来显式。
- 凭据：`channel.email.username` / `password` 从环境变量取（`MAIL_USERNAME` / `MAIL_PASSWORD`），yaml 只写占位符。**密码不得进仓库**。

**默认关闭**：`channel.email.enabled: false`。未配置 SMTP 时模块照常打包但不装配任何 Bean，`MessageSenderRegistry` 的日志里也就不会出现 `email` 渠道——**「没配就别喊」**比「配了但发不出去」好排查。

**为什么不做 SMTP 探活**：每次探活要建一次 TCP + 认证，成本远高于 HTTP，而且会占用服务商的连接额度。改为「用真实流量当探针」——`EmailChannelHealth.snapshot()` 只返回最近一次投递的结果与时间，超过 7 天无投递则报「未知（无近期投递）」，而不是假装健康。

- 正文：纯文本 + 固定头部（来源、目标、时间、错误原因），便于手机上看清。不做 HTML（更容易被判垃圾，且要维护模板）。
- 失败分流：认证失败 → `DEAD`（配置错，重试只会一直失败且可能触发服务商风控）；连接超时 → 重试；收件人拒绝 → `DEAD`。

### 6.4 告警抑制与元告警

**抑制**（`AlertGate`，内存实现）：同一 `source + ref_id` 24 小时内最多一条；`DEAD` 后恢复成功补一条「已恢复」；连续 3 次失败才发第一条。

**元告警的死结**：如果连邮件都失败，还能发给谁？答案是**发不出去**，只能：

1. 记 ERROR 日志（含完整链与错误码）
2. 打一个 `harness_delivery_dead_total` 指标（Micrometer 已有）
3. 让 `/actuator/health` 的 `delivery` 组变 DOWN

第 3 条是留给存活层的接口：**进程内能做的到此为止，剩下的交给外部观察者**。这个边界必须写在代码注释里，否则后面会有人试图在 `deliver()` 里堆重试。

## 7. M2：渠道探活告警（0.5 人日）

### 7.1 渠道探活告警（复用现有 `ChannelHealth`）

现有资产：`ChannelHealth` 端口 + `NapCatHealth`（`get_login_info`，缓存 30s）。缺的只是「把状态变化变成通知」。

新增 `channel-feishu` 侧 `FeishuHealth implements ChannelHealth`（调 `authen/v1/user_info` 或拉 token 判活），以及 `server` 侧：

```text
ChannelWatchdog  @Scheduled(fixedDelay = 5min)
  → 遍历所有 ChannelHealth
  → 连续 3 次 available=false → 走「非本渠道」的通道发告警
     （QQ 挂了走邮件，飞书挂了走邮件；邮件也挂了只能记日志——此时已无可用通道，属 7.2 的残余风险）
  → 恢复时发「已恢复」
```

**「走非本渠道」是关键**：不能用 `feishu:xxx` 去告警「飞书挂了」。实现上给 `sendWithFallback` 加一个 `excludeChannel` 参数。

> **落地时的两处修正（2026-11-08）**
>
> 1. 告警不是「直接发一次」，而是**剔除故障渠道后入队 outbox**（`source=alert`）。理由：outbox 的一行就是一条链，入队前定下来，重投才会遵守「不走坏渠道」；`excludeChannel` 只能管第一次发送。
> 2. `ChannelHealth.Snapshot` 由 `(enabled, available)` 二元组扩成四态 `UP / DOWN / UNKNOWN / DISABLED`。邮件渠道 7 天没投递报「未知」，若按 `available=false` 判故障，会周期性发假警报。旧的三参构造保留，监控页与既有测试不受影响。
>
> 阈值没有新增 `channel-watchdog.fail-threshold`，直接复用 `app.alert.min-failures`——同一语义设两个键必然漂移。

### 7.2 为什么不做进程外心跳（取舍记录）

「进程死亡无人知」是真实风险，但**本方案按需跳过**。理由：

- 单实例个人部署，进程死亡最常见的表现是「你自己会察觉」（机器重启、容器退出、系统卡顿），而不是「安静地跑着但没人知道」。
- 外部观察者本身是一个需要长期维护的依赖：它的配置、凭据、告警渠道都要跟着环境变，而它失效时**没有任何人监控它**——只是把「谁看着谁」的问题往上推一层。
- compose 里已有 `restart: unless-stopped`，覆盖了「进程崩溃自动拉起」这个最常见场景。

**残余风险**（写进 12 节，不作为验收项）：容器一直起不来（镜像损坏、磁盘满、端口占用）时，所有通知一起停，且没有机制告诉你。发现方式退化为「你注意到今天没收到消息」。

将来若确实需要，接一个外部探活即可——**它不需要改本方案的任何结构**，只是多一个使用者调用 `/actuator/health`。这也是现在不做的好理由：接入成本不随时间增加。

## 8. M3：飞书渠道（0.5 + 3 人日，分两阶段）

### 8.1 两条路线：先做单向，再决定是否双向

判据只有一句：**投递可靠性只需要「发得出去」，不需要「收得进来」。** 这两件事在飞书上对应两套完全不同的接入方式，成本差 6 倍。

| 能力 | 接入方式 | 需要域名 | 需要 SDK | 成本 |
| --- | --- | --- | --- | --- |
| **单向推送**（本方案需要） | 群自定义机器人 webhook | 不需要 | 不需要 | 0.5 人日 |
| 双向对话（可选） | 事件订阅 · Webhook 回调 | 需要 HTTPS | 不需要 | 3 人日 |
| 双向对话（备选） | 事件订阅 · 长连接 WS | 不需要 | 需要，Java 25 兼容性待验 | 4 人日 |

M3a 是 outbox 的自然延伸：它只需要「一个有错误码的出站通道」，webhook 就够。M3b 才有「对话入口」——能不能做、值不值得做，等 M3a 跑一两周再判断。

### 8.2 M3a：群机器人 webhook（0.5 人日）

不需要建应用、不需要申请权限、不需要任何审核——在飞书里建一个只有自己的群，加「自定义机器人」，拿到 webhook 地址即可：

```text
POST https://open.feishu.cn/open-apis/bot/v2/hook/<token>
{"msg_type":"text","content":{"text":"..."}}
→ {"code":0,"msg":"success"}
```

约束（每一条都影响 outbox 设计）：

- **请求体 ≤ 20 KB** → 超长正文必须转邮件，不能截断（见 8.4）
- **频率 100 次/分钟、5 次/秒** → 个人用量无感，但 `11232` 仍要按退避处理
- **没有 message_id** → 不能查已读、不能撤回（那是 M3b 的事）
- **单向**：不能接收消息，不能当对话入口
- **URL 本身就是凭证**：泄露即被滥用，必须开签名校验

签名：`sign = Base64(HMAC-SHA256(timestamp + "\n" + secret, 空字符串))`，`timestamp` 用**秒级**（毫秒会导致校验失败）。secret 走环境变量。

实现：`FeishuWebhookSender implements MessageSender`，就是一个带签名的 HTTP POST，约 80 行，零 SDK 依赖。

> **落地（2026-11-08）**：`channel-feishu` 只依赖 `harness-api` + `spring-boot-starter` + `jackson-databind`（无 web starter、无 lark SDK）。请求体与错误码用 Jackson 解析，不手写字符串拼接。测试用 JDK `HttpServer` 起本地桩，断言「实际发出的 JSON」与「响应怎么翻译」，不 mock `HttpClient`；签名向量由 Python `hmac` 独立算出后硬编码，避免测试与被测代码共用同一份实现而一起错。HTTP 维度比初稿多分了一层：429 / 5xx 可重试，其余 4xx 终态。

### 8.3 M3b：事件订阅（3 人日，可选）

两条路，按「有没有域名」二选一：

| | Webhook 回调（推荐） | 长连接 WS（备选） |
| --- | --- | --- |
| 前置 | 域名 + 有效 HTTPS 证书（Let's Encrypt 免费） | 服务器能出站访问公网即可 |
| 依赖 | 零 SDK，Spring `@PostMapping` | `lark-oapi` 的 WS 客户端 |
| 代码 | URL challenge 握手 + 验签解密 + 路由 | 连接生命周期 + 重连 + 心跳 |
| 攻击面 | **公网暴露端点**，必须验签 | 无入站端口 |

> 云服务器 + 独立 IP 不等于「有可用回调地址」：飞书要求能通过验证的 **HTTPS 地址**，纯 IP 通常过不了证书校验。没有域名又不想买，就只剩长连接这一条路（回到 SDK 兼容性问题）。

**3 秒约束两种模式都有**：回调要求 3 秒内返回 HTTP 200，长连接要求 3 秒内处理完不抛异常，超时都会重推。所以入站结构相同：

```text
收到事件 → 验签 → 解析 → event_id 去重 → 入队（有界队列）→ 立即 200
                                                        ↓
                                    后台线程池消费（复用 OneBotEventServiceImpl 的
                                    队列 + 背压 + 超时中断结构，不重新发明）
```

3 秒对聊天链路是致命的（一次对话几十秒），所以「收到即入队」是**硬要求**，不是优化。

### 8.4 推送目标与长文处理

- **M3a 的目标是「群」**：webhook URL 本身指向那个群，`notify_channel` 写 `feishu:main`（别名），别名到 URL 的映射在配置里，**不把 URL 存进业务表**——凭证不该躺在数据库里。
- **M3b 的目标是 `open_id`**（`ou_` 前缀）：需要一次配对——用户给机器人发一句话，日志打印其 `open_id`，复制进 `notify_channel`。不做自动发现（否则任何能给机器人发消息的人都能收你的通知）。
- `allowed_users` 白名单：不在白名单的消息直接丢弃。个人 agent 的默认姿态是拒绝，不是接受。
- **长文分流**：webhook 上限 20 KB。入队时按内容长度决定链的顺序——超过阈值（建议 6000 字符）就把 `email` 排到前面。**截断会丢信息，转邮件不丢**；这条也顺带解释了为什么邮件不只是兜底通道。

### 8.5 事件去重与幂等（M3b）

- 飞书事件头带唯一 `event_id`，重推时不变。
- `MessageDedup` 用 `LinkedHashMap` + 5 分钟 TTL（复用 `MessageDedupCache` 的写法），命中直接返回 200 不处理。
- 取舍：不持久化。进程重启后飞书可能重放分钟级内的事件，接受少量重复（会看到两条，但不会丢消息）。持久化会让每次接收多一次写库，不值。

### 8.6 模块与依赖（分阶段，M3a 不需要 SDK）

M3a 阶段：

```text
channel-feishu/pom.xml                          harness-api + spring-boot-starter-web（无 lark-oapi）
channel-feishu/.../FeishuProperties.java        enabled / webhook-url / webhook-secret / allowed-users
channel-feishu/.../FeishuWebhookSender.java     MessageSender：签名 + POST + 错误码映射
channel-feishu/.../FeishuHealth.java            最近一次投递结果（同 email，不做主动探活）
```

M3b 阶段追加：

```text
channel-feishu/.../event/FeishuEventController.java   回调入口（长连接路线则是 ws/FeishuWsClient.java）
channel-feishu/.../event/FeishuEventRouter.java        事件 → 验签 → 入队
channel-feishu/.../event/MessageDedup.java             event_id 去重
channel-feishu/.../reply/FeishuReplyService.java       复用 core 的 ChatService
```

模块登记的三处同步点见 6.3（根 pom modules / server pom runtime + enforcer 排除 / 自身 pom 的 jacoco 门槛）。`ArchitectureTest` 的分层守卫要跑一遍确认。

### 8.7 可选增强（M3a 稳定后再评估）

| 项 | 价值 | 前置条件 |
| --- | --- | --- |
| 已读追查 | 日报发出 2 小时未读则追一条 | M3b（webhook 模式没有 message_id） |
| 卡片按钮 | 「要不要执行」类确认可点选 | M3b + 卡片回传回调 |
| 长文转邮件 | 超阈值自动走邮件 | M3a 即可（见 8.4） |

**默认不做**，除非 M3a 上线后确认主链路稳定。

## 9. 配置项清单

```yaml
app:
  delivery:
    chain-default: "feishu:ou_xxx|email:me@example.com"  # 未显式配置 notify_channel 时的默认链
    retry: 3                          # 单通道重投次数上限
    backoff-seconds: [60, 180, 600]   # 重投退避
    lease-seconds: 60                 # 领取租约：发送中崩溃时，到期后自动重试
    batch: 50                         # 单轮扫描上限
    scan-interval-ms: 30000
  retention:
    outbox-sent-days: 7               # SENT 保留（审计窗口）
    outbox-dead-days: 30
  alert:
    min-failures: 3                   # 连续失败几次才告警
    throttle-hours: 24                # 同类告警抑制窗口
  channel-watchdog:
    interval-ms: 300000               # 探活扫描节拍
    fail-threshold: 3                 # 连续几次不可达才算故障

channel:
  email:
    enabled: false                    # 未配 SMTP 就不装配，注册表里也不会出现 email 渠道
    host: smtp.example.com
    port: 465
    from: me@example.com
    username: ${MAIL_USERNAME}        # 环境变量注入，密码不得进仓库
    password: ${MAIL_PASSWORD}
    ssl: true
    connect-timeout-ms: 10000
  feishu:
    enabled: false
    # —— M3a：群机器人 webhook（单向推送，无需建应用/审核） ——
    webhook-alias: main               # notify_channel 里写 feishu:main
    webhook-url: ${FEISHU_WEBHOOK_URL}
    webhook-secret: ${FEISHU_WEBHOOK_SECRET}   # 签名校验；URL 泄露时这是唯一防线
    # —— M3b（可选）：事件订阅 ——
    event-mode: callback              # callback（需域名 + HTTPS）/ websocket（需 lark-oapi）
    app-id: ${FEISHU_APP_ID}
    app-secret: ${FEISHU_APP_SECRET}
    verification-token: ${FEISHU_VERIFICATION_TOKEN}
    encrypt-key: ${FEISHU_ENCRYPT_KEY}
    allowed-users: []                 # open_id 白名单，空 = 全部拒绝
```

## 10. 测试策略

| 层 | 测试类 | 覆盖 |
| --- | --- | --- |
| 链路解析 | `MessageSenderRegistryTest`（改造） | 单值兼容 / 两跳 / 空段拒绝 / 未知前缀拒绝 / `excludeChannel` |
| outbox | `DeliveryOutboxServiceTest` | 入队 / 领取幂等（同一行第二次领取不命中）/ 重投退避 / 链推进 / DEAD |
| 重投调度 | `DeliveryRetrySchedulerTest` | 到期扫描 / 失败分流 / 不重复发 |
| 抑制 | `AlertGateTest` | 连续 N 次才发 / 24h 抑制 / 恢复补发 |
| 邮件（`channel-email`） | `EmailMessageSenderTest` | 成功 / 认证失败→DEAD / 超时→重投 / 未启用时不装配 |
| 探活 | `ChannelWatchdogTest` | 连续不可达触发 / 走非本渠道 / 恢复通知 |
| 飞书 M3a（`channel-feishu`） | `FeishuWebhookSenderTest` | 签名用秒级 timestamp / `0`·`9499`·`11232` 三分支 / 超长转邮件的链顺序 |
| 飞书 M3b（入站） | `FeishuEventRouterTest` | 3 秒内返回 / 重复 event_id 丢弃 / 队列满背压 / 验签失败拒绝 |
| 集成 | `DeliveryOutboxIntegrationTest`（真实 MySQL） | 领取条件更新的原子性（这条只能真库验） |

**必做的有效性验证**（沿用项目习惯）：

- 把 `deliver()` 里的 `markNotified` 改回「失败也标记」，`DeliveryOutboxServiceTest` 必须失败。
- 把领取 SQL 的 `status='PENDING'` 条件去掉，重复领取用例必须失败（证明幂等生效）。

## 11. 验收标准

| # | 阶段 | 验收项 | 判据 |
| --- | --- | --- | --- |
| 1 | M1 | 一条推送有终态 | 手动把 SMTP 配置改错 + 飞书 target 改成非法别名 → outbox 出现 `DEAD` 行，`last_error` 含渠道原始错误 |
| 2 | M1 | 降级生效 | 停 NapCat 但保留邮件 → 定时任务结果从邮箱收到，outbox 该行 `chain_index=1`、`status=SENT` |
| 3 | M1 | 调度语义不串轮 | 发送期间 dispatch 换 `last_goal_id`，旧行不被新轮标记（现有 `markNotified` 条件更新测试继续通过） |
| 4 | M1 | 发送中崩溃可恢复 | 把 SMTP 指向黑洞地址（连接挂起）后 `docker restart`，重启后该行 `attempts` 已递增并在租约到期后重试，不会卡在「发送中」 |
| 5 | M2 | 探活告警走非本渠道 | 停 NapCat，5 分钟内邮箱收到「QQ 渠道不可达」；恢复后收到「已恢复」 |
| 6 | M3a | 限流退避 | 用假 webhook 固定返回 `11232` → outbox 保持 `PENDING`，按 1s/2s/4s 退避，3 次后转下一个通道 |
| 7 | M3a | 长文转邮件 | 正文 8000 字符 → 链顺序变为 `email` 优先，webhook 不被调用；正文 500 字符 → 正常走 `feishu:main` |
| 8 | M3b | 3 秒不超时 | 长连接/回调下发一条需要 30 秒生成的消息 → 飞书无「超时重推」，回复正常送达 |
| 9 | M1 | 存量零迁移 | 现有 `qq:xxx` 单值配置不改也能正常推送 |

## 12. 风险与取舍

| 风险 | 判断 | 缓解 |
| --- | --- | --- |
| 飞书账号归属 | M3a 完全不需要建应用。M3b 才需要，而个人账号可建「企业自建应用」（组织=你自己），个人未认证账号发布免审 | 若当前账号属于某公司且无建应用权限，用另一手机号注册独立账号；发布时可用范围选「仅自己」 |
| webhook URL 泄露 | 真实：URL 本身就是凭证，泄露即被冒名发消息 | 必须开签名校验（HMAC-SHA256 + 秒级 timestamp），secret 走环境变量，URL 不进数据库（`notify_channel` 只存别名） |
| `lark-oapi` SDK 与 Java 25 | 仅**长连接**路线需要；Webhook 回调与 M3a 都不依赖它 | M3a 先做（零 SDK），把 SDK 风险推迟到 M3b 且只在「没有域名」时才需要面对 |
| 3 秒约束下队列积压（M3b） | 个人用量极低（自己一个人发消息），队列满的概率接近 0 | 队列满时回一句「忙，稍等」而不是静默丢弃 |
| 回调端点暴露公网（M3b） | 任何人知道 URL 就能伪造事件 | 校验 `verification_token` + `encrypt_key` 解密 + `allowed_users` 白名单，三关缺一不可；不为「先跑通」而临时关掉验签 |
| 邮件被判垃圾 | 真实存在 | 用邮箱服务商授权码 SMTP + 发件人白名单；**把「邮件是否进垃圾箱」写进首次部署检查清单**，不假设它一定到达 |
| outbox 表膨胀 | 消息量极小；SENT 7 天清理 | 接入 `DataRetentionCleaner`，与归档/goal 同一套机制 |
| 进程/主机死亡无人知 | 真实残余风险，本方案不解决（见 7.2） | `restart: unless-stopped` 覆盖崩溃自动拉起；剩余场景靠「你注意到没收到消息」发现。将来接外部探活不需要改本方案结构 |
| 新增两个模块的维护成本 | 真实：每个模块有 3 处登记点（根 pom / server pom + enforcer / 自身 pom），漏一处就是构建失败 | 接受。换来的是「加渠道不动 core 依赖树」——邮件和飞书都不启用时，`server` 的依赖里没有任何 mail / lark 相关的库 |
| 渠道默认关闭导致「以为配好了」 | 真实 | `MessageSenderRegistry` 启动日志打印已注册渠道；`channel.email.enabled=false` 时该渠道不出现，配错的 target 会在入队校验阶段被拒绝（5.2） |

## 13. 里程碑

| 阶段 | 内容 | 人日 | 交付物 |
| --- | --- | --- | --- |
| M1 | outbox + 重投 + 降级链 + `channel-email` 模块 | 2 | 失败可追踪；QQ 挂了邮件兜底 |
| M2 | 渠道探活告警 | 0.5 | 渠道断了会主动通知你，不用打开监控页 |
| M3a | 群机器人 webhook（单向推送 + 错误码映射 + 长文转邮件） | 0.5 | 飞书可当主通道，失败原因可分类 |
| —— | **M1 + M2 + M3a 小计** | **3** | 可靠投递闭环，够用就可停在这里 |
| M3b | 事件订阅（对话入口：回调 3 / 长连接 4） | 3~4 | 1:1 对话、已读追查、卡片交互 |
| 合计（含 M3b） | | 6~7 | |

**执行顺序的理由**：M1 之后，「消息丢了会知道」已经成立，与主通道是 QQ 还是飞书无关。**M3a 之后，「主通道可换、失败原因可分类」也成立了**——而它只要半天。M3b 的收益（对话入口、查已读、卡片）是产品升级，不是可靠性修复，所以用一到两周的真实数据再判断。

## 14. 与现有清单的关系

- **H2-3（多实例防护）仍待办，本方案不涉及**：单实例是既定前提，outbox 的领取写法只是不做额外假设，不构成对 H2-3 的部分完成。
- `personal-agent-gap.md` 的 **P3-2（微信/企微渠道）** 由本方案的飞书渠道替代：M3a 的群机器人 webhook **不需要建应用、不需要审核、不需要公网回调**，就实现了「国内直连 + 主动推送」这个原目标。建议在 P3-2 条目下注明「改由飞书 webhook 实现（M3a），理由：零审核 + 结构化错误码」。
- `NapCatHealth` 已存在，M2 不重写探活，只补「告警」这一段。
