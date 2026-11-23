# 模型上下文窗口滑块 · 实施计划书

> 状态：已实施（2026-11-19；见 `ContextUsageRing.vue` 与 `PUT /api/admin/models/{id}/context-window`）　日期：2026-11-19
>
> 参考交互：设置页内一个绿色渐变的滑轨 + 白色圆形拇指，居中标注「600K 上下文 · 适中」。
>
> 结论先行：方向合理，推荐做**模型能力滑块**，但必须先明确「上下文」指什么——否则只是把
> `capabilities` JSON 换个皮肤，运行时行为不会变。

---

## 一、需求理解

用户目标：在 Web 端可视化地设置「模型上下文」，拖动滑块即可，带档位/程度文案。

关键语义选择：

| 理解 | 含义 | 是否推荐 |
|---|---|---|
| A. 模型能力窗口 `context_window` | 该模型技术上能接收的最大上下文（8K～1M 量级） | **推荐作为首期** |
| B. 单次请求输入预算 `history-budget` 等 | 实际装进 prompt 的历史/工具结果额度（现为 4000～12000 量级） | 不作为首期；数值量级和 600K 不匹配，直接暴露会误导 |
| C. 两者都做 | 滑块设能力，另在对话页展示"本会话有效预算" | 建议作为二期 |
| D. 编排总消费预算 | graph 全程累计 token 上限（现 `orchestration-budget: 60000`） | 已存在，**不是窗口**；二期可加第二个滑块 |

推荐方案：**滑块写入模型 `capabilities.context_window`，守卫落在"每次 LLM 调用"上**。
只改配置不联动运行时=假功能；只联动不提供编辑入口=现状。

### 为什么不做"全局总上下文"守卫

graph 路径下上下文是**按节点分配**的：lead / 各专家 / 聚合各自是一次独立 LLM 调用，
各自带自己的 prompt 预算；把窗口当成"整条编排的总输入"去裁剪，既不符合模型协议
（窗口约束的是单次请求），也会让节点间互相挤占、行为不可解释。

项目里其实已经有两条正交的预算轴：

| 轴 | 语义 | 现状落点 | 与窗口的关系 |
|---|---|---|---|
| per-call 输入预算 | 单次请求装多少历史/工具结果/子任务书 | agentic：history；graph：lead / aggregate / expert 各自预算 | **窗口约束这一轴**，逐调用生效 |
| per-orchestration 消费总量 | 整条编排累计消耗多少 token（含输出） | `orchestration-budget`，每次调用发起前经 BudgetLedger 门控 | 与窗口无关，是成本/防失控策略 |

因此**一套 per-call 守卫即可同时覆盖 agentic 与 graph**，不需要两套窗口语义；
"总量"诉求由已有 `orchestration-budget` 承担，二期若要做成滑块，它是全局策略开关，
不挂在某个模型上。

---

## 二、现状与约束（已按代码核对）

