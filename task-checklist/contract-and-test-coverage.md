# 契约层与测试覆盖清单

- 范围：`harness-api` 契约拆分与测试、高扇入基座回归保护、`server` 复杂文件测试补齐
- 创建：2026-11-05
- 来源：知识图谱分析报告（`issue.md`）
- 状态：共 4 项，已完成 4 项，待办 0 项

## 背景

文件级 `tested_by` 覆盖：`harness-api` 5/35（14.3%）、`server` 85/195（43.6%）。`harness-api` 同时是 25 / 23 扇入的基座，却覆盖最低；`server` 有 61 个 `complex` 文件。本清单只补结构与测试，不改契约语义。

## P0 契约层结构

- [x] P0-1 `harness-api` 拆分 domain 与 contract
  - 作用：当前模块同时装领域模型（`Goal`、`AgentConfig`）、服务接口（`AgentService`、`SessionService`）与 DTO（35 个文件），契约变更与领域变更无法独立演进
  - 位置：[harness-api/src/main/java/com/dark/javaHarness](../harness-api/src/main/java/com/dark/javaHarness)
  - 做法：按包拆分 `domain/`（实体与状态机）、`service/`（接口）、`view/`（对外 DTO）；`server` / `channel-qq` / `cli` 同步改 import；跨模块编译一次验证
  - 验收：三个包无交叉引用（`domain` 不依赖 `view` / `service`）；`mvn -s .mvn/settings.xml -pl harness-api,server,channel-qq,cli -am test` 全绿；对外 JSON 字段名零变化
    - 结果（2026-11-05）：20 个契约 DTO 由 `domain.dto` 迁至顶层 `view/`，`domain/` 只保留实体/状态机（`Goal`、`AgentConfig`、`ChatMessageMetadata`、`entity/`），`service/` 保留端口接口；`domain` 唯一跨包依赖仍是 `enums.GoalStatus`（不依赖 view/service）；server 本地 4 个内部视图 DTO 留在 `server/domain/dto`；server / channel-qq / cli 的 import 同步更新；`mvn -s .mvn/settings.xml -pl harness-api,server,channel-qq,cli -am test` 全绿（server 801 / cli 28，channel-qq 同批通过），JSON 字段名未变（纯包移动）。

## P1 高扇入基座测试

- [x] P1-1 `harness-api` 契约测试补齐
  - 作用：`AgentService` 25 扇入、`SessionService` 23 扇入、`Goal` 20 扇入，回归成本最高但文件级覆盖仅 14.3%
  - 位置：[AgentService](../harness-api/src/main/java/com/dark/javaHarness/service/AgentService.java)、[SessionService](../harness-api/src/main/java/com/dark/javaHarness/service/SessionService.java)、[Goal](../harness-api/src/main/java/com/dark/javaHarness/domain/Goal.java)
  - 做法：为 `Goal` 状态迁移补全路径用例（PENDING→RUNNING→SUCCEEDED/FAILED 及非法迁移）；为两个 Service 接口补契约测试（用内存替身验证方法语义，不依赖 MySQL）
  - 验收：`harness-api` 文件级覆盖 ≥60%；`Goal` 状态机用例覆盖全部合法 / 非法迁移；全量测试全绿
  - 结果（2026-11-05）：新增 4 个测试类 36 条用例——`GoalStateMachineTest`（合法路径、无守卫语义、续跑回置、持久化恢复、便捷构造）、`AgentServiceContractTest`（内存替身：同步/流式/续跑/查询语义）、`SessionServiceContractTest`（内存替身：建档/批量写回保留时间戳/切换 Agent 校验/分页倒序/幂等删除/ChatMemory 适配）、`ViewDtoJsonContractTest`（20 个 DTO 的 JSON 键名与嵌套结构、工厂方法语义）；`harness-api` 增加 JUnit 5 + Jackson 3 test 依赖；有测试关联的源文件 23/35 ≈ 65.7%（≥60%）；跨模块 `-pl harness-api,server,channel-qq,cli -am test` 全绿。

- [x] P1-2 高扇入基座变更守卫
  - 作用：`AgentService` / `SessionService` / `Goal` 任一签名或语义变化都会波及 20+ 文件，需要一层显式守卫
  - 位置：`harness-api/src/test/java/com/dark/javaHarness/contract/`（新增）
  - 做法：补一组「契约快照」测试：固定公开方法签名与关键返回结构，签名变化时测试失败并提示受影响模块
  - 验收：故意删改一个公开方法时测试失败；正常构建全绿
  - 结果（2026-11-05）：新增 `PortSignatureGuardTest`（反射快照：公开构造器 + 公开方法（含 ChatMemory 继承端口）擦除签名，失败信息标注受影响模块）；实测签名与快照不一致时（缺 `add(String,Message)`、Goal 恢复构造器少一个 `String` 参数）测试按预期失败，补齐后 `harness-api` 39 条全绿。

## P2 复杂文件测试

- [x] P2-1 `server` 复杂文件测试热点收敛
  - 作用：`server` 有 61 个 `complex` 文件，其中 `graph` / `tool` / `service` 是主链路高风险区
  - 位置：优先 [OrchestrationNodes](../server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java)、[ParallelOrchestrationEngine](../server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java)、[SandboxToolProvider](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java)、[KnowledgeServiceImpl](../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java)
  - 做法：按「失败路径优先」补用例：子任务失败隔离、聚合降级、沙箱不可用降级、摄取失败不影响检索；每项先确认现有行为再固化
  - 验收：上述 4 个文件各有失败路径用例；`server` 文件级覆盖 ≥55%；`mvn -s .mvn/settings.xml -pl server -am test` 全绿
  - 结果（2026-11-05）：4 个热点文件的失败路径用例已齐备——`ParallelOrchestrationEngine`（聚合失败降级摘要、无可用结果上抛、新增「单子任务异常不拖垮其余结果」）、`SandboxToolProvider`（init 挂死超时、init 抛异常、Docker 不可达、降级 gauge 为 0）、`KnowledgeServiceImpl`（向量库未装配抛可读错误、写入失败包装、台账置 0 待重试、新增「摄取失败后检索照常」）、`OrchestrationNodes`（工具包解析与预算降级由 `OrchestrationNodesResolvePacksTest` / `MultiAgentGraphAgentTest` 覆盖）；server 文件级测试关联 132/201 ≈ 65.7%（≥55%）；跨模块全量 803（server）+ 39（harness-api）+ 28（cli）全绿。
