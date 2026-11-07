# 工程护栏与结构优化清单（质量门禁 / 集成测试 / 部署默认项）

- 范围：根 `pom.xml` 与 `.github/workflows`、`web/` 前端工程、`server` 测试与运行配置、`docker/docker-compose.yml`；P2 另含 `SessionServiceImpl` 与两个超大类
- 创建：2026-11-07
- 状态：共 11 项，已完成 10 项，待办 1 项
- 关系：`project-optimization.md`（2026-11-03，15 项全完成）处理的是当时热路径的重复读写与结构清理，与本清单不重叠；本清单是 2026-11-07 新一轮体检的结论。能力补齐（主动性 / 记忆 / 触达）见 `personal-agent-gap.md`，本清单只处理工程护栏与结构债。

## 进度概览

| 分类            | 已完成 | 待办 | 小计 |
| ------------- | --- | -- | -- |
| 体检与分级         | 1   | 0  | 1  |
| P1 尽快（低成本高收益） | 5   | 0  | 5  |
| P2 结构性（择机）    | 4   | 1  | 5  |
| 合计            | 10  | 1  | 11 |

## 背景

2026-11-07 对全仓做了六维度体检（构建 / 依赖 / 架构 / 测试 / 部署 / 安全），结论：**没有 P0 级问题**。ArchUnit 的 5 条分层规则在跑，异常降级（沙箱故障、知识库缺失、渠道未注册）都有可读兜底，1143 条用例全绿，P2 阶段的增量记忆也做了备份与恢复路径。

短板不在功能，在**工程护栏**：

- 质量门禁是空的：根 `pom.xml` 只有 `maven-compiler-plugin`，没有 jacoco / spotbugs / checkstyle；`web` 只有 `vue-tsc` 类型检查，没有 lint
- 集成测试全是 Mockito：Mapper、SQL、Flyway 迁移没有一条被真实执行过。P2-1 那次「V10 被 `@SpringBootTest` 提前应用、校验和不匹配」就是这条路线缺护栏的真实信号
- 测试依赖真实时间：23 处 `Thread.sleep`，慢机或 CI 抖动即 flaky
- 部署缺两个默认项：`server.shutdown` 未配置（默认 immediate）、compose 的 app 服务没有日志上限

护栏缺失会放大后续结构改动的风险，所以 P1 全部排在 P2 之前。P1 每项都能独立提交，合计约 2 个工作日。

**建议执行顺序**：H1-5（15 分钟消除重启风险）→ H1-1 / H1-2（门禁，为 P2 的结构改动提供量化保护）→ H1-3（真实仓储）→ H1-4（去时间依赖，可随测试改动顺手做）。

## P1 尽快（低成本、收益明确）

- [x] 全仓体检与问题分级（2026-11-07）
  - 作用：把散落的优化点收敛成一份可执行清单，避免重复排查同一批问题
  - 位置：本文件
  - 做法：构建配置 / 依赖 / 架构约束 / 测试结构 / 部署配置 / 安全六个维度逐项核对，按「出事故的概率 × 修复成本」分级；P3 与不建议项单列在文末
  - 验收：结论可追溯到具体文件与行号；每项给出可独立提交的边界
