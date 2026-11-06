# 个人 Agent 能力补齐清单（主动性 / 记忆 / 触达）

- 范围：`server` 主链路 + `harness-api` 端口 + `channel-qq` 推送 + `web` 入口；不改 `cli` 的命令行协议（仅加命令）
- 创建：2026-11-06
- 状态：共 14 项，已完成 6 项，待办 8 项
- 关系：`docs/HARNESS_TODO.md` 是技术路线图（工程化 / 编排智能化 / 多实例），本清单是**产品视角**的能力补齐。重叠 4 条（画像长青、归档 RAG、滚动摘要、Goal 重投）在此提前并给出可执行口径；其余为新增。两者冲突时以本清单的验收口径为准，完成后回写 HARNESS_TODO 勾选。

## 进度概览

| 分类             | 已完成 | 待办 | 小计 |
| -------------- | --- | -- | -- |
| P0 主动化的安全前置   | 2   | 0  | 2  |
| P1 存在感（自己开始）  | 4   | 0  | 4  |
| P2 记忆连续性（人生级） | 0   | 4  | 4  |
| P3 随身触达        | 0   | 2  | 2  |
| P4 个人数据源       | 0   | 2  | 2  |
| 合计             | 6   | 8  | 14 |

## 背景

现状判断：**底下很厚，上面缺一层盖。**

- 行动力与编排力已成型：沙箱容器级隔离、MCP 生态、按专家最小权限、双路径编排、预算护栏、断点续跑、四张观测表——解决的都是「一次任务能跑多好」。
- 记忆力只到会话级：画像一次会话只提炼一次、归档检索被 `session_id` 硬过滤、无滚动摘要。
- 存在感几乎空白：全仓 `@Scheduled` 只有 4 处且全是内部维护（画像提炼 / 日志清理 / Goal 停滞检测 / 知识库目录监听）；`Goal` 必须 `POST /api/submit` 才动；QQ 有 `sendPrivateMsg` 但只在收到消息时回；没有 `user` 表，`DEFAULT_TENANT = "default"` 写死。

个人 agent 与「任务执行器」的分水岭不在单次执行质量，在**没人叫它的时候它在干什么**。所以顺序是：先补主动化会放大的安全洞（P0），再让它能自己开始（P1），再让它记得住（P2），最后才是入口与数据源（P3/P4）。

## P0 主动化的安全前置

> 不先做这两条，P1 的无人值守执行就是在放大事故面：现在是「你让它跑代码」，之后是「它凌晨自己跑代码」。

- [x] P0-1 沙箱生命周期自愈（探测 / 重建 / 孤儿回收）
  - 作用：沙箱容器懒创建一次后无重置机制，坏状态只能停服恢复；强杀进程会残留容器。主动任务跑在无人值守时段，坏状态等于任务永久卡死
  - 位置：[SandboxToolProvider](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java)（持有 `SandboxService`，仅 `@PreDestroy` 销毁）、`application-agent.yaml` 的 `app.sandbox.*`
  - 做法：容器句柄加健康探测（`exec echo` 带超时）；连续 N 次失败或镜像/配置变更时销毁重建，重建期间工具调用返回可读错误而非挂死；启动时扫描并回收本项目前缀的孤儿容器；重建事件落 `tool_call_log` 或独立日志
  - 验收：手动 `docker kill` 容器后下一次工具调用自动重建并成功；强杀进程重启后无残留容器；单测覆盖「探测失败 → 重建 → 调用成功」与「重建失败 → 可读错误」
  - 结果（2026-11-06）：每 5 分钟在 base 容器内 echo 探活（独立线程限时，超时判故障），失败销毁重建并整组重生成工具；重建失败清空工具面；启动时 cleanupAllSandboxes 回收遗留容器。browser 组不探测（本就按需降级）。SandboxToolProviderTest 5→17 条。提交 2a672ce。
