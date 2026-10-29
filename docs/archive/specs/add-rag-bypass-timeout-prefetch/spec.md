# RAG 旁路超时治理与 RouteJudge 并行预取 Spec

对应 HARNESS_TODO「RAG 旁路超时治理」（L71-73，2026-09-12 tavily e2e 发现）及后续讨论收敛的并行预取方案。

## Why

RAG 检索链路（嵌入调用 + pgvector 查询 + BM25）无显式超时：外部依赖挂起时（实测 DashScope 无响应 + PG 不可达），请求线程在「工具分配后、LLM 发起前」被拖最长 16 分钟，多请求还会在同一释放点扎堆。同时 RouteJudge 判定（LLM 秒级）与 RAG 检索（嵌入+PG）**无数据依赖**（都只吃用户原始消息）却串行执行，白白叠加等待。

## What Changes

- **检索秒级超时**：`KnowledgeRetriever.buildKnowledgeBlock` 内的 `knowledgeService.search()` 包裹到独立守护线程池执行，`Future.get` 秒级限时（`app.knowledge.search-timeout-seconds`，默认 10，0=关闭包裹直通现状）；超时/异常 → warn 日志 + 返回 null，走既有「静默降级不阻断主链路」语义
- **预取缓存短路**：`KnowledgeRetriever` 新增请求级预取缓存（sessionId 键，命中条件 = **query 相同 && kb 绑定列表相同**），新增 `prefetch()` 入口提前完成检索；`buildKnowledgeBlock` 开头命中缓存则短路不再触发 search——专家节点 user 是子任务描述（query 不同）天然不命中，仍按子任务精准现查
- **judge/预取并行汇合**：`ChatServiceImpl` 同步与流式两入口，先向专属单线程池提交预取任务，再在主线程跑 RouteJudge（**judge 语义零改动**：异常兜底 SIMPLE 的既有 catch 保留），judge 结束后小幅等待预取结果汇合；预取失败/超时静默 null
- 配置收尾：application.yaml `app.knowledge.search-timeout-seconds` 键；HARNESS_TODO L71-73 勾选；README 检索参数说明
- **不做**：judge future 再套超时（judge 既有超时语义已兜底）；Assembly/Goal/图 state 透传预取（缓存方案替代，改动面最小）；embed→search 内部依赖链并行化（串行数据依赖，TODO 明令禁止）；CLI 通道预取（不经 ChatService，但超时治理在 KnowledgeRetriever 层同样生效）

## Impact

- 受影响 specs：enhance-knowledge-base（检索参数家族扩展一个键，无冲突）
- 受影响代码：
  - `knowledge/KnowledgeRetriever.java`：超时包裹 + 预取缓存 + `prefetch()` 入口（本 spec 核心落点）
  - `knowledge/KnowledgeService.java` / `KnowledgeServiceImpl.java`：**不改**（超时治理在 retriever 层，service 保持纯检索语义）
  - `config/KnowledgeProperties.java`：新增 `searchTimeoutSeconds` 绑定字段
  - `service/impl/ChatServiceImpl.java`：两入口并行汇合 + 专属单线程池生命周期
  - `application.yaml`、`docs/HARNESS_TODO.md`、`README.md`
  - 测试：`KnowledgeRetrieverTest`、`ChatServiceImplTest`
- 明确不改：`AgentRequestSpecFactory.build()` 调用点签名（缓存短路发生在 `buildKnowledgeBlock` 内部，装配管线无感知）、`AgentChatCaller`、`GeneralAssistantAgent`、`MultiAgentGraphAgent`

## ADDED Requirements

### Requirement: 检索秒级超时与静默降级

系统 SHALL 对 `buildKnowledgeBlock` 触发的知识检索（含混合检索双路）施加独立秒级超时：检索在专属守护线程池执行，超过 `app.knowledge.search-timeout-seconds` 未完成时中断并降级。降级 SHALL 复用既有语义——返回 null（不注入知识块），绝不抛异常阻断聊天主链路。

#### Scenario: 嵌入接口挂起时秒级降级
- **WHEN** 嵌入端点无响应（挂起超过 search-timeout-seconds）
- **THEN** 检索在超时后被打断，返回 null，聊天请求正常走无知识块的降级回答，全程秒级（而非分钟级阻塞）