- [x] H1-1 后端覆盖率门禁（jacoco）
  - 作用：1143 条用例是资产，但覆盖率不可见。P2 要拆 `SessionServiceImpl`（581 行 / 18 个 public），没有量化保护无法判断改动是否伤到未覆盖分支
  - 位置：根 `pom.xml` 的 `<build><plugins>`（当前仅 `maven-compiler-plugin` + lombok）、`.github/workflows/ci.yml` 的 `backend` job
  - 做法：加 `jacoco-maven-plugin`（`prepare-agent` + `report` + `check` 绑定到 `verify`）；`check` 的 bundle 下限先跑一次 `report` 取实测值，设在实测值下方约 2 个百分点，避免刚接入就红；CI 用 `mvn verify` 代替 `mvn test` 并上传覆盖率报告产物
  - 验收：`mvn verify` 产出 `target/site/jacoco/index.html`；把某个类的测试删掉后构建在 `check` 阶段失败，且日志打印实际值与阈值
  - 结果（2026-11-07）：jacoco 0.8.15（Java 25 需 0.8.14+，0.8.8 读不了 class 69），`prepare-agent` + `report` + `check` 全部绑 `verify`；阈值走 `jacoco.line.minimum` 属性，父 pom 默认 `0.00`（只出报告），各模块 properties 覆盖为实测下方约 2 个点。实测行覆盖：server 81.3% / channel-qq 77.2% / wms 75.0% / harness-api 63.6% / cli 23.2%。拦截验证：`mvn -pl cli -am verify -Djacoco.line.minimum=0.99` → `Rule violated for bundle harness-api: lines covered ratio is 0.63, but expected minimum is 0.99`，退出码 1。CI 的 `mvn verify` 原样复用，新增 `Upload coverage reports`（`if: always()`，失败时也能拿到报告区分「覆盖率下降」还是「测试失败」）。
- [x] H1-2 前端 ESLint 与 lint 脚本
  - 作用：`web` 目前只有 `vue-tsc --noEmit`，类型错能拦住，但未使用变量、错误依赖、易错写法只能靠人眼；`scripts` 里没有 `lint`
  - 位置：`web/package.json`（现有 `dev` / `build` / `preview` / `typecheck` / `test` / `test:e2e`）、`web/` 根配置、`.github/workflows/ci.yml` 的 `web` job
  - 做法：引入 `eslint` + `@typescript-eslint` + `eslint-plugin-vue`（flat config），加 `"lint": "eslint ."`；首次接入用 `--fix` 消化存量，规则集先取推荐集，不追求风格全覆盖；CI 的 `web` job 在 `typecheck` 前加一步 `npm run lint`
  - 验收：`npm run lint` 0 error；故意留一个未使用变量能被报出；CI 上 lint 失败会阻断构建
  - 结果（2026-11-07）：eslint 10.14 + typescript-eslint 8.72 + eslint-plugin-vue 10.12 + globals（flat config，`web/eslint.config.js`），`npm run lint` / `lint:fix`；CI `web` job 在 build 前加 lint。存量 30 个问题（1 error + 29 warning）清零：`TraceRow.vue` 的 `props` 对象未被引用（模板直接用解构后的 prop）去掉赋值；`MessageBubble.vue` 的 `v-html` 加 `eslint-disable-next-line` 并在上一行注明「已由 renderMarkdown 里的 DOMPurify.sanitize 净化」。关掉 8 条纯排版规则（`max-attributes-per-line` / `multiline-html-element-content-newline` / `html-closing-bracket-spacing` 等），只留会出错或腐化的规则。拦截验证：临时文件写未使用变量 → `'unused' is assigned a value but never used`，退出码 1。`npm run build`（含 vue-tsc）与 77 条单测仍绿。
