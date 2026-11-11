# 投递层：outbox 与降级链（M1 前半）

- 范围：`server` 的 `message` 包与新增 V14 迁移；不改 `channel-*` 模块
- 创建：2026-11-08
- 状态：共 10 项，已完成 10 项，待办 0 项
- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 4 / 5 / 6 节
- 关系：本清单只做「投递层」。邮件渠道见 `email-channel.md`，探活告警见 `channel-health.md`，飞书渠道见 `feishu-channel.md`

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| 端口契约 | 1 | 0 | 1 |
| 链路解析 | 1 | 0 | 1 |
| 存储与状态 | 3 | 0 | 3 |
| 调度与重投 | 3 | 0 | 3 |
| 抑制与保留 | 2 | 0 | 2 |
| 合计 | 10 | 0 | 10 |

## 背景

现状是**失败即永久丢弃**：`ScheduleServiceImpl.deliver()` 无论 `send()` 返回 true 还是 false 都调 `markNotified()`，注释写的理由是「避免每 10 秒重试刷爆渠道」。顾虑成立，但结论错——正确做法是交给 outbox 控制重投节奏，而不是直接放弃。

做完本清单后，「上周那条日报到底发没发」有据可查；主通道失败会自动走备用通道。**全部条目不依赖飞书**，用邮件或测试替身就能验收。

## 条目

- [x] D1-0 端口回执扩展（`MessageSender.sendForReceipt`）
  - 作用：outbox 要按失败原因决定「重试还是终态」，而 `boolean` 只能表达成/败
  - 位置：`harness-api/src/main/java/com/dark/javaHarness/service/MessageSender.java`
  - 做法：新增 `default sendForReceipt` 与 `DeliveryReceipt(sent / messageId / reason / retryable)`，工厂 `sent` / `failed`（默认可重试）/ `fatal`（配置错）；现有实现零改动——default 折 `send`
  - 验收：`harness-api` 的 `MessageSenderTest` 覆盖 default 折 send、三种工厂语义、空原因兜底
  - 结果（2026-11-08）：新增 6 条用例。`retryable` 是这条链的关键：未知失败默认 `true`（宁可多试一次也别丢消息），配置错用 `fatal` 明确终止，避免白白重试三轮。该端口原本没有契约守卫快照，无需改守卫。

- [x] D1-1 通道链解析与入队校验
  - 作用：`feishu:main|email:me@x.com` 按序降级；单值即长度 1 的链，存量 `qq:123` 零迁移
  - 位置：`server/src/main/java/com/dark/javaHarness/message/MessageSenderRegistry.java`
  - 做法：新增 `chainOf(channelRef)` 负责解析与校验（空段、未知渠道前缀、缺 target 一律拒绝）与 `sendWithFallback(channelRef, text, excludeChannel)`；`send()` 保留原语义（只试第一个可用通道）。校验提前到 `ScheduleService.create()` 与 outbox 入队前，配置错误当场报，不等触发任务才在日志里发现
  - 验收：五类用例——单值兼容 / 两跳成功 / `"qq:"` 空 target 拒绝 / `"q:1"` 未知前缀拒绝 / `excludeChannel` 跳过本渠道；存量 `qq:123` 配置零改动可推送
  - 结果（2026-11-08）：`chainOf` 严格校验、抛 `IllegalArgumentException`（入队前用）；`sendWithFallback` 宽松解析、绝不抛（发送时用）——两种职责刻意分开，合并会让「配置错误当场报」与「投递失败不炸链路」互相打架。新增 `sendOnce` 供调度器只发当前一跳。测试 7 → 18 条。

- [x] D1-2 V14 建 outbox 表
  - 作用：每条主动消息有记录、有终态（`PENDING` / `SENT` / `DEAD`），不存在「发没发不知道」
  - 位置：`server/src/main/resources/db/migration/V14__delivery_outbox.sql`
  - 做法：按规划书 4 节 DDL 建表，含 `chain_index`（当前尝试到链上第几个）、`attempts`（跨通道累加）、`next_retry_at`（兼作领取租约）、`last_error`；索引 `idx_outbox_due(status, next_retry_at)` 与 `idx_outbox_created(created_at)`
  - 验收：空库从 V1 顺序跑到 V14 成功；存量库 V13→V14 增量执行；`SHOW CREATE TABLE delivery_outbox` 两个索引齐备
  - 结果（2026-11-08）：V14 建表。比原设计多一列 `hop_attempts`——原方案只有跨通道累加的 `attempts`，无法判断「本跳已试几次」，会导致第二跳一上来就被判超限。

- [x] D1-3 outbox 实体与 Mapper
  - 作用：给服务层提供持久化入口
  - 位置：`server/src/main/java/com/dark/javaHarness/domain/entity/DeliveryOutboxEntity.java`、`server/src/main/java/com/dark/javaHarness/mapper/DeliveryOutboxMapper.java`
  - 做法：MyBatis-Plus 实体 + `BaseMapper`；查询一律用**字符串列名** `QueryWrapper`，不用 lambda（纯 Mockito 单测下没有 `TableInfo` 缓存，会报 `can not find lambda cache`——P2-4 踩过）
  - 验收：`mvn -pl server -am compile` 通过；单测里不出现 lambda 条件构造
  - 结果（2026-11-08）：实体用 Lombok `@Data`，Mapper 继承 `BaseMapper`；条件构造一律用字符串列名（纯 Mockito 下没有 `TableInfo` 缓存，lambda 会报 `can not find lambda cache`）。

