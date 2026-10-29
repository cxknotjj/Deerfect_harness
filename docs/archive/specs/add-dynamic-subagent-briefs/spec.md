# 动态子 Agent：模板 × 任务书 Spec

## Why

当前编排子任务只传"子任务描述"（desc）给专家，desc 既要当标题、又要当检索 query、还要当执行输入——任务背景全靠专家猜。DeerFlow 2.0 的解法是：执行体收敛为少数预置模板，任务特异性由 Lead 现场生成的**自包含任务书**（brief）承载。本 spec 引入该模式，同时修复 wms 专家指派被白名单静默回退的现存不一致。

## What Changes

- Lead 拆解输出协议升级：`{"subtasks":[{"desc","agent","brief"}]}`——desc=短标题（进度行/RAG query/聚合摘要），agent=执行模板（agent 表专家行），brief=自包含任务书（目标/背景/约束/交付物）
- **BREAKING（lead 输出协议）**：lead prompt 的 JSON 格式说明新增 brief 字段；解析器向后兼容旧格式（无 brief → 行为与现状一致）
- `LeadOutputParser.Subtask` 增加 `brief` 字段；白名单补入 `wms`（修复 V26 之后"lead 指派 wms → 回退 general → 无 wms_* 工具"的静默回退缺陷）
- 新增状态键 `subtaskBrief_0..3`（K_SUBTASK_BRIEF_PREFIX）传递任务书，旧 checkpoint 缺键安全
- 子任务执行：user 文本 = brief 非空 ? brief : desc；fallbackSystem、工具装配、预算、记忆策略（子任务不注入会话记忆）零改动
- 任务书长度上限：`app.context.subtask-brief-max-chars`（默认 2000，0=不限制），超限截断 + warn 单行
- V31 迁移：lead prompt 输出格式说明升级为模板 + 任务书
- RAG 预取参数不变：query 保持 desc（短文本利于向量检索），agentName=resolvedExpert 口径不变

## Impact

