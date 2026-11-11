# 知识图谱分析报告

> 分析对象：Deerfect Harness（java-harness）
> 图谱基线：提交 `322c6a2`，1696 节点 / 2901 边 / 10 层 / 12 步导览
> 统计口径：基于 `file:` 级节点的 `imports` 边（跨模块、跨层均按文件归属统计）；测试覆盖为文件级 `tested_by` 关系，非行覆盖率
> 时点说明：图谱统计（节点/边数、类名、符号数）为该提交快照，`SandboxToolkit` 等文件在后续重构中已拆分；文中测试数已按当前主干更新为 1162，其余数字不随代码回溯。

## 摘要

架构方向正确、模块边界清晰、失败处理成熟，但 `server` 内部的包级分层和几个上帝类会成为长期维护的主要成本来源。

---

## 一、高风险模块（按爆炸半径排序）

| 风险级 | 模块 / 文件 | 依据 | 建议关注点 |
|---|---|---|---|
| P0 | [ChatServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 全项目扇出最高（24 条出边、0 条入边），一个类同时承担建档 / 路由判定 / 直答 / 编排 / SSE / 续跑 / 写回 / RAG / 限流 / 降级；`complex` | 任一环节异常直接影响主链路，优先拆分职责 |
| P0 | [ParallelOrchestrationEngine.java](../../server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java) + [MultiAgentGraphAgent.java](../../server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java) + [OrchestrationNodes.java](../../server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java) | Graph 路径核心，三者互相依赖形成导入环；涉及并发子任务、检查点续跑、聚合降级，状态机复杂 | 子任务失败隔离、续跑一致性、聚合降级边界 |
| P1 | [SandboxToolProvider.java](../../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java) + `SandboxToolkit.java`（已拆分为 File/Shell/Browser 工具与 `SandboxGuards`） | 容器沙箱 = 任意命令 / 文件 / 浏览器权限；20 个 ToolCallback 集中在一个类；无 Docker 时静默降级为空工具面 | 权限最小化、降级可观测性、工具面按类别拆分 |
| P1 | [McpToolProvider.java](../../server/src/main/java/com/dark/javaHarness/tool/mcp/McpToolProvider.java) + [ToolAssignments.java](../../server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java) | 外部 MCP 服务懒连接、失败隔离；工具权限边界（agent 表 `tools` 列）在此收口，配置错即越权或空工具；与两个 Provider 构成导入环 | 工具名与注册名一致性校验、启动期一次性告警 |
| P2 | [LlmCallRecorder.java](../../server/src/main/java/com/dark/javaHarness/observability/LlmCallRecorder.java) | 异步落库且「异常只告警」，观测数据可能静默丢失；被 20 个文件引用 | 有界队列 + 丢弃 / 失败指标，避免排障数据缺失 |
| P2 | [KnowledgeServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java) | 增量摄取 + 孤儿清理 + 向量 / BM25 混合检索，写路径有数据一致性风险（16 个符号） | 摄取幂等、孤儿清理事务边界、检索失败隔离 |
| P2 | [OneBotEventServiceImpl.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java) | 渠道入口扇出 15，外部 OneBot 协议 + 长连接状态 | 事件重放 / 断连恢复 / 消息去重 |

---

## 二、耦合最严重的文件

### 双向枢纽（同时高扇入 + 高扇出）

| 文件 | 扇入 | 扇出 | 备注 |
|---|---|---|---|
| [ToolAssignments.java](../../server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java) | 18 | 7 | 处于导入环，权限装配与工具实现纠缠 |
| [MultiAgentGraphAgent.java](../../server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java) | 4 | 22 | 编排器同时依赖引擎、节点与工厂 |
| [PromptAssembler.java](../../server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java) | 17 | 5 | 提示装配成为多路径公共依赖 |
| [KnowledgeRetriever.java](../../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java) | 15 | 5 | RAG 入口被多路径复用 |

### 单向下游巨口（改一处牵连多）

| 文件 | 扇出 | 说明 |
|---|---|---|
| [ChatServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 24 | 依赖面最广，任何下游签名变化都会波及 |
| [AgentScopeGeneralAgent.java](../../server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java) | 22 | ReAct 执行器依赖消息、工具、观测多个子系统 |
| [AgentRequestSpecFactory.java](../../server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java) | 21 | 请求规格组装横跨 prompt / 工具 / 检索 |

### 高扇入基座（动它就全站回归）

| 文件 | 扇入 |
|---|---|
| [AgentService.java](../../harness-api/src/main/java/com/dark/javaHarness/contract/api/AgentService.java) | 25 |
| [ChatClientRegistry.java](../../server/src/main/java/com/dark/javaHarness/config/agent/ChatClientRegistry.java) | 24 |
| [SessionService.java](../../harness-api/src/main/java/com/dark/javaHarness/contract/api/SessionService.java) | 23 |
| [Goal.java](../../harness-api/src/main/java/com/dark/javaHarness/contract/model/Goal.java) | 20 |

### 「上帝文件」（文件内符号数最多）

| 文件 | 符号数 |
|---|---|
| `SandboxToolkit.java`（当时，已拆分） | 20 |
| [client.ts](../../web/src/api/client.ts) | 18（整个前端的 HTTP + SSE 都在一个文件里） |
| [KnowledgeServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java) | 16 |
| [ChatServiceImpl.java](../../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 15 |
| [ChatCli.java](../../cli/src/main/java/com/dark/javaHarness/cli/ChatCli.java) | 13 |

### 导入环（2 处）

1. `McpToolProvider` ↔ `SandboxToolProvider` ↔ `ToolAssignments`
2. `ParallelOrchestrationEngine` ↔ `MultiAgentGraphAgent`

> 环为文件级 `imports` 关系推导，属于结构信号，不等同于运行时循环依赖。

---

## 三、分层评估

### 模块级：干净

跨模块导入只有 3 个方向，没有反向依赖：

| 方向 | 导入边数 |
|---|---|
| `server → harness-api` | 155 |
| `channel-qq → harness-api` | 21 |
| `cli → harness-api` | 11 |

- `wms-mcp-adapter` 完全独立（零跨模块依赖，75% 文件级测试覆盖）
- `web` 与后端零编译期耦合
- `channel-qq` / `cli` 严格只依赖契约层

### server 内部包级：分层失效

- `server-runtime ↔ agent-orchestration` 双向依赖（111 / 50 条跨层导入），`service` / `controller` 与 `agent` / `graph` 边界被打穿
- `tool` 包自成一个环，权限装配（`ToolAssignments`）与工具实现纠缠
- 观测（`LlmCallRecorder` 20 条入边）、提示装配、配置 Bean 散布在各层，没有稳定的横切层
- `harness-api` 同时装领域模型 + 服务接口 + DTO（35 个文件），契约与领域未拆分
- `web` 仅 10.6% 生产文件有测试关系，且 API 客户端单体化

### 文件级测试覆盖（按模块）

| 模块 | 已测试 / 生产文件 | 占比 |
|---|---|---|
| wms-mcp-adapter | 6 / 8 | 75.0% |
| server | 85 / 195 | 43.6% |
| cli | 3 / 8 | 37.5% |
| channel-qq | 7 / 22 | 31.8% |
| harness-api | 5 / 35 | 14.3% |
| web | 5 / 47 | 10.6% |

> 口径为图中 `tested_by` 边覆盖，非行覆盖率。

---

## 四、综合评价

### 优点

1. 多模块边界纪律好：渠道 / CLI 只依赖 `harness-api`，没有反向耦合，WMS 适配器独立部署
2. 失败处理意识强：Graph 聚合降级、子任务失败占位、沙箱不可用降级、向量库故障不阻断主链路
3. 测试体量与覆盖面在同类 Demo 中偏上：1162 个测试（`server` 968 例），`server` 文件级覆盖 43.6%，核心编排 / 工具类大多带 `tested` 标签
4. 可观测性成体系：四张观测表 + Prometheus + traceId + JSON 日志

### 主要问题

1. 核心类职责过载：`ChatServiceImpl` 一个类串起 9 项职责，是最大的单点风险
2. `server` 内部包级分层失效：双向依赖 + 2 个导入环，重构时容易连环回归
3. 契约层欠测试：`harness-api` 仅 14.3%，而它是 25 / 23 扇入的基座
4. 前端是短板：`client.ts` 单体 + 10.6% 覆盖 + 12 个 `complex` 文件
5. 异步观测静默失败：`LlmCallRecorder` 只告警不重试，排障数据可能缺失

### 优先级建议

1. 把 `ChatServiceImpl` 拆成 `route → execute → stream → persist` 四段流水线，保留薄门面
2. 用 ArchUnit 规则禁止 `tool` / `graph` 包环，并把 `ToolAssignments` 抽出接口
3. 拆分 `harness-api`：`domain` 与 `contract` 分开，补契约层测试
4. `LlmCallRecorder` 改有界队列 + 丢弃指标；`client.ts` 按领域拆分
5. 对 `server` 中 61 个 `complex` 文件做一次复杂度热点收敛（优先 `graph` / `tool` / `service`）

---

## 附录：图谱统计

- 节点 1696：file 437、function 795、class 352、document 59、config 24、table 16、service 7、pipeline 6
- 边 2901：contains 1156、imports 828、exports 358、tested_by 213、documents 107、calls 96、depends_on 53、configures 33、related 25、implements 13、defines_schema 13、migrates 4、deploys 2
- 复杂文件分布：`server` 61、`web` 12、`docs` 7，其余模块合计 14
