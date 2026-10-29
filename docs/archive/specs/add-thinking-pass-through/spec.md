# 模型思考内容透传（web 端）Spec

## Why

`model_provider` 中仅 `qwen3.8-max`（researcher 执行模型）`disable_thinking=0`，其思考 token 正在真实发生、计费、并构成子任务耗时主体，但 `tokenStream` 的 `contentOf()` 只取文本、把流式 chunk metadata 中的 `reasoningContent` 静默丢弃。把已有思考旁路透出给 web 前端，可将「子任务执行时前端长时间无反馈的死等」变为可见进度，并顺带量化思考 token 占比——零模型配置改动、零额外成本。

显示范围按 **agent 表逐 agent 控制**（新增 `thinking` 列）：agent 行本来就是「角色 × 能力」的声明点（同 `knowledge`/`tools` 列先例），显示口径归 agent 表、思考发生口径归 model_provider 端点，各管各的。

## What Changes

- `AgentChatPipeline` 流式管道增加可选 `reasoningTap`（`Consumer<String>`）：在 ChatResponse 层读取 `AssistantMessage` metadata 键 `reasoningContent`，非空即回调；null 直通 = 全部既有调用点与行为零改动
- 思考增量经 `ProgressLine` 进度行编码（复用既有线协议与 `ChatServiceImpl` 的 `isProgress` 分流，天然不落入会话记忆与聚合内容）；stage 携带归属 agent（「思考 · agent名」「思考N · 专家名」「思考 · 聚合」），前端折叠块标题据此标注归属
- agent 表新增 `thinking` 列（TINYINT，NULL/0 = 不显示，1 = 显示）：**仅控制该 agent 的思考内容是否透传前端显示，并不是控制模型是否思考**（模型思考与否仍由 `model_provider.disable_thinking` 端点配置决定）；V33 迁移加列并将 general、researcher 行置 1（qwen3.8-max 链路立即可见），其余行为 NULL 可后续 UPDATE 调整
- **无 app.* 全局配置开关**：显示范围完全由 agent 表逐 agent 声明
- 接线判定：各接线点在流构建时查一次调用方 agent 的 `thinking` 配置（闭包持有布尔，不做 per-delta 查询），=1 才接线发射
- 路径 A 接线（简单路径主回答 + 专属 Agent 直答）：`executeStreamReactive` 流构建处按 agent 配置传入 tap，思考行合入浏览器 SSE 输出流（与既有「路由」进度行同通道）
- 复杂路径接线（聚合节点）：按 aggregator 行配置复用 `liveTokens` 旁路 sink 发射（`contentSent` 判定不受影响，进度行不置位）
- 复杂路径接线（子任务专家）：按 expert 行配置透出，思考增量带序号身份（stage=「思考N · 专家名」，N 为 1 基序号，与前端「第 N 个子任务」口径一致），经既有 `toolEvents` 同款旁路 sink 发射；后端合帧节流（积攒约 300ms 或阈值字符再 flush，流结束兜底 flush）控制行数量级，并行子任务思考行互可区分
- **范围外（明确不做）**：lead/路由判定的思考不透出（lead 行即使置 thinking=1，其调用在图节点阻塞路径内、无显示通道，天然无效）；QQ 渠道不接线（其回复走阻塞 `call`，tap 缺省 null，天然不受影响）；不改任何 `model_provider` 思考开关；`llm_call_log` 不改（usage 已含思考 token）
- 前端 `useChat`/`TraceView` 新增「思考」阶段渲染：按 stage 聚合为灰色折叠区（「思考」为主回答/聚合，「思考N」按子任务分组；默认折叠、可展开），不进入最终回答气泡

## Impact

