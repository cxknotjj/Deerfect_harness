# Spring AI 2.0 迁移方案（去 Spring AI Alibaba）

> 状态：已完成（代码迁移 2026-10-25；2026-10-27 本机真机启动、Flyway 迁移与真实模型对话冒烟均通过）　|　硬性目标：Spring AI 2.0（取最新稳定 2.0.2）　|　日期：2026-10-25
>
> 本文为迁移设计存档；当前技术基线以 [TECH_STACK.md](../TECH_STACK.md) 为准（Spring Boot 4.1.2 / Spring AI 2.0.2 / Java 25）。

## 1. 目标与边界

**硬性要求**：Spring AI 2.0（选 2.0.2，当前最新稳定版）。由于 Spring AI 2.0 的基线是 Spring Boot 4.0/4.1 + Spring Framework 7 + Jackson 3，Boot 4 与 Java 升级是**被动必选**。

**同时移除**：`spring-ai-alibaba`（BOM、graph-core、sandbox）。理由：其 2.0 仅有里程碑版（2.0.0-M1.1），且 1.1.2.2 与 Spring AI 2.0 / Boot 4 不兼容。

**必须保留的行为**（验收底线）：
- 单 Agent 直答（路径 A）、多 Agent 编排（路径 B：lead 拆解 → 并行子任务 → 聚合）
- SSE 进度行、token 打字机、meta 收尾
- `/resume` 断点续跑（lead 不重跑、已完成子任务不重跑）
- 编排预算熔断、子任务并发限流、子任务墙钟超时、失败占位
- RAG（pgvector + BM25 混合）、工具装饰链、MCP client/server、用户画像、观测落库
- QQ 渠道、CLI、Web 三端协议不变

**明确取舍**：
- 编排引擎由自研替换（不再依赖 StateGraph）。
- 沙箱保留能力，但改为直接依赖 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`，自维护薄封装。
- 不升级前端（Vue/Vite/TS 与本次无关）。

**版本选择原则**：只取**最新稳定版**，不取 RC / 里程碑；同一大版本取最新补丁。截至 2026-10-25，Spring Boot 4.2.0-RC1、Spring AI 2.1.0-RC1 均属预发布，不纳入。

## 2. 目标版本矩阵

| 组件 | 当前 | 目标 | 说明 |
|---|---|---|---|
| Java | 17 | **25（LTS）** | 本机存放 `D:\env\jdk-25`（WSL `/mnt/d/env/jdk-25`）；Boot 4.1.2 支持 17–27，异常时回退 21 |
| Spring Boot | 3.5.14 | **4.1.2** | 2026-10-23 最新稳定补丁 |
| Spring AI | 1.1.4 | **2.0.2** | 2026-10-23 最新稳定版（2.1.0 仍 RC） |
| Spring AI Alibaba | 1.1.2.2 | **移除** | graph-core / sandbox 全部替换 |
| MyBatis-Plus | 3.5.7 | **3.5.17** | 当前最新；使用 `mybatis-plus-spring-boot4-starter` |
| Jackson | 2.x | **3.x** | databind/core 包名 `com.fasterxml.jackson` → `tools.jackson`；注解仍为 `com.fasterxml.jackson.annotation` |
| MCP SDK | 0.17.0（手动锁） | 由 Spring AI 2.0.2 传递（实际 2.1.0） | 已删除根 pom 的 0.17.0 版本锁定 |
| agentscope runtime | 由 SAA 传递 | **1.0.2 直接依赖** | `io.agentscope:agentscope-runtime-sandbox-core` 当前最新 |
| JLine | 3.27.1 | **3.30.17** | 最新 3.x；4.x 为破坏性大版本，本次不跨 |
| OkHttp | 4.12.0 | **5.5.0** | 5.x 为 KMP，Maven 使用 `okhttp-jvm` 制品；CLI/渠道 HTTP 客户端已回归 |
| jsoup | 1.21.1 | **1.22.1** | 最新稳定，API 兼容 |
| Lombok | 由 Boot 3.5 BOM 管理 | 由 Boot 4.1.2 BOM 管理 | 若 Java 25 注解处理报错，显式升到支持 25 的版本 |
| MySQL / pgvector | 8.4 / pg16 | 不变 | — |
| Docker 基础镜像 | temurin-17 | **temurin-25** | 构建与运行阶段同步；回退方案用 temurin-21 |

## 3. 架构变化

```
现状：
  MultiAgentGraphAgent ── StateGraph/CompiledGraph ── MysqlSaver(GRAPH_* 表)
                       └─ GraphLifecycleListener ── 进度旁路
  SandboxToolProvider ── SAA ToolkitInit ── agentscope runtime