- [x] D1-4 DeliveryOutboxService（入队 / 领取 / 标记 / 链推进）
  - 作用：把「领取」与「发送」分开，让「发送中崩溃」可恢复、重试次数可审计
  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryOutboxService.java`
  - 做法：`enqueue(source, refId, chain, content)` 写 `PENDING`；`claimDue(limit)` 用条件更新抢占（`WHERE id=? AND status='PENDING' AND next_retry_at<=NOW()`，`SET attempts=attempts+1, next_retry_at=NOW()+lease`，改到 1 行才算领到）；`markSent` / `markFailed(reason, retryable)` / `advanceChain`。`attempts` 必须在发送**前**递增，否则发送中崩溃会永远不计次、无限重试
  - 验收：领取幂等（同一行第二次 `claim` 不命中）；可重试失败回到 `PENDING` 且 `next_retry_at` 按 60s/180s/600s 退避；三次耗尽后 `chain_index+1`；链走完置 `DEAD` 并保留最后一次错误
  - 结果（2026-11-08）：`claimDue` 先查再逐条条件更新（MySQL 没有 UPDATE...RETURNING，而调度器要知道领到了哪些行）；所有状态更新都带 `status=PENDING` 条件，避免覆盖已结案行。`DeliveryOutboxServiceTest` 14 条。

- [x] D1-5 调度侧改为入队（`notified_at` 语义变更）
  - 作用：调度只管「任务跑完了」，投递成败交给 outbox，两个关注点分开
  - 位置：`server/src/main/java/com/dark/javaHarness/service/impl/ScheduleServiceImpl.java`
  - 做法：`deliver()` 改为 `enqueue()`：写 outbox 成功后再 `markNotified()`；`notified_at` 语义从「已送达」改为「已入队」，同步改写方法注释（现有注释「失败也标记避免刷屏」需要删掉，理由已由 outbox 承接）；`markNotified` 的条件更新（`last_goal_id` 仍匹配才置位）**保持不变**
  - 验收：`ScheduleServiceImplTest` 相关断言改为「入队即标记」；新增「入队失败不标记」用例（下一轮会重新收集）；`markNotified` 原有的「不串轮」测试继续通过
  - 结果（2026-11-08）：`notifyFinished` 的返回值语义改为「入队数」；`create()` 调 `chainOf` 校验链。`ScheduleServiceImplTest` 19 条按新语义改造（含新增「入队失败不标记」），全绿。

- [x] D1-6 DeliveryRetryScheduler（扫描 / 重投 / 降级）
  - 作用：到期消息自动重投，链上还有下一个通道就换路
  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryRetryScheduler.java`
  - 做法：`@Scheduled(fixedDelayString = "${app.delivery.scan-interval-ms:30000}")`，每轮 `claimDue(batch)`（默认 50）；逐条按 `chain_index` 取通道，调 `sendForReceipt`；成功 `markSent`，可重试失败按退避回 `PENDING`，不可重试或耗尽则 `advanceChain`；异常吞在单条内，不影响同批其它行
  - 验收：到期行被处理、未到期行不动；单条抛异常不影响同批其余行；链上第二个通道被调用时 `chain_index=1`
  - 结果（2026-11-08）：只发当前一跳，不用 `sendWithFallback`——那会一次跑完整条链，使「每跳重试 3 次」失去意义。`DeliveryRetrySchedulerTest` 9 条。

- [x] D1-7 告警抑制 AlertGate
  - 作用：避免「每次失败发一条」把自己练成不看告警的人
  - 位置：`server/src/main/java/com/dark/javaHarness/message/AlertGate.java`
  - 做法：内存计数（重启重置可接受）——同一 `source + ref_id` 连续失败达 `min-failures`（默认 3）才放行首条；`throttle-hours`（默认 24）内同类只发一条；成功恢复后补一条「已恢复」
  - 验收：连续 2 次不发、第 3 次发；24h 内第二条被抑制；恢复后补发且计数清零
  - 结果（2026-11-08）：内存态 + `Clock` 注入，测试用 `MutableClock` 推进 24 小时窗口，不 sleep。`AlertGateTest` 7 条。

- [x] D1-8 长文分流（超阈值改走邮件）
  - 作用：webhook 请求体上限 20 KB，QQ 单条也有长度限制——截断会丢信息，转邮件不丢
  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryOutboxService.java`（入队时决定链顺序）
  - 做法：入队时若 `content.length() > app.delivery.long-text-threshold`（默认 6000）且链上含 `email`，把 `email` 提到链首；不分段、不截断
  - 验收：8000 字符 → 链首是 `email`，`feishu` 不被调用；500 字符 → 顺序不变；链上没有 `email` 时保持原序（此时才允许下游按各自上限截断）
  - 结果（2026-11-08）：超 6000 字符且链上有 `email` 时把 email 提到链首，不分段不截断；链上没有 email 则保持原序。测试 3 条，配置 `app.delivery.long-text-threshold` 已进 yaml。

- [x] D1-9 outbox 保留期接入清理器
  - 作用：表不会无界增长，同时保留「上周那条发了没」的审计窗口
  - 位置：`server/src/main/java/com/dark/javaHarness/observability/DataRetentionCleaner.java`
  - 做法：新增 `outbox-sent-days`（默认 7）与 `outbox-dead-days`（默认 30），按 `created_at` 分批删；**`PENDING` 永不删**；`0` = 禁用该表清理
  - 验收：`SENT` 超 7 天被删、`DEAD` 超 30 天被删、`PENDING` 无论多久都保留；配置为 0 时一条不删
  - 结果（2026-11-08）：两档保留期（SENT 7 天 / DEAD 30 天），`PENDING` 永不删；`DataRetentionCleanerTest` 5 → 8 条。

## 验收前置

D1-6 的端到端降级验收（第二个通道真的收到消息）需要 `email-channel.md` 的 E1-3 完成。在此之前可用测试替身（注册一个假的 `MessageSender`）验证链路切换。