- Affected specs: add-subagent-tool-packs（无依赖，仅同链路认知）、add-web-ui-features（前端轨迹渲染扩展）
- Affected code:
  - `server/src/main/java/com/dark/javaHarness/agent/AgentChatPipeline.java`（tokenStream/streamCore 增加 reasoningTap 参数与 metadata 提取；既有重载委托 null）
  - `server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java`（stream/streamWithAssembly 重载链透传 reasoningTap）
  - `server/src/main/java/com/dark/javaHarness/agent/GeneralAssistantAgent.java`（executeStreamReactive 按路径 A 接线）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/OrchestrationNodes.java`（聚合节点 + 子任务节点接线）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/`（新增包私有合帧节流器：零 Spring 装配，仿 AggregateStreamGuard 先例）
  - `server/src/main/java/com/dark/javaHarness/service/AgentConfigProvider.java` + AgentConfig record（新增 thinking 字段读取）
  - `server/src/main/resources/db/migration/V33__agent_thinking_column.sql`（新建：加列 + general/researcher 置 1）
  - `web/src/composables/useChat.ts`、`web/src/components/TraceView.vue`（「思考」/「思考N」折叠区渲染）
  - 测试：pipeline 提取单测（有/无 reasoning、content 不混入）、节流器单测（时间窗/字数阈值/终帧 flush）、thinking 列判定单测（1 接线/0 与 NULL 不接线）、子任务身份标签单测、思考行不落会话内容回归、前端 build
- 不改动：QQ 渠道（channel/qq）、SessionServiceImpl（角色过滤天然跳过进度行）、LlmCallRecorder/llm_call_log、预算与 watchdog 链路（tap 只读旁路）、model_provider 表与 ThinkingSwitchChatModel（端点口径原样）

## ADDED Requirements

### Requirement: agent 表 thinking 显示口径列

agent 表 SHALL 新增 `thinking` 列（TINYINT，可空），语义为**仅控制该 agent 的思考内容是否透传前端显示，并不是控制模型是否思考**（模型思考与否完全由 `model_provider.disable_thinking` 端点配置决定）；NULL/0 = 不透传，1 = 透传。V33 迁移 SHALL 加列并将 general、researcher 行置 1，列注释 SHALL 写明上述口径。

#### Scenario: thinking=1 显示

- **WHEN** agent 行 `thinking=1` 且该 agent 的模型产生思考流
- **THEN** 该 agent 的调用透出思考行至前端轨迹

#### Scenario: thinking=0/NULL 不显示

- **WHEN** agent 行 `thinking=0` 或 NULL，即使模型实际在思考（如 qwen3.8-max 端点默认思考）
- **THEN** 不接线 tap，无任何思考行产生（如实按配置静默）

#### Scenario: 判定一次闭包持有

- **WHEN** 流构建时完成接线判定
- **THEN** thinking 配置仅查询一次（tap 闭包持有布尔），不逐 delta 查库

### Requirement: 思考增量提取（管道层）

流式管道 SHALL 在 ChatResponse 层提取 `AssistantMessage` metadata 键 `reasoningContent`，非空白即回调 `reasoningTap`；tap 为 null 或提取异常时 SHALL 静默跳过，不影响内容 token 流、watchdog、预算记账与取消逻辑（tap 为只读旁路）。

#### Scenario: 有思考增量

- **WHEN** 模型（如 qwen3.8-max）流式 chunk 携带非空 `reasoningContent`
- **THEN** 每个增量回调 tap 一次，内容 token 流与返回的完整内容完全不含思考文本

#### Scenario: 无思考增量

- **WHEN** 思考被禁用的端点（`disable_thinking=1`，如 qwen3.8-27b）返回的 chunk 无 `reasoningContent`
- **THEN** tap 零回调，行为与现状逐字节一致

#### Scenario: 提取异常降级

- **WHEN** metadata 缺失或提取抛异常
- **THEN** 静默跳过该增量，主内容流不受影响

### Requirement: 进度行编码与会话隔离

思考增量 SHALL 以 ProgressLine 进度行形态进入输出流（主回答 stage=「思考 · agent名」，聚合 stage=「思考 · 聚合」，子任务 stage=「思考N · 专家名」）；`ChatServiceImpl` 的内容收集（会话记忆/会话摘要口径）SHALL 继续排除进度行——思考文本绝不落入 `session_messages`，也绝不进入聚合输入。

#### Scenario: 思考行不落库

- **WHEN** 一次带思考的直答完成并写回会话上下文
- **THEN** `session_messages` 快照仅含 user/assistant 正文，无「思考」行内容

### Requirement: 路径 A 接线（主回答 + 专属 Agent 直答）

`executeStreamReactive` 的流构建 SHALL 按调用方 agent 的 `thinking` 配置决定是否传入 reasoningTap，思考行与既有进度行同通道合入浏览器 SSE；QQ 渠道回复路径（阻塞 call）SHALL 不接线。

#### Scenario: 简单路径直答可见思考