- Affected specs: add-subtask-rag-prefetch（RAG 预取提交点与参数不变，仅确认兼容）、refactor-multi-agent-graph-agent（状态键扩展）
- Affected code:
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/LeadOutputParser.java`（Subtask record + 解析 + 白名单）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/MultiAgentGraphAgent.java`（状态键 + KeyStrategy）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/OrchestrationNodes.java`（lead 出口写 brief、executeSubtask user 选择、截断）
  - `server/src/main/java/com/dark/javaHarness/config/ContextBudgetProperties.java` + `application.yaml`（brief 上限配置）
  - `server/src/main/resources/db/migration/V31__lead_prompt_task_brief.sql`（新建）
  - 测试：LeadOutputParser/MultiAgentGraphAgent/OrchestrationNodes 相关单测
- 不改动：AgentChatCaller / CallSpecAssembler / AgentRequestSpecFactory（装配链路复用）、聚合节点（K_RESULT_PREFIX 输入不变）、RagPrefetcher / KnowledgeRetriever（预取链路参数不变）、进度行（沿用 desc）

## ADDED Requirements

### Requirement: 任务书状态传递

编排图状态 SHALL 新增 `subtaskBrief_{i}` 键（i ∈ [0, MAX_SUBTASKS)），lead 出口节点写入解析出的 brief，KeyStrategy 注册为 replace；旧 checkpoint 缺该键时 `orElse(null)` 安全回退，不报错。

#### Scenario: lead 出口写入 brief

- **WHEN** lead 拆解返回含 brief 的新格式子任务
- **THEN** 状态键 `subtaskBrief_{i}` 逐槽位写入 brief 原文，`subtask_{i}`（desc）与 `subtaskAgent_{i}` 写入逻辑不变

#### Scenario: 断点续跑旧 checkpoint

- **WHEN** 从 lead 已完成、brief 键不存在的旧 checkpoint 恢复编排
- **THEN** subtask 节点读到 null，执行退化为 desc 输入，编排正常完成

### Requirement: 任务书长度上限

系统 SHALL 通过 `app.context.subtask-brief-max-chars` 配置限制单条 brief 字符数（默认 2000，0=不限制；Java 字段缺省 0，yaml 显式配置 2000 作为部署默认）；超限时截断至上限并记录 warn 单行日志（含截断前后长度），不阻断编排。截断发生在 lead 出口写入状态键之前（写入即定稿，执行侧只读取）。

#### Scenario: brief 超限截断

- **WHEN** lead 输出的某条 brief 超过配置上限且上限 > 0
- **THEN** brief 被截断为前 N 字符写入状态，日志输出一行 warn，子任务继续执行

#### Scenario: 上限置 0

- **WHEN** `subtask-brief-max-chars: 0`
- **THEN** 不做任何截断，brief 原样传递

## MODIFIED Requirements

### Requirement: Lead 拆解输出协议（模板 × 任务书）

LeadOutputParser SHALL 解析新格式 `{"subtasks":[{"desc":"短标题","agent":"模板名","brief":"自包含任务书"}]}`：Subtask record 扩展为 `(desc, agent, brief)`；任务书 SHALL 为自包含说明（目标/背景/约束/交付物），不依赖会话历史——Lead 拆解时注入的会话记忆是唯一上下文来源。兼容性 SHALL 保持：旧格式 `{"subtasks":[{"desc","agent"}]}` 与 `{"subtasks":[".."]}` 解析为 brief=null，非法 JSON 回退空表现状不变。

#### Scenario: 新格式解析成功

- **WHEN** lead 返回 `{"subtasks":[{"desc":"调研竞品定价","agent":"researcher","brief":"目标：…\n背景：…\n约束：…\n交付物：…"}]}`
- **THEN** 解析出 desc/agent/brief 三字段，agent 按白名单归一化，brief 随状态键传递

#### Scenario: 旧格式兼容

- **WHEN** lead 返回不含 brief 的旧格式（对象或纯字符串数组）
- **THEN** brief=null，执行期 user 文本退化为 desc，与现状逐字段一致

#### Scenario: 非法 JSON

- **WHEN** lead 返回非法 JSON
- **THEN** warn 日志 + 回退空表，走既有单子任务兜底（现状不变）

### Requirement: 编排执行模板白名单

EXPERT_WHITELIST SHALL 为 `researcher/coder/analyst/writer/general/wms`：通用模板四件套 + general 兜底 + wms 领域模板（持有 wms_* MCP 工具）。不在名单中的模板名一律回退 null（执行走 general）的现状语义不变。

#### Scenario: lead 指派 wms

- **WHEN** lead 对仓储类子任务指派 `agent:"wms"`
- **THEN** 不再回退 general，子任务由 wms agent 表行执行（携带 wms_* MCP 工具链），与 V26 lead prompt 的意图对齐

#### Scenario: 未指派回退

- **WHEN** lead 输出旧格式无 agent 字段，或 agent 名不在白名单
- **THEN** resolvedExpert 回退 general，行为与现状一致

### Requirement: 子任务执行注入任务书

OrchestrationNodes.executeSubtask SHALL 以 brief 非空 ? brief : desc 作为模型调用的 user 文本；fallbackSystem（subtaskPersona）、工具装配、ContextBudget 预算、maxTokens expert 档、MemoryPolicy（子任务不注入会话记忆——与 DeerFlow 2.0 Sub-agent 上下文隔离同语义，现状已满足）全部零改动。

#### Scenario: brief 注入执行

- **WHEN** 子任务槽位存在非空 brief
- **THEN** chatCaller.call 的 user 参数为 brief（可含截断），system 段与工具面同现状

#### Scenario: 无 brief 执行

- **WHEN** 子任务槽位 brief 为 null 或空白
- **THEN** user 参数为 desc，与现状一致

### Requirement: lead prompt 任务书迁移（V31）

V31 迁移 SHALL 以 REPLACE 幂等写法更新 agent 表 lead 行 prompt：JSON 格式说明升级为含 brief 字段，要求 brief 为自包含任务书（目标/背景/约束/交付物四要素），保留"至多 4 条、禁止凑数"约束与模板清单（含 wms）。

#### Scenario: 迁移幂等

- **WHEN** V31 重复执行（或目标子串已被替换）
- **THEN** REPLACE 不再变更，迁移静默通过

### Requirement: RAG 预取参数不变

子任务 RAG 预取 SHALL 保持 query=desc、agentName=resolvedExpert 口径不变（lead 出口主提交点与 subtask 入口补提交均不动）——desc 仍是短标题，适合向量检索；brief 长文本不参与预取。

#### Scenario: 预取照常工作

- **WHEN** lead 出口对含 brief 的子任务提交预取
- **THEN** 预取请求参数与现状完全一致，KnowledgeRetriever 复合缓存键命中逻辑不受影响

## 后续演进（不在本 spec 范围）

wms 作"领域模板"是刻意保留的过渡态（白名单一行即让编排可用）。下一阶段 **工具包机制**（见 `add-subagent-tool-packs` spec）：lead 拆解输出增加 `toolPacks` 可选字段，按子任务挂载领域能力（wms 行 tools 列复用为包定义），通用模板执行任意领域子任务——对齐 DeerFlow 2.0"能力收敛到工具，不靠角色膨胀"。届时本 spec 补入白名单的 wms 模板在编排侧自然退化（保留向后兼容，直答路径不受影响）。

## REMOVED Requirements

（无）
