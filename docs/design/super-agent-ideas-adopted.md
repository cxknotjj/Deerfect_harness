# Super-agent 思想在本项目的落地对照

> 来源文章：《Super-agent 设计探索：迈向更自主的 Agent 系统》——一篇面向工程实践的架构思路探讨，不是对某个具体产品的功能说明。
>
> 本文只记录**已经在本项目落地**的部分，并标注应用程度（完整 / 部分），用来回答「这篇文章里哪些思想我们已经用上了」。未采纳的架构（双层 Router / Worker、常驻事件路由、全双工 ReAct Loop、端侧 daemon）不在此文范围内。
>
> 判定口径：以当前代码与配置为准。**完整** = 文章该机制的主要结构已存在；**部分** = 只实现了其中一个侧面，或缺少关键一环。

---

## 一、对照总表

| 文章概念 | 文章核心机制 | 本项目落点 | 应用程度 |
|---|---|---|---|
| Fast / Slow Path（路由） | 确定性规则先筛，模型只处理灰区 | `LlmRouteJudge`：规则短路 → 灰区 LLM → 置信度收敛 → 兜底 | 完整 |
| Fast / Slow Path（事件） | 程序层准入滤噪，语义层再判断 | QQ 渠道：`ChannelEventPolicy` 规则表 + `SemanticTriggerJudge` 语义扩展点 | 完整（语义层默认关闭） |
| 投机执行 | 结果未定前后台先做，有价值就交付 | `RagPrefetcher`：入口预取 + judge 并行 + 汇合窗口 | 完整 |
| Inline & Spill | 窗口内只留高频信息，其余留引用按需读取 | 会话/提示词/工具结果三级预算 + 工具 Schema 两段式 | 部分（有 Spill） |
| 链式召回（最小版） | 窗口外历史留引用，按需取回 | 消息稳定 ID + 归档表 + system 引用行 + `recall_history` 工具 | 部分（无引用图/多跳/向量化） |
| 计算、状态与环境分离 | 计算无状态、状态落库、执行环境独立 | Goal / 会话 / 检查点全部落库 + 断点续跑 | 部分（状态已外置，执行环境未解耦） |
| Watchdog | 主系统之外独立的外部控制回路 | 启动清理 + 流超时 + 调用重试 + `StaleGoalWatchdog` 停滞扫描 | 部分（无独立 Agent 介入） |
| 面向人的多信道交互 | 输出方式匹配信道带宽与习惯 | 渠道独立模块 + `ReplyPostProcessor` SPI + 按渠道输出参数 + 信道 prompt 段 | 完整 |
| 事件驱动 | 环境事件作为 Agent 的主要输入 | NapCat 事件入口 + 会话绑定 + 程序层规则 + 语义层候选 | 部分（无 Thread 订阅/主动默认关闭） |

---

## 二、逐项落地

### 2.1 Fast / Slow Path：确定性规则先行，模型只处理灰区

**文章观点**：环境事件「高噪音、低触发率」，全部交给大模型判断成本过高；把稳定高频的判断固化为确定性代码（Fast Path），只把需要语义理解的候选交给模型（Slow Path）。

**本项目已应用（完整）**——`LlmRouteJudge.plan()` 是同一结构的两段式判定：

- 空消息 → 直接 Agentic（规则）；
- 命中多工作流措辞（`GRAPH_SIGNAL`）→ 直接 Graph，置信度 0.9（规则短路）；
- 消息长度 ≤ 12 字符 → 直接 Agentic，置信度 0.95（规则短路）；
- 其余灰区才发起一次轻量 LLM 判定，输出 `route / confidence / workstreams / deliverables / reason`；
- 置信度策略收敛（Graph 需 ≥ 2 个工作流且置信度 ≥ 0.75），任何异常一律兜底 Agentic。

