# Tasks

- [x] Task 1: 检索秒级超时包裹（KnowledgeRetriever + 配置键）
  - [x] 1.1 KnowledgeProperties 新增 `searchTimeoutSeconds` 字段（int，默认 0 保持存量测试零回归；getter/setter 齐全，注释写明「0=关闭包裹直通现状」口径）
  - [x] 1.2 KnowledgeRetriever 构造器不变（props 已注入）；新增共享检索线程池（固定 2 线程 daemon 命名 `knowledge-search`，懒创建，Abort 拒绝策略）
  - [x] 1.3 buildKnowledgeBlock 中 `knowledgeService.search(query, kbs)` 调用点改造：timeoutSeconds>0 时提交线程池 + `Future.get(timeout, SECONDS)`，超时 `cancel(true)` + warn 日志（含 query 摘要与耗时）返回 null；异常 warn 返回 null；=0 时直通现状
  - [x] 1.4 测试（KnowledgeRetrieverTest）：① mock search 正常返回 → 命中渲染不变；② mock search 挂起（sleep 远大于测试超时值）→ 返回 null 且耗时秒级；③ timeoutSeconds=0 → 不走池直通；④ search 抛异常 → 返回 null
  - [x] 1.5 application.yaml `app.knowledge` 块新增 `search-timeout-seconds: 10`（注释含口径说明）
  - [x] 1.6 运行 `mvn -s .mvn/settings.xml test -Dtest=KnowledgeRetrieverTest` 全绿（19 例）

- [x] Task 2: 预取缓存与命中短路（KnowledgeRetriever）
  - [x] 2.1 新增嵌套 record `PrefetchedKnowledge(String query, List<String> kbs, String block)`（knowledge 包或 retriever 嵌套，public 可从 ChatServiceImpl 引用）
  - [x] 2.2 新增请求级缓存字段：`ConcurrentHashMap<String, PrefetchedKnowledge> prefetchCache`（sessionId 键，容量有界 MAX_SESSIONS=512 复用既有清空语义）
  - [x] 2.3 新增 public `prefetch(String agentName, String sessionId, String query, List<String> kbs)`：内部调 buildKnowledgeBlock（复用前置检查+超时包裹+预算截留），非 null 结果写入缓存；异常 catch debug 静默
  - [x] 2.4 buildKnowledgeBlock 开头加缓存判定：sessionId 非空 → cache 命中且 `query.equals(user) && kbs.equals(cached.kbs())` → 直接返回 block（debug 日志「预取命中」）；命中后保留缓存（同会话 lead 节点可复用）
  - [x] 2.5 测试（KnowledgeRetrieverTest 新增 5 例）：① prefetch 后同 query 同 kbs 调 buildKnowledgeBlock → 命中且 search 不再被调（verify 次数）；② query 不同（子任务描述）→ 不命中现查；③ kbs 顺序/内容不同 → 不命中；④ 预取无命中（null）不缓存；⑤ 缓存超限清空
  - [x] 2.6 运行 KnowledgeRetrieverTest 全绿（24 例）

- [x] Task 3: ChatServiceImpl judge/预取并行汇合
  - [x] 3.1 新增专属单线程 daemon 池（命名 `rag-prefetch`；knowledgeRetriever=null 不创建；每请求独立创建用完即弃 shutdown，生命周期自洽）
  - [x] 3.2 新增私有方法 `doPrefetch(message, sessionId)`：sessionAgentName → getAgentConfig → parseBinding(knowledge)；绑定非空时调 `knowledgeRetriever.prefetch(...)`；任何失败 catch 静默
  - [x] 3.3 同步入口 chat()：resolveAgent 之前提交预取 future；resolveAgent（judge 主线程零改动）之后 `awaitPrefetch`（2s 汇合窗口，超时 cancel 放弃）；try/finally 保证 pool.shutdown
  - [x] 3.4 流式入口 streamReactiveInternal 同样改造（flatMapMany 内 submit 在 resolveAgent 前、await 在后）
  - [x] 3.5 测试（ChatServiceImplTest 新增 3 用例）：① 同步/流式预取提交验证（verify prefetch 参数）；② knowledgeRetriever=null 不提交不建池（存量用例保持绿）；③ 预取异常不影响主流程；④ judge 异常兜底语义不变（既有用例回归）
  - [x] 3.6 运行 `mvn -s .mvn/settings.xml test -Dtest=ChatServiceImplTest` 全绿（33 例）

- [x] Task 4: 配置收尾与全量回归
  - [x] 4.1 docs/HARNESS_TODO.md L71-73 条目勾选 `- [x]` 并补完成说明；L73 顺带优化小项同步标注（并行汇合已随本次落地）
  - [x] 4.2 README 知识库小节补 `search-timeout-seconds` 参数说明 + 检索限时与预取行为说明
  - [x] 4.3 全量回归：`mvn -s .mvn/settings.xml test`（568 例，0 失败 0 错误 1 跳过，BUILD SUCCESS）
  - [x] 4.4 grep 校验无遗留：`search-timeout-seconds` 仅在 yaml（配置值）/绑定字段/文档注释出现；代码无 TODO/FIXME 残留
  - [x] 4.5 不 commit 不 push（等用户明示）

# Task Dependencies
- Task 1 → Task 2 → Task 3 → Task 4（严格串行：2 依赖 1 的超时包裹与前置检查复用；3 依赖 2 的 prefetch 入口；4 收尾回归）
- Task 1 的 1.1（配置字段）与 1.5（yaml 键）可并行编辑；Task 2 的测试与实现按 TDD 红绿节奏交替