- [x] P0-2 沙箱写 / 执行边界拦截
  - 作用：无人值守时模型输出没人复核，路径越界与危险命令必须在服务端拦
  - 位置：[SandboxFileTools](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxFileTools.java)、[SandboxShellTools](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxShellTools.java)（当前完全信任模型输出透传容器）
  - 做法：写入路径按 Unix 语义归一化后必须落在 `/workspace` 内（拒绝 `../` 越界；符号链接逃逸纯字符串判断不了，靠容器隔离兜底，已写进类注释）；shell 加危险命令黑名单 + 结果长度上限；**主防线仍是容器本身不挂载宿主目录**，黑名单只当减速带；拒绝时抛可读异常给模型
  - 验收：`../` 越界写与 `rm -rf /` 等破坏性命令被拒且错误可读；正常读写零回归；`SandboxGuardsTest` 覆盖路径归一化、四类越界、命令正反例与截断
  - 结果（2026-11-06）：新增 SandboxGuards：写入路径 Unix 归一化后限 /workspace（相对路径按工作区解析、~ 拒绝），覆盖写/编辑/建目录/移动四处；Shell 拦截删根、mkfs、dd 写块设备、关机重启、fork 炸弹、改根权限六类；结果统一截断 4 万字符并注明原长。类注释写明「减速带非主防线」与符号链接依赖容器隔离。新增 12 条测试。提交 575b193。

## P1 存在感：让它自己开始

- [x] P1-1 定时任务（schedule 表 + 调度器 + 双入口）
  - 作用：让它到点自己干活，这是「存在感」的第一块砖；当前所有主动行为都必须由人触发
  - 位置：Flyway `V7__agent_schedule.sql`、`service/ScheduleService`（+ impl）、`controller/ScheduleController`；复用 [`goalExecutor`](../server/src/main/java/com/dark/javaHarness/config/GoalExecutorConfig.java) 与 [`AgentService.submit`](../server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java)
  - 做法：表字段 `id / objective / agent_name / cron / enabled / notify_channel / last_run_at / next_run_at / last_goal_id / notified_at`；扫描到点转 goal 执行，**不另写执行链**；cron 用 Spring `CronExpression`（6 段，秒级）不自研；入口 `POST/GET/DELETE /api/schedules` + CLI `/schedule`；`next_run_at` 落库保证重启不丢不重跑
  - 验收：配一条每分钟任务，两分钟后 `goal` 表出现两条执行记录；重启后不重复触发；同一时刻只触发一次（单实例即可，多实例另案）
  - 结果（2026-11-06）：agent_schedule（V7）+ /api/schedules 增删查 + CLI /schedule；派发=扫描到期行→AgentService.submit→推进 next_run_at，失败也推进（防每 10 秒重扫卡住队首）；cron 用 Spring CronExpression 6 段，创建即校验并验 agent 存在；永不触发表达式派发后停用。11 条测试。提交 d80d0e8。
  - 端到端（2026-11-06 19:21，真机 MySQL + Flyway V7/V8）：创建每分钟任务 → 19:21:00.786 触发 → goal 落库并 SUCCEEDED（summary=收到）→ next_run_at 推进到 19:22:00，DELETE 与「任务不存在」400 均正常。