目标：
  MultiAgentOrchestrator ── 自研 ParallelOrchestrationEngine ── OrchestrationCheckpointStore(新表)
                          └─ EngineEvent(progress/token/final) ── SSE
  SandboxToolProvider    ── 自研 SandboxToolkit ── agentscope runtime(直接依赖)
```

**新增包**：`com.dark.javaHarness.graph.engine`
- `OrchestrationEngine`：`String execute(Goal)` / `Flux<String> stream(Goal)` / `Flux<String> resume(Goal)`
- `ParallelOrchestrationEngine`：lead → 并行 subtask（信号量）→ aggregate
- `OrchestrationState`：`Map<String,Object>` 包装（替代 `OverAllState`）
- `EngineEvent`：`Progress` / `Token` / `Final` 三类事件
- `OrchestrationCheckpointStore`：读写检查点（MyBatis-Plus）
- 复用：`OrchestrationNodes`（把 `OverAllState` 改为 `Map`）、`OrchestrationBudget`、`LeadOutputParser`、`ThinkingThrottle`、`OrchestrationPrompts`

**检查点设计**（替代 MysqlSaver）：
- 新表 `orchestration_checkpoint(goal_id, checkpoint_id, stage, state_json, created_at)`，Flyway V41 建表。
- 落点：`LEAD_DONE`（含 objective/subtaskCount/agents/briefs）、`SUBTASKS_DONE`（含全部 result_*）、`DONE`（含 final）。
- 续跑选择：`DONE` → 直接回放 final；`SUBTASKS_DONE` → 只补 aggregate；`LEAD_DONE` → 补子任务 + aggregate；无检查点 → 报错（与现行为一致）。
- `threadId` 语义保留：`goal.id()`。

## 4. 分阶段任务

> 执行顺序调整：本方案保留 P0–P5 的阶段划分，但**实施计划改为「先去 SAA（保持 Boot 3.5 全绿）→ 再升 Boot 4/Spring AI 2」**。这样编排重写与 API 大迁移不同时爆发，回归定位更容易；详见 `docs/superpowers/plans/2026-10-25-spring-ai-2-dependency-migration.md`。

### P0 基线冻结（独立分支）
1. 建分支 `refactor/spring-ai-2`，不合并 master。
2. 记录基线：`mvn test` 结果、Web 单聊/轨迹、CLI 单聊/`/resume`、一次 COMPLEX 编排截图/日志。
3. 把本方案落入 `docs/design/spring-ai-2-migration.md`。

### P1 构建基线切换（先让编译器说话）
1. 根 `pom.xml`：
   - parent → `spring-boot-starter-parent:4.1.2`
   - `java.version` → 25
   - `spring-ai.version` → 2.0.2
   - 删除 `spring-ai-alibaba.version` 与 alibaba BOM
   - `mybatis-plus.version` → 3.5.17
   - 删除 MCP SDK `0.17.0` 的显式锁定（mcp/mcp-core/mcp-json 三处）
   - JLine → 3.30.17、OkHttp → 5.5.0、jsoup → 1.22.1
2. `server/pom.xml`：删除 `spring-ai-alibaba-graph-core`、`spring-ai-alibaba-sandbox`；`mybatis-plus-spring-boot3-starter` → `mybatis-plus-spring-boot4-starter`；新增 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`。
3. `channel-qq/pom.xml`、`harness-api/pom.xml`：MyBatis-Plus 构件切换到 3.5.17 对应坐标（api 仍用 `extension`，optional）。
4. Docker：`docker/Dockerfile`、`docker-compose.yml`、`README` 的 JDK 17 → **25**；本机 JDK 25 安装到 `D:\env\jdk-25`（WSL `/mnt/d/env/jdk-25`），任务 10 起设置 `JAVA_HOME`；阶段一仍用 JDK 17。若 Java 25 注解处理或 JLine 终端异常，整体回退 21。
5. 运行 `mvn -DskipTests compile`，把编译错误按模块归档为迁移清单（此阶段不要求绿）。

