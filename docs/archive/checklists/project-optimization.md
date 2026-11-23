# 项目优化清单（一致性 / 性能 / 结构）

- 范围：`server` 主链路（Chat / Session / Agent 配置 / Knowledge）、`harness-api` 会话契约、`project-structure.md` 文档
- 创建：2026-11-03
- 状态：共 15 项，已完成 15 项，待办 0 项

## 进度概览

| 分类         | 已完成 | 待办 | 小计 |
| ---------- | --- | -- | -- |
| 检查与分级      | 1   | 0  | 1  |
| P0 正确性与一致性 | 1   | 0  | 1  |
| P1 性能      | 4   | 0  | 4  |
| P2 结构清理    | 5   | 0  | 5  |
| P3 文档与工程   | 4   | 0  | 4  |
| 合计         | 15  | 0  | 15 |

## 背景

2026-11-03 对全仓做了一轮通读检查：主代码 262 个 Java 文件（约 26.9k 行）、测试 109 个文件（约 18.1k 行）；基线 `mvn -s .mvn/settings.xml -o test` 全绿（935 条用例，0 失败，约 2 分钟）。

问题集中在两类：**热路径上的重复读取/重复写入**，以及 `principal.md` §4「不留兼容层」未清理干净的重载堆积。本清单按 P0→P1→P2→P3 排列，每一项都可独立提交、可独立验证。

执行结果（2026-11-03）：15 项全部落地。后端 `mvn -s .mvn/settings.xml -o test` 942 条用例全绿（较基线 +7）；前端 `npm run build`（含 vue-tsc）+ `npm test` 25 条 + `npm run test:e2e` 4 条冒烟全绿。

## 已完成

- [x] 全仓检查与问题分级（2026-11-03）
  - 作用：把散落的优化点收敛成一份可执行清单，避免重复排查
  - 位置：本文件
  - 做法：读 `docs/principal.md` / `project-structure.md` 建立判定标准，再按模块扫代码、跑基线测试
  - 验收：每条问题都有文件与行号定位，并按 正确性 / 性能 / 结构 / 文档 分级

## P0 正确性与一致性

