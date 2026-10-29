# RAG 检索轨迹日志 Spec

## Why

RAG 检索目前是观测盲区：仅 7 处 DEBUG/WARN 日志（生产 INFO 级不可见），llm_call_log 无 knowledge 维度——预取是否生效、命中短路是否发生、注入了哪几个库几条命中、降级了几次，全部不可验证。第一阶段/二阶段把预取做成了核心路径，恰恰最需要可观测。

## What Changes

- **BREAKING（方法签名，编译级非协议）**：`KnowledgeRetriever.buildKnowledgeBlock` / `prefetch` 增加 `source`（检索来源标记）与 `turnId`/`traceId`（轨迹归因，字符串类型不引 agent 包）参数；`RagPrefetcher.submit` 增加 `source` 参数（三个提交点各传各的）
- V33 迁移新建 `kb_retrieval_log` 表（结构/索引见下），纳入 `ObservabilityLogCleaner` 每日清理
- 新增 `KbRetrievalLog` record + mapper + `KbRetrievalRecorder` 观测组件（可 null = 单测/未装配零行为，对齐 LlmCallRecorder 先例）；KnowledgeConfig 统一装配
- KnowledgeRetriever 采集四类事件：预取命中短路（cache_hit）、现查成功（inline_query，含注入条数）、无命中（hit_count=0）、降级（ok=0 + error_msg）
- RagPrefetcher 采集预取事件：完成（含命中/无命中）与失败均落行，source 标记 entry_prefetch / lead_prefetch / subtask_prefetch
- 未绑定知识库 / aggregator 跳过 / query 过短等"零行为短路"不记行（避免噪声）

## Impact

- Affected specs: add-subtask-rag-prefetch（三提交点与短路语义不变，仅叠加采集）
- Affected code:
  - `server/src/main/resources/db/migration/V33__kb_retrieval_log.sql`（新建）
  - `server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java`（签名扩展 + 四类事件采集）
  - `server/src/main/java/com/dark/javaHarness/service/impl/route/RagPrefetcher.java`（submit 加 source + 采集）
  - `server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java`（buildKnowledgeBlock 调用点传 turnId/traceId，trace 参数就在手边）
  - `server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java`（入口预取提交点传 source）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/OrchestrationNodes.java`（lead/subtask 预取提交点传 source）
  - `server/src/main/java/com/dark/javaHarness/service/impl/observe/`（新增 KbRetrievalLog/KbRetrievalLogMapper/KbRetrievalRecorder）
  - `server/src/main/java/com/dark/javaHarness/config/knowledge/KnowledgeConfig.java`（recorder 装配）
  - `server/src/main/java/com/dark/javaHarness/service/impl/observe/ObservabilityLogCleaner.java`（纳入清理）
  - 测试：retriever 采集/prefetch 采集/cleaner
- 不改动：检索与短路语义、预取三提交点位置、主链路行为（采集为纯旁路，recorder 异常吞掉不阻断）、web UI 展示（后置）

## ADDED Requirements

### Requirement: 检索轨迹表

V33 SHALL 新建 `kb_retrieval_log` 表：id（PK 自增）、session_id（NOT NULL，索引）、agent_name、source（NOT NULL：entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit）、query（VARCHAR(512) 截断）、kbs（CSV）、hit_count（注入条数；cache_hit 为 NULL）、duration_ms、ok（默认 1）、error_msg、turn_id / trace_id（预取为 NULL，索引）、started_at（发起时刻，排序键）、created_at。`ObservabilityLogCleaner` SHALL 将本表纳入每日保留期清理（与 llm_call_log/tool_call_log 同 retention 策略）。

#### Scenario: 表创建与清理

- **WHEN** V33 应用且到达每日清理时刻
- **THEN** kb_retrieval_log 按 `app.observability.retention-days` 与其他观测表一并清理

### Requirement: 执行期检索采集

`KnowledgeRetriever.buildKnowledgeBlock` SHALL 在执行期（非预取）路径记录检索事件：预取命中短路 → source=cache_hit（duration≈0，hit_count=NULL）；真实检索 → source=inline_query（hit_count=预算截留后实际注入条数，duration_ms=检索耗时）；无命中 → inline_query 且 hit_count=0；超时/异常/池拒绝降级 → ok=0 + error_msg。检索发生时 SHALL 把调用方的 turnId/traceId 落入轨迹列（AgentRequestSpecFactory.build 的 trace 参数在手）。未绑定知识库、aggregator 跳过、query 过短等零行为短路 SHALL NOT 记行。

#### Scenario: 预取命中短路可见

- **WHEN** 子任务执行时命中入口预取的缓存
- **THEN** 落一行 source=cache_hit、turn/trace 为该次调用的轨迹值，可据此确认预取生效

#### Scenario: 现查与降级可见

- **WHEN** 执行期真实检索发生（或检索超时降级）
- **THEN** 落 inline_query 行（hit_count 与 duration 真实值；降级行 ok=0 + error_msg）

### Requirement: 预取采集

`RagPrefetcher` SHALL 按提交来源标记采集：三个提交点（入口/lead 出口/subtask 入口补提交）分别传 source=entry_prefetch / lead_prefetch / subtask_prefetch；`doPrefetch` 完成后落行（真实检索含 hit_count/duration；内部命中缓存短路记 cache_hit），失败落 ok=0 行。预取行 turn_id/trace_id 落 NULL（fire-and-forget 无轨迹上下文），按 sessionId + started_at 归档。

#### Scenario: 三来源区分

- **WHEN** 一次复杂请求触发入口预取与 lead 出口预取
- **THEN** 两行 source 分别为 entry_prefetch / lead_prefetch，可区分各提交点的实际执行情况

### Requirement: 观测旁路容错

`KbRetrievalRecorder` SHALL 满足：未装配（null）时零行为；任何采集异常（表缺失/DB 抖动）吞掉并以 WARN 单行记日志，绝不阻断检索与编排主链路。采集行 SHALL NOT 影响检索返回值与缓存语义。

#### Scenario: 采集异常不阻断

- **WHEN** kb_retrieval_log 表不可写（如迁移未应用）
- **THEN** WARN 单行日志，检索/短路/预取行为与无观测时完全一致

## MODIFIED Requirements

### Requirement: 检索入口签名扩展

`buildKnowledgeBlock(agentName, sessionId, user, kbs)` SHALL 扩展为 `buildKnowledgeBlock(agentName, sessionId, user, kbs, source, turnId, traceId)`：source 为调用方声明的来源标记（执行期调用方传 inline_query，预取传 prefetch: 前缀标记），turnId/traceId 为轨迹归因（预取场景传 null）。`prefetch` SHALL 同步扩展并以来源标记区分三个提交点。`RagPrefetcher.submit` SHALL 扩展 source 参数。既有调用点全部适配，短路/缓存/预算语义零变化。

#### Scenario: 签名扩展后行为不变

- **WHEN** 既有单测与主链路在签名扩展后运行
- **THEN** 检索返回值、缓存命中判定、预算截留行为逐字段一致（仅新增旁路采集）

## REMOVED Requirements

（无）