- [x] P1-2 主动推送通道（harness-api 端口 + QQ 落地）
  - 作用：任务跑完要能找到人，否则定时任务等于写日志
  - 位置：`harness-api` 新增 `MessageSender` 端口（与 [ReplyPostProcessor](../harness-api/src/main/java/com/dark/javaHarness/service/ReplyPostProcessor.java) 平级但语义不同）；[channel-qq](../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatApiClient.java) 实现
  - 做法：`MessageSender.send(target, text)` + `channel()` 渠道标识，由 `MessageSenderRegistry` 解析 `channel:target`；QQ 实现调 `sendPrivateMsg`（NapCat 客户端自身含 1 次重试）；目标来自 `agent_schedule.notify_channel`，不反查绑定表
  - 注意：**不要复用 `ReplyPostProcessor`** —— 它是「清洗模型回复文本」的 SPI，无副作用、幂等，与主动外发语义相反
  - 验收：定时任务跑完 10 秒内 QQ 收到消息；未注册渠道 / 地址非法不抛异常且 WARN 日志可查；core 的 enforcer 规则未被打破
  - 结果（2026-11-06）：harness-api 新增 MessageSender 端口 + MessageSenderRegistry（channel:target 路由，未注册/非法/实现违约一律 false）；channel-qq 实现私聊送达；推送判定改轮询（last_goal_id + notified_at 幂等，V8 加列）而非事件——Goal 完成点有三处，事件易漏发重发；派发与推送合成 tick 节拍；发送失败也标记避免刷渠道。16+7 条测试。提交 42bc16b。
  - 端到端（2026-11-06 19:28）：触发 → goal SUCCEEDED → 19:28:19 在 schedule-notify 线程推送 → NapCat 未启动，重试 2 次后 WARN 未送达且不抛异常 → 19:28:20 notified_at 置位。验证暴露并修复两处单测覆盖不到的问题（同步推送阻塞调度线程；高频 cron 下 last_goal_id 被派发覆盖导致推送饿死），提交 8eed771。
  - 已知限制：推送定位依赖 `last_goal_id` 单字段，cron 间隔必须 ≥ 30 秒（已做成创建期校验）。若要支持更密的触发，需要独立通知表（每次触发插一条待推送记录），属后续技术债。
- [x] P1-3 Goal 常驻（对话内声明 + 每轮注入）
  - 作用：现在的 goal 是「提交—查询」的一次性任务，不是「记得住你在追什么」的长期目标
  - 位置：`goal` 表已有 `session_id`（无需加字段）；[ContextAssemblingAdvisor](../server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java) / [PromptAssembler](../server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java) 加「活跃目标」段；[ChatServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) 入口解析 `/goal`
  - 做法：`/goal <objective>` 落 goal 并绑定当前 session；每轮组装把 RUNNING 目标的 objective + 最近进展注入（按预算截断，不挤掉 system 主体）；`/goal done <id>` 与 LLM 自评双通道收口；CLI 先行，Web 后续
  - 验收：声明目标后连续三轮对话，system 段都能看到目标；标记完成 / 取消后不再注入；超预算时按段裁剪，`system.channel` 等必需段不被挤掉
  - 结果（2026-11-06）：新增 `GoalStatus.ACTIVE`（不复用 RUNNING——watchdog 会判死、重启会清理）；`GoalService.createActive/activeBySession/completeActive`；新增「活跃目标」prompt 段（order 160，按 sessionId 注入，provider 扩展点与画像同构）；快照加 `system.activeGoal` 映射；REST 三个端点 + CLI `/goal`。**只做手动完成，不做 LLM 自评**——误判完成会让目标悄悄消失。端到端：登记后发消息快照出现 107 token 的活跃目标段，完成后再发消失，重复完成 400。提交 183ed87（22 文件）。

- [x] P1-4 Goal 失败重投
  - 作用：无人值守的定时任务失败后没人管，等于白跑
  - 位置：[AgentServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java) 提交的异常分支、[StaleGoalWatchdog](../server/src/main/java/com/dark/javaHarness/observability/StaleGoalWatchdog.java)
  - 做法：失败落 `retry_count` / `last_error`（Flyway 迁移补列）；指数退避重投上限 2 次；连续失败置 `FAILED` 并经 P1-2 主动推送失败原因与最后一次错误摘要
  - 验收：注入必失败的 Goal，验证重投 2 次后置 FAILED 且只推送一次；成功路径 `retry_count` 恒为 0；watchdog 与重投不互相打架（同一 Goal 不并发执行）
  - 结果（2026-11-06）：V9 给 goal 补 agent_name / retry_count / next_retry_at（此前 goal 不记录执行 Agent，既没法重投也看不出谁跑的）；失败按 1→2 分钟退避排期，`GoalRetryScheduler` 每 30 秒扫到期项调 `AgentService.retry`，重投跑**同一个 goal**（保住 traceId 与会话归属）；取消类异常不排期；受理失败清标记防每轮重扫。验证中发现 `updateById` 忽略 null 导致 next_retry_at 残留，改为调度器显式 set null。端到端：造 FAILED 到期 goal → 30 秒后受理「重投第 1/2 次」→ 执行成功，retry_count=1、标记清空。契约守卫 PortSignatureGuardTest 同步。提交 6ef7401。