代码：[LlmRouteJudge.java](../../server/src/main/java/com/dark/javaHarness/service/impl/route/LlmRouteJudge.java#L139-L165)

这与文章 3.1 的「程序策略 + 语义策略」同构：规则层零成本、可预测；模型层只处理规则无法覆盖的灰区；失败时宁可走轻量路径，不阻塞请求。

### 2.2 投机执行：先算后验，无价值即丢弃

**文章观点**：在结果是否被采用尚不确定时，根据环境信号在后台提前尝试，用额外后台算力换人类的等待时间；有用就交付，无用就丢弃。

**本项目已应用（完整）**——RAG 入口预取就是标准的投机执行：

- `ChatServiceImpl` 在路径判定**之前**提交 RAG 预取，判定与检索并行执行，互不依赖；
- 判定完成后进入汇合窗口（2 秒）等待预取；窗口内未完成则取消任务、放弃结果；
- 预取池饱和时静默丢弃；任何失败都退化为「组装期现查」，主流程零感知。

代码：[RagPrefetcher.java](../../server/src/main/java/com/dark/javaHarness/service/impl/route/RagPrefetcher.java#L27-L86)、[ChatServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L440-L467)

编排路径同样应用了该思想：lead 拆解出子任务后、扇出之前，对每个子任务并行提交 RAG 预取，把检索延迟消化在子任务并发限流的排队空档里（见 [data-flow.md](../data-flow.md) 5i 节）。

### 2.3 Inline & Spill：把有限的上下文窗口当作 Cache 管理

**文章观点**：注入过多会挤占窗口、稀释注意力，注入过少会迫使模型反复调工具；应把近期高频信息 Inline 进窗口，低频/超预算内容 Spill 到窗口外，仅保留可读取的入口。

**本项目已应用（部分）**——「按预算注入 + 超限外置」已形成三层结构：

| 层次 | 落点 | 行为 |
|---|---|---|
| 会话历史 | [ContextAssemblingAdvisor.java](../../server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L82-L89) | 过滤噪声、归一化 role 顺序、按 `app.context.history-budget` 从最旧裁剪，system 始终保留 |
| Prompt 分段 | [PromptBudgetAdvisor.java](../../server/src/main/java/com/dark/javaHarness/advisor/PromptBudgetAdvisor.java) | 历史 / 当前 user / 工具结果三段分别设预算 |
| 工具结果 | [ToolCallBudget.java](../../server/src/main/java/com/dark/javaHarness/tool/budget/ToolCallBudget.java) | 单次调用的工具结果总量上限，超限不再真执行并返回引导文本 |
| 知识注入 | [KnowledgeRetriever.java](../../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java) | top-k 命中按 token 预算截断后再渲染【出处N】 |
| 工具 Schema | [ToolLazyManager.java](../../server/src/main/java/com/dark/javaHarness/prompt/ToolLazyManager.java#L18-L39) | 首轮只注入「名称 + 用途」，完整参数 Schema 留在窗口外，模型调 `expand_tool` 按需展开 |

其中 `ToolLazyManager` 最接近文章 3.3 的 Inline & Spill：窗口内放索引（Inline），完整 Schema 放窗口外（Spill），用一次元工具调用换取参数细节。网页抓取缓存（[WebTools.java](../../server/src/main/java/com/dark/javaHarness/tool/builtin/WebTools.java) 的 30 分钟内容缓存）也是同一思路：被裁掉的长内容可重新读取，而不是要求模型重新抓取。

上下文构成还可观测：[ContextCompositionCalculator.java](../../server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionCalculator.java) 把 system 各段、RAG、工具 Schema、历史消息的 token 占比算成快照，便于确认预算花在哪。

**边界**：已补齐「保留引用 + 按需展开」的最小版（见 2.8），但没有 Trigger / Thread / Session 的引用图与多跳召回，归档内容也未向量化。

### 2.4 计算、状态分离与断点续跑

**文章观点**：把推进 Agent Loop 的计算、需要持久保存的会话状态、重状态的执行环境三者解耦，使 Agent 摆脱单机生命周期限制，并能在异常后恢复。

**本项目已应用（部分）**——前两层已经分开：

- 持久状态全部在 MySQL：`session_messages`、`goal`、`orchestration_checkpoint`、四张观测表；
- 进程内没有权威状态：重启后从库中恢复会话、目标与检查点；
- Goal 有完整生命周期 `PENDING → RUNNING → SUCCEEDED / FAILED`，异常退出由启动清理兜底（[GoalServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/GoalServiceImpl.java#L55-L73)）；
- 复杂编排按 `LEAD_DONE / SUBTASKS_DONE / DONE` 三阶段落检查点，`/api/chat/resume` 从最近检查点续跑，已完成阶段不重跑（[OrchestrationCheckpointStore.java](../../server/src/main/java/com/dark/javaHarness/state/persistence/OrchestrationCheckpointStore.java)、[resume.md](../guides/resume.md)）；
- 计算通过受管线程池承载：后台 Goal 走 `goalExecutor`（8 线程 + 50 队列，满则显式落 FAILED），MVC 异步派发走独立槽位（[GoalExecutorConfig.java](../../server/src/main/java/com/dark/javaHarness/config/GoalExecutorConfig.java#L27-L43)、[AgentServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java#L56-L69)）。

**边界**：只完成了「计算与状态」的分离，没有完成「计算与执行环境」的分离——工具执行仍在同机 Docker 沙箱内，调度仍是进程内线程池，`failAllRunning` 与本地队列都基于单实例假设（见 [HARNESS_TODO.md](../HARNESS_TODO.md) 的「多实例水平扩展」）。

### 2.5 外部控制回路（Watchdog 的轻量形态）

**文章观点**：长期在线的系统很难只靠主系统内部逻辑保证可靠，需要在主系统之外引入独立的外部控制回路；而且 Agent 的异常不只有进程崩溃，还包括执行陷入无效循环或偏离目标。

**本项目已应用（部分）**——已有若干「主链路之外的兜底回路」：

- 启动恢复：非正常退出残留的 `RUNNING` Goal 在启动时批量标记 FAILED；
- 运行中超时：[AgentChatPipeline.java](../../server/src/main/java/com/dark/javaHarness/agent/AgentChatPipeline.java#L195) 对流做首帧/空闲双重超时，厂商端静默挂死时快速失败；
- 调用重试：[LlmRetry.java](../../server/src/main/java/com/dark/javaHarness/agent/LlmRetry.java) 对可重试错误做有限次数退避；
- 断连收尾：客户端取消时 Goal 落 FAILED，编排节点共享取消标志，不再发起新的 LLM 调用；
- 定时任务：观测表保留期清理（[ObservabilityLogCleaner.java](../../server/src/main/java/com/dark/javaHarness/observability/ObservabilityLogCleaner.java#L52)）、用户画像静默提炼（[UserProfileService.java](../../server/src/main/java/com/dark/javaHarness/memory/UserProfileService.java#L109)）。

**边界**：已新增 `StaleGoalWatchdog`（`app.watchdog.enabled`，默认关闭）扫描「RUNNING 且长时间无进展」的 Goal 并条件标记 FAILED，补上了任务级进展心跳；但仍没有文章所说的独立 Watchdog Agent Session，不会判断任务方向是否偏离目标。

### 2.6 面向人的多信道交互

**文章观点**：不同工作信道的信息密度与交互习惯不同，系统输出应匹配信道带宽；不应把面向单人终端的冗长推理与工具密集输出原样搬进群聊。

**本项目已应用（部分）**——渠道与 core 已经分层：

- `channel-qq` 是独立 Maven 模块，core 编译期不依赖渠道（`maven-enforcer-plugin` 守护），渠道只依赖 `harness-api`；
- 渠道侧输出控制走通用 SPI [ReplyPostProcessor.java](../../harness-api/src/main/java/com/dark/javaHarness/service/ReplyPostProcessor.java)：core 在写回记忆与构造响应前调用，渠道可清洗文本、声明 flag（如语音工件）；
- SSE 协议与进度行（[SseProtocol.java](../../harness-api/src/main/java/com/dark/javaHarness/enums/SseProtocol.java)、[ProgressLine.java](../../server/src/main/java/com/dark/javaHarness/agent/ProgressLine.java)）让 CLI / Web 拿到 token、阶段进度、meta，而 QQ 只收到最终文本；
- 渠道侧有独立的输出参数：长度上限、是否 @ 提问者、引用回复、表情发送、语音 flag、限频与聊天超时（[NapCatProperties.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatProperties.java)）。

**边界**：已通过 `ChatRequest.channel` → `Goal` → `CallTrace` 把来源渠道贯通到 `PromptAssembler`，按 qq / cli / web / api 生成「信道规范」段（IM 限 100 词、异步端允许结构化交付）；发送层的长度、表情、语音仍由各渠道自行控制。

### 2.7 事件驱动入口：主动性的起点

**文章观点**：驱动 Agent 的主要输入应从「人类逐条键入的提示词」转向「环境中的半结构化事件」，并通过过滤、路由、上下文组织把事件送到正确的执行单元。

**本项目已应用（部分）**——QQ 渠道已经是一个真实的环境事件入口：

- NapCat 上报的 OneBot 事件进入 `OneBotEventController → OneBotEventServiceImpl.handle()`；
- 程序层准入：自消息过滤、私聊白名单、按用户限频、指令路由（`/new /reset /history /agent /recall`）、群聊 @/回复触发判定；
- 会话绑定：`qq:private:<uid>` / `qq:group:<gid>:<uid>` 绑定到 harness 会话，命中复用多轮记忆，未命中建档落库；
- 失败语义：超时/异常/FAILED 一律静默不回复；聊天队列满时背压丢弃本条；超时中断底层调用，防止僵尸任务占满聊天池。

代码：[OneBotEventServiceImpl.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java#L146-L221)、[OneBotEventController.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventController.java)

**边界**：程序层已抽成 `ChannelEventPolicy` 规则表（respond / candidate / ignore），候选事件可由 `SemanticTriggerJudge` 语义层判断（默认关闭）；仍没有文章 3.2 的 Thread 订阅与直通——同一个群/话题下的连续事件不会构成一条 Thread，也不会被直接路由给已订阅的 Worker。

### 2.8 链式召回（最小版）

**文章观点**：窗口内只保留摘要/ID 等引用，低频或超预算内容留在窗口外，模型按需沿引用关系逐跳展开。

**本项目已应用（部分）**——在不引入引用图的前提下先做「可定位、可取回」：

- 快照 item 写入稳定消息 ID（`SessionServiceImpl`），读取时放进 Spring AI `Message` metadata；
- 快照裁剪移除的整条消息与单条超限截断的尾部先写入 `session_message_archive`（`V3`）；
- 上下文裁剪时把被丢弃消息的条数与 ID 示例追加到 system（`ContextAssemblingAdvisor`），提示模型可用 `recall_history`；
- `recall_history` 工具（`RecallHistoryTools`）按关键词或精确消息 ID 检索本会话归档，`sessionId` 经 ToolContext 注入、服务端强制会话过滤；授予 `general` 与编排 `lead`（`V5`）。

代码：[SessionServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L190-L343)、[SessionHistoryArchiveService.java](../../server/src/main/java/com/dark/javaHarness/service/impl/SessionHistoryArchiveService.java)、[RecallHistoryTools.java](../../server/src/main/java/com/dark/javaHarness/tool/builtin/history/RecallHistoryTools.java)

**边界**：没有 Trigger / Thread / Worker 的引用图与多跳展开，归档检索靠关键词/精确 ID，未做摘要与向量化。

---

## 三、未覆盖的部分（一句话清单）

以下属于文章的核心机制，但本项目目前**没有**落地，列出是为了避免把「部分应用」误读成「完整对齐」：

- 引用图与多跳召回：只有 ID + 归档 + 单跳检索，没有沿 Trigger/Thread/Worker 逐跳展开；
- 主动观察：语义层与候选规则已就位但默认关闭，且没有 Thread 订阅与直通；
- 独立 Watchdog Agent：`StaleGoalWatchdog` 只做停滞标记，不判断方向偏离、不自动介入修复；
- 全双工 ReAct：路径 A 的工具调用仍是同步循环，QQ 的超时 + 线程池是阻塞等待而非 `start()/stop()`；
- 执行环境解耦（BYOM / 端侧 daemon）：工具执行仍在同机容器内；
- Router / Worker 双层架构：编排是「按请求」的 Lead → 并行 → 聚合，没有常驻 Router 层，也没有跨会话的 Thread 订阅。

---

## 四、相关文档

- [architecture.md](../architecture.md)：两路径架构总览
- [data-flow.md](../data-flow.md)：路由、上下文组装、工具调用、观测的数据流
- [guides/context-optimization.md](../guides/context-optimization.md)：Token 预算与上下文优化口径
- [guides/resume.md](../guides/resume.md)：检查点与断点续跑
- [guides/qq-channel.md](../guides/qq-channel.md)：QQ 渠道接入与触发规则
- [HARNESS_TODO.md](../HARNESS_TODO.md)：未完成能力与优先级
