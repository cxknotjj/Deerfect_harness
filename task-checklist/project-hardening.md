# 工程护栏与结构优化清单（质量门禁 / 集成测试 / 部署默认项）

- 范围：根 `pom.xml` 与 `.github/workflows`、`web/` 前端工程、`server` 测试与运行配置、`docker/docker-compose.yml`；P2 另含 `SessionServiceImpl` 与两个超大类
- 创建：2026-11-07
- 状态：共 11 项，已完成 1 项，待办 10 项
- 关系：`project-optimization.md`（2026-11-03，15 项全完成）处理的是当时热路径的重复读写与结构清理，与本清单不重叠；本清单是 2026-11-07 新一轮体检的结论。能力补齐（主动性 / 记忆 / 触达）见 `personal-agent-gap.md`，本清单只处理工程护栏与结构债。

## 进度概览

| 分类                | 已完成 | 待办 | 小计 |
| ----------------- | --- | -- | -- |
| 体检与分级             | 1   | 0  | 1  |
| P1 尽快（低成本高收益）     | 0   | 5  | 5  |
| P2 结构性（择机）        | 0   | 5  | 5  |
| 合计                | 1   | 10 | 11 |

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

- [ ] H1-1 后端覆盖率门禁（jacoco）
  - 作用：1143 条用例是资产，但覆盖率不可见。P2 要拆 `SessionServiceImpl`（581 行 / 18 个 public），没有量化保护无法判断改动是否伤到未覆盖分支
  - 位置：根 `pom.xml` 的 `<build><plugins>`（当前仅 `maven-compiler-plugin` + lombok）、`.github/workflows/ci.yml` 的 `backend` job
  - 做法：加 `jacoco-maven-plugin`（`prepare-agent` + `report` + `check` 绑定到 `verify`）；`check` 的 bundle 下限先跑一次 `report` 取实测值，设在实测值下方约 2 个百分点，避免刚接入就红；CI 用 `mvn verify` 代替 `mvn test` 并上传覆盖率报告产物
  - 验收：`mvn verify` 产出 `target/site/jacoco/index.html`；把某个类的测试删掉后构建在 `check` 阶段失败，且日志打印实际值与阈值

- [ ] H1-2 前端 ESLint 与 lint 脚本
  - 作用：`web` 目前只有 `vue-tsc --noEmit`，类型错能拦住，但未使用变量、错误依赖、易错写法只能靠人眼；`scripts` 里没有 `lint`
  - 位置：`web/package.json`（现有 `dev` / `build` / `preview` / `typecheck` / `test` / `test:e2e`）、`web/` 根配置、`.github/workflows/ci.yml` 的 `web` job
  - 做法：引入 `eslint` + `@typescript-eslint` + `eslint-plugin-vue`（flat config），加 `"lint": "eslint ."`；首次接入用 `--fix` 消化存量，规则集先取推荐集，不追求风格全覆盖；CI 的 `web` job 在 `typecheck` 前加一步 `npm run lint`
  - 验收：`npm run lint` 0 error；故意留一个未使用变量能被报出；CI 上 lint 失败会阻断构建

- [ ] H1-3 真实仓储集成测试（@SpringBootTest + 真实 MySQL）
  - 作用：现有业务测试全是 Mockito，`Memory` / `Session` 的 Mapper 与 SQL 没被真实执行过——改写 SQL、加索引、改迁移都不会被测出来；CI 的 MySQL service 目前只支撑 `JavaHarnessApplicationTests` 的 `contextLoads`
  - 位置：`server/src/test/java`（新增 IT 类）、`.github/workflows/ci.yml`（已有 MySQL service）、`MemoryMapper` / `MemoryServiceImpl`
  - 做法：加一条仓储冒烟（如 `MemoryRepositoryIT`），用 `@SpringBootTest` 连真实库跑完整链路：写入 → 同 key 覆盖（旧行 `deleted_at` 非空）→ 忘掉（软删）→ 按 `active` / `expired` / `deleted` 三种状态读取；本地无库时按 `JavaHarnessApplicationTests` 现有方式跳过（`@EnabledIf` 或 profile 判定），CI 上必须真实执行
  - 验收：CI 日志能看到该用例真实连库并通过；把 `MemoryServiceImpl` 的 `deleted_at IS NULL` 过滤条件改错后该用例失败