#### Scenario: 超时开关关闭
- **WHEN** `app.knowledge.search-timeout-seconds=0`
- **THEN** 不做线程池包裹，检索直通现状同步执行（行为与治理前逐字节一致）

#### Scenario: 正常检索不受影响
- **WHEN** 检索在时限内正常完成
- **THEN** 返回结果与治理前完全一致（命中渲染、出处透出、预算截留逻辑零变化）

### Requirement: 预取缓存与命中短路

`KnowledgeRetriever` SHALL 提供 `prefetch(agentName, sessionId, query, kbs)` 入口：执行一次完整检索（复用 `buildKnowledgeBlock` 全部前置检查与超时包裹）并写入请求级缓存（sessionId 键，容量有界、超限整体清空，仿 recentSources MAX_SESSIONS=512 先例）。`buildKnowledgeBlock` SHALL 在检索前查缓存：同 sessionId 且 **query 与当前 user 完全相等** 且 **kb 列表与当前绑定完全相等** 时直接返回缓存知识块，不再触发嵌入与向量查询。

#### Scenario: 路径 A 命中短路
- **WHEN** 入口预取（query=用户消息，kb=入口 agent 绑定）后，路径 A general 以同一 user、同一 kb 绑定进入 `buildKnowledgeBlock`
- **THEN** 缓存命中，不再发起嵌入调用与 pgvector 查询，注入内容与预取块一致

#### Scenario: 编排专家不命中（query 不同）
- **WHEN** 编排路径专家节点以子任务描述作为 user 进入 `buildKnowledgeBlock`（预取 query 是用户原始消息）
- **THEN** query 不相等，缓存不命中，按子任务描述正常现查（节点内精准检索语义保留）

#### Scenario: kb 绑定不同不命中
- **WHEN** 当前节点 kb 绑定与预取时的绑定列表不一致（含顺序差异）
- **THEN** 缓存不命中，正常现查

#### Scenario: 无命中预取不缓存
- **WHEN** 预取检索返回 null（无命中/前置检查不过/超时降级）
- **THEN** 不写缓存，后续调用照常现查

### Requirement: RouteJudge 与 RAG 预取并行汇合

`ChatServiceImpl` 的同步 `chat()` 与流式 `chatStream()` 入口 SHALL 先提交预取任务到专属单线程守护池，再同步执行 RouteJudge 判定（主线程），judge 完成后汇合预取结果（小幅等待后仍未完成则取消并放弃）。RouteJudge 自身语义 SHALL 零改动：判定结果路由、异常兜底 SIMPLE/会话 Agent、日志格式均保持现状。

#### Scenario: 并行节省串行等待
- **WHEN** 用户发起一次绑定知识库 agent 的聊天
- **THEN** 嵌入+PG 检索在 judge LLM 调用期间并行执行，总耗时 ≈ max(judge, RAG) 而非两者之和

#### Scenario: judge 异常兜底不变
- **WHEN** RouteJudge 抛出异常
- **THEN** 兜底走会话绑定 Agent（既有 resolveAgent catch 语义），预取结果照常汇合使用

#### Scenario: 预取失败静默
- **WHEN** 预取任务异常或超时未完成
- **THEN** 静默放弃（缓存无值），后续 `buildKnowledgeBlock` 正常现查，主链路零感知

#### Scenario: 知识库禁用零开销
- **WHEN** `app.knowledge.enabled=false`（knowledgeRetriever 为 null）
- **THEN** 不提交预取任务、不建预取线程池，行为与现状一致

### Requirement: 配置与可观测

超时与预取参数 SHALL 全部经 application.yaml 配置（禁止 Java 类硬编码数值）：`app.knowledge.search-timeout-seconds`（默认 10，0=关闭包裹）。预取/超时/降级事件 SHALL 有日志可见（检索超时 warn 含耗时；预取成功 debug；预取放弃 debug）。

#### Scenario: 配置生效
- **WHEN** 修改 `app.knowledge.search-timeout-seconds` 并重启
- **THEN** 超时行为按新值执行（启动/检索日志可见当前值）
