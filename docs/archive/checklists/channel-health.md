# 渠道探活告警（M2）

- 范围：`server` 的 `message` 包与 `app.channel-watchdog.*` 配置；复用已有的 `ChannelHealth` 端口
- 创建：2026-11-08
- 状态：共 4 项，已完成 4 项
- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 7 节
- 关系：依赖 `delivery-outbox.md` 的 D1-1（`sendWithFallback` 的 `excludeChannel`）与 `email-channel.md`（告警的落点）

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| 探活与告警 | 3 | 0 | 3 |
| 边界与文档 | 1 | 0 | 1 |
| 合计 | 4 | 0 | 4 |

## 背景

探活其实已经有了：`harness-api` 的 `ChannelHealth` 端口 + `channel-qq` 的 `NapCatHealth`（调 `get_login_info`，结果缓存 30 秒）。缺的只是**把状态变化变成通知**——现在它只被 `ComponentStatusService` 读给监控页，渠道断了要你自己打开 Web 页面才发现。

一条硬约束：**不能用飞书去告警「飞书挂了」**。所以告警必须走「非本渠道」，实现上在入队前把告警链里该渠道的段删掉。

## 条目

- [x] W1-1 ChannelWatchdog 扫描与状态判定
  - 作用：把「渠道当前可不可用」从一次性查询变成连续状态
  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`
  - 做法：`@Scheduled(fixedDelayString = "${app.channel-watchdog.interval-ms:300000}")` 遍历所有 `ChannelHealth`（`ObjectProvider<ChannelHealth>`，渠道未装配时为空）；维护内存态「连续失败次数」，达到阈值（默认 3）才判定为故障，避免抖动时来回报警；`enabled=false` 的渠道不参与判定
  - 验收：连续 2 次 `available=false` 不告警、第 3 次告警；中间恢复一次则计数清零；渠道未装配时不报错
  - 结果（2026-11-08）：`ChannelWatchdog` 新增，`scan()` 可单测、`tick()` 只做异常兜底。计数与抑制复用 `AlertGate`（key 前缀 `channel:`），**没有**新增 `fail-threshold` 配置——它与 `app.alert.min-failures` 是同一语义，两个键会漂移。`ChannelWatchdogTest` 14 条覆盖阈值、恢复、抑制、关闭、空渠道、入队异常隔离。

- [x] W1-2 告警走非本渠道
  - 作用：渠道挂了仍然能通知到你——这是整个存活层唯一的意义
  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`
  - 做法：告警链配置 `app.channel-watchdog.alert-chain`（如 `email:me@example.com`）；发送前剔除故障渠道，正文含渠道名、失败次数、最后错误、判定时间；发送本身也进 outbox（`source=alert`），失败可重投
  - 验收：停 NapCat 后最迟 `app.alert.min-failures × interval-ms`（默认 15 分钟）+ 一个投递节拍（30 秒）内收到邮件告警；`alert-chain` 只含同一渠道时不会「用坏渠道报坏渠道」，而是记 ERROR 日志
  - 结果（2026-11-08）：实现为「入队前从链上剔除故障渠道」，没有走 `sendWithFallback(excludeChannel)`——outbox 的一行就是一条链，入队前定下来才能让**重投**也遵守排除；用 `excludeChannel` 只能管第一次发送。`withoutChannel()` 对 `qq:123|feishu:main|email:me@example.com` 剔 `feishu` 后保持原序。
  - 延迟口径：默认 5 分钟 × 3 次 = 15 分钟，比初稿写的「5 分钟」长。要更快就把 `interval-ms` 调到 60000（3 分钟判定）；NapCat 探活本身有 30 秒缓存，代价可忽略。

- [x] W1-3 恢复通知与抑制
  - 作用：知道「坏了」也要知道「好了」，同时不让反复抖动刷屏
  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`（复用 D1-7 的 `AlertGate`）
  - 做法：故障态持续期间不重复告警（`throttle-hours` 内一条）；`available=true` 且此前处于故障态时补一条「已恢复」，并清零计数
  - 验收：故障持续 1 小时只收到 1 条；恢复后收到 1 条「已恢复」；再次故障会重新计次数
  - 结果（2026-11-08）：`AlertGate.onSuccess` 只在「此前已告警」时返回 true，所以「抖一下又好了」不会发恢复；恢复文案含渠道名、最近状态、恢复时间。抑制窗口推移用 `MutableClock` 测，不 sleep。

- [x] W1-4 边界声明（不做进程外心跳）
  - 作用：避免后来者把「进程死亡无人知」当成漏做，反复提议加外部探活
  - 位置：`docs/dev.md` 的运行观测章节、本清单结果行
  - 做法：写明本层只覆盖「进程活着、渠道发不出去」；进程/主机死亡不在范围，理由见规划书 7.2 的三条（单实例下自己会察觉 / 外部观察者是无人监控的依赖 / `restart: unless-stopped` 已覆盖崩溃拉起）；注明将来接外部探活**不需要改本方案结构**
  - 验收：`docs/dev.md` 能查到该边界；`docs/task-checklist/project-hardening.md` 的 H2-3 仍标待办，不与本清单混淆
  - 结果（2026-11-08）：`docs/dev.md` 新增「渠道探活告警（能报什么 / 不能报什么）」，含四态判定口径、非本渠道规则、进程死亡边界与残余风险。H2-3 未动。

## 与初稿的两处偏差

| 初稿 | 实际 | 原因 |
| --- | --- | --- |
| `sendWithFallback(chain, text, excludeChannel)` 直接发 | 剔除后入队 outbox | 重投也要遵守「不走坏渠道」；直接发只能管第一次 |
| 独立配置 `fail-threshold` | 复用 `app.alert.min-failures` | 同一语义不设两个键；`AlertGate` 已经是这套状态机 |

另外补了一处初稿没考虑到的判定漏洞：`ChannelHealth.Snapshot` 由二元组扩成四态（`UP` / `DOWN` / `UNKNOWN` / `DISABLED`）。邮件渠道 7 天没投递报的是「未知」，若按 `available=false` 判故障，会周期性地发假警报。旧的三参构造保留，监控页与既有测试无需改动。

## 已知残余风险

容器一直起不来（镜像损坏、磁盘满、端口占用）时，所有通知一起停，且没有机制告诉你。发现方式退化为「你注意到今天没收到消息」。这一条按需接受，不列为验收项。