- [ ] H1-4 测试去时间依赖（Thread.sleep 归零）
  - 作用：全仓测试有 23 处 `Thread.sleep`，多数是「睡一段时间等状态变化」，慢机或 CI 负载高时就是 flaky 来源；这类失败重跑又能过，排查成本远高于修复成本
  - 位置：`LoginStateStore`（`now()` 直接返回 `System.currentTimeMillis()`）、`LoginStateStoreTest`（4 处，睡 150ms 等 token 过期）、`MultiAgentGraphAgentTest`（7 处）、`KnowledgeDirectoryWatcherTest`（5 处）、`SandboxToolProviderTest`（2 处）、其余零星 4 处
  - 做法：`LoginStateStore` 注入 `Clock`（默认 `Clock.systemUTC()`），测试用 `Clock.fixed` 或可变 Clock 直接推进时间，不睡；流式 / 异步断言改 Awaitility 轮询条件（或 Reactor `StepVerifier` 的虚拟时间），等条件成立而不是等固定时长；文件监听类测试改成显式触发一次扫描
  - 验收：上述四个测试类里 `Thread.sleep` 归零；连续跑 5 次 `mvn -o test` 无失败；单类耗时下降（`LoginStateStoreTest` 不再固定消耗 600ms）

- [ ] H1-5 部署默认项（优雅停机 + 容器日志上限）
  - 作用：两个默认值直接对应线上风险——`server.shutdown` 未配置时默认 `immediate`，重启会硬断在途的 SSE 流；compose 的 `app` 服务没有日志上限，json-file 驱动会无限增长，小内存机（如 2G）长期运行会被日志写满
  - 位置：`server/src/main/resources/application.yaml`（全局配置，非 profile）、`docker/docker-compose.yml` 的 `app` 服务（第 62 行起）
  - 做法：`application.yaml` 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`；compose 的 `app` 补 `stop_grace_period: 30s`，并加 `logging.options.max-size: 20m` / `max-file: "3"`（与 mysql / pgvector 保持一致的写法）
  - 验收：`docker compose restart app` 时在途 SSE 请求收到正常结束而非连接重置；`docker inspect` 能看到 `max-size` / `max-file` 配置生效；`docker compose config` 校验通过

## P2 结构性（择机）

> 四项都建议在 H1-1 覆盖率门禁之后动手——没有覆盖率数据，重构无法证明「只改结构、不改行为」。

- [ ] H2-1 `SessionServiceImpl` 抽出 `SessionSnapshotStore`
  - 作用：581 行 / 18 个 public 方法，同时承担建档、快照写回、裁剪归档、摘要触发、查询五类职责；快照逻辑（裁剪阈值、消息 ID 补齐、归档落库）与「会话端口」混在一起，改一处要读全文
  - 位置：`server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java`、`harness-api` 的 `SessionService` 契约、`SessionServiceImplTest`
  - 做法：抽 `SessionSnapshotStore`（快照读、批量写回、裁剪与归档落库），`SessionServiceImpl` 只保留端口方法编排与事务边界；先移动代码不改语义，再考虑合并重复的读取路径
  - 验收：`SessionServiceImpl` 行数与 public 方法数明显下降（目标 ≤ 400 行 / ≤ 12 个 public）；`SessionServiceImplTest` 与 `SessionServiceContractTest` 全绿且断言未放宽；`mvn verify` 覆盖率不低于改动前

- [ ] H2-2 登录失败计数按窗口清理
  - 作用：`failuresByIp` 只在登录成功时 `remove`，失败过的 IP 会永久留一条记录；公网暴露时被扫描器刷一轮，map 只增不减
  - 位置：`server/src/main/java/com/dark/javaHarness/config/security/AuthController.java`（`failuresByIp` 声明在第 49 行，成功重置在第 83 行，失败累加在第 117 行）
  - 做法：给 `FailState` 记录最后一次失败时间；加 `@Scheduled` 每小时清理「最后一次失败早于锁定窗口」的条目（窗口与现有锁定策略同源，避免两处阈值漂移）；清理量记日志便于观察
  - 验收：构造 1000 个已过窗口的 IP 后执行清理，map size 明显下降；窗口内仍在计数的 IP 不受影响；有单测覆盖「过期清理 / 活跃保留」两条分支

- [ ] H2-3 多实例假设显式化（定时任务防护）
  - 作用：6 个 `@Scheduled` 全是单实例假设（画像提炼 300s、日志清理 cron 03:00、停滞 Goal 检测 60s、Goal 重投 30s、调度派发 10s、沙箱探活 300s），多实例部署时同一任务会被并发执行；现在没有任何提示，扩容时才发现重复推送
  - 位置：`UserProfileService`、`ObservabilityLogCleaner`、`StaleGoalWatchdog`、`GoalRetryScheduler`、`ScheduleServiceImpl`、`SandboxToolProvider`
  - 做法：短期不改调度实现——启动时打印一条显式警告日志（「检测到 N 个 @Scheduled，本版本假设单实例」），并在 `docs/dev.md` 写明约束；长期再引入 ShedLock 或 DB 行锁（派发与推送这两条并发代价最高，优先加锁）
  - 验收：启动日志有该警告；`docs/dev.md` 能查到单实例约束与扩容前置条件；清单里记录「已评估、暂不加锁」的理由

- [ ] H2-4 隐式配置补进 yaml
  - 作用：三个键只有 `@Value` 默认值，yaml 里搜不到——运维改配置时无从发现，改错了也没有对照
  - 位置：`SessionSummaryService`（`app.context.summary-max-chars:600`、`app.context.summary-model:qwen3.8-27b`）、`McpToolProvider`（`spring.ai.mcp.client.stdio-config-file:mcp-config.json`）、`application-chat.yaml`（目前只有 `app.context.history-budget`）
  - 做法：把三个键补进对应 yaml 并加注释（默认值、影响范围、何时该改），值保持与 `@Value` 默认一致，不改变现有行为
  - 验收：三个键在 yaml 中可见；改 yaml 值能覆盖默认值（用一条测试或启动日志确认生效值）；`grep` 不到「只存在于 @Value」的配置项

- [ ] H2-5 两个超大类补「不拆理由」结论
  - 作用：`OrchestrationNodes` 620 行、`OneBotEventServiceImpl` 578 行，体检结论是**职责单一、不建议拆**——但结论只存在于这次对话里，下次有人看到行数又会重新评估一遍
  - 位置：`server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java`、`channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java`
  - 做法：类头补注释说明职责边界与不拆理由（`OrchestrationNodes` 就是「编排节点的集合」，拆散增加跳转；`OneBotEventServiceImpl` 是单事件链路的顺序处理，拆开会让链路不可读）；结论回写本条目，与 `project-optimization.md` P3-2 的做法保持一致
  - 验收：两个类头有注释；本条目记录结论与日期，后续不再重复评估

## 边界：不在本清单内

以下项体检时看过，明确**不纳入**，避免以后重复讨论：

- **P3 可选，收益低于成本**：Dependabot / Renovate 自动升级（Spring AI 迭代快，但当前升级频率可控）、慢 SQL 日志（排查时手工 `EXPLAIN` 够用）、`AgentRegistry` 三处构造分支的工厂收敛（9 个重复块，收敛后跳转更多）。
- **明确不做**：
  - 全量 checkstyle 格式整改——会污染 git 历史，收益低于成本；H1-1 的覆盖率门禁不依赖格式规则
  - 给 record / DTO / 配置类补单测——没有分支逻辑，补了只是刷数字
  - 拆 `OrchestrationNodes`——见 H2-5，职责单一，拆了反而增加跳转