- [x] H1-3 真实仓储集成测试（@SpringBootTest + 真实 MySQL）
  - 作用：现有业务测试全是 Mockito，`Memory` / `Session` 的 Mapper 与 SQL 没被真实执行过——改写 SQL、加索引、改迁移都不会被测出来；CI 的 MySQL service 目前只支撑 `JavaHarnessApplicationTests` 的 `contextLoads`
  - 位置：`server/src/test/java`（新增 IT 类）、`.github/workflows/ci.yml`（已有 MySQL service）、`MemoryMapper` / `MemoryServiceImpl`
  - 做法：加一条仓储冒烟（如 `MemoryRepositoryIT`），用 `@SpringBootTest` 连真实库跑完整链路：写入 → 同 key 覆盖（旧行 `deleted_at` 非空）→ 忘掉（软删）→ 按 `active` / `expired` / `deleted` 三种状态读取；本地无库时按 `JavaHarnessApplicationTests` 现有方式跳过（`@EnabledIf` 或 profile 判定），CI 上必须真实执行
  - 验收：CI 日志能看到该用例真实连库并通过；把 `MemoryServiceImpl` 的 `deleted_at IS NULL` 过滤条件改错后该用例失败
  - 结果（2026-11-07）：`MemoryRepositoryIntegrationTest` 8 条，`@SpringBootTest` 连真实 MySQL + Flyway 建表 + MyBatis-Plus 生成的 SQL；类级 `@Transactional` 回滚，key 拼随机后缀隔离本地数据。覆盖写入 / 同 key 覆盖（旧行 `deleted_at` 非空且只在 DELETED 可见）/ 忘掉（重复忘返回 false）/ 过期（EXPIRED 不注入）/ 未来过期（仍 ACTIVE）/ 同 key 计数 / 按 key 批量忘 / 关键词命中正文与标签。命名用 `*IntegrationTest` 而非 `*IT`：Surefire 默认只跑 `*Test`，用 IT 后缀必须再引 failsafe 插件。有效性验证：把 `MemoryServiceImpl` 的 ACTIVE 分支 `isNull("deleted_at")` 改成 `isNotNull`，8 条里 4 条失败（`已删记忆不再注入`、`未到期的记忆应继续注入` 等），退出码 1，改回后恢复。
- [x] H1-4 测试去时间依赖（Thread.sleep 归零）
  - 作用：全仓测试有 23 处 `Thread.sleep`，多数是「睡一段时间等状态变化」，慢机或 CI 负载高时就是 flaky 来源；这类失败重跑又能过，排查成本远高于修复成本
  - 位置：`LoginStateStore`（`now()` 直接返回 `System.currentTimeMillis()`）、`LoginStateStoreTest`（4 处，睡 150ms 等 token 过期）、`MultiAgentGraphAgentTest`（7 处）、`KnowledgeDirectoryWatcherTest`（5 处）、`SandboxToolProviderTest`（2 处）、其余零星 4 处
  - 做法：`LoginStateStore` 注入 `Clock`（默认 `Clock.systemUTC()`），测试用 `Clock.fixed` 或可变 Clock 直接推进时间，不睡；流式 / 异步断言改 Awaitility 轮询条件（或 Reactor `StepVerifier` 的虚拟时间），等条件成立而不是等固定时长；文件监听类测试改成显式触发一次扫描
  - 验收：上述四个测试类里 `Thread.sleep` 归零；连续跑 5 次 `mvn -o test` 无失败；单类耗时下降（`LoginStateStoreTest` 不再固定消耗 600ms）
  - 结果（2026-11-07）：测试代码 `Thread.sleep` 实际调用从 23 处降到 **0**（剩余 2 处是注释里的说明文字）。分三类处理：① 时间推进——`LoginStateStore` 注入 `Clock`（默认 `systemUTC`），新增测试工具 `support/MutableClock`，`LoginStateStoreTest` / `ApiTokenFilterTest` 改为推进时钟，不再睡等过期；`SkillRepository` 注入 `LongSupplier` 纳秒时钟，TTL 用例 `+50ms` / `+110ms` 两步推进。② 阻塞模拟——`SandboxToolProviderTest`（60s/5s）、`KnowledgeRetrieverTest`（5s）、`OneBotEventServiceImplTest`（2.5s）改 `CountDownLatch`，超时路径的 `cancel(true)` 中断后立即退出。③ 条件等待——`KnowledgeDirectoryWatcherTest` 的「窗口内不得再触发」改 Awaitility `during()`（窗口内持续轮询，条件一破坏立即失败，比 sleep 后单点抽查更严格）；`MultiAgentGraphAgentTest` 的等图启动改轮询调用数、等流结束改完成闩锁、两处暴露窗口改 `during()`、`delayedFluxOf` 的 `Thread.sleep` 改 `Mono.delay`（不阻塞线程且可随取消终止）；`LlmCallRecorderTest` 的「睡 300ms + `assertTrue(true)`」改成 `verify(mapper, timeout(1000)).insert(...)`——原来那个断言恒真，什么都没验证。效果：`LoginStateStoreTest` 从固定 600ms 降到 0.016s，`SandboxToolProviderTest` 从 65s 的模拟阻塞降到 1.7s。稳定性：`MultiAgentGraphAgentTest` + `KnowledgeDirectoryWatcherTest` 连跑 3 次全绿（50 条/次）。
