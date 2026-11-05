# 韧性与可观测性清单

- 范围：异步观测、沙箱 / MCP 降级、知识摄取一致性、渠道连接韧性、工具标识校验
- 创建：2026-11-05
- 来源：知识图谱分析报告（`issue.md`）
- 状态：共 5 项，已完成 3 项，待办 2 项

## 背景

高风险模块普遍已有降级逻辑，但降级本身缺少指标与一次性校验：「失败但不影响主链路」的同时，也可能「失败且无人知道」。本清单目标是让降级可观测、可告警。

## P1 观测与降级可见性

- [x] P1-1 `LlmCallRecorder` 改有界队列并上报丢弃
  - 作用：异步落库遇到异常只告警，队列积压或失败时观测数据会静默丢失，排障时误以为没有调用
  - 位置：[LlmCallRecorder](../server/src/main/java/com/dark/javaHarness/observability/LlmCallRecorder.java)
  - 做法：改为有界队列 + 明确的拒绝策略；新增 `llm_log_dropped_total` / `tool_log_dropped_total` 指标并在日志中带丢弃计数
  - 验收：队列打满或写库失败时指标增长；主链路行为不变；`LlmCallRecorderTest` 覆盖拒绝与失败路径
  - 结果（2026-11-05）：改为单线程 + 容量 1024 有界队列，打满按 `kind=llm/tool` 计入 `harness.observability.dropped`，丢弃日志每 100 条节流；新增队列打满用例；`mvn -s .mvn/settings.xml -pl server -am test` 793 条全绿。

- [x] P1-2 沙箱不可用降级可观测
  - 作用：无 Docker 时工具面静默降级为空，Agent 只会「不会用工具」，外部看不出原因
  - 位置：[SandboxToolProvider](../server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java)
  - 做法：降级时打一条 WARN（含原因）并暴露健康状态；每次 agent 装配记录「沙箱工具 0 个」的可观测事件
  - 验收：无 Docker 环境下启动即可看到明确降级日志与指标；有 Docker 时行为不变
  - 结果（2026-11-05）：初始化/超时/浏览器组降级后同步 `harness.sandbox.tools` 与 `harness.sandbox.browser.tools` gauge（0=降级），保留原有降级 WARN；新增降级指标用例；`mvn -s .mvn/settings.xml -pl server -am test` 全绿。

- [x] P1-3 工具标识配置一致性一次性校验
  - 作用：agent 表 `tools` 列出现未识别 token 时逐请求重复 WARN，且 Agent 实际拿不到该工具，配置与运行结果不一致
  - 位置：[ToolAssignments.forAgent / forNames](../server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java#L132-L245)、[ChatAgentConfig](../server/src/main/java/com/dark/javaHarness/config/agent/ChatAgentConfig.java)
  - 做法：装配期或首次加载时对 `tools` 列做一次全量校验，汇总输出未知 token；运行期不再重复告警；未知 token 记录到指标
  - 验收：未知 token 只告警一次并汇总；工具装配结果不变；补充单测覆盖「含未知 token」场景
  - 结果（2026-11-05）：首次解析时按 agent 汇总输出未登记 token，后续请求不再重复；新增 `harness.tool.unavailable_tokens`（kind=unregistered/unavailable，按 agent+token 只计一次）；新增去重用例；`mvn -s .mvn/settings.xml -pl server -am test` 全绿。

## P2 一致性与渠道韧性

- [ ] P2-1 知识摄取写路径一致性
  - 作用：增量摄取 + 孤儿清理存在部分成功风险，失败后可能留下孤儿记录或半更新台账
  - 位置：[KnowledgeServiceImpl](../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java)、[KnowledgeRetriever](../server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java)
  - 做法：明确事务边界（单文档摄取为最小事务单元）；孤儿清理与台账更新同批提交；失败时记录可重试状态而不是静默跳过
  - 验收：模拟中途失败后重跑可收敛到与全量重建一致的结果；补充摄取失败 / 重试测试

- [ ] P2-2 `channel-qq` 事件处理韧性
  - 作用：OneBot 外部协议 + 长连接状态，断连或重复投递时缺少统一的重放 / 去重约定
  - 位置：[OneBotEventServiceImpl](../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java)
  - 做法：按消息 id 做幂等去重；断连恢复后不回放已处理事件；失败事件记录并可观测
  - 验收：重复投递同一事件只处理一次；断连恢复不重复回复；补充幂等与恢复用例
