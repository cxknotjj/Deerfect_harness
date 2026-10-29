# Tasks

- [x] Task 1: KnowledgeRetriever 预取缓存多条目改造（复合键）
  - [x] 1.1 缓存键 `sessionId` → `sessionId + '|' + query`（query 沿用既有 strip 归一口径；键为 null/空白 sessionId 时维持现状不缓存）；命中条件改为「键存在 && kbs.equals(cached.kbs())」
  - [x] 1.2 容量语义沿用「有界 + 超限整体清空」先例（条目口径取代会话口径，容量常量 512 保持，注释更新说明复合键口径）
  - [x] 1.3 测试（KnowledgeRetrieverTest）：① 同会话入口 query + 2 个子任务 query 三条目共存、各自命中（verify search 调用次数）；② kbs 不同不命中；③ 未命中/异常不写缓存（存量回归）；④ 容量清空后现查
  - [x] 1.4 `mvn -B -pl server -am test -Dtest=KnowledgeRetrieverTest` 全绿

- [x] Task 2: RagPrefetcher 显式入口 + bean 化
  - [x] 2.1 提交签名显式化：`submit(agentName, sessionId, query)`（kb 绑定解析内聚：agentService.getAgentConfig(agentName) → parseBinding；解析 null → 不入池无事发生）；`doPrefetch` 的 sessionAgentName 解析职责移出
  - [x] 2.2 ChatServiceImpl 适配：入口路径先 `sessionAgentName(sessionId)` 解析 agent 名再调显式签名（agentName=null 时跳过提交）；外部行为（提交时机、2s 汇合、enabled=false 零行为）逐项保持
  - [x] 2.3 bean 化：RagPrefetcher 注册为 Spring bean（构造依赖 KnowledgeRetriever + AgentService；knowledgeRetriever=null 时 bean 为 null 或空实现，消费方 null 安全），ChatServiceImpl 改注入使用
  - [x] 2.4 测试：ChatServiceImplTest 存量回归（两入口 + enabled=false + judge 异常兜底）；新增 RagPrefetcherTest（显式提交解析/未绑定跳过/异常静默）
  - [x] 2.5 `mvn -B -pl server -am test -Dtest='KnowledgeRetrieverTest,ChatServiceImplTest,RagPrefetcherTest'` 全绿

- [x] Task 3: 编排接线与子任务入口钩子
  - [x] 3.1 `ChatAgentConfig` 注入 RagPrefetcher，沿 `new MultiAgentGraphAgent(...)` 构造链传递（新增构造参数，knowledgeRetriever 缺位传 null）
  - [x] 3.2 `OrchestrationNodes` 接收 ragPrefetcher（构造参数，null 安全）；`subtask` 节点在 task 槽位校验通过后、专家解析与 LLM 调用之前提交预取：agentName=与执行时同源解析的专家名、query=task 文本、sessionId=state 的 K_SESSION_ID
  - [x] 3.3 null 安全：ragPrefetcher=null（知识库禁用）时钩子零行为，编排流程与现状逐字节一致
  - [x] 3.4 测试（MultiAgentGraphAgentTest）：① 已布置槽位触发预取提交（verify agentName/query/sessionId）；② 未布置空槽位不提交；③ ragPrefetcher=null 零行为；④ 编排结果与进度行为回归（存量用例全绿）
  - [x] 3.5 `mvn -B -pl server -am test -Dtest='MultiAgentGraphAgentTest,OrchestrationNodes 相关测试'` 全绿
  - [x] 3.6 主提交点上移 lead 出口：`OrchestrationNodes.lead` 在子任务列表解析、状态键写出之后，对全部已布置槽位批量提交预取（agentName 用 resolvedExpert 同源口径、query=子任务文本）；subtask 入口钩子保留（覆盖断点续跑）；测试补「lead 出口提交时序早于子任务执行/检索」与「重复提交幂等（同复合键）」断言，存量用例回归

- [x] Task 4: 专家测试知识库绑定迁移（V29，原 V28 撞孤儿版本号已重命名）
  - [x] 4.1 新增 `V29__bind_expert_knowledge.sql`：`UPDATE agent SET knowledge='java' WHERE agent_name='coder'`；`UPDATE agent SET knowledge='java,frontend' WHERE agent_name='researcher'`（带存在性 WHERE，幂等可重跑；文件头注释说明对照组设计：analyst/writer 刻意不绑）
  - [x] 4.2 验证：本地库执行 flyway 迁移后 `SELECT agent_name, knowledge FROM agent` 断言 4 个专家绑定值正确、general/wms 等未受影响（可用 mysql 客户端或集成测试校验）——mysql/mysql.exe 客户端均不可用，DB 断言延后至全量回归（JavaHarnessApplicationTests 触发 flyway 实际应用）与后续 e2e 核验
  - [x] 4.3 全新空库口径核对：V29 不依赖 V25/V27 数据（UPDATE 0 行不报错）

- [x] Task 5: 文档同步与全量回归
  - [x] 5.1 `docs/data-flow.md` 知识检索/编排小节补子任务预取时序说明（节点入口提交、排队空档消化检索延迟、静默降级口径）
  - [x] 5.2 修正 `V12__agent_knowledge_column.sql` 列注释：NULL/空白 = 未绑定不检索（与 parseBinding 实际语义对齐；仅改注释不改结构，不动 flyway 校验和）
  - [x] 5.3 全量回归：`mvn -B -pl server -am test`（0 失败 0 错误，BUILD SUCCESS）
  - [x] 5.4 grep 校验：无新增硬编码数值/配置键；无 TODO 残留；未执行任何 git commit/push（等用户明示）
  - [x] 5.5 e2e 验收发现修复：toSseBody 的 meta.sources 由 Flux 组装期求值改为 meta 发射期求值（Flux.defer）——编排路径检索发生在执行期（组装之后），组装期求值恒取空；ChatServiceImplTest 34 例回归全绿，e2e 实测 meta.sources 透出绑定库命中

# Task Dependencies
- Task 1 与 Task 2 相互独立可并行（1 改缓存键，2 改预取器入口）
- Task 3 依赖 Task 2（钩子调用显式签名与 bean 装配）
- Task 4 独立（纯数据迁移，可与 1/2/3 并行）
- Task 5 依赖 Task 1/2/3/4 全部完成（收尾文档 + 全量回归）
