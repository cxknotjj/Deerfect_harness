# Super-agent 思想落地清单（链式召回 / 事件过滤 / Goal 心跳）

- 范围：`server`、`channel-qq`、`harness-api`、`server/src/main/resources/db/migration`
- 背景：[super-agent-ideas-adopted.md](../docs/design/super-agent-ideas-adopted.md)（已落地部分对照）、[HARNESS_TODO.md](../docs/HARNESS_TODO.md)（P2 长期记忆 / Goal 失败重投）
- 创建：2026-11-03
- 状态：共 14 项，已完成 14 项，待办 0 项

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
|---|---|---|---|
| A 链式召回（历史引用 + 按需取回） | 5 | 0 | 5 |
| B 渠道事件过滤与信道 prompt | 5 | 0 | 5 |
| C Watchdog 与 Goal 心跳 | 4 | 0 | 4 |
| 合计 | 14 | 0 | 14 |

## 背景

当前三个缺口对应文章的三条思想：

1. **链式召回**：`ContextAssemblingAdvisor` 按预算从最旧裁剪，`SessionServiceImpl.trimSnapshot` 在写回时还会物理丢弃旧消息；被裁掉的历史没有 ID、没有归档、没有按需读取入口。
2. **事件过滤分层 + 信道感知**：QQ 的自消息过滤、白名单、限频、触发判定硬编码在 `OneBotEventServiceImpl` / `EventTextParser`；`PromptAssembler` 不知道消息来自 QQ 还是 CLI，输出适配只发生在发送层（`max-length` 截断）。
3. **Watchdog**：`GoalServiceImpl.failAllRunning` 只在启动时清理 `RUNNING`；运行中卡住、无进展的 Goal 没有外部回路发现。

范围边界：不做引用图/多跳召回、不做归档向量化、不做自动重投（沿用 P2 另立任务）、不动全双工 ReAct 与 Router/Worker 架构。B2 只留语义层入口，默认关闭。

---

## A 链式召回

- [x] A1 会话快照增加消息级稳定 ID
  - 作用：为窗口外历史提供可引用、可召回的定位符；当前快照 item 只有 role/content/ts，裁掉即无法定位
  - 位置：[SessionServiceImpl.saveContext/loadContext/toMessage](../server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L105-L228)、[ContextAssemblingAdvisor](../server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L82-L89)
  - 做法：快照 JSON 的每个 item 增加 `id`（写入时生成，建议 `m-<uuid>`）；读取时把 id 放进 Spring AI `Message` 的 metadata；存量 item 在下次写回时补 id（不改表结构、不做双读兼容）
  - 验收：新写入消息带稳定 id；旧快照写回后 id 补齐；裁剪后的消息仍能从 metadata 读回 id

- [x] A2 归档表与裁剪转归档
  - 作用：快照写回本身会物理丢弃旧消息，只改上下文窗口不够；归档是召回的数据源
  - 位置：[SessionServiceImpl.trimSnapshot](../server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L237-L274)、新增 `SessionMessageArchiveMapper/Entity`、Flyway `V3__session_message_archive.sql`
  - 做法：新增归档表（`session_id` / `message_id` / `role` / `content` / `ts` / `tenant_id` / `archived_at`，按 `(session_id, message_id)` 建索引）；在 `trimSnapshot` 移除项前先写归档（整条被移除项与单条超限截断的尾部都要保留）；保持 `snapshot-max-messages` 与字符上限语义不变；归档写入与快照写回同一事务
  - 验收：触发条数/字符上限后旧消息进归档且快照行为不变；归档可按 session + messageId 查回原文（含被截断尾部）；写入失败不产生半写快照

- [x] A3 窗口内历史引用提示
  - 作用：模型需要知道「窗口外还有历史、用什么取」，否则不会调用召回工具
  - 位置：[ContextAssemblingAdvisor.assemble](../server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L89-L160)
  - 做法：裁剪时统计被丢弃消息的 id 范围与条数，在 system 消息中追加一行「历史引用：更早 N 条已归档，ID a..b，可用 recall_history 读取」；未发生裁剪时不输出；不改变原有 role 归一化与 system 置前
  - 验收：超预算时出现引用行且 ID 能被 A4 命中；预算内不出现；system/历史顺序与现有单测一致

- [x] A4 `recall_history` 工具与权限
  - 作用：把「引用」变成可执行的按需召回，替代「裁掉即丢」
  - 位置：新增 `tool/builtin/history/RecallHistoryTools.java`、[ToolAssignments](../server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java)、`TOOL_PURPOSES`、agent 表 `tools` 列
  - 做法：入参 `messageId` 或 `fromId/toId` + `limit`；`sessionId` 经 ToolContext 注入（不作为模型可见参数），服务端强制按会话过滤；返回内容走既有 `ToolCallBudget` 截断；只授予 `general` 与编排 `lead`，子任务专家与 `aggregator` 不注入
  - 验收：general/lead 能取回本会话归档原文；跨会话 ID 被拒绝；其他 agent 不可见；调用进 `tool_call_log` 并占用预算
  - 后续（2026-11-06，P2-2）：`recall_history` 增加 `scope=all`，显式跨会话检索已放行并在结果标注来源会话号；默认 `scope=current` 仍保持本会话隔离，上条验收口径对默认行为继续成立。