| 项 | 现状 | 证据 |
|---|---|---|
| 存储 | `llm_model.capabilities` JSON 已含 `context_window`；种子默认 65536 | `deerfect-server/src/main/resources/db/migration/V20__llm_provider_model_route.sql:23,83` |
| 解析 | `ModelCapabilities.parse` 读取该字段，非法 JSON 抛错 | `deerfect-server/src/main/java/com/dark/javaHarness/llm/model/ModelCapabilities.java:15-30` |
| 现有消费 | **仅** `BindingValidator` 校验 Agent `max_tokens` 不得超过窗口；运行时不使用窗口裁剪输入 | `deerfect-server/src/main/java/com/dark/javaHarness/llm/validation/BindingValidator.java:74-77` |
| 输入预算 | 全部来自 `app.context.*`（history 4000 / tool-result 5000 / aggregate 12000 等），0=不限制 | `deerfect-server/src/main/resources/application-chat.yaml:15-45`；`config/ContextBudgetProperties.java` |
| 预算落点（agentic） | `AgentRequestSpecFactory` 把 `budgets.getHistoryBudget()` 直接交给 `ContextAssemblingAdvisor` | `deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java:190,323` |
| 预算落点（graph 节点） | lead 用 `PromptBudgetAdvisor.tail(leadBudget)`；聚合用 `sections(aggregateBudget)`；专家走 `assemblyForRole`；工具结果经 `ToolBudgetDecorator` 按 `toolResultBudget` 限 | `graph/OrchestrationNodes.java:469,623-624`；`tool/budget/ToolBudgetDecorator.java:40` |
| 输出分档 | `maxTokensForRole`：lead / final / expert 三档，守卫公式需要按角色取输出上限 | `agent/CallSpecAssembler.java:70-77` |
| 编排总量门控 | `orchestration-budget` 在每次调用发起前与每轮 roundtrip 经 BudgetLedger 熔断 | `agent/AgentChatPipeline.java:83-90,352-373` |
| 管理 API | 已有 `POST/PUT /api/admin/models`，`capabilities` 以整段 JSON 传入；保存前做 JSON 合法性校验；写后热刷新 | `llm/admin/LlmAdminController.java:50,56`；`llm/admin/LlmAdminService.java:367-368,89-249` |
| 热更新 | 管理端每次写库后 `refreshAfterWrite()`，配置即时生效 | `llm/admin/LlmAdminService.java:89,108,146,164,208,228,249` |
| Web 模型面 | 模型列表只读（显示名/上游名），**没有模型编辑入口**；`LlmModelView.capabilities` 已是字符串 JSON | `deerfect-web/src/components/SettingsView.vue:480-486`；`deerfect-web/src/api/admin.ts:23-32` |
| 上下文可视 | 对话页有「上下文构成」圆环，但明确不含上限 | `deerfect-web/src/components/ContextUsageRing.vue:1-7` |

约束（来自 `docs/principal.md`）：

- 预算数值的唯一来源是 yaml（`app.context.*`），代码不写第二套数字；窗口守卫的公式属于派生逻辑，不新增配置数字。
- 系统边界才做校验：滑块值来自 HTTP 入参，必须在管理接口校验；逻辑层不写静默兜底。
- 不留兼容层：不新增旧字段/旧接口桥接。

---

## 三、功能规格

### 3.1 入口与布局

- 位置：设置 → 模型管理 → 展开某个 Provider → 模型行末尾新增「上下文」按钮。
- 点击打开行内编辑面板（沿用现有 `provider-form` 卡片风格），包含：
  - 居中大字：`600K 上下文 · 适中`（值 + 档位词）
  - 滑轨：品牌绿渐变已选区 + 白色圆形拇指，两端各一个小圆点（对齐参考图）
  - 下方小字：`仅影响该模型的输入上限；实际请求仍受全局预算约束`
- 不新增独立页面、不引入第三方组件库。

### 3.2 档位（非线性离散）

滑块按**索引**定位、按**档位值**保存，避免用户设出 12345 这类无意义值：

| 索引 | 值 | 文案 |
|---|---|---|
| 0 | 8K (8192) | 精简 |
| 1 | 32K | 基础 |
| 2 | 64K | 标准 |
| 3 | 128K | 适中 |
| 4 | 256K | 充裕 |
| 5 | 600K (600000) | 加大 |
| 6 | 1M (1048576) | 超大 |

- 当前值不在档位上（历史配置）时：显示最近档位并把真实值以小字标注，保存时才对齐档位。
- 0（未知）不放进滑块；未配置过窗口的模型显示「未声明」并提供「按档位设置」入口。

### 3.3 交互与无障碍

- `input[type=range]` 原生控件 + CSS 定制，保留键盘 ←/→、Home/End。
- `aria-valuetext="600K，加大"`；滑块与文案用 `aria-describedby` 关联。
- 拖动过程只更新本地值；停手后 500ms 防抖自动保存，保存中显示「保存中…」，成功轻提示「已保存」，失败回滚并显示原因。
- 窄屏（≤640px）滑块横向铺满卡片，文案换行不溢出。

### 3.4 不做的事

