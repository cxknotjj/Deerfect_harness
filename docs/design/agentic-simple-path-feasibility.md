# Agentic 路径接入 AgentScope ReAct 可行性分析

> 日期：2026-10-28（评估） / 2026-10-29（落地）　|　状态：已实施　|　目标：Agentic 路径改为 AgentScope ReAct 执行，Graph 编排路径保留自研并行引擎
>
> 术语说明：本文写于改造前，文中的 `SIMPLE` 路径即现在的 **Agentic 路径**，`COMPLEX` 即 **Graph 编排路径**。
>
> 评估依据：仓库现有代码与依赖（`mvn dependency:tree` 实测）、AgentScope Java 2.0.4 官方文档。
> 结论先行：**技术可行且已落地**——只引 `agentscope-core`，工具经 Spring AI 桥接，MCP/Victools 冲突已排除；落地结果与剩余观察项见第 12 节。

---

## 1. 结论摘要

| 问题 | 结论 |
|---|---|
| AgentScope 能实现 agentic SIMPLE 路径吗？ | 能。`ReActAgent` 提供推理-行动循环、工具调用、流式事件、中断与多会话隔离 |
| 能否与本项目现有架构共存？ | **可以**。只引 `agentscope-core`，排除其 MCP SDK 0.17.2，工具全部走 Spring AI 桥接；MCP 冲突由「不用 AgentScope MCP」规避 |
| 当前 SIMPLE 路径是否已经算 agentic？ | 已有**工具调用循环**（Spring AI ChatClient 内部 ReAct），但没有显式迭代/计划/反思/任务清单 |
| 推荐做法 | 方案 A：AgentScope `ReActAgent` + Spring AI 工具桥接 + feature flag；模型层二选一（见 5.1） |
| 主要剩余风险 | 模型适配（AgentScope 需要自己的 `Model`）与事件/记忆/观测对等，不再是 MCP 版本 |
| PoC 状态 | ✅ 2026-10-29 已落地：SIMPLE 路径默认由 `AgentScopeGeneralAgent`（ReAct）执行，工具/模型/记忆/观测复用 Spring AI 侧设施；`app.agentic.enabled=false` 可回退。详见第 12 节 |

---

## 2. 现状基线（代码证据）

### 2.1 两条路径现状