## P2 记忆连续性：从会话级到人生级

- [ ] P2-1 画像长青会话（去掉一次性锁定）
  - 作用：`profile_extracted=1` 让一次会话只提炼一次；QQ 渠道按用户固定 sessionId，等于每个用户一辈子只提炼一次画像，后面聊的全不进
  - 位置：[UserProfileService](../server/src/main/java/com/dark/javaHarness/memory/UserProfileService.java)（`@Scheduled` 扫描 + `profile_extracted` 锁定 + 原子落盘）
  - 做法：改「按增量提炼」——记录 `last_extracted_message_id`，每次只喂新增消息；画像条目按 key 合并（同 key 覆盖、新 key 追加），不做全文重写；单次提炼限制消息条数与 token 上限
  - 验收：同一 session 连续三轮新增对话，第二、三轮的新偏好能被捕获；重复内容不产生重复条目；单次提炼 token 不超预算；画像文件损坏时可从备份恢复

- [ ] P2-2 归档检索跨会话
  - 作用：`recall_history` 现在只能回忆「这段对话里被裁掉的」，回忆不了「上个月那次」
  - 位置：[SessionHistoryArchiveService](../server/src/main/java/com/dark/javaHarness/service/impl/SessionHistoryArchiveService.java)（`.eq("session_id", ...)` 硬过滤）、`recall_history` 工具
  - 做法：工具加 `scope` 参数（`current` / `all`），**默认 `current` 保持现有行为**；`all` 走跨会话检索，先按时间倒序 + 关键词，数据量上来后接向量（与 P2-3 共用工件）
  - 验收：两个不同 session 各留一条特征消息，在第三个 session 用 `scope=all` 能召回两条；默认 `scope` 行为零变化；跨会话结果带 sessionId 与时间，不串会话上下文

- [ ] P2-3 长期记忆滚动摘要
  - 作用：长会话被预算裁掉的最旧轮次直接丢失，用户问「开头说的那个约束」就答不上
  - 位置：[MemoryPolicy](../server/src/main/java/com/dark/javaHarness/prompt/MemoryPolicy.java) / [ContextAssemblingAdvisor](../server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java) 的裁剪点
  - 做法：裁剪时把被丢弃的轮次压缩成摘要（轻量模型异步调用，失败不阻塞主链），落独立表或 `session_messages` 的 summary 行；组装时摘要作为 system 段注入；摘要自身有 token 上限并支持滚动合并
  - 验收：构造超预算的 30 轮会话，摘要能答出第 1–3 轮的关键事实；摘要 token 不超上限；摘要失败时退化为现状（丢最旧轮次），主链不受影响

- [ ] P2-4 记忆显式管理（记住 / 忘掉 / 查看）
  - 作用：用户要能说「记住这个」「忘掉那个」，否则记忆不可控也不可信
  - 位置：新增 `memory` 表 + `remember` / `forget` / `list_memories` 工具 + CLI 命令
  - 做法：显式记忆与画像分离（用户声明 vs 模型推断，来源不同、注入策略不同）；字段含 `content / source / created_at / expires_at / session_id`；注入先全量（量小），超过阈值改按相关度检索；删除为软删 + 可审计
  - 验收：说「记住我的车牌是 X」后新会话能答出；说「忘掉」后不再出现；`list` 能看到来源、时间与状态；显式记忆与画像同名条目不打架

## P3 随身触达