- 不按模型名自动猜测窗口（容易错且不可审计）。
- 不把窗口当 token 计费额或输出上限。
- 不在对话页暴露 600K 级滑块（历史预算语义不同，留给二期展示"有效预算"）。

---

## 四、技术设计

### 4.1 数据与接口

数据模型不变：继续使用 `llm_model.capabilities` JSON，无需 Flyway 迁移。

新增专用接口，避免前端整段回传 `capabilities` 时误删 `tool_calling/vision/thinking/json_mode`：

```
PUT /api/admin/models/{id}/context-window
Body: { "contextWindow": 600000 }
```

- 服务端读取现有 capabilities JSON → 仅替换 `context_window` → 写回。
- 校验（系统边界）：整数；允许 0（未知）；非 0 时 `[4096, 2000000]`；非法返回 400 与明确字段名。
- 写后沿用 `refreshAfterWrite()`，路由与校验立即使用新值。
- `GET /api/admin/models` 无需改动；前端从 `capabilities` 字符串解析出窗口值（新增纯函数 `parseCapabilities`，带损坏容错，仅用于展示）。

### 4.2 运行时联动：per-call 窗口守卫（两条路径通用）

窗口约束的是**单次 LLM 请求**，所以守卫按"节点角色"逐调用计算，agentic 与 graph 共用同一公式：

```
effectiveInput(role) =
    window <= 0                 -> yamlBudget(role)          # 未声明窗口：完全保持现状
    cap = window - maxTokensForRole(role) - reserve(window)  # reserve = min(window*5%, 2048)
    cap = max(cap, 1024)                                     # 下限，避免负预算
    yamlBudget(role) == 0       -> cap                       # yaml 不限制时由窗口兜底
    否则                        -> min(yamlBudget(role), cap)
```

角色 → yaml 预算映射（不新增配置键）：

| 调用 | yamlBudget(role) | maxTokens 档 |
|---|---|---|
| agentic / 路径 A 直出 | `history-budget`（另对 `tool-result-budget` 同式封顶） | final |
| graph lead | `lead-budget` | lead |
| graph 专家 | 专家 brief/历史装配预算 | expert |
| graph 聚合 | `aggregate-budget` | final |

落点（四个装配点共用一个纯函数 `ContextWindowGuard.effective(...)`，无状态、不查库）：

1. `AgentRequestSpecFactory` 组装 `ContextAssemblingAdvisor` 前（L190/L323）——覆盖 agentic 与 graph 专家；
2. `OrchestrationNodes.predictLead` 的 `PromptBudgetAdvisor.tail(...)`（L469）；
3. `OrchestrationNodes.aggregateBudgetAdvisor()`（L623-624）；
4. `ToolBudgetDecorator` 的工具结果预算（L40）按同式封顶。

窗口值来源：该次调用实际命中路由成员的 `capabilities.context_window`（RoutePlan 已携带），
经 `CallSpecAssembler`/`AgentChatCaller` 传入装配点；**实现时需确认 AgentScope ReAct 路径
也经过同一装配链**，若否则在该路径补同一守卫调用（不另写第二套公式）。

与编排总量的关系：`orchestration-budget` 继续由 BudgetLedger 在调用前/roundtrip 门控，
守卫不读不写它；两者相乘的效果是"单请求不超窗口、整编排不超总量"。

- 不做：改 `ContextBudgetProperties` 语义、在 yaml 增加第二套数字、把窗口当编排总量。
- 不做：窗口不足以容纳 system prompt 时静默截断；记录 warn 并照旧发送（上游拒绝走现有错误链路）。
- 二期可选：把 `orchestration-budget` 做成设置页第二个滑块（全局策略，非模型能力），
  走独立管理接口；与窗口滑块并列展示但语义文案明确区分「单模型上下文 / 编排总预算」。

### 4.3 前端结构

- 新组件 `ContextWindowSlider.vue`：受控（value/档位） + 事件（change/save），无业务请求。
- 设置页接入：模型行「上下文」按钮 → 编辑面板 → 组件 + 保存状态。
- API：`admin.ts` 增加 `updateModelContextWindow(id, value)`。
- 不新增全局状态；保存成功后就地更新 `models` 列表中的 `capabilities` 字符串。