- [x] H1-5 部署默认项（优雅停机 + 容器日志上限）
  - 作用：两个默认值直接对应线上风险——`server.shutdown` 未配置时默认 `immediate`，重启会硬断在途的 SSE 流；compose 的 `app` 服务没有日志上限，json-file 驱动会无限增长，小内存机（如 2G）长期运行会被日志写满
  - 位置：`server/src/main/resources/application.yaml`（全局配置，非 profile）、`docker/docker-compose.yml` 的 `app` 服务（第 62 行起）
  - 做法：`application.yaml` 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`；compose 的 `app` 补 `stop_grace_period: 30s`，并加 `logging.options.max-size: 20m` / `max-file: "3"`（与 mysql / pgvector 保持一致的写法）
  - 验收：`docker compose restart app` 时在途 SSE 请求收到正常结束而非连接重置；`docker inspect` 能看到 `max-size` / `max-file` 配置生效；`docker compose config` 校验通过
  - 结果（2026-11-07）：`application.yaml` 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`（全局文件，不依赖 profile）；compose 的 `app` 加 `stop_grace_period: 30s` + `logging`（json-file，`max-size: 20m` / `max-file: "3"`），`mysql` / `pgvector` 同步同一口径。验证：`docker compose config` 通过，三个服务各解析出一份 logging（`grep -c max-size` 逐段为 1）；`JavaHarnessApplicationTests` 新增 `gracefulShutdown_isConfigured` 断言配置被绑定到 Spring 环境（写在错误层级会失败），与 contextLoads 共享上下文不额外启动。真机验证留待容器环境：`docker compose restart app` 观察日志出现 `Commencing graceful shutdown`。

## P2 结构性（择机）

> 四项都建议在 H1-1 覆盖率门禁之后动手——没有覆盖率数据，重构无法证明「只改结构、不改行为」。

- [x] H2-1 `SessionServiceImpl` 抽出 `SessionSnapshotStore`
  - 作用：581 行 / 18 个 public 方法，同时承担建档、快照写回、裁剪归档、摘要触发、查询五类职责；快照逻辑（裁剪阈值、消息 ID 补齐、归档落库）与「会话端口」混在一起，改一处要读全文
  - 位置：`server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java`、`harness-api` 的 `SessionService` 契约、`SessionServiceImplTest`
  - 做法：抽 `SessionSnapshotStore`（快照读、批量写回、裁剪与归档落库），`SessionServiceImpl` 只保留端口方法编排与事务边界；先移动代码不改语义，再考虑合并重复的读取路径
  - 验收：`SessionServiceImpl` 行数与 public 方法数明显下降（目标 ≤ 400 行 / ≤ 12 个 public）；`SessionServiceImplTest` 与 `SessionServiceContractTest` 全绿且断言未放宽；`mvn verify` 覆盖率不低于改动前
  - 结果（2026-11-07）：新增 `SessionSnapshotStore`（345 行），承接「快照读 + 追加写 + 裁剪规则 + 归档落库 + 序列化重试 + 写回行锁」**整体**；`SessionServiceImpl` 581 → **318 行**（-45%），构造参数 8 → 5，私有方法 12 → 2。
  - 与原类头「约 520 行不拆」的关系：那条注释担心的是「读路径与裁剪规则分家会不一致」。抽取时把两者连同归档、行锁一起搬走——它们仍在同一个类里同步演进，风险不成立；分开的只是「会话元数据」（建档 / 自动改名 / 切 Agent / 分页 / 软删）与「ChatMemory 适配」，那部分不关心快照怎么存。类头注释已改写并保留这段推理。
  - 一处口径修正：原验收写「public 方法数 ≤ 12」不成立——18 个 public 由 `SessionService`（12）+ `ChatMemory`（4）+ 摘要端口（2）的契约决定，拆实现类不会减少对外方法。实际减少的是行数、字段数、私有方法数。
  - 验收：`SessionServiceImplTest` 28 条全绿且断言未改（三处构造改为共享同一批 mapper mock）；全量 1156 条 0 失败；server 行覆盖率 81.3%（与拆分前一致）。