### P2 编排引擎替换（去 graph-core）
1. 新建 `engine` 包与 `OrchestrationEngine` 接口；`MultiAgentGraphAgent` 改名为 `MultiAgentOrchestrator`（保留 `Agent` 接口实现）。
2. `OrchestrationNodes`：方法签名 `OverAllState` → `Map<String,Object>`；逻辑不动。
3. `ParallelOrchestrationEngine`：
   - lead 阶段：复用现有 lead 节点逻辑。
   - 子任务：`Semaphore(subtask-concurrency)` + `CompletableFuture`；取消标志、墙钟超时、失败/预算占位沿用现有常量与文案。
   - aggregate：复用现有聚合逻辑；流式 token 直接写 `EngineEvent.Token`。
4. 流式管线：`MultiAgentStreamPipeline` 改为消费 `Flux<EngineEvent>`；`ProgressLine` 协议不变；`BranchProgressListener` 删除，进度由引擎在阶段边界发射。
5. 检查点：新增实体/Mapper/Store + V41 迁移；`ChatAgentConfig` 的 `BaseCheckpointSaver` Bean 替换为 `OrchestrationCheckpointStore`。
6. 续跑：实现「DONE 回放 / SUBTASKS_DONE 补聚合 / LEAD_DONE 补子任务」三级选择。
7. 测试：重写 `MultiAgentGraphAgentTest` 为 `MultiAgentOrchestratorTest`；新增引擎单测（并行、预算、超时、失败占位、取消、续跑）。

### P3 沙箱替换（去 SAA ToolkitInit）
1. 新建 `SandboxToolkit`，直接基于 `io.agentscope.runtime.sandbox.box.*`，实现当前实际使用的工具（base 10 个 + browser 6 个；其余按需）。
   - 参考 SAA `ToolkitInit`（Apache-2.0，保留出处注释）。
2. `SandboxToolProvider` 仅替换 `ToolkitInit.*` 调用；懒初始化、失败降级、`@PreDestroy` 逻辑不动。
3. 新增配置 `app.sandbox.enabled`（默认 true）；Docker 不可用时的降级路径保持现状。
4. 单测：Mockito 模拟 sandbox 返回；真实 Docker 冒烟可选。

### P4 Spring AI 2.0 API 迁移（编译驱动，按清单推进）
1. **Jackson 3**（27 个文件）：`com.fasterxml.jackson.*` → `tools.jackson.*`；`JsonProcessingException` → Jackson 3 异常类型；`TypeReference` 包名替换。MyBatis-Plus 等仍用 Jackson 2 的库允许共存。
2. **OpenAI 适配**：
   - `ChatClientFactory`、`KnowledgeConfig` 的 `OpenAiApi.builder()` 按 2.0 OpenAI SDK 调整。
   - `OpenAiChatOptions` 改为不可变 + builder；`ThinkingSwitchChatModel` 的 `copy()/setExtraBody()` 改为 builder 注入。
   - `retryTemplate` 关闭内置重试的接线按 2.0 的 `ChatClient`/`ChatModel` API 调整。
