# 架构分层与耦合治理清单

- 范围：`server` 内部包级依赖、`tool` / `graph` 导入环、分层回归防护
- 创建：2026-11-05
- 来源：知识图谱分析报告（`issue.md`）
- 状态：共 4 项，已完成 3 项，待办 1 项

## 背景

模块级边界是干净的（`server` / `channel-qq` / `cli` 只依赖 `harness-api`，无反向依赖）。问题集中在 `server` 内部：`server-runtime ↔ agent-orchestration` 双向依赖（111 / 50 条跨层导入），并存在 2 处文件级导入环。本清单只处理结构问题，不改业务行为。

## P0 消除导入环

- [x] P0-1 消除 tool 包导入环
  - 作用：打断 `McpToolProvider ↔ SandboxToolProvider ↔ ToolAssignments` 的循环依赖，避免工具权限装配与工具实现互相牵制，为后续拆分 SandboxToolkit 铺路
  - 位置：[ToolAssignments](../server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java#L132-L245)、[McpToolProvider](../server/src/main/java/com/dark/javaHarness/tool/mcp/McpToolProvider.java)、[SandboxToolProvider](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java)
  - 做法：把 `ToolAssignments` 对两个 Provider 的依赖改为面向 `ToolProvider` 接口（`ToolSet tools()`）；Provider 只依赖工具元数据接口，不再反向依赖分配表；装配期由 `ChatAgentConfig` 注入实现
  - 验收：`ToolAssignments` 与两个 Provider 之间无相互 import；`mvn -s .mvn/settings.xml -pl server -am test` 全绿；工具装配结果与改动前逐项一致（现有 `ToolAssignmentsTest` 覆盖）
  - 结果（2026-11-05）：核查发现两处反向 import 仅用于 Javadoc `{@link}`（`McpToolProvider` / `SandboxToolProvider` 对 `ToolAssignments`、`McpToolProvider` 对 `SandboxToolProvider`），代码依赖本身是单向的（分配表 → Provider）。按 principal §1「无真实复用不加抽象」，直接删除 Javadoc-only import 并改为纯文本引用，不引入 `ToolProvider` 接口；`mvn -s .mvn/settings.xml -pl server -am test` 全绿，工具装配语义零变化。

- [x] P0-2 消除 graph 引擎导入环
  - 作用：解除 `ParallelOrchestrationEngine ↔ MultiAgentGraphAgent` 的循环依赖，让引擎与上层执行体各自可独立测试
  - 位置：[ParallelOrchestrationEngine](../server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java)、[MultiAgentGraphAgent](../server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java)
  - 做法：抽 `OrchestrationListener` / `StreamSink` 接口，引擎只依赖接口；`MultiAgentGraphAgent` 实现回调，删除对引擎实现类的反向 import
  - 验收：两文件无相互 import；`MultiAgentGraphAgentTest` / 引擎相关测试全绿；Graph 链路行为不变
  - 结果（2026-11-05）：状态键与占位文案下沉到 `state/OrchestrationStateKeys` 与 `agent/SubtaskResults`，引擎不再 import `MultiAgentGraphAgent`；占位文案契约保持不变；`mvn -s .mvn/settings.xml -pl server -am test` 全绿。

## P1 分层回归防护

- [x] P1-1 引入 ArchUnit 分层规则
  - 作用：把「`controller` / `service` 不得依赖 `graph` / `agent` 内部实现」「`tool` 不得依赖 `service`」等约定固化成测试，防止重构后再次打穿
  - 位置：[server/pom.xml](../server/pom.xml)（新增 test 依赖）、`server/src/test/java/com/dark/javaHarness/architecture/`
  - 做法：加入 `archunit-junit5`；编写包依赖规则与「禁止环」规则；对既有违规先登记为 `FreezingArchRule` 存量基线，新增违规直接失败
  - 验收：新增 1 个 `ArchitectureTest`；故意引入一条违规依赖时测试失败；`mvn -s .mvn/settings.xml -pl server -am test` 全绿
  - 结果（2026-11-05）：新增 `archunit-junit5` 1.4.1（test 作用域，字节码级依赖分析无法用源码扫描可靠替代）；落地 4 条规则：agent↛graph、controller↛mapper、tool↛service.impl、validation↛运行时包；`RecallHistoryTools` 作为已登记例外显式排除。实测临时让 `ProgressLine` 引用 `graph.OrchestrationBudget` 时测试按规则失败，回退后全量 797 条全绿。

- [ ] P1-2 收敛 server-runtime 与 agent-orchestration 双向依赖
  - 作用：当前 `service` / `controller` 与 `agent` / `graph` 之间 111 / 50 条跨层导入，改一处容易连环回归
  - 位置：[ChatServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L112-L520)、[AgentRequestSpecFactory](../server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java)、[OrchestrationNodes](../server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java)
  - 做法：明确单向调用方向（`service` → `agent`/`graph` 的入口，只允许回调经接口）；把 `service` 被 `agent` 依赖的部分下沉为 `agent` 自有的参数对象 / 接口，禁止 `agent` 直接 import `service.impl`
  - 验收：`agent` / `graph` 包对 `service.impl` 的 import 降为 0；ArchUnit 规则可覆盖；全量测试全绿