- [x] H2-2 登录失败计数按窗口清理
  - 作用：`failuresByIp` 只在登录成功时 `remove`，失败过的 IP 会永久留一条记录；公网暴露时被扫描器刷一轮，map 只增不减
  - 位置：`server/src/main/java/com/dark/javaHarness/config/security/AuthController.java`（`failuresByIp` 声明在第 49 行，成功重置在第 83 行，失败累加在第 117 行）
  - 做法：给 `FailState` 记录最后一次失败时间；加 `@Scheduled` 每小时清理「最后一次失败早于锁定窗口」的条目（窗口与现有锁定策略同源，避免两处阈值漂移）；清理量记日志便于观察
  - 验收：构造 1000 个已过窗口的 IP 后执行清理，map size 明显下降；窗口内仍在计数的 IP 不受影响；有单测覆盖「过期清理 / 活跃保留」两条分支
  - 结果（2026-11-07）：`FailState` 增 `lastFailureAt`；新增 `@Scheduled(fixedDelayString = "${app.security.failure-clean-interval-ms:3600000}")` 的 `purgeStaleFailures()`，清理「超过 1 小时没有新失败」的 IP 条目（锁定中的条目只要还在保留窗口内就保留），清理量记 info 日志。
  - 可测性：清理逻辑拆成包可见的 `purgeStaleFailures(long cutoffMillis)`，测试直接传 cutoff 推进「时间」，不引入时钟注入、不等调度。
  - 测试：`AuthControllerFailureCleanupTest` 3 条——窗口外清理 / 锁定中保留 / 调度入口不误清；原 `AuthControllerTest` 8 条不受影响。
- [ ] H2-3 多实例假设显式化（定时任务防护）
  - 作用：6 个 `@Scheduled` 全是单实例假设（画像提炼 300s、日志清理 cron 03:00、停滞 Goal 检测 60s、Goal 重投 30s、调度派发 10s、沙箱探活 300s），多实例部署时同一任务会被并发执行；现在没有任何提示，扩容时才发现重复推送
  - 位置：`UserProfileService`、`ObservabilityLogCleaner`、`StaleGoalWatchdog`、`GoalRetryScheduler`、`ScheduleServiceImpl`、`SandboxToolProvider`
  - 做法：短期不改调度实现——启动时打印一条显式警告日志（「检测到 N 个 @Scheduled，本版本假设单实例」），并在 `docs/dev.md` 写明约束；长期再引入 ShedLock 或 DB 行锁（派发与推送这两条并发代价最高，优先加锁）
  - 验收：启动日志有该警告；`docs/dev.md` 能查到单实例约束与扩容前置条件；清单里记录「已评估、暂不加锁」的理由
  - 2026-11-07：本轮按需跳过（先做 H2-1/2/4/5），条目仍待办——启动告警与 `docs/dev.md` 的单实例约束说明都还没做
