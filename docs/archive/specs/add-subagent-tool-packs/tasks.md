# Tasks

- [x] Task 1: LeadOutputParser 解析 toolPacks 字段
  - [x] SubTask 1.1: `Subtask` record 增加 packs 字段（List&lt;String&gt;），解析可选 `toolPacks` 数组（trim/去空/保序），缺省空名单
  - [x] SubTask 1.2: 单测——toolPacks 解析、缺省空名单、旧格式（第一/两阶段前）兼容不变
- [x] Task 2: 包解析器（编排层）
  - [x] SubTask 2.1: 包解析依赖注入 OrchestrationNodes（实施偏离：注入 AgentConfigProvider 而非 AgentService——新增 `findToolPack` 单查询同时取 tools/prompt/is_internal，避免扩 AgentConfig record；已按 Provider 经 MultiAgentGraphAgent 构造透传）
  - [x] SubTask 2.2: 解析逻辑——包资格（is_internal=0 且 tools 非空）→ 工具名单（tools 列 CSV）+ 纪律段（行 prompt 原文）；非法包名/无工具/解析异常 warn 单行跳过
  - [x] SubTask 2.3: 单测——合法包、非法包名跳过、is_internal=1 行拒绝、解析异常静默（AgentConfigProviderTest 8 用例）
- [x] Task 3: 工具面并入通道（装配层）
  - [x] SubTask 3.1: `ToolAssignments.forNames(packName, declaredCsv)` 按名单解析回调（复用 resolveDeclared 引擎，含 MCP 工具）
  - [x] SubTask 3.2: Assembly 增加 extraToolNames 字段，CallSpecAssembler.assemblyForRole 新增 5 参重载透传（既有 4 参委托 null）；build() 在 disableTools 短路后、装饰链前并入包工具（按 getToolDefinition().name() 去重，模板自身工具优先）；AgentChatCaller 新增 10 参 call 重载（既有重载委托，调用点零改动）
  - [x] SubTask 3.3: 单测——并入去重与顺序（ToolAssignmentsTest.forNames）、未声明时性状不变、装饰链覆盖包工具、disableTools 短路不受影响
- [x] Task 4: 编排接线（状态键 + 执行注入）
  - [x] SubTask 4.1: MultiAgentGraphAgent 新增 `K_SUBTASK_PACKS_PREFIX = "subtaskPacks_"`（CSV），KeyStrategy 注册 replace；lead 出口写入 packs（空名单写空串，读侧 orElse("") 归一化）
  - [x] SubTask 4.2: executeSubtask 读 packs → resolvePacks（Task 2）→ 纪律段 `【领域规范·包名】` 追加 user 文本 → extraToolNames 经 PackResolution 小 record 传入 Assembly
  - [x] SubTask 4.3: 单测——状态键读写、旧 checkpoint 缺键安全、纪律段拼入、空名单性状不变
- [x] Task 5: V32 迁移 lead prompt 工具包引导（独立可并行）
  - [x] SubTask 5.1: 新建 `V32__lead_prompt_tool_packs.sql`：REPLACE 幂等——模板清单移除 wms 专家引导（V26 逆操作）、新增 toolPacks 用法说明（锚点特意延伸至插入点之后保证真幂等）
- [x] Task 6: 子任务数量上限配置化（依赖 Task 4，同文件 OrchestrationNodes）
  - [x] SubTask 6.1: ContextBudgetProperties 新增 `subtaskMaxCount`（缺省 0），application.yaml 配 `subtask-max-count: 4`；MultiAgentGraphAgent.MAX_SUBTASKS 注释更新为"兜底常量"
  - [x] SubTask 6.2: OrchestrationNodes lead 出口生效值解析 resolvedMaxSubtasks()（clamp [1,4]，0/超界回退 4）替换硬编码上限；KeyStrategy 槽位 0..3 不变
  - [x] SubTask 6.3: 单测——配置下调生效（3 条拆 2 条）、缺省/超界回退 4、现状回归（配置 4 = 4 条全落）
- [x] Task 7: 子任务墙钟超时（依赖 Task 4，同文件 OrchestrationNodes）
  - [x] SubTask 7.1: ContextBudgetProperties 新增 `subtaskWallClockSeconds`（缺省 0 = 不限制），application.yaml 配 `subtask-wall-clock-seconds: 600`
  - [x] SubTask 7.2: subtask 节点预包装独立 BooleanSupplier（断连 OR 超时，绝不置位共享 AtomicBoolean 防连坐）；命中写独立占位常量 TIMEOUT_SKIPPED_RESULT + warn 单行；aggregate 独立 timedOut 计数 + 【超时说明】前置段（与预算 degradationNote 互不掺混），全失败兜底判定补 timedOut==0
  - [x] SubTask 7.3: 单测——超时中止写占位、聚合注入超时说明、0=不限制行为不变（delayedFluxOf 拖慢 + 限并发 1 串行确定）
- [x] Task 8: 全量验证
  - [x] SubTask 8.1: `mvn -pl server -am test` 0 失败 0 错误（659 tests, 0 failures, 0 errors, BUILD SUCCESS）

# Task Dependencies

- 前置：add-dynamic-subagent-briefs spec 全部完成（Subtask record 扩展与状态键先例）
- Task 4 依赖 Task 1、2、3
- Task 6、Task 7 依赖 Task 4（同文件 OrchestrationNodes，串行实施避免冲突）；二者可互相并行
- Task 5 无依赖，可与 Task 1-4 并行
- Task 8 依赖 Task 1-7 全部完成

# 实施备注

- findToolPack 放 AgentConfigProvider（新增 record ToolPackDef）而非 AgentService 接口——单查询取 tools/prompt/is_internal，避免扩 AgentConfig record，偏离 spec 初稿"AgentService 透传"表述
- 装配层扩参统一采用"新重载 + 旧签名委托 null"：AgentChatCaller 既有 call 调用点、Assembly 直构点仅 2 处编译适配（GeneralAssistantAgent、LlmRequestLogAdvisorChainTest 补 null）
- 空工具面兜底 warn 落在 AgentRequestSpecFactory.build()（packCallbacks 为空时 warn 单行），补于实施收尾核验时
- 超时说明独立成段（TIMEOUT_SKIPPED_RESULT 常量 + aggregate timedOut 计数），不复用预算 degradationNote——两通道同构但文案互不掺混