- [x] A5 链式召回测试与文档
  - 作用：锁定 ID、归档、引用、权限四段行为，避免后续裁剪逻辑改动造成静默丢失
  - 位置：`SessionServiceImplTest`、`ContextAssemblingAdvisorTest`、新增 `RecallHistoryToolsTest`、`ToolAssignmentsTest`；`docs/data-flow.md`、`docs/guides/context-optimization.md`
  - 做法：覆盖旧快照补 id、裁剪归档、引用行生成、跨会话拒绝、权限矩阵；文档补「链式召回最小版」一节与限制说明
  - 验收：新增用例全绿；`mvn -s .mvn/settings.xml -pl server -am test` 通过；文档与实现一致

## B 渠道事件过滤与信道 prompt

- [x] B1 程序层策略抽成 `ChannelEventPolicy`
  - 作用：去掉 `OneBotEventServiceImpl` 中散落的准入 if，让来源/群/用户/触发方式成为可配置策略，而不是业务代码
  - 位置：[OneBotEventServiceImpl.doHandle](../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java#L146-L191)、[EventTextParser](../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/EventTextParser.java)、[NapCatProperties](../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatProperties.java)、NapCat yaml
  - 做法：新增 `Decision { RESPOND, CANDIDATE, IGNORE }` 与首条匹配规则表（来源 group/private、群/用户白名单、触发方式 at/reply/prefix/all → 决策）；默认规则逐条等价当前行为；`doHandle` 只做「取文本 → 策略判定 → 分发」
  - 验收：默认配置下现有 `OneBotEventServiceImplTest` 全绿；改 yaml 可调整响应范围且不改代码

- [x] B2 语义层触发判断扩展点
  - 作用：为「主动观察群聊」留一个 LLM 入口，避免以后继续往 `doHandle` 加分支
  - 位置：`channel-qq` 新增 `SemanticTriggerJudge` 接口与 `LlmTriggerJudge` 实现；`OneBotEventServiceImpl` 处理 `CANDIDATE`
  - 做法：新增 `napcat.semantic-trigger.enabled`（默认 false）；关闭时 `CANDIDATE` 按 `IGNORE`（行为零变化）；开启时对候选事件调用一次轻量 LLM，返回 respond/silent；提示词与模型走 agent 表 internal 行（沿用 route-judge 模式）；超时/异常/非 JSON 兜底 silent
  - 验收：默认配置无行为变化；开启后候选事件可在无 @ 时被响应一次；judge 失败不回复、不向群聊泄露错误

- [x] B3 `ChatRequest` 增加 `channel` 并贯通到执行层
  - 作用：让 core 知道消息来自哪个渠道，prompt 才能按信道定制
  - 位置：[ChatRequest](../harness-api/src/main/java/com/dark/javaHarness/view/ChatRequest.java)、[ChatServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java)、`AgentService/AgentServiceImpl`、`Goal/GoalEntity`、QQ `chatWithTimeout`、CLI `ChatApiClient`
  - 做法：新增可空 `channel` 字段（`qq` / `cli` / `web` / `api`）；QQ 显式传 `qq`；经 Goal 携带到 Agent 执行与 `AgentChatCaller`；同步更新所有构造调用与测试，不保留旧重载
  - 验收：QQ 触发的 Goal 带 `qq`；CLI/Web 带各自值或 null；全模块编译与测试通过

- [x] B4 信道段落注入 `PromptAssembler`
  - 作用：在生成侧约束输出，替代发送侧「先写长再截断」
  - 位置：[PromptAssembler](../server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java)、[ContextCompositionCalculator](../server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionCalculator.java)、`AgentRequestSpecFactory`
  - 做法：新增「信道规范」段（order 放在输出约定附近）：IM = ≤100 词、只问直接问题、给简短结论；CLI/API = 结构化完整交付；null/未知渠道不注入；快照 part 登记 `system.channel`
  - 验收：QQ 请求 system 含 IM 段；CLI/Web/API 只含结构化交付段、不含 IM 长度限制；空渠道不注入；上下文快照能统计该段 token（E2E：QQ 会话 `system.channel` 70 token，CLI 会话为结构化段）

- [x] B5 渠道与信道测试、联调
  - 作用：确认默认行为不回归，且信道约束真的生效
  - 位置：`ChatServiceImplTest`、`PromptAssemblerTest`、`OneBotEventServiceImplTest`、`ContextCompositionCalculatorTest`
  - 做法：覆盖默认策略回归、channel 贯通、IM 段生成、无 channel 无段；手工用 QQ 发一条开放式长问题
  - 验收：用例全绿；QQ 回复明显更短且不再依赖 `max-length` 截断

## C Watchdog 与 Goal 心跳

- [x] C1 `goal` 表增加 `last_progress_at`
  - 作用：让「有没有进展」成为可查询事实，作为 Watchdog 判据
  - 位置：Flyway `V4__goal_last_progress.sql`（在 A2 的 V3 之后）、[GoalEntity](../server/src/main/java/com/dark/javaHarness/domain/entity/GoalEntity.java)、[Goal](../harness-api/src/main/java/com/dark/javaHarness/domain/Goal.java)、[GoalServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/GoalServiceImpl.java)
  - 做法：新增可空列 `last_progress_at`；Goal 增加字段与 `touchProgress()`；`markRunning()` 时初始化；GoalService 增加 `touchProgress(goalId)`，条件更新仅当 `status=RUNNING`
  - 验收：新建并运行的 Goal 有值；非 RUNNING 状态不更新；Flyway 在存量库上正常应用

- [x] C2 进度打点（节流）
  - 作用：心跳不能按 token 逐帧写库，需要固定在阶段/时间边界
  - 位置：[AgentServiceImpl.streamWithLifecycle](../server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java#L140-L172)、`OrchestrationCheckpointStore` 保存处、`AgentChatCaller` 每轮往返完成处（按需）
  - 做法：统一节流（如 30 秒一次）调用 `touchProgress`；Graph 在 `LEAD_DONE` / `SUBTASKS_DONE` 检查点保存时同步打点；同步路径在检查点与每轮 LLM 完成时打点；完成/失败后不再更新
  - 验收：长任务 RUNNING 期间 `last_progress_at` 持续刷新且写入频率受控；结束后不再变化

- [x] C3 `StaleGoalWatchdog` 定时扫描
  - 作用：在主系统之外发现「在跑但无进展」的僵尸 Goal，弥补只有启动清理的缺口
  - 位置：新增 `StaleGoalWatchdog`（`@Scheduled`，放 `observability` 或 `service` 包）、`GoalService` 条件失败方法
  - 做法：扫描 `status=RUNNING` 且 `last_progress_at < now - stagnant-minutes`；用条件更新把仍为 RUNNING 的目标置 FAILED 并写原因/`finished_at`（不覆盖已结束目标）；日志 + 计数；`app.watchdog.enabled` 默认 false，观察一轮后再开；阈值必须大于 LLM 读超时与流空闲超时
  - 验收：构造停滞 Goal 被标 FAILED；活跃 Goal 不受影响；已结束 Goal 不被改写；开关关闭时零行为；单实例假设与 `failAllRunning` 一致

- [x] C4 Watchdog 测试与文档
  - 作用：锁定阈值、条件更新与开关语义，并与 P2 重投划清边界
  - 位置：`GoalServiceImplTest`、新增 `StaleGoalWatchdogTest`、`docs/data-flow.md` 5h 节、`docs/HARNESS_TODO.md` P2「Goal 失败重投」
  - 做法：覆盖阈值边界、非 RUNNING 不更新、开关关闭；文档写明「Watchdog 只标失败、不自动重投，重投由 P2 另立」
  - 验收：用例全绿；`mvn -s .mvn/settings.xml -pl server -am test` 通过；文档口径一致

## 执行记录（2026-11-03）

- 核心落点：`SessionServiceImpl`（消息 ID + 裁剪转归档）、`SessionHistoryArchiveService` / `RecallHistoryTools`（按需召回）、`ContextAssemblingAdvisor`（历史引用行）、`ChannelEventPolicy` + `SemanticTriggerJudge`（程序层/语义层过滤）、`PromptAssembler` 信道段、`Goal.last_progress_at` + `StaleGoalWatchdog`（停滞看门狗）。
- 迁移：`V3__session_message_archive.sql`、`V4__goal_last_progress.sql`、`V5__recall_history_tool_assignment.sql`、`V6__goal_channel.sql`。
- 开关：`app.trigger-judge.enabled` / `napcat.semantic-trigger.enabled` / `app.watchdog.enabled` 均默认关闭；`napcat.event-policy.rules` 为空时保持既有行为。
- 验证：全量 `mvn -s .mvn/settings.xml test` 通过（server 785、channel-qq 79、cli 28、wms 43；失败 0）。
- E2E（真实服务 + MySQL）：V3~V6 迁移成功；QQ/CLI 聊天 `goal.channel` 分别落 `qq`/`cli`；上下文快照出现 `system.channel`（QQ 70 token，CLI 为结构化段）；QQ 群聊无 @ 被策略忽略、@ 后进入聊天并成功产出；`StaleGoalWatchdog` 将 10 分钟无进展目标标 FAILED、新鲜 RUNNING 目标保持不变；会话快照裁剪转归档后，模型真实调用 `expand_tool` + `recall_history` 取回 `MAGIC_OLD_FACT=42`，`tool_call_log` 记录 `recall_history OK`。

## 推荐执行顺序

1. **C1 → C2 → C3 → C4**：改动最小、与业务链路解耦，先解决真实的 RUNNING 僵尸态。
2. **A1 → A2 → A3 → A4 → A5**：按「ID → 归档 → 引用 → 工具」顺序推进，每步都可独立验收；A2 的 Flyway 版本先于 C1。
3. **B1 → B3 → B4 → B5**：先做行为不变的程序层抽离与 channel 贯通，再接信道段落；B2 语义层最后做，默认关闭。