- [x] P0-1 配置读取失败不再静默降级
  - 作用：DB 抖动时不再悄悄换模型、换提示词、换 Agent，避免「请求成功但答案来自另一个 Agent」这类无感错误
  - 位置：[AgentConfigProvider.findAgentNameById/getAgentConfig/findAgentTools/findToolPack](../../deerfect-server/src/main/java/com/dark/javaHarness/service/AgentConfigProvider.java#L34-L170)（4 处 `catch (Exception)` 返回 empty）、[ChatServiceImpl.sessionAgentName](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L533-L548)（异常回退 general）、[AgentChatCaller.configOf](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L577-L586)、[PromptAssembler.resolveRolePrompt](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java#L199-L213)
  - 做法：把「行不存在」（`Optional.empty`，走默认）与「查询失败」（抛 `DataAccessException`）分开；`sessionAgentName` 只在会话未绑定 / agent 行已删时回退 general，查询失败向上抛。失败语义（500 还是可读错误）需先确认期望行为再动手
  - 验收：单测覆盖 mapper 抛异常 → 请求失败且错误信息可读；正常路径行为零变化；935 条基线测试仍全绿
  - 结果（2026-11-03）：配置读取失败改为上抛（`AgentConfigProvider` 4 处 catch 删除、`sessionAgentName` 不再回退 general）；新增 3 条失败路径测试。

## P1 性能

- [x] P1-1 会话快照批量写回（一轮一次锁、一次序列化）
  - 作用：现在一轮消息连续两次 `saveContext`，每次都全量解析 + 全量序列化数百条历史 JSON，并各占一次行锁；改成一次可把锁持有时间和写放大减半
  - 位置：[ChatServiceImpl.writeBackBoth](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L245-L250)、[SessionServiceImpl.saveContext](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L191-L241)、[SessionService](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/api/SessionService.java#L44-L50)（契约在 harness-api）
  - 做法：`SessionService` 新增 `saveContextBatch(sessionId, List<Message>, ts)`；实现内一次 `lockSessionRow` + 一次 parse/serialize + 一条 UPDATE；`writeBackBoth` 改为一次调用。注意 `harness-api` 是跨模块契约，改动要同步 `channel-qq` / `cli` 编译
  - 验收：一轮写回 SQL 由 8 条降为 ≤4 条；快照 JSON 与逐条写回的结果逐字节一致（ts 取真实时刻的口径不变）；并发写回测试通过
  - 结果（2026-11-03）：新增 `SessionService.saveContextBatch` + `SessionMessageEntry`；一轮写回合并为 1 次锁 / 1 次序列化（SQL 8→4）；新增 2 条测试（批量一次 UPDATE、空批次短路）。
- [x] P1-2 消除每轮重复读取同一 session 行
  - 作用：同一行 `session` 每轮被读 4 次（`sessionAgentName` 两次 + `getSession` + `FOR UPDATE`），其中至少 2 次是纯重复
  - 位置：[ChatServiceImpl.resolveRouteWithPrefetch](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L450-L471)（已收到 `agentName` 入参却重新查表）、[SessionServiceImpl.saveContext](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L196-L204)（`getSession` 与 `lockSessionRow` 读同一行）
  - 做法：`resolvedAgent` 直接用入参 `agentName`（所有调用点传入的就是同一值）；`saveContext` 删掉 `getSession`，用 `lockSessionRow` 的返回值判存
  - 验收：每轮 session 行读取 4 → 2 次；「会话已删则跳过写回」的守卫行为不变（仍记 INFO 日志）
  - 结果（2026-11-03）：`resolveRouteWithPrefetch` 直接用入参 `agentName`；`saveContext` 用 FOR UPDATE 的返回值判存，删掉重复 `getSession`；每轮 session 行读取 4→2。
- [x] P1-3 agent / model_provider 配置缓存 + 日志降级
  - 作用：每条 LLM 调用都要查 `agent` + `model_provider` 两张表并打一行 6 字段 INFO；一次 4 子任务编排约 6 次调用 → 12 条查询、6 行日志
  - 位置：[AgentConfigProvider.getAgentConfig / resolveModelName](../../deerfect-server/src/main/java/com/dark/javaHarness/service/AgentConfigProvider.java#L89-L116)、[AgentChatCaller.configOf](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L574-L586)、[ProviderAdminServiceImpl.add](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ProviderAdminServiceImpl.java#L101)（已有 `registry.reload()` 失效钩子）
  - 做法：`AgentConfigProvider` 加进程内缓存（TTL 或版本号）；`ProviderAdminServiceImpl` 新增 provider 后一并失效；`configOf` 的命中日志降到 debug
  - 验收：同一编排内 agent 查询由约 12 条降为 ≤2 条；新增 model_provider 行仍即时生效（现有 `ProviderAdminServiceImplTest` 覆盖）；日志无重复刷屏
  - 结果（2026-11-03）：`AgentConfigProvider` 加 30s TTL 缓存（仅缓存命中结果）；`configOf` 与注册表「命中」日志降 debug；新增缓存命中测试。
- [x] P1-4 知识库 sync 消除 N+1
  - 作用：`sync` 在文件循环里逐个查 `kb_document`，`docs/` 下 130+ 文档即 130+ 条 SELECT
  - 位置：[KnowledgeServiceImpl.sync](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L106-L160)、[rowOf](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L466-L468)
  - 做法：循环前 `selectList` 全量载入 `Map<docName, row>`，循环内查 Map
  - 验收：130 文档的 sync 中 `kb_document` 查询数 = 1；增量判据（`mtime` + `kb`）与跳过/重摄取行为不变
  - 结果（2026-11-03）：sync 循环前一次载入台账并按 `doc_name` 建索引，孤儿清理复用同一份快照；新增「多文档只查一次台账」测试。

## P2 结构清理

- [x] P2-1 `AgentChatCaller` 重载与构造器收敛
  - 作用：7 个 `call` + 6 个 `stream` 重载 + 4 个构造器，参数已到 11 个还在靠 null 占位，传错位置编译器不报错；与 `principal.md` §4「旧重载直接删」冲突
  - 位置：[AgentChatCaller.call 重载](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L140-L212)、[stream 重载](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L419-L477)、[构造器链](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L71-L137)
  - 做法：保留全参入口 + 一个参数 record（toolEmitter / cancelled / ledger / trace / extraToolNames / reasoningTap / extraAdvisors），删中间重载与单测专用构造器；`callWithAssembly` / `streamWithAssembly` 作为唯一实现
  - 验收：调用点全部显式构造、无 null 占位；`AgentChatCallerTest` / `AgentChatCallerRetryTest` 改写后全绿
  - 结果（2026-11-03）：7 个 `call` + 6 个 `stream` 重载收敛为「4 参便捷入口 + `CallOptions` 参数对象」，方法数 13→4。构造器保留 4/5/6 参便捷链：删掉会强迫单测写 13 个 null，属同类问题，留待构造依赖对象化时统一处理。
- [x] P2-2 `OrchestrationNodes` / `KnowledgeServiceImpl` 构造器收敛
  - 作用：两处为单测保留的尾参构造器链，属于兼容层
  - 位置：[OrchestrationNodes](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java#L58-L87)（3 个构造器）、[KnowledgeServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L88-L107)（2 个构造器）
  - 做法：只保留全参构造器，单测改为传入显式 null 或构造轻量替身
  - 验收：每类只剩 1 个构造器；相关单测全绿
  - 结果（2026-11-03）：`OrchestrationNodes` 3→1 个构造器（另两个本就无调用方）；`KnowledgeServiceImpl` 2→1，单测改传显式 null。
- [x] P2-3 全限定类名改回 import
  - 作用：同一文件里既 import 又写全名（`ChatServiceImpl` 第 13 行 import 了 `KnowledgeRetriever`，第 52 行仍写全名），可读性差且易与包迁移脱节
  - 位置：[ChatServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java)（28 处）、[MultiAgentGraphAgent](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java)（28 处）、[ChatAgentConfig](../../deerfect-server/src/main/java/com/dark/javaHarness/config/agent/ChatAgentConfig.java)（26 处）、[AgentScopeGeneralAgent](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java)（23 处）
  - 做法：补齐 import，替换全限定写法；同名冲突时用单行 import + 局部别名注释
  - 验收：上述文件内 `com.dark.javaHarness.` 全名出现次数仅剩 package/import 行；编译与测试全绿
  - 结果（2026-11-03）：9 个文件的全限定名改为 import，非导入行残留 0；`AgentScopeGeneralAgent` 3 处同名冲突（Spring AI / AgentScope 的 UserMessage、AssistantMessage）保留全名。
- [x] P2-4 删除无调用方的重载
  - 作用：死代码，且与「不留兼容层」冲突
  - 位置：[ChatServiceImpl.resolveRouteWithPrefetch 5 参重载](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L444-L447)
  - 做法：删除；所有调用点已走 6 参版本
  - 验收：编译通过，无行为变化
  - 结果（2026-11-03）：删除 `ChatServiceImpl.resolveRouteWithPrefetch` 5 参死重载。
- [x] P2-5 回复后处理器异常改为上抛
  - 作用：`ReplyPostProcessor` 实现都在本仓库内，逐个 `catch` 后继续会把实现缺陷变成「静默少做一步后处理」
  - 位置：[ChatServiceImpl.applyReplyPostProcessors](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L205-L228)
  - 做法：去掉循环内 try/catch（或改为装配期校验 + 首个异常即抛）；若确实要容错，改为「首个异常上抛，后续处理器不执行」
  - 验收：后处理器抛异常时请求失败且日志含处理器标识；无实现时行为不变
  - 结果（2026-11-03）：后处理器异常上抛（去掉逐条 catch 与 `ObjectProvider` 空判）；新增失败路径测试。

## P3 文档与工程

- [x] P3-1 `project-structure.md` 补齐缺失类与包
  - 作用：文档自称「逐文件说明」但只覆盖 88/190 个 server 类，新读者按文档找不到入口
  - 位置：[docs/project-structure.md](../project-structure.md)
  - 做法：补 `MonitorService`、`HarnessMetrics`、`BudgetLedger`、`ContextCompositionRecorder`、`StreamConnectionLimiter` 等，以及 `config/logging`、`config/security`、`tool/budget`、`tool/trace` 等包
  - 验收：新增/迁移的类都能在文档中找到；抽查 10 个随机类命中率 100%
  - 结果（2026-11-03）：`project-structure.md` 覆盖 190/190 个 server 类：补 `config/logging`、`config/security`、监控三件套、`BudgetLedger` 等。
- [x] P3-2 超长类补「不拆理由」注释
  - 作用：`principal.md` §1 要求单文件超约 300 行须说明职责边界与不拆理由；`KnowledgeServiceImpl`（520 行）、`SessionServiceImpl`（516 行）类注释里没有，另外 4 个 >500 行的都有
  - 位置：[KnowledgeServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L36-L44)、[SessionServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L35-L48)
  - 做法：二选一——补类注释说明边界，或把可独立的部分（如快照裁剪归档、BM25 融合）拆出去
  - 验收：`grep 拆分/职责边界` 在 6 个 >500 行类中均能命中
  - 结果（2026-11-03）：`KnowledgeServiceImpl` / `SessionServiceImpl` 类注释补「体量说明（principal §1）」与不拆理由。
- [x] P3-3 数据源默认值收口
  - 作用：`username: root` 与 `useSSL=false` 写死，只有 password 走环境变量，公网部署容易「直接能跑」
  - 位置：[application-datasource.yaml](../../deerfect-server/src/main/resources/application-datasource.yaml)
  - 做法：username 改为 `${MYSQL_USERNAME:root}`；`useSSL` 改为可配置项并在注释写明生产必须开启
  - 验收：本地默认行为不变；生产可用环境变量覆盖；`docs/guides/setup.md` 同步说明
  - 结果（2026-11-03）：datasource 的 username / useSSL 改为环境变量（`MYSQL_USERNAME` / `MYSQL_USE_SSL`），`setup.md` 补覆盖说明；compose 显式覆盖不受影响。
- [x] P3-4 前端超大单文件拆分
  - 作用：`MonitorView.vue` 1088 行、`main.css` 1341 行，已影响定位与复用；当该文件还要加功能时先拆
  - 位置：[web/src/components/MonitorView.vue](../../deerfect-web/src/components/MonitorView.vue)、[web/src/styles/main.css](../../deerfect-web/src/styles/main.css)
  - 做法：MonitorView 按「指标卡 / 轨迹表 / 明细浮层」拆子组件；CSS 按域拆文件后由入口 `@import`
  - 验收：`npm run build`（含 vue-tsc）与 `npm test` 通过；页面视觉无回归
  - 结果（2026-11-03）：`MonitorView.vue` 1088 → 128 行，拆出 `components/monitor/` 四个子组件（指标卡 / 分布 / 会话明细 / 调用轨迹）+ `utils/monitorFormat.ts`；`main.css` 1341 行拆为 12 个域文件、入口只留 `@import`；监控样式迁到 `styles/monitor.css` 并统一以 `.monitor` 前缀限定。验收：build 通过、vitest 25 条（新增 9 条格式化用例）、e2e 4 条全绿；样式声明数与规则数与拆分前逐项相等（244/73）。

## 执行顺序

1. P1-1 → P1-2 → P1-4 → P1-3：纯性能，改动局部，有基线测试兜底，先拿收益
2. P0-1：涉及错误语义，需先确认「读配置失败该返回什么」
3. P2-1 → P2-2 → P2-3 → P2-4 → P2-5：结构性清理，一次提交一个类
4. P3-1 → P3-2 → P3-3 → P3-4：文档、配置与前端拆分，可与其他项同批