- **路由分流**：`RouteJudge` 输出 `SIMPLE` / `COMPLEX`，异常兜底 SIMPLE（[LlmRouteJudge.java](../../server/src/main/java/com/dark/javaHarness/service/impl/route/LlmRouteJudge.java)）。
- **SIMPLE（路径 A）**：`GeneralAssistantAgent` → `AgentChatCaller` → Spring AI `ChatClient`，已具备：
  - 多轮工具调用（`ToolCallback` 由 Spring AI 内部循环执行）
  - 工具调用硬上限（`tool-call-limit: 4`）、工具结果预算（5000 token）、懒加载 Schema、技能索引
  - 流式 token、思考内容透传、工具起止进度行
  - 重试（`LlmRetry` 3 次）、取消、`llm_call_log` 观测、MCP/沙箱/RAG 工具
  - 见 [GeneralAssistantAgent.java](../../server/src/main/java/com/dark/javaHarness/agent/GeneralAssistantAgent.java#L27-L45) 与 [AgentChatCaller.java](../../server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L163-L214)
- **COMPLEX（路径 B）**：自研 `ParallelOrchestrationEngine`（Lead 拆解 → 子任务并行 → 聚合 → 检查点续跑），见 [ParallelOrchestrationEngine.java](../../server/src/main/java/com/dark/javaHarness/agent/orchestrate/engine/ParallelOrchestrationEngine.java#L28-L98)。

### 2.2 项目里的 AgentScope 是什么（同名不同物）

- 当前依赖树里只有一个 AgentScope 构件：`io.agentscope:agentscope-runtime-sandbox-core:1.0.2`。
  实测命令：`mvn -pl server dependency:tree -Dincludes=io.agentscope:*`，只输出该一行。
- 它属于 **AgentScope Runtime**（容器沙箱运行时，仓库 `agentscope-runtime-java`），POM 传递依赖是
  docker-java、aliyun-sdk-oss、agentbay-sdk、Jackson 2 等，**不包含 `agentscope-core`**。
- 本文讨论的 `ReActAgent` / `Toolkit` / `streamEvents` 位于另一条产品线 **AgentScope Java**
  （artifactId `agentscope-core`，仓库 `agentscope-java`）。两者同 groupId，但依赖树互不包含。

| 构件 | 作用 | 当前是否在项目里 |
|---|---|---|
| `agentscope-runtime-sandbox-core:1.0.2` | Docker 容器沙箱运行时（`SandboxToolProvider` 使用） | ✅ 已引入 |
| `agentscope-core:2.0.4` | ReActAgent / Toolkit / 事件 / 状态框架 | ❌ 未引入 |
| `agentscope-extensions-model-*` | DashScope / OpenAI 等模型扩展 | ❌ 未引入 |

- 版本属性 `agentscope.version=1.0.2` 仅为沙箱模块服务（[pom.xml](../../pom.xml#L42)、[server/pom.xml](../../server/pom.xml#L41-L48)）。
- 因此引入 `agentscope-core` 属于**新增第二套 Agent 执行框架**，不是复用已有能力。

### 2.3 关键依赖实测（`mvn dependency:tree -pl server`）

| 依赖 | 项目当前 | AgentScope 2.0.4 期望 | 判断 |
|---|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | **2.1.0** | **0.17.2** | ⛔ 主版本冲突（硬风险） |
| `reactor-core` | 3.8.8 | 3.8.2 | ✅ 兼容，项目更新 |
| `okhttp-jvm` | 5.5.0 | 5.3.2 | ✅ 同主版本，可被根 BOM 覆盖 |
| `jackson-databind`（Jackson 2） | 2.21.7（已存在） | 2.21.1 | ✅ 可对齐到 2.21.7 |
| `jackson`（Jackson 3 / `tools.jackson`） | Boot 4 自带 | 不涉及 | ✅ 包名不同可共存，但多一套 JSON 栈 |
| `snakeyaml` | 2.6 | 2.6 | ✅ 一致 |
| `tree-sitter` / `tree-sitter-bash` | 无 | 原生依赖 | ⚠️ 新增 native 体积与供应链面 |

**依赖体量澄清**（回应「AgentScope 已引入、不会太重」的直觉）：

- `agentscope-core:2.0.4` 的多数传递依赖项目已有：Reactor、Jackson 2（当前 2.21.7）、OkHttp、SLF4J、SnakeYAML。
- 真正新增的构件主要是：`victools:jsonschema-generator` / `jsonschema-module-jackson`、
  `networknt:json-schema-validator`、`tree-sitter` 与 `tree-sitter-bash` 原生库、以及
  **`io.modelcontextprotocol.sdk:mcp:0.17.2`（与项目 2.1.0 冲突）**。
- 所以「重量」不是主要问题；**版本冲突 + 适配层工作量**才是。若只用 `agentscope-core`、不引
  `agentscope-harness`、不用 AgentScope 自带 MCP/沙箱，新增依赖面是可控的。

---

## 3. AgentScope 2.0.4 能力盘点

来源：[Quickstart](https://java.agentscope.io/v2/zh/docs/quickstart.html)、[Agent](https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html)、[Tool](https://java.agentscope.io/v2/en/docs/building-blocks/tool)、[Middleware](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html)、[Message & Event](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)、[Model](https://java.agentscope.io/v2/en/docs/building-blocks/model)。

| 能力 | 说明 | 对本项目价值 |
|---|---|---|
| `ReActAgent` | 推理-行动循环，`maxIters` 默认 10，支持 `call` / `streamEvents` / `observe` | ★★★ 显式 agentic 主循环 |
| `Toolkit` | `@Tool` 反射注册、`ToolBase` 自定义、ToolGroup、MCP、Skill | ★★ 与现有工具体系重叠 |
| 流式事件 | `TextBlockDelta` / `ThinkingBlockDelta` / `ToolCallStart` / `ToolResult*` / `AgentEnd` / `ExceedMaxIters` | ★★★ 可映射现有 SSE |
| 用量 | assistant `Msg.getUsage()`（`ChatUsage`） | ★★ 可补 `llm_call_log` |
| Middleware | `onAgent` / `onReasoning` / `onModelCall` / `onActing` / `onSystemPrompt` | ★★★ 观测与预算适配点 |
| 中断 | `agent.interrupt(userId, sessionId)`，下一个检查点生效 | ★★★ 对应现有取消 |
| 权限系统 | 工具确认 / 外部执行，支持 ASK 暂停 | ★ 与现有白名单重叠，暂不需要 |
| 状态持久化 | `AgentStateStore` 按 `(userId, sessionId)` 自动存取 | ⚠️ 与现有 MySQL 会话记忆冲突 |
| 模型扩展 | `agentscope-extensions-model-dashscope/openai/deepseek/...` | ★★ 需按 `model_provider` 表重建模型 |
| HarnessAgent | 工作区 / 长期记忆 / 子 agent / 压缩 / 沙箱 | ⚠️ 与现有架构大面积重叠 |

---

## 4. 目标架构（两条路径）

```
ChatService / RouteJudge
├─ SIMPLE → AgenticAgentAdapter（可切换 provider）
│    ├─ provider=spring-ai（现状，默认）
│    └─ provider=agentscope（ReActAgent + 适配层）
└─ COMPLEX → ParallelOrchestrationEngine（保持不变）
```

设计原则：

1. **路由与出口不变**：仍由 `RouteJudge` 决定路径，SSE 协议、`meta`、`llm_call_log`、会话写回保持一致。
2. **COMPLEX 零改动**：本次只替换路径 A 的执行体，避免编排引擎与 AgentScope 状态模型互相污染。
3. **可回退**：通过 `app.agentic.provider` 开关切换，AgentScope 不可用时自动回退 Spring AI。

---

## 5. 集成设计（要写哪些适配层）

### 5.1 模型适配（两条可选路线）

AgentScope `ReActAgent` 需要自己的 `Model`（接口：`stream(messages, tools, options) → Flux<ChatResponse>`，`ChatResponse` 内含文本/思考/工具调用块与 `ChatUsage`）。

**路线 1：AgentScope 模型扩展（PoC 首选，改动少）**

- 用 `model_provider` 表的 `provider / model / api_url` 构造显式 Model：
  - DashScope → `DashScopeChatModel.builder().baseUrl(api_url).apiKey(...)`
  - DeepSeek / 其他 OpenAI 兼容 → `OpenAIChatModel.builder().baseUrl(api_url)`（官方文档确认支持自定义 `baseUrl`）
- 复用现有 API Key 解析规则（`app.providers.*` > `<PROVIDER>_API_KEY` 环境变量）。
- 关闭 `ModelRegistry` 字符串 id 与 Spring Boot Starter，避免引入第二套自动配置。
- 代价：多一套模型客户端与配置装配，但 `Msg.getUsage()` 可回传真实 token。

**路线 2：Spring AI 模型适配器（生产推荐，单一模型栈）**

- 实现 `SpringAiModelAdapter implements io.agentscope.core.model.Model`，内部委托 Spring AI `ChatModel`：
  - AgentScope `Msg` / `ToolResultBlock` → Spring AI `Message` / `Prompt`
  - AgentScope `ToolSchema` → Spring AI `ToolDefinition`（只传定义，不执行工具，工具循环仍由 AgentScope 驱动）
  - Spring AI `ChatResponse`（文本/工具调用/usage）→ AgentScope `ChatResponse` 内容块
- 复用现有 `ChatClientRegistry`、模型路由、超时与 Key 解析，观测口径统一。
- 代价：消息/工具 Schema/流式块的双向映射代码量更大，需要单测覆盖。

### 5.2 工具适配（Spring AI `ToolCallback` → AgentScope `ToolBase`）

一个桥接类即可覆盖全部现有工具（含 MCP、沙箱、技能）：

```
SpringAiToolBridge extends ToolBase
  - name/description/inputSchema ← ToolCallback.getToolDefinition()
  - callAsync(param) → ToolCallback.call(param.input(), ToolContext)
  - 结果 → ToolResultBlock.success/error
```

要点：

- 桥接**装饰后的** ToolCallback（保留 `ToolCallBudget`、`ToolCallTracer`、`ToolLazyManager`、`expand_tool`、`load_skill` 语义）。
- 工具行只保留一处发射：现有 `ToolCallTracer` 已发 `tool`/`tool-done`，AgentScope 的 `ToolCallStartEvent` 不要再重复发进度行。
- 不注册 AgentScope 自带 MCP client，继续用 Spring AI 的 MCP 工具桥接，规避 SDK 版本冲突。
- 权限系统统一配置为 allow（工具可见性已由 `agent.tools` 白名单在服务端约束），不启用 ASK/外部执行事件。

### 5.3 记忆适配（避免双写）

- 现有会话记忆以 MySQL `session_messages` 为唯一事实源，由 `ChatService` 统一写回。
- AgentScope 侧**不启用 `AgentStateStore`**，每次请求把裁剪后的历史转成 `Msg` 列表注入上下文，再发起 `streamEvents`。
- 需要 PoC 验证：`observe(history)` + `streamEvents(userMsg)` 的行为是否符合预期（历史不重复追加、上下文不跨请求残留）。
- 若必须用 `AgentStateStore`，需实现 MySQL 实现并与现有快照双向同步，复杂度显著上升，不建议一期做。

### 5.4 Prompt / RAG / 画像

- 沿用现有 `PromptAssembler` 与 `AgentRequestSpecFactory` 产出的 system prompt。
- RAG 检索仍在组装 prompt 前旁路完成（`KnowledgeRetriever`），AgentScope 只接收最终 prompt，不改检索链路。
- 用户画像段继续经 `UserProfileSectionProvider` 注入 system prompt。

### 5.5 流式事件映射（AgentScope → 现有 SSE）

| AgentScope 事件 | 现有输出 |
|---|---|
| `TextBlockDeltaEvent.delta` | `event: token` |
| `ThinkingBlockDeltaEvent.delta` | `ProgressLine("思考 · <agent>", delta)` |
| `ToolCallStartEvent` | 不重复发（由 `ToolCallTracer` 负责） |
| `ToolResultEndEvent` | `tool-done` 进度行（同上） |
| `ExceedMaxItersEvent` | `ProgressLine("agentic", "达到最大迭代轮次")` |
| `AgentEndEvent` + `Msg.getUsage()` | `meta`（status/usage/sessionId） |
| `RequireUserConfirmEvent` / `RequireExternalExecutionEvent` | 一期不启用；出现即按错误降级 |

### 5.6 观测与预算

- **观测**：自定义 `MiddlewareBase.onModelCall` 包裹每次模型调用，记录耗时/异常；最终 `Msg.getUsage()` 写 `llm_call_log`（若事件层拿不到逐次 usage，则退化为「一轮一行」口径，需接受与现状差异）。
- **迭代上限**：`maxIters` 与现有 `tool-call-limit` 对齐（建议 `maxIters = tool-call-limit + 1`，避免语义双算）。
- **输出上限**：通过 `GenerateOptions.maxTokens` 注入 `max-tokens-final`。
- **工具预算**：由桥接后的 `ToolCallBudget` 装饰器继续生效，AgentScope 侧不重复实现。
- **重试**：二选一——关闭 AgentScope `ModelConfig` 重试、沿用项目 `LlmRetry`；或启用 AgentScope 重试并在 Middleware 记账。**不建议两套同时开**。

### 5.7 取消、超时与并发

- 外层 `StreamConnectionLimiter`（默认 50）不变。
- Flux `cancel` → 从 `RuntimeContext` 定位 `(userId, sessionId)` → `agent.interrupt(...)`；流空闲/首帧超时仍由适配层 watchdog 兜底。
- AgentScope 同一 `(userId, sessionId)` 会串行化；项目当前同一会话也基本串行，需确认并发策略一致性。

### 5.8 需要新增/改动的模块

| 模块 | 改动 |
|---|---|
| `server` | 新增 `agent/agentic` 包：`AgentScopeAgentAdapter`、`SpringAiToolBridge`、`AgentScopeModelFactory`、`ObservabilityMiddleware` |
| `server` | `GeneralAssistantAgent` 增加 provider 选择（或新增 `AgenticAgent` 委托类） |
| `harness-api` | 无改动（SSE / DTO 契约不变） |
| `pom.xml` | 新增 `agentscope-core` + 模型扩展；排除冲突依赖；版本锁定 |
| 配置 | 新增 `app.agentic.*`（provider / max-iters / 是否启用） |

---

## 6. 依赖冲突与兼容性（本方案的关键风险）

### 6.1 MCP SDK 主版本冲突（已选定规避方案）

- 项目：`io.modelcontextprotocol.sdk:mcp:2.1.0`（Spring AI 2.0.2 管理）
- AgentScope 2.0.4 ~ 2.1.0-BETA1：均为 `0.17.2`
- **选定方案**：只引 `agentscope-core`，从依赖中排除 `io.modelcontextprotocol.sdk:mcp:0.17.2`，
  保留项目 2.1.0；**不使用 AgentScope 的 MCP client / MCP 相关 API**，所有 MCP 工具继续由
  Spring AI 获取为 `ToolCallback`，再桥接成 AgentScope `ToolBase`。
- 残余验证点：`Toolkit` 类加载与工具注册是否会触发旧版 MCP API（预期不会，因其 MCP 代码路径不执行）。
  PoC 若出现 `NoSuchMethodError`，退路是隔离类加载/独立进程，或放弃 AgentScope 路径。
- **PoC 结论（2026-10-28）**：`Toolkit` 创建、`registerTool`、真实工具调用均正常，未出现
  `NoSuchMethodError` / `NoClassDefFoundError`；`mvn dependency:tree` 确认 classpath 上只有
  `io.modelcontextprotocol.sdk:mcp:2.1.0`，0.17.2 已被完全排除。
- 风险等级：已由「硬风险」降为「已规避（基础工具场景）」，MCP 工具桥接仍需真实 server 联调。

### 6.2 其他依赖

| 风险 | 说明 | 缓解 |
|---|---|---|
| Jackson 2 + Jackson 3 并存 | AgentScope 用 Jackson 2.21，Boot 4 用 Jackson 3 | 可共存；统一 Jackson 2 版本到 2.21.7，避免两套 `ObjectMapper` 配置混用 |
| Reactor 版本 | 项目 3.8.8 > AgentScope 3.8.2 | 由 Boot BOM 统一，不降级 |
| OkHttp | 5.5.0 > 5.3.2 | 根 BOM 锁定 5.5.0 |
| tree-sitter native | 新增操作系统相关二进制 | 评估镜像体积、CI 构建平台、安全扫描 |
| Spring Boot Starter | AgentScope 提供 starter，但会引入自己的自动配置与模型装配 | 只用 `agentscope-core`，不用 starter |

---

## 7. 风险与缓解清单

| # | 风险 | 影响 | 缓解 | 验证方式 |
|---|---|---|---|---|
| R1 | MCP SDK 冲突 | 启动/工具注册 `NoSuchMethodError` | 排除 0.17.2、不用 AgentScope MCP，工具全走 Spring AI 桥接 | PoC 启动 + 调用 MCP 工具 |
| R2 | 工具桥接语义偏差 | 预算/懒加载/追踪失效 | 桥接装饰后的 callback，保持单点计数 | 对照现有 `ToolCallBudgetTest` 行为 |
| R3 | 记忆双写/串会话 | 上下文污染 | 禁用 stateStore，每请求注入历史 | 两轮会话 + 并发两会话测试 |
| R4 | 逐次 usage 缺失 | 观测口径退化 | `Msg.getUsage()` + Middleware 记账；路线 2 可在模型适配器内拿真实 usage | 对比 `llm_call_log` |
| R5 | 取消不生效 | 客户端断开后仍烧 token | interrupt + watchdog 双保险 | 中途 abort 压测 |
| R6 | 事件重复发射 | 前端出现双份工具行/token | 明确单一发射源 | 前端回归 |
| R7 | AgentScope 版本演进快 | BETA/不兼容 | 锁定 2.0.4，建立升级评估门禁 | 依赖升级记录 |
| R8 | 双框架维护成本 | 两套 prompt/工具/观测 | 工具与模型统一走 Spring AI 适配层（模型路线 2），AgentScope 只保留 ReAct 循环 | 代码边界与开关检查 |

---

## 8. 方案对比与推荐

| 方案 | 描述 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **A. AgentScope ReActAgent** | 只引 `agentscope-core`，SIMPLE 路径用 `ReActAgent`；工具与模型走 Spring AI 桥接 | 显式 agentic、事件/中断/中间件成熟，MCP 冲突可规避 | 需要工具桥接与模型适配层 | ✅ **选定方向** |
| **B. AgentScope HarnessAgent** | 使用工作区/长期记忆/子 agent/沙箱 | 开箱即用的长任务能力 | 与现有 MySQL 记忆、RAG、skills、沙箱大面积重叠 | ❌ 不建议 |
| **C. 现有链路上自建显式 ReAct** | 在 `AgentChatCaller` 外层增加迭代循环、任务清单工具、步骤可见化 | 零新增框架依赖、复用全部护栏与观测、风险最低 | 需要自己实现循环控制与中断 | 作为 A 的回退 |
| **D. 维持现状** | 继续用 Spring AI 内部工具循环 | 零改动 | 无显式计划/反思，agentic 叙事弱 | 作为基线 |

**推荐路径（按已确认方向）**：方案 A——只引 `agentscope-core`，MCP 工具经 Spring AI `ToolCallback` 桥接，模型层 PoC 先用路线 1、生产优先路线 2；通过 `app.agentic.provider` 开关灰度，保留方案 C 作为回退。HarnessAgent 不引入。

---

## 9. 分阶段落地建议（不含工期承诺）

- **阶段 0 · 隔离 PoC**
  - 在独立 profile/模块引入 `agentscope-core:2.0.4`，排除 MCP SDK 0.17.2
  - 验证：Spring Boot 4 启动、工具桥接、`streamEvents` 映射、`interrupt`、usage 记账、两轮记忆
  - 产出：依赖冲突结论 + 事件字段实测
- **阶段 1 · 适配层**
  - `AgentScopeAgentAdapter` + `SpringAiToolBridge` + `ObservabilityMiddleware`
  - 开关 `app.agentic.provider=spring-ai|agentscope`，默认 `spring-ai`；异常自动回退
- **阶段 2 · 对等验收**
  - 工具/预算/RAG/记忆/观测/取消/错误六项与现状对齐
  - 全量回归 + 前端 SSE 回归
- **阶段 3 · 决策**
  - 指标达标且维护成本可接受 → 灰度放量
  - 任一硬指标不达标 → 回退方案 C，移除 AgentScope 核心依赖

**回退策略**：`app.agentic.provider` 切回 `spring-ai` 即恢复现状；AgentScope 依赖只存在于 `server` 的可选 profile，默认构建不引入。

---

## 10. 验收标准（若实施）

- [ ] SIMPLE 请求端到端可用，SSE `token/progress/meta` 与现状一致
- [ ] 工具调用行、思考行、归属 agent 与现状一致，无重复事件
- [ ] `llm_call_log` 有记录，耗时/token 口径可解释
- [ ] 两轮会话记忆正确；并发两会话不串上下文
- [ ] 客户端中途断开后 1 个 token 边界内停止模型调用
- [ ] `general` 最小权限工具集在 AgentScope 路径同样生效
- [ ] MCP 工具（Tavily/WMS）可调用，且未引入两个 MCP SDK 版本
- [ ] 回退开关切换无需重启或一次重启内完成
- [ ] 新增依赖不破坏 COMPLEX 路径全量测试

---

## 11. 待确认问题

1. **agentic 的定义**：是「多步工具调用」（现状已具备），还是需要计划清单、反思、子 agent、工作区？
2. 是否接受新增一套 Agent 框架依赖与 native 依赖（tree-sitter）？
3. SIMPLE 与 COMPLEX 是否允许使用不同模型/观测口径？
4. AgentScope 版本策略：锁 2.0.4，还是跟随 2.1.x/BETA？
5. 若 PoC 发现 MCP SDK 无法共存，是否接受「AgentScope 不使用 MCP，全部走 Spring AI 桥接」？

---

## 12. PoC 实测结果（2026-10-28）

**分支**：`feat/agentscope-agentic-poc`（`agentscope-core` 与模型扩展均为 **test scope**，不影响运行期产物）

**依赖验证**

- `agentscope-core:2.0.4` + `agentscope-extensions-model-openai:2.0.4`，显式排除
  `io.modelcontextprotocol.sdk:mcp`。
- `mvn -pl server dependency:tree -Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:mcp`
  结果：`agentscope-core:2.0.4:test`、`agentscope-extensions-model-openai:2.0.4:test`、
  `io.modelcontextprotocol.sdk:mcp:2.1.0:compile`（无 0.17.2）。

**用例与结果**：[AgentScopeReActPoC](../../server/src/test/java/com/dark/javaHarness/agent/agentic/AgentScopeReActPoC.java)

| 用例 | 验证内容 | 结果 |
|---|---|---|
| `reactLoopCallsSpringRegisteredStyleToolAndStreamsEvents` | 真实 DeepSeek 模型 + `@Tool` 反射工具 + `streamEvents` | ✅ 2.69s，工具被调用，事件流完整 |
| `springAiToolCallbackBridgesIntoAgentScopeToolkit` | Spring AI `ToolCallback` → `ToolBase` 桥接 + `Toolkit.registerTool` + 调用回填 | ✅ 参数/结果双向转换正常 |

**实测事件序列**：

```
AGENT_START → MODEL_CALL_START → TOOL_CALL_START → TOOL_CALL_DELTA → TOOL_CALL_END
→ MODEL_CALL_END → TOOL_RESULT_START → TOOL_RESULT_TEXT_DELTA → TOOL_RESULT_END
→ TEXT_BLOCK_START → TEXT_BLOCK_DELTA → TEXT_BLOCK_END → AGENT_RESULT → AGENT_END
```

**已确认**

- AgentScope 与 Spring Boot 4 / Spring AI 2 / MCP SDK 2.1.0 同 classpath 可启动；
- ReAct 循环、工具调用、文本增量事件可用；
- Spring AI 工具（含未来 MCP 工具）可通过一个 `ToolBase` 桥接类进入 AgentScope，无需第二套 MCP client。

**落地结果（2026-10-29）**

- 新增 `AgentScopeGeneralAgent`：SIMPLE 路径默认 ReAct；`AgentRegistry` 按 `app.agentic.enabled` 选择，
  初始化失败回退既有 `GeneralAssistantAgent`（流开始后不切换）；
- 新增 `AgentScopeModelFactory`：按 `model_provider` 行构建 OpenAI 兼容 Model，Key 解析与 ChatClient 同源，
  `disable_thinking=1` 映射为 `enable_thinking=false`；
- 新增 `SpringAiToolBridge`：工具仍由 Spring AI 提供并经装饰链（观测→预算→懒加载→元工具），
  trace 标识经 `RuntimeContext` → `ToolContext` 透传；
- 记忆不启用 AgentStateStore，每请求注入裁剪后的 MySQL 历史；system 复用 `PromptAssembler` + RAG；
- 观测记一轮 `llm_call_log`，usage 按 `MODEL_CALL_END` 逐次累加（多轮工具调用不低估）；取消映射为 `agent.interrupt`；
- `AgentRequestSpecFactory` 抽出 `assembleSystem` / `loadHistory` / `resolveTools`，两条路径共用装配面。

**依赖冲突补充（实测）**

- 第二个冲突：AgentScope 自带 `victools:jsonschema-generator/module-jackson:4.38.0` 会将项目
  Spring AI 需要的 **5.0.0** 降级，导致 `ToolDefinitions` 缺 `JacksonSchemaModule`、注解工具 Schema 生成失败；
  已在 `agentscope-core` / 模型扩展上排除这两个 artifact，最终 classpath 为 5.0.0。
- 第三个冲突（MySQL 可用后暴露）：AgentScope 自带 `com.networknt:json-schema-validator:2.0.0`（仅 Jackson 2 API）
  会覆盖 MCP SDK 2.1.0 需要的 **3.0.6**（Jackson 3 API），`mcpSyncServer` 初始化时
  `Schema.validate(tools.jackson.databind.JsonNode)` 报 `NoSuchMethodError`；已在两个 AgentScope
  artifact 上排除 `json-schema-validator`，最终使用 3.0.6（其保留 Jackson 2 方法，AgentScope
  `ToolValidator` 的 `SchemaRegistry.getSchema` / `Schema.validate(String, InputFormat)` 仍可解析）。
- 最终依赖树：`agentscope-core:2.0.4`、`agentscope-extensions-model-openai:2.0.4`、
  `io.modelcontextprotocol.sdk:mcp:2.1.0`、`com.github.victools:*:5.0.0`、`com.networknt:json-schema-validator:3.0.6`。
- 测试：`AgentScopeReActPoC` 2 例 + `AgentScopeGeneralAgentTest` 3 例（真实模型/回退/历史+工具桥接）通过；
  MySQL 可用时 server 全量 697 例通过（含 `JavaHarnessApplicationTests` 上下文加载）。

**仍未验证（生产观察项）**

- 真实 MCP server 工具（Tavily/WMS）经桥接后的调用与错误结构（当前验证的是合成 Spring AI ToolCallback）；
- 客户端断连 `interrupt` 的真实链路（单测未覆盖取消时序）；
- DashScope 等其他供应商的 `api_url` 兼容性（当前仅 DeepSeek 官方端点实测）；
- 历史裁剪只对历史段做预算，未把 system/本轮合并裁剪（与 ChatClient 路径存在轻微口径差异）。

---

## 13. 参考

**仓库代码**
- [GeneralAssistantAgent.java](../../server/src/main/java/com/dark/javaHarness/agent/GeneralAssistantAgent.java)
- [AgentChatCaller.java](../../server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java)
- [ParallelOrchestrationEngine.java](../../server/src/main/java/com/dark/javaHarness/agent/orchestrate/engine/ParallelOrchestrationEngine.java)
- [server/pom.xml](../../server/pom.xml#L41-L48)
- [TECH_STACK.md](../TECH_STACK.md)

**AgentScope Java 文档**
- [Quickstart](https://java.agentscope.io/v2/zh/docs/quickstart.html)
- [Agent（ReActAgent / interrupt / RuntimeContext）](https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html)
- [Tool（Toolkit / ToolBase / MCP / Skill）](https://java.agentscope.io/v2/en/docs/building-blocks/tool)
- [Middleware（onModelCall / onActing）](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html)
- [Message & Event（AgentEvent / usage）](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)
- [Model（扩展模块 / Spring Boot Starter）](https://java.agentscope.io/v2/en/docs/building-blocks/model)

**Maven 元数据**
- `io.agentscope:agentscope-core` 最新稳定 2.0.4（2.1.0-BETA1 仍依赖 MCP SDK 0.17.2）