- [x] H2-4 隐式配置补进 yaml
  - 作用：三个键只有 `@Value` 默认值，yaml 里搜不到——运维改配置时无从发现，改错了也没有对照
  - 位置：`SessionSummaryService`（`app.context.summary-max-chars:600`、`app.context.summary-model:qwen3.8-27b`）、`McpToolProvider`（`spring.ai.mcp.client.stdio-config-file:mcp-config.json`）、`application-chat.yaml`（目前只有 `app.context.history-budget`）
  - 做法：把三个键补进对应 yaml 并加注释（默认值、影响范围、何时该改），值保持与 `@Value` 默认一致，不改变现有行为
  - 验收：三个键在 yaml 中可见；改 yaml 值能覆盖默认值（用一条测试或启动日志确认生效值）；`grep` 不到「只存在于 @Value」的配置项
  - 结果（2026-11-07）：`application-chat.yaml` 补 `app.context.summary-max-chars: 600` 与 `summary-model: qwen3.8-27b`（值与原 `@Value` 默认一致，行为不变，附用途与「何时该改」注释）；`application-ai.yaml` 补 `spring.ai.mcp.client.stdio-config-file: mcp-config.json`，注明「文件缺失回退 legacy 单 server、改后需重启」。顺带确认 `app.session.snapshot-max-messages: 200` 已在 yaml，无需补。
  - 验收：`JavaHarnessApplicationTests` 新增 `implicitConfigKeys_areDeclaredInYaml`，断言三个键在 Spring 环境里可取到——yaml 声明被删时键仍能靠默认值工作，但测试会失败，从而逼着保留声明（把「可发现性」变成可执行约束，而不是靠注释）。
- [x] H2-5 两个超大类补「不拆理由」结论
  - 作用：`OrchestrationNodes` 620 行、`OneBotEventServiceImpl` 578 行，体检结论是**职责单一、不建议拆**——但结论只存在于这次对话里，下次有人看到行数又会重新评估一遍
  - 位置：`server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java`、`channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java`
  - 做法：类头补注释说明职责边界与不拆理由（`OrchestrationNodes` 就是「编排节点的集合」，拆散增加跳转；`OneBotEventServiceImpl` 是单事件链路的顺序处理，拆开会让链路不可读）；结论回写本条目，与 `project-optimization.md` P3-2 的做法保持一致
  - 验收：两个类头有注释；本条目记录结论与日期，后续不再重复评估
  - 结果（2026-11-07）：两个类头补「体量说明」，不只写「不拆」，还写清**什么条件下再拆**：
    - `OrchestrationNodes`（620 行）：三节点共享同一份状态键、预算账本与取消令牌，拆开后跨节点改动要同时改三处并对齐约定，语义漂移风险高于阅读成本；行数里相当比重是拆解 JSON 的容错解析与 prompt 拼装，分支并不密。触发拆分条件：节点数增加，或各节点长出独立的重试/降级策略。
    - `OneBotEventServiceImpl`（578 行）：整条链路是「一条上报消息的顺序处理」，顺序本身即语义（限频先于调用、绑定先于建档、超时清理先于回复），拆成 pipeline 后顺序不再一眼可见，且每步要搬运同一组上下文。触发拆分条件：同一条链路要复用到其他渠道。
  - 结论：本轮不拆。与 `project-optimization.md` P3-2 的做法一致（补「不拆理由」而非硬拆）。

## 边界：不在本清单内

以下项体检时看过，明确**不纳入**，避免以后重复讨论：

- **P3 可选，收益低于成本**：Dependabot / Renovate 自动升级（Spring AI 迭代快，但当前升级频率可控）、慢 SQL 日志（排查时手工 `EXPLAIN` 够用）、`AgentRegistry` 三处构造分支的工厂收敛（9 个重复块，收敛后跳转更多）。
- **明确不做**：
  - 全量 checkstyle 格式整改——会污染 git 历史，收益低于成本；H1-1 的覆盖率门禁不依赖格式规则
  - 给 record / DTO / 配置类补单测——没有分支逻辑，补了只是刷数字
  - 拆 `OrchestrationNodes`——见 H2-5，职责单一，拆了反而增加跳转
