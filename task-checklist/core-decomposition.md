# 核心类职责拆分清单

- 范围：`ChatServiceImpl`、`SandboxToolkit`、`KnowledgeServiceImpl`、`ChatCli` 等上帝类
- 创建：2026-11-05
- 来源：知识图谱分析报告（`issue.md`）
- 状态：共 4 项，已完成 2 项，待办 2 项

## 背景

图谱中文件内符号数与扇出最高的几个类，同时承担过多职责。拆分目标是让每个类只有一个变化原因，行为保持不变。

## P0 主链路拆分

- [x] P0-1 `ChatServiceImpl` 拆成 route → execute → stream → persist 四段
  - 作用：当前一个类同时承担建档 / 路由判定 / 直答 / 编排 / SSE / 续跑 / 写回 / RAG / 限流 / 降级，24 条出边为全项目最高，是最大的单点风险
  - 位置：[ChatServiceImpl](../server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L112-L520)：`chat`、`streamReactive`、`resume`、`toSseBody`、`writeBackContext`、`resolveRouteWithPrefetch`、`decidePlan`
  - 做法：保留 `ChatService` 门面，仅做参数归一与流程编排；抽出 `RouteDecider`（判定 + 降级决策）、`ChatStreamPipeline`（SSE 组装与断连处理）、`TurnPersistence`（会话建档 / 写回 / 续跑状态）、`KnowledgePrefetcher`（RAG 预取）
  - 验收：`ChatServiceImpl` 行数下降 ≥40%，出边 ≤12；`ChatServiceImplTest` 与全量测试全绿；SSE 事件顺序、断连取消、续跑行为逐项不变
  - 结果（2026-11-05）：`ChatServiceImpl` 由 586 行收敛为 57 行门面（仅 `chat`/`streamReactive`/`resume` 三个分发，出边 7）；抽出 `RouteDecider`（判定 + 入口预取汇合）、`ChatTurnExecutor`（同步执行 + COMPLEX 降级重答）、`ChatStreamPipeline`（流式执行 / SSE 组装 / 断连 / 续跑）、`TurnPersistence`（建档 / 绑定 / 写回 / 续跑校验）、`ReplyDecorator`（后处理 / 语音 flag / RAG 出处），RAG 预取沿用既有 `RagPrefetcher` 共享池 bean；`ChatServiceImplTest` 47 条与全量 800 条全绿（含 Spring 上下文装配）。

## P1 能力组件拆分

- [x] P1-1 `SandboxToolkit` 按权限类别拆分
  - 作用：20 个 ToolCallback 集中在一个类，命令执行、文件读写、浏览器操作三类权限混在一起，权限最小化难以审查
  - 位置：[SandboxToolkit](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolkit.java#L53-L202)
  - 做法：拆为 `SandboxShellTools`（`runPythonCode` / `runShellCommand`）、`SandboxFileTools`（读写 / 列目录 / 搜索 / 移动）、`SandboxBrowserTools`（navigate / snapshot / click / type / pressKey / close）；公共参数解析保留在 `SandboxToolkit` 内部工具
  - 验收：3 个类各自只暴露本类别回调；`SandboxToolProvider` 装配结果与拆分前完全一致；相关单测全绿
  - 结果（2026-11-05）：拆为 `SandboxShellTools` / `SandboxFileTools` / `SandboxBrowserTools`，公共封装下沉到包内 `SandboxToolSupport`，删除 `SandboxToolkit`；`SandboxToolProvider` 装配顺序与工具面不变；`mvn -s .mvn/settings.xml -pl server -am test` 全绿。

- [ ] P1-2 `KnowledgeServiceImpl` 拆分摄取与检索
  - 作用：增量摄取、孤儿清理、台账分页与向量 + BM25 混合检索同处一个类（16 个符号），写路径与读路径的失败语义混在一起
  - 位置：[KnowledgeServiceImpl](../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java)
  - 做法：拆出 `KnowledgeIngestor`（sync / 增量判据 / 孤儿清理）与 `HybridKnowledgeRetriever`（向量 + BM25 + RRF）；`KnowledgeServiceImpl` 只保留对外门面与事务边界
  - 验收：读路径不依赖写路径实现；`KnowledgeServiceImplTest` / `KnowledgeRetrieverTest` 全绿；摄取幂等与检索结果排序不变

## P2 外围类收敛

- [ ] P2-1 `ChatCli` 拆分命令分发与渲染
  - 作用：13 个符号同时处理参数解析、SSE 消费、终端渲染与本地续跑状态
  - 位置：[ChatCli](../cli/src/main/java/com/dark/javaHarness/cli/ChatCli.java)、[ChatApiClient](../cli/src/main/java/com/dark/javaHarness/cli/api/ChatApiClient.java)
  - 做法：抽 `CommandDispatcher`（/help、/agent、/resume、/provider）与 `StreamRenderer`（逐帧渲染）；`ChatCli` 只负责启动与依赖组装
  - 验收：CLI 交互命令行为不变；`mvn -s .mvn/settings.xml -pl cli -am test` 全绿