- [ ] P3-1 PWA（手机主屏入口）
  - 作用：Web 要开电脑，CLI 要开终端，QQ 要额外跑 NapCat；PWA 是成本最低的「随身」方案
  - 位置：`web/`（`vite-plugin-pwa`）+ `web/vite.config.ts`
  - 做法：`manifest` + service worker 离线壳；本轮只做「添加到主屏 + 全屏 + 离线页 + 未读角标」，Web Push（VAPID + HTTPS）等 P1-2 稳定后再评估
  - 验收：iOS Safari 与 Android Chrome 都能添加到主屏，全屏无地址栏；断网打开显示离线页不白屏；`npm run build` 产物含 manifest 与 sw

- [ ] P3-2 微信 / 企微渠道
  - 作用：QQ 的 NapCat 要单独跑容器，微信才是真实日常场景
  - 位置：新增 `channel-wecom` 模块（照 `channel-qq` 的模块边界与 enforcer 规则）
  - 做法：企业微信自建应用 HTTP 回调 + AES 加解密，实现 `MessageSender`（P1-2）；`server/pom.xml` 的 enforcer 白名单同步；**决策点**：个人无企业资质时改走公众号或微信客服，需先确认可用通道再立项
  - 验收：企微里发消息能流式回复；定时任务结果能推到企微；core 仍不编译期依赖渠道模块

## P4 个人数据源

> 记忆不该只来自对话。agent 不知道你的日程，就没法主动提醒；没读过你的邮件，就没法替你起草。

- [ ] P4-1 日历 / 待办连接器
  - 作用：主动提醒的前提是知道「今天有什么」；这是 P1 定时任务最有价值的输入源
  - 位置：新增 MCP server（CalDAV 优先，兼容 iCloud / Google / Outlook）；或直接做成 `tool/builtin` 的一个工具组
  - 做法：只读起步——今日 / 本周日程查询工具；配一条 P1-1 的「每日早报」任务，把当天前三个日程推送到 P1-2；写操作（新建/改期）留到只读跑顺之后
  - 验收：能读到真实日历事件（含时区与全天事件）；早报任务准确说出当天前三个日程；无日程时给出明确空态而非编造

- [ ] P4-2 笔记 / 邮件连接器
  - 作用：个人知识大多在笔记和邮件里，不在对话里
  - 位置：MCP server（Obsidian 本地库 / IMAP 只读）
  - 做法：笔记走本地目录索引（复用 [KnowledgeDocumentScanner](../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeDocumentScanner.java) 的扫描与增量思路）；邮件先做「按发件人/主题搜索」，不做全量同步
  - 验收：能按关键词检索到笔记原文并给出出处；邮件搜索能按发件人 + 时间范围返回摘要；索引增量更新，不全量重建

## 明确不做（决策记录，避免反复讨论）

- **CI/CD、Prometheus、多实例水平扩展、消息队列、Redis 会话缓存、JWT/KMS**：这些是「把自用工具做成 SaaS」的工程，对个人 agent 是负价值——增加部署复杂度，换不来体验。个人自用单机足够。
- **PDF 导出、Swagger/OpenAPI、WebSocket**：低频需求，等有人真的开口再排。
- **多模态输入**：等有真实传图场景再做，否则是给模型加一个用不上的入口。
- **多租户 / user 表**：单用户是产品选择，不是缺陷。`DEFAULT_TENANT = "default"` 保持不变；**除非**要给家人共用，那时再补 `user` 表与租户隔离（会牵连会话、goal、画像三处，属独立立项）。

## 执行顺序

1. P0-1 → P0-2：主动化的前提，先堵住会被放大的洞
2. P1-1 → P1-2 → P1-3 → P1-4：先能自己开始，再能找到人，再记得住目标，最后补失败兜底
3. P2-1 → P2-2 → P2-3 → P2-4：记忆从「会话内」逐步扩到「跨会话」再到「用户可控」
4. P3-1 → P3-2：入口按成本从低到高
5. P4-1 → P4-2：数据源按「能否直接支撑 P1 主动提醒」排序，日历优先

**建议先做的一条**：P1-1 + P1-2 组合（定时任务 + 主动推送）。地基全在（`goal` 表、`goalExecutor`、`sendPrivateMsg` 都已存在），改动最小，能直接把「存在感」从 0 拉到 1。
