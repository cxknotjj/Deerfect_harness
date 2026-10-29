# 子任务工具包挂载 Spec

## Why

领域能力扩展目前靠"专家行膨胀"：每个领域要同步 agent 行、lead prompt 清单、代码白名单三处（wms 已出现 V26 与白名单不同步的教训）。DeerFlow 2.0 的哲学是能力收敛到工具：lead 拆解时按子任务**挂载领域工具包**，通用模板即可执行领域子任务。本 spec 为"动态子 Agent"第二阶段，依赖并叠加于 `add-dynamic-subagent-briefs`（模板 × 任务书）之上。

## What Changes

- Lead 拆解输出协议新增可选字段 `toolPacks`（包名数组）：`{"subtasks":[{"desc","agent","brief","toolPacks":["wms"]}]}`
- **BREAKING（lead 输出协议）**：lead prompt 的模板清单移除 wms 专家指派引导，改为工具包用法说明（解析器保留 wms 白名单容错，向后兼容）
- 工具包定义 = agent 表行：**包名 = is_internal=0 且 tools 列非空的行名**；包内容 = 该行 tools 列（工具名单）+ 该行 prompt（领域纪律段）
- Assembly 扩展 `extraToolNames` 通道：子任务执行时把包工具并入工具面，**在装饰链之前**并入（统一受 ToolBudgetDecorator 预算约束）；按名去重
- 领域纪律段拼入子任务 user 文本（brief 之后，`【领域规范·包名】` 前缀），不触碰 system 装配
- 新增状态键 `subtaskPacks_0..3`（CSV 字符串）传递包名单，旧 checkpoint 缺键安全
- 包解析失败兜底：包名无效/行无工具/工具回调解析为空（如 wms-mcp-adapter 未启动的空工具面缓存）→ warn 单行（包名 + 期望/实得数），静默降级不阻断编排
- V32 迁移：lead prompt 增补 toolPacks 用法（何时挂包、至多挂载引导），REPLACE 幂等
- 子任务数量上限配置化：`app.context.subtask-max-count`（对齐"预算数字只进配置"惯例；可下调 1~4，0/超界回退内置兜底 4——拆解数量不存在"不限制"的安全语义，KeyStrategy 槽位保持 0..3 不变）
- 子任务墙钟超时：`app.context.subtask-wall-clock-seconds`（yaml 默认 600，0=不限制；对齐 DeerFlow 2.0 的单 sub-agent 15 分钟硬顶）——deadline 包装取消令牌在 token 边界中止在途调用，命中写占位结果 + warn 单行，聚合注入超时降级说明

## Impact