3. **Advisor / tool loop**：自定义 `ContextAssemblingAdvisor`、`PromptBudgetAdvisor`、`LlmRequestLogAdvisor` 适配 2.0 `Advisor` 链；工具循环由 `ToolCallingAdvisor` 接管，现有工具预算/观测装饰链保持顺序语义。
4. **MCP 2.0**：`McpToolProvider` 的 `io.modelcontextprotocol.*` 导入、transport、JSON mapper 适配；删除 0.17.0 pin 后验证 server/client 两端。
5. **Response/记忆**：`ChatResponse`/`Generation`/metadata 构造点逐个适配；`SessionService implements ChatMemory` 保持不变（自定义实现）。
6. **RAG/向量**：`Document`、`SearchRequest`、`VectorStore`、`EmbeddingModel` 的 2.0 签名核对。
7. **测试 triage**：按模块修复 Mockito 桩与断言；保持测试意图不变。

### P5 收尾
1. 全量 `mvn test` 绿；`mvn -DskipTests package` 产 fat jar。
2. Web 真机：单聊 + 轨迹 + COMPLEX 编排 + `/resume`。
3. CLI：单聊 + `/resume`。
4. 沙箱：有 Docker 时 python/shell/文件工具可用；无 Docker 时降级不阻断。
5. `mvn dependency:tree | grep alibaba` 必须为空（除无关传递项，需逐项确认）。
6. 更新 README / TECH_STACK / docs 的版本矩阵与启动说明。

## 5. 验收标准

- `mvn test`：0 Failures / 0 Errors。
- 真实聊天：路由 + 直答 + 轨迹统计正常。
- COMPLEX：lead 拆解 ≥2 子任务并行、聚合、进度行、预算/超时/失败占位均有单测覆盖。
- `/resume`：中断后从 `SUBTASKS_DONE` 续跑，lead 与已完成子任务不重跑。
- 沙箱：Docker 可用时工具可执行；不可用时启动无失败、工具组为空。
- 依赖树中无 `com.alibaba.cloud.ai`。
- Web / CLI 协议不变（`SseProtocol` 字面量不变）。

## 6. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Spring AI 2.0 API 变动超出预期 | 编译/行为回归 | 以 2.0.2 Javadoc + upgrade notes 为准，逐模块提交；每步跑聚焦测试 |
| Jackson 3 与 MyBatis-Plus/SAA 生态冲突 | 序列化异常 | 允许 Jackson 2/3 共存；仅迁移自有代码；必要时保留 `jackson2` 兼容模块 |
| 自研编排语义不等价 | `/resume`、流式、预算行为偏差 | 先写行为对照测试（lead/parallel/aggregate/resume/取消/超时），再替换实现 |
| agentscope runtime API 漂移 | 沙箱工具失效 | 固定 1.0.2；封装隔离；Docker 不可用时降级 |
| MCP 2.0 破坏性变更 | 外部工具接入失败 | 单独 P4.4 小步验证 server/client 两端 |
| Boot 4 生态（Flyway/MyBatis） | 启动失败 | P1 先 compile，P4 前跑上下文测试；必要时锁定兼容版本 |

## 7. 回滚策略

- 全程在 `refactor/spring-ai-2` 分支，`master` 不动。
- 回滚以分支为粒度：引擎与沙箱均为直接替换，不保留运行时 feature flag；如需回退，切回 master 或逐文件回滚。
- 若 P4 受阻，可保留 P1+P2+P3 成果（去 SAA + Boot 4 + Spring AI 2 基线），在分支上继续；不合并即不影响生产。

## 8. 工作量判断

- **P1**：小（版本与依赖切换 + JDK 25 工具链准备）。
- **P2**：大（约 700 行编排/流式/检查点重写 + 测试重写）。
- **P3**：中（约 18 个工具薄封装 + 单测）。
- **P4**：大（Jackson 3 + Spring AI 2 API 面 75 个文件，编译驱动）。
- **P5**：中（回归与真机验证）。