- **WHEN** general 会话提问（general 行 thinking=1）且所配模型产生思考流
- **THEN** 浏览器轨迹区实时出现「思考」折叠行，最终回答内容不受影响

### Requirement: 复杂路径聚合接线

聚合节点的流式调用 SHALL 按 aggregator 行 `thinking` 配置复用 `liveTokens` 旁路 sink 发射思考进度行；`contentSent` 判定与 END 帧兜底逻辑 SHALL 不受进度行影响（进度行不置位 contentSent、不作为 final 内容）。

#### Scenario: 聚合思考可见

- **WHEN** aggregator 行 thinking=1 且聚合模型产生思考流
- **THEN** 浏览器轨迹区在「聚合」进度行附近实时出现思考增量，最终回答仍正常流式推送

### Requirement: 子任务思考透出（身份标识 + 合帧节流）

子任务节点的专家调用 SHALL 按 expert 行 `thinking` 配置传入 reasoningTap：思考增量以 stage=「思考N」（N = 槽位 idx+1，1 基）编码，经既有 `toolEvents` 同款旁路 sink 串行发射；SHALL 经合帧节流器控制行数量（积攒约 300ms 或阈值字符再 flush，流正常结束兜底 flush，异常中止允许丢弃缓冲）——思考行数量与 delta 帧数解耦；并行子任务的思考行 SHALL 互带各自序号可区分。

#### Scenario: 并行子任务思考可区分

- **WHEN** 2 个子任务（researcher，thinking=1）并行执行且均产生思考流
- **THEN** 输出流中出现「思考1」「思考2」两类行，各自归属明确、内容不串位

#### Scenario: 节流生效

- **WHEN** 某子任务思考流持续产生数千个 delta 帧
- **THEN** 实际发射的「思考N」行数量级为「数百行以内」（由时间窗/字数阈值决定），且流结束时缓冲残余被 flush

#### Scenario: 异常中止丢弃缓冲

- **WHEN** 子任务因超时/断连中止且节流器尚有未 flush 缓冲
- **THEN** 缓冲直接丢弃（思考为瞬时展示信息），不写占位、不影响超时/断连既有处理

### Requirement: 前端折叠渲染

前端 SHALL 将 stage 以「思考」开头（「思考 · agent名」「思考 · 聚合」「思考N · 专家名」）的行聚合渲染为独立折叠区（灰色弱化样式，默认折叠、可展开），「思考N · 专家名」按序号+专家分组对齐对应子任务；SHALL 不渲染进最终回答气泡；未知 stage 保持既有忽略行为。

#### Scenario: 折叠区交互

- **WHEN** 轨迹流中出现带序号的连续思考行
- **THEN** 按子任务分组展示「思考N」折叠入口，展开可见对应思考全文；回答内容独立展示

## Design Notes（非需求，实现参照）

- 提取点唯一：metadata 键名 `reasoningContent` 为 Spring AI 1.1.4 `OpenAiChatModel` 流式映射既有键（OpenAiChatModel.java:323），勿自造
- 路径 A 的 SSE 已有「路由」进度行先例（ChatServiceImpl:235/252），思考行与它同构
- 思考行进 `liveTokens`/`toolEvents` sink 前必须已编码为 ProgressLine（消费方按 raw 行处理，进度行由 ChatServiceImpl 分流）；`toolEvents` sink 的串行化锁（`BranchProgressListener.tryEmitSerialized`）直接复用
- 子任务序号 1 基（`idx+1`），与前端「第 N 个子任务完成」口径一致（节点名 subtask-{i} 是 0 基，勿混用）
- thinking 判定在接线点一次完成（查 AgentConfigProvider 既有读取链，闭包持有布尔）；lead/route-judge/memory-profile 等内部调用不在接线点或无 web 流通道，即使误配 thinking=1 也天然无显示
- 节流器定位：包私有小类、零 Spring 装配（仿 AggregateStreamGuard 先例），参数（时间窗/字数阈值）先内置常量不进 yaml（YAGNI，出现调优诉求再配置化）
- 主回答/聚合思考行不节流（delta 直发，与内容 token 同量级）；仅子任务（多流交织）节流
- V33 初始仅 general、researcher 置 1：general 对应主回答首见效场景，researcher 对应子任务长思考场景；aggregator 行是否置 1 留给用户按聚合模型实际思考情况 UPDATE