---

## 五、测试计划

### 后端

- `ModelCapabilities`：0/合法/越界/非 JSON 的解析口径（已有基础，补范围校验单测）。
- 新接口：仅替换 `context_window`，其余 capability 键原样保留；非法值 400；写后刷新被触发（mock refresher）。
- 守卫纯函数：窗口 0、窗口小于输出、yaml 预算更小、下限 1024 四类边界；
  并按 lead / expert / final 三个角色各验一遍（graph 与 agentic 共用公式）。
- 集成口径：graph 一次编排中 lead/专家/聚合三次调用的有效预算互不挤占；
  `orchestration-budget` 门控行为不变（既有 BudgetLedger 测试保持绿）。

### 前端

- 组件单测：档位吸附、键盘、aria-valuetext、防抖保存、失败回滚、未知值展示。
- E2E（mock）：设置 → 模型 → 滑块 → 文案变为目标档位 → 刷新后保持；窄屏不溢出。
- 回归：现有 119 条单测 + 25 条 E2E + Pages/PWA 产物测试保持全绿。

---

## 六、实施批次（每批单独测试并 commit）

1. **后端接口**：`PUT /models/{id}/context-window` + 校验 + 保留其余 capability 键 + 测试。
2. **运行时守卫**：`ContextWindowGuard.effective` 接到四个装配点（agentic/lead/聚合/工具结果），
   确认 AgentScope 路径同链 + 按角色测试。
3. **前端滑块**：组件 + 设置页入口 + 单测 + E2E。
4. **文档**：`docs/guides/models.md` 与 `docs/dev.md`（如涉及命令/接口）同批更新。

预计改动规模：后端约 6 个文件（接口 + 守卫 + 四个装配点）+ 3 个测试类；前端 1 个新组件 + 2 个文件改动。

---

## 七、验收标准

- 设置页可拖动设置模型窗口，文案形如「600K 上下文 · 加大」，刷新后保持。
- 保存只改 `context_window`，模型其他能力字段不变。
- 窗口值经管理接口校验，非法值被拒绝且有可读错误。
- 窗口 > 0 时，**每次调用**（agentic 直出、graph lead/专家/聚合）的有效输入预算不超过
  `窗口 − 该角色输出上限 − 预留`；graph 各节点预算互不挤占；窗口为 0 时行为与当前完全一致。
- `orchestration-budget` 的总量门控不受窗口滑块影响。
- 全部既有测试通过，新增测试覆盖上述边界。

---

## 八、风险与待确认

1. **600K 是否是真实窗口**：滑块只是声明，模型不支持时上游仍会报错；需要管理员按模型文档配置。
2. **预算联动会改变裁剪时机**：小窗口模型下，agentic 的历史与 graph 各节点的 prompt 都会早于
   全局 yaml 预算开始裁剪；graph 节点间不再共享"总上下文"，需产品确认按节点独立裁剪可接受。
3. **档位取值**：文中档位（8K～1M）为初稿，可按你实际使用的模型（如 Qwen 128K、DeepSeek 128K）调整。
4. **模型编辑入口缺失**：当前 Web 只能查看模型；本次需同时补一个最小编辑入口，工作量比纯滑块大。
5. **是否要第二个滑块**：编排总预算（`orchestration-budget`）做成滑块属二期全局策略项；
   若首期就要"总量"可视化，建议先在监控页展示而非新增可写入口，避免与窗口语义混淆。

---

## 九、业界对照（公开资料核实，2026-11-19）

结论：**没有一家把"整条编排的总上下文"当成一个共享窗口来裁剪**；普遍是
「每个 agent 实例自己的窗口 + 按窗口比例触发压缩」与「跨实例共享的成本/迭代预算」两条轴。