- Affected specs: add-dynamic-subagent-briefs（前置依赖：Subtask record 扩展模式与状态键先例）
- Affected code:
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/LeadOutputParser.java`（toolPacks 字段解析）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/MultiAgentGraphAgent.java`（K_SUBTASK_PACKS_PREFIX + KeyStrategy）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/OrchestrationNodes.java`（lead 出口写 packs、executeSubtask 读包 → 解析 → 纪律段拼 user）
  - `server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java`（Assembly 加 extraToolNames，build() 并入包工具）
  - `server/src/main/java/com/dark/javaHarness/agent/CallSpecAssembler.java`（assemblyForRole 透传 extraToolNames）
  - `server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java`（按名单解析 API `forNames`，复用既有按名查找）
  - `server/src/main/java/com/dark/javaHarness/config/ContextBudgetProperties.java` + `application.yaml`（`subtask-max-count`、`subtask-wall-clock-seconds`）
  - `server/src/main/java/com/dark/javaHarness/agent/orchestrate/MultiAgentGraphAgent.java`（MAX_SUBTASKS 转兜底常量，注释更新）
  - `server/src/main/resources/db/migration/V32__lead_prompt_tool_packs.sql`（新建）
  - 测试：parser/状态键/executeSubtask/Assembly 并入/空工具面降级/数量配置化/墙钟超时
- 不改动：ToolBudgetDecorator 及装饰链（包工具统一装饰，预算口径不变）、RAG 预取链路（kb 绑定仍按执行模板行解析）、聚合节点（超时占位复用既有降级说明通道）、直答路径（wms 专属绑定会话仍跳过编排直答）

## ADDED Requirements

### Requirement: 工具包定义与解析

系统 SHALL 以 agent 表行定义工具包：包资格为 `is_internal=0` 且 tools 列非空；包工具名单 = 该行 tools 列解析结果，包纪律段 = 该行 prompt 原文。解析 SHALL 静默容错：包名无效、行无工具、回调解析为空均记 warn 单行并跳过，不阻断编排。

#### Scenario: 合法包解析

- **WHEN** 子任务声明 `toolPacks:["wms"]` 且 wms 行存在（is_internal=0、tools 列含 10 个 wms_* 工具）
- **THEN** 解析出 10 个工具名单与 wms 行 prompt 纪律段

#### Scenario: 空工具面降级

- **WHEN** 包声明合法但 wms-mcp-adapter 未启动，McpToolProvider 缓存空工具面导致回调解析为 0
- **THEN** warn 单行日志（包名 + 期望/实得数），子任务按无包工具继续执行，不阻断编排

#### Scenario: 非法包名

- **WHEN** lead 声明了不存在的包名（如 `"erp"`）
- **THEN** warn 单行 + 跳过该包，其余包与子任务正常执行

### Requirement: 包工具并入执行工具面

Assembly SHALL 新增 `extraToolNames` 字段（List&lt;String&gt;，可空）；AgentRequestSpecFactory.build() SHALL 在 disableTools 短路之后、装饰链之前，将包工具按名解析并入工具回调列表（与模板自身工具按回调名去重），装饰链对所有工具统一生效。

#### Scenario: 包工具并入

- **WHEN** 子任务以 general 模板执行且声明 wms 包
- **THEN** 该次调用的工具面 = general 模板自身工具 + wms_* 10 个工具（去重），经装饰链后注入请求

#### Scenario: 预算统一约束

- **WHEN** 挂载包工具的子任务运行
- **THEN** tool-call-limit（8）与 tool-result-budget（5000）对所有工具（含包工具）统一生效，无豁免

#### Scenario: 未声明包

- **WHEN** 子任务未声明 toolPacks（旧格式或空数组）
- **THEN** extraToolNames 为空，工具面与第一阶段性状完全一致

### Requirement: 领域纪律段注入 user 文本

OrchestrationNodes.executeSubtask SHALL 把已解析包的纪律段以 `【领域规范·包名】` 前缀追加到 user 文本（brief/desc 之后、多包按声明顺序）；不修改 system 装配与 fallbackSystem 语义。

#### Scenario: 纪律段随包注入

- **WHEN** 子任务挂载 wms 包执行
- **THEN** user 文本 = 任务书 + `【领域规范·wms】` + wms 行 prompt（含三数量口径等回答纪律）

### Requirement: 包名单状态传递

编排图状态 SHALL 新增 `subtaskPacks_{i}` 键（CSV 字符串，i ∈ [0, MAX_SUBTASKS)），KeyStrategy 注册 replace；lead 出口写入解析出的包名单，旧 checkpoint 缺键安全回退为空。

#### Scenario: 断点续跑旧 checkpoint

- **WHEN** 从无 packs 键的旧 checkpoint 恢复
- **THEN** subtask 节点读到空名单，按无包执行，编排正常完成

### Requirement: 子任务数量上限配置化

系统 SHALL 通过 `app.context.subtask-max-count` 配置 lead 拆解数量上限：配置生效值 clamp 到 [1, 4]；0 或超界（>4）回退内置兜底 4——拆解数量不存在"不限制"的安全语义（无界拆解 = 无界成本），故本键不适用"0=不限制"类约定。KeyStrategy 槽位保持 `subtask*0..3` 不变（上限不可高于 4）。Java 字段缺省 0，application.yaml 显式配置 4 作为部署默认（行为与现状一致）。

#### Scenario: 配置下调生效

- **WHEN** `subtask-max-count: 2` 且 lead 返回 3 条子任务
- **THEN** 仅前 2 条落状态槽位（subtask_0/1），第 3 条丢弃，编排按 2 条执行

#### Scenario: 缺省与非法值回退

- **WHEN** 配置缺省（字段 0）或配置 8（超界）
- **THEN** 生效值为内置兜底 4，与现状行为一致

### Requirement: 子任务墙钟超时

系统 SHALL 通过 `app.context.subtask-wall-clock-seconds` 为单个子任务执行设墙钟上限（yaml 默认 600 秒，0=不限制）。实现 SHALL 复用既有取消令牌机制：deadline 包装 `cancelled` 布尔供给（`cancelled.get() || 已超时`），在流式 token 边界中止在途调用；命中后该子任务写占位结果（与预算超限占位同通道）+ warn 单行（子任务序号、耗时、上限），聚合节点据此注入超时降级说明，其余子任务与聚合照常执行。

#### Scenario: 子任务超时中止

- **WHEN** 某子任务执行超过墙钟上限且上限 > 0
- **THEN** 在途调用在下一个 token 边界中止（零额外 HTTP），该槽位写超时占位结果，warn 单行，聚合照常汇总并注入超时说明

#### Scenario: 超时不限制

- **WHEN** `subtask-wall-clock-seconds: 0`
- **THEN** 不设 deadline，行为与现状完全一致（由编排 token 总账与流式空闲超时间接约束）

## MODIFIED Requirements

### Requirement: Lead 拆解输出协议（第二阶段扩展）

LeadOutputParser SHALL 解析可选字段 `toolPacks`（字符串数组，trim 去空白去空项，保持声明顺序）；字段缺省解析为空名单，旧格式兼容性不变（继承第一阶段）。解析器不校验包有效性（解析器保持纯静态，校验在编排层）。

#### Scenario: toolPacks 解析

- **WHEN** lead 返回 `{"subtasks":[{"desc":"查询在库库存","agent":"general","brief":"…","toolPacks":["wms"]}]}`
- **THEN** Subtask 携带 packs=["wms"]，随状态键传递

#### Scenario: 无 toolPacks 字段

- **WHEN** lead 返回第一阶段格式（无 toolPacks）
- **THEN** packs 为空名单，行为与第一阶段完全一致

### Requirement: lead prompt 工具包引导（V32）

V32 迁移 SHALL 以 REPLACE 幂等写法更新 lead 行 prompt：模板清单中移除 wms 专家指派引导，新增工具包说明——需要仓储数据等领域能力时为子任务声明 `toolPacks`（现可用包：wms），通用模板 + 包即可执行领域子任务；保留"至多 4 条、禁止凑数"与任务书四要素约束。

#### Scenario: 迁移幂等

- **WHEN** V32 重复执行（或目标子串已替换）
- **THEN** REPLACE 不再变更，迁移静默通过

### Requirement: wms 模板容错保留（向后兼容）

EXPERT_WHITELIST SHALL 保留 wms（第一阶段已补入）：lead 若仍以专家名指派 wms（旧习惯输出），子任务继续由 wms 表行执行；编排主引导已转向"通用模板 + 工具包"，两条路径行为均正确。

#### Scenario: 旧习惯指派 wms

- **WHEN** lead 输出 `agent:"wms"`（无 toolPacks）
- **THEN** 子任务由 wms 表行执行（携带 wms_* 工具），与第一阶段行为一致

## REMOVED Requirements

（无）
