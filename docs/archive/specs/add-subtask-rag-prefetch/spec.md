# 编排子任务 RAG 并行预取 Spec

## Why

编排路径（COMPLEX）下，专家子任务的 RAG 检索发生在「轮到自己执行时」同步现查（每次限时 10s），而子任务受 `subtask-concurrency` 限流会在闸门前排队——排队空档被白白浪费。把子任务检索提前到「节点入口、排队/执行之前」异步并行完成，可将检索延迟完全藏进既有等待窗口，用户感知更快；复用既有共享预取池与静默降级语义，不引入新配置与新的失败面。

## What Changes

- **KnowledgeRetriever 预取缓存改多条目**：缓存键从 `sessionId`（单条目）改为 `sessionId + query` 复合键，入口预取（lead，query=用户消息）与多个子任务条目（query=子任务文本）同会话共存互不覆盖；有界容量 + 超限整体清空语义沿用既有先例
- **RagPrefetcher 显式化 + bean 化**：预取提交统一为显式签名 `submit(agentName, sessionId, query)`（kb 绑定解析内聚：agentService 查 config → parseBinding）；ChatServiceImpl 入口路径改为自行解析会话 agent 名后调用同一显式入口（外部行为不变）；RagPrefetcher 从 ChatServiceImpl 私有对象上移为可注入 bean，供编排侧复用同一共享池
- **子任务节点入口预取钩子**：`OrchestrationNodes.subtask` 在槽位校验通过后、专家 LLM 调用之前，异步提交该子任务的 RAG 预取（agentName=实际执行专家名，与执行时 forAgent 同源；query=子任务文本；sessionId=编排会话）；fire-and-forget，任何失败静默
- **接线**：`ChatAgentConfig` 注入 RagPrefetcher 并沿 `MultiAgentGraphAgent → OrchestrationNodes` 构造链传递；knowledgeRetriever 缺位时传 null，钩子零行为
- 不新增任何配置键；不新增 SSE 进度行（观测沿用既有 debug 日志：预取命中/退化现查）

## Impact

- Affected specs: `add-rag-bypass-timeout-prefetch`（既有入口预取机制，本 spec 扩展其覆盖范围至编排子任务；其 checklist 中「专家节点不命中缓存、按子任务精准现查」行为升级为「预取后命中」）
- Affected code:
  - `server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java`（缓存键）
  - `server/src/main/java/com/dark/javaHarness/service/impl/route/RagPrefetcher.java`（显式入口 + bean 化）
  - `server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java`（入口路径适配）
  - `server/src/main/java/com/dark/javaHarness/config/agent/ChatAgentConfig.java`（装配）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/MultiAgentGraphAgent.java`、`OrchestrationNodes.java`（钩子 + 传递）
  - 测试：`KnowledgeRetrieverTest`、`ChatServiceImplTest`、`MultiAgentGraphAgentTest`
  - 文档：`docs/data-flow.md`、`server/.../db/migration/V12__agent_knowledge_column.sql`（列注释与实际语义相反，顺带修正）
  - 迁移：`V29__bind_expert_knowledge.sql`（专家测试绑定，幂等）

## ADDED Requirements

### Requirement: 子任务批量预取（拆解后提交 + 节点入口补提交）

编排 SHALL 在 **lead 节点出口**（子任务列表解析、状态键写出之后，扇出之前）对全部已布置槽位并行提交 RAG 预取——此为主提交点，使检索延迟消化在子任务并发限流的排队空档内（graph-core ParallelNode 先获取信号量许可再执行节点方法体，节点入口无法覆盖排队期，故主提交点必须在 lead 出口）。同时 subtask 节点入口 SHALL 保留轻量补提交，覆盖**断点续跑**路径（lead 不重跑）。预取参数与执行完全同源（agentName=实际执行专家名含回退口径，query=子任务文本，sessionId=编排会话）；同会话同参数的重复提交幂等（复合缓存键，结果一致）。所有提交 fire-and-forget，任何失败静默。

#### Scenario: 已绑定专家的子任务预取生效

- **WHEN** lead 拆解出子任务且指派专家（如 researcher）的 agent 行 `knowledge` 列非空
- **THEN** lead 出口即对每个子任务提交预取（早于扇出与限流排队），该专家执行时 `buildKnowledgeBlock` 缓存命中（检索不再现查），回答照常注入【出处N】知识段

#### Scenario: 未绑定知识库的专家零行为

- **WHEN** 指派专家的 `knowledge` 列为空（含未指派走回退专家的情况）
- **THEN** 预取解析 kb 绑定为 null → 不发起任何检索，执行期 `buildKnowledgeBlock` 照常跳过（行为与现状一致）

#### Scenario: 预取失败静默降级

- **WHEN** 预取池饱和（DiscardPolicy 拒绝）、检索超时/异常、或 KnowledgeRetriever 缺位（ragPrefetcher=null）
- **THEN** 该子任务退化为执行期现查或无知识段，编排主流程零感知、零报错；SSE 进度行与现状完全一致

#### Scenario: 断点续跑同样覆盖

- **WHEN** 编排经检查点续跑（lead 不重跑，直接进入 subtask 节点）
- **THEN** subtask 节点入口补提交生效，续跑子任务同样预取

### Requirement: 专家测试知识库绑定

系统 SHALL 提供幂等迁移（V29）为编排专家子任务绑定既有知识库，使预取链路可端到端验证：`coder → java`（单库绑定）、`researcher → java,frontend`（多库绑定）；`analyst`/`writer` 刻意保持未绑定，作为「未绑定专家零行为」的运行时对照组。knowledge/ 已有现成子库（java/frontend，git 跟踪），无需新建库与文档。

#### Scenario: 绑定迁移幂等

- **WHEN** 迁移 V29 在已有绑定的库上重复执行（或全新空库首建）
- **THEN** 绑定结果确定（目标专家 knowledge 列为指定值），不产生重复行/报错；不影响其他 agent 行（general/wms/lead 等保持未绑定）

### Requirement: 多条目预取缓存

KnowledgeRetriever 的预取缓存 SHALL 以 `sessionId + query` 为键支持同一会话多条目共存（入口预取与各子任务预取互不覆盖），命中条件保持「query 一致 + kb 绑定列表一致」；缓存有界（沿用超限整体清空先例），预取无命中/异常不写缓存。

#### Scenario: 入口与子任务条目共存

- **WHEN** 同一会话先发生入口预取（query=用户消息），随后 lead 拆解出 2 个子任务并行预取（query=各自子任务文本）
- **THEN** 三个条目共存；lead 节点、两个子任务节点执行时各自缓存命中，`knowledge-search` 检索池对同一 query 不重复调用

## MODIFIED Requirements

### Requirement: 入口预取（源自 add-rag-bypass-timeout-prefetch）

ChatServiceImpl 的入口预取 SHALL 保持外部行为不变（judge 与预取并行、2s 汇合窗口、超时放弃），仅将实现适配到 RagPrefetcher 显式签名：会话 agent 名解析留守 ChatServiceImpl，kb 绑定解析内聚 RagPrefetcher。

#### Scenario: 入口路径回归

- **WHEN** 简单/复杂请求到达（同步与流式两入口）
- **THEN** 预取提交时机、汇合语义、`app.knowledge.enabled=false` 时零行为等与现状完全一致（ChatServiceImplTest 存量用例全绿）

## REMOVED Requirements

（无移除项）