整体属于「一个独立里程碑」，不建议与业务功能混做。

## 实施进展（2026-10-25）

**阶段一：去 Spring AI Alibaba（保持 Boot 3.5 / Spring AI 1.1）**
- 移除 `spring-ai-alibaba-graph-core`：自研 `ParallelOrchestrationEngine` 接管执行/流式/续跑；
  检查点改落 `orchestration_checkpoint`（V41 迁移，阶段 `LEAD_DONE`/`SUBTASKS_DONE`/`DONE`）。
- 移除 `spring-ai-alibaba-sandbox`：`SandboxToolkit` 本地薄封装直接调用 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`
  （18 个工具，名称/描述/入参 schema/结果结构不变）。
- 删除 `spring-ai-alibaba-bom` 与版本属性；依赖树无 `com.alibaba.cloud.ai`。

**阶段二：Boot 4 / Spring AI 2 / Java 25 / Jackson 3**
- 根 pom：Boot 4.1.2、Spring AI 2.0.2、Java 25、MyBatis-Plus 3.5.17（boot4 starter + jsqlparser）、
  JLine 3.30.17、OkHttp 5.5.0（`okhttp-jvm`）、jsoup 1.22.1。
- OpenAI 接入迁移到官方 SDK（`com.openai.client.OpenAIClient`，经 `OpenAiClients` 统一构建
  OkHttp 传输/超时/关闭隐式重试）；Embedding 同源改造。
- `ChatClientRequestSpec.options(...)` 改为接收 Builder；`OpenAiChatOptions` 配置改用 `mutate()`/builder。
- 错误分类从 `NonTransientAiException` 改为 `OpenAIServiceException.statusCode()`。
- MCP SDK 2.1.0：`McpJsonMapper` 改由 `JacksonMcpJsonMapper` 提供；`JsonNode.fields()` → `properties()`。
- Jackson 3 全量迁移（server / channel-qq / cli / wms-mcp-adapter）。
- Boot 4 包迁移：`DataSourceProperties` → `org.springframework.boot.jdbc.autoconfigure`。
- JDK 25 安装于 `D:\env\jdk-25`（WSL `/mnt/d/env/jdk-25`），`run-wsl.sh` 的 `JAVA_HOME` 已指向该目录。

**验证（2026-10-25，MySQL 已恢复）**
- `mvn test`：server 678（含 `JavaHarnessApplicationTests`）/ channel-qq 72 / cli 28 / wms-mcp-adapter 43，全绿。
- 真机：
  - 单聊 `/api/chat`：真实模型调用成功，会话/观测落库正常；
  - COMPLEX 编排：路由 → 拆解 → 并行子任务 → 聚合，进度行/思考行/工具行正常；
  - 中断后 `/api/chat/resume` 从 `LEAD_DONE` 续跑：lead 不重跑、子任务补齐、聚合完成，meta SUCCEEDED；
  - CLI：非交互管道下单聊正常（JLine 3.30.17 + JDK 25）；
  - Web：Vite 页面可访问，Web 调用链 API（agents / sessions / messages / llm-calls / kb-retrievals）正常；
  - 无 Docker：沙箱工具面为空，应用启动与其余功能不受影响（按约定不做 Docker 验证）。
- 迁移中修复的运行时差异：
  1. OpenAI 官方 SDK 的 baseUrl 需包含版本前缀，已在 `OpenAiClients` 补齐 `/v1`（DB 中 api_url 沿用 1.x 根地址语义）；
  2. Boot 4 的 Flyway 自动配置拆到独立模块，补 `spring-boot-flyway` starter 后迁移才执行（V41 已落库）；
  3. `/resume` 入口原先调用的是全新执行方法，已改为 `Agent.resumeStreamReactive`（默认退化 + 编排器覆写）。
