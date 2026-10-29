# Tasks

- [ ] Task 1: V33 迁移 + 清理纳入
  - [ ] SubTask 1.1: 新建 `V33__kb_retrieval_log.sql`：建表（字段/索引见 spec）+ 文件头注释
  - [ ] SubTask 1.2: `ObservabilityLogCleaner` 纳入 kb_retrieval_log（同 retention 策略），补/扩单测
- [ ] Task 2: 观测组件
  - [ ] SubTask 2.1: 新增 `KbRetrievalLog` record + entity/mapper + `KbRetrievalRecorder`（null 零行为、异常吞掉 WARN 单行）
  - [ ] SubTask 2.2: `KnowledgeConfig` 装配 recorder；单测——正常落行、null 零行为、异常不抛
- [ ] Task 3: 采集接线（依赖 Task 1、2）
  - [ ] SubTask 3.1: `KnowledgeRetriever`：buildKnowledgeBlock/prefetch 签名扩展（source + turnId/traceId）；四类事件采集（cache_hit/inline_query/无命中/降级，零行为短路不记）
  - [ ] SubTask 3.2: `RagPrefetcher.submit` 加 source 参数，三提交点（ChatServiceImpl/OrchestrationNodes lead/subtask）各传各的；doPrefetch 完成与失败采集
  - [ ] SubTask 3.3: `AgentRequestSpecFactory.build` 调用点传 turnId/traceId（CallTrace 参数取值，null 安全）
  - [ ] SubTask 3.4: 单测——五类 source 行断言、cache_hit 行、降级行 ok=0、零行为短路不记行、签名扩展后检索行为回归
- [ ] Task 4: 全量验证
  - [ ] SubTask 4.1: `mvn -pl server -am test` 0 失败 0 错误

# Task Dependencies

- Task 3 依赖 Task 1、2
- Task 1 与 Task 2 相互独立，可并行
- Task 4 依赖 Task 1-3 全部完成