| 产品 | 窗口归属 | 压缩/裁剪触发 | 总量轴 |
|---|---|---|---|
| Claude Code | 每个 subagent **独立上下文窗口**，只把摘要回传主会话 | 主会话与 subagent 各自 auto-compact；subagent 可用 `autoCompactWindow`（≥100000）单独下调 | subagent 请求计入与主会话**相同的用量限额**（成本共享、窗口不共享） |
| Codex | 父/子可不同策略；社区请求 `default_subagent_context_window` / `default_subagent_auto_compact_token_limit`（父 1M、子 272K） | `model_context_window` + `model_auto_compact_token_limit`；新实验性 compaction；Responses API 服务端 `context_management` | 订阅用量/迭代限制，与窗口配置分离 |
| Hermes Agent | 窗口是**解析出的模型事实**：显式配置 > provider 探测/报错学习 > 静态表 | 双层：agent 内压缩默认 50%×窗口（<512K 地板 75%）、gateway 卫生网 85%；尾部预算=窗口 2.5% clamp 10K–25K 且 ≤20% 窗口；`model_thresholds` 按模型覆盖 | `IterationBudget`：父与子**共享**的线程安全计数，70% 触发干预 |
| Qoder CLI | subagent「independent context, dedicated prompts, toolsets」 | `/compact` 手动 + 自动压缩；`/context` 查看构成 | 用量/配额独立于窗口 |

对本计划的印证与补充：

- 我们的 per-call 守卫 = 业界「每实例窗口」轴；graph 节点 ≈ subagent；`orchestration-budget` ≈ Hermes IterationBudget / Claude 共享用量限额。**方向一致，不需要全局总窗口。**
- 业界窗口值是「解析/声明的模型事实」而非用户随手填：Hermes 有 provider 探测与报错学习。
  我们首期用 `capabilities.context_window` 声明 + 校验已够；二期可加「按 provider 报错学习窗口」回填。
- 业界压缩是**有损摘要 + 保护尾部**，不是纯截断。我们已有会话滚动摘要（SessionSummaryService），
  守卫触发裁剪时应优先走摘要而非静默丢轮次（二期与压缩策略对齐）。
- 可选二期：按角色覆盖窗口（lead/expert/aggregate），对应 Claude `autoCompactWindow` 与
  Codex `default_subagent_context_window`；默认仍取模型窗口。

来源：Claude Code sub-agents 官方文档；openai/codex issue #49195；Hermes Agent
「Context Compression and Caching」开发者文档；Qoder CLI「Built-in Capabilities Overview」。
WorkBuddy 无公开架构资料，本对照不纳入（仅本项目迁移记忆，不含其上下文实现）。

---

## 十、编排总额度取值（实证 + 推荐）

总额度（`orchestration-budget`）是**跨调用累计的成本/防失控策略**，与单模型窗口无关：
N 个各自合法的并行专家，累计消耗可以远超任一窗口，故**不得**把总额度设成窗口值。

实证（本地 `llm_call_log` 按 trace 聚合，多调用编排 n=33，2026-11-19）：

| 指标 | 值 |
|---|---|
| 平均累计 | 22,077 tok |
| 最大累计 | 69,626 tok（7 次调用，正常满扇出） |
| ≤60K 占比 | 97% |
| ≤100K 占比 | 100% |

理论满扇出估算（现有分档）：lead(4K入+4K出) + 4×专家(任务书~1K+工具≤5K+出4K，含多轮重发≈×2)
+ 聚合(12K入+8K出) ≈ 108K。

**推荐默认 100,000 tok**：≈实测最大×1.4、≈理论满扇出；放行正常满扇出、只砍病态重试。
现状 60K ≈ p97，会误伤实测最重的合法编排（69.6K），偏紧。

落地形式：**倍率滑块**，基准 = 运行时推导的满扇出上限
`lead_cap + subtask_max_count × expert_cap + aggregate_cap`（各 cap 用第四节守卫公式），
档位 0.5× / 0.75× / 1× / 1.5×，默认 1×（≈100K）；成本敏感取 0.6×（≈现状 60K）。
改分档或扇出时总额自动跟随，不手调魔法数。

校准机制：样本仅 33 次属指示性；上线后定期按 trace 聚合 `llm_call_log`，
以 p99×1.2 复核默认倍率（可做成监控页只读指标，二期）。
