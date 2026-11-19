import{d as xe,r as k,w as te,n as K,o as Re,a as Ie,b as u,c as v,e as n,f as D,v as V,t as g,g as b,h as je,i as De,F as T,j as w,u as F,k as L,l as we,m as N,p as Ne,q as Oe,s as Ge,x as He}from"./index-DPop3Sxy.js";const Be=`---\r
title: "架构总览"\r
order: 3\r
---\r
\r
# 架构总览\r
\r
> README 精简后迁入：整体架构图与相关文档入口。\r
\r
---\r
\r
\`\`\`mermaid\r
flowchart TD\r
    A[🖥️ CLI / REST 请求] --> B[ChatController<br/>Harness 外壳入口]\r
    B --> C{🧭 RouteJudge<br/>选择 Agentic / Graph 执行路径}\r
    C -->|Agentic 路径| D[⚡ AgentScope ReActAgent<br/>单 Agent 自主推理-行动 · 逐 token 流式]\r
    C -->|Graph 编排路径| E[🕸️ MultiAgentGraphAgent<br/>自研并行引擎编排]\r
    E --> F[🧩 Lead 拆解<br/>至多 4 条 · 禁凑数]\r
    F --> G1[🔍 researcher]\r
    F --> G2[💻 coder]\r
    F --> G3[📊 analyst]\r
    F --> G4[✍️ writer]\r
    G1 & G2 & G3 & G4 --> H[📌 Aggregator 聚合<br/>打字机输出最终回答]\r
    D --> I[(🗄️ Goal 状态 + 会话记忆<br/>+ LLM 调用观测落库)]\r
    H --> I\r
    I --> J[📤 统一出口<br/>同步 JSON / SSE 流式]\r
\`\`\`\r
\r
> [!TIP]\r
> 数据流细节见 [\`docs/data-flow.md\`](data-flow.md)，落地 TODO 见 [\`docs/roadmap.md\`](roadmap.md)，测试全景见 [\`docs/guides/functional-testing.md\`](guides/functional-testing.md)。\r
>\r
> 上图是单次请求的双路径执行视图。长期记忆（增量画像 / 跨会话归档召回）与主动化（定时调度 / 主动推送 / 活跃目标）不在这条链上，能力清单见 [\`features.md\`](features.md)，归档召回数据流见 [\`data-flow.md\` 5j 节](data-flow.md#5j-会话历史归档与链式召回最小版2026-11-03)。\r
\r
\r
---\r
\r
[⬅ 返回 README](../README.md)\r
`,Fe=`---\r
title: "数据流"\r
order: 4\r
---\r
\r
# 数据流\r
\r
> 本文档描述 Harness 的完整业务数据流：请求进入 → 会话加载 → 主 Agent 前置判断（路径分流）→ 两条路径执行 → 统一出口。\r
> 术语说明：代码与 SSE 标签中的 \`SIMPLE\` / \`COMPLEX\` 分别对应文档中的 **Agentic 路径** 与 **Graph 编排路径**；为兼容既有 API/日志暂不改枚举名。\r
> 与「双路径架构」保持一致，以实际编码为准。\r
\r
***\r
\r
## 一、总览\r
\r
\`\`\`\r
用户请求 (message / sessionId / agentId)\r
        │\r
        ▼\r
① ChatController（Harness 外壳入口）\r
     /api/chat         → ChatService.chat()\r
     /api/chat/stream  → ChatService.streamReactive()\r
        │\r
② 加载会话原始数据 + 执行上下文组装\r
     sessionId → SessionService.loadContext() → 还原 Message 列表\r
     → 过滤/截断/角色归一化 → ContextAssemblingAdvisor（token 预算控制）\r
        │\r
③ 主 Agent 前置判断（路由分流）\r
     RouteJudge.plan(message) → LlmRouteJudge（规则短路 → 灰区 LLM → 置信度策略，失败兜底 Agentic）\r
        ├─ SIMPLE  → 路径 A：Agentic（AgentScope ReAct / 回退 GeneralAssistantAgent）\r
        └─ COMPLEX → 路径 B：多 Agent 编排（MultiAgentGraphAgent + 自研并行引擎）\r
        │\r
③' Agent 路由（表驱动动态注册）\r
     AgentServiceImpl.requireAgent(name) → AgentRegistry.require(name)\r
       注册表命中 → 直取实例；未命中 → 查 agent 表惰性注册（computeIfAbsent 原子构造）\r
       内部角色（is_internal=1）不注册；运行中插行立即可用（已有行的改动走 30s 缓存）\r
        │\r
④ 统一出口：会话记忆写回 + 同步/SSE 响应（对外契约一致）\r
\r
旁路：每次 LLM 调用（路由判断 / lead / 专家子任务 / 聚合 / 路径 A）结束\r
      → LlmCallRecorder 异步落库 llm_call_log（耗时 / token / 成败，见 5d 节）\r
\`\`\`\r
\r
***\r
\r
## 1. 请求入口（Harness 外壳）\r
\r
| 端点                      | 方法                                                         | 返回                                                      |\r
| ----------------------- | ---------------------------------------------------------- | ------------------------------------------------------- |\r
| \`POST /api/chat\`        | \`ChatController.chat()\`                                    | \`ChatResponse\`（JSON）                                    |\r
| \`POST /api/chat/stream\` | \`ChatController.stream()\` → \`ChatService.streamReactive()\` | \`Flux<String>\`（SSE：逐 token \`data:\` + \`[DONE]\` + \`meta\`） |\r
\r
请求体 \`ChatRequest\`：\`message\`（必填）、\`sessionId\`（可选，空则建档）、\`agentId\`（可选）、\`routing\`（可选，\`auto\` / \`agentic\` / \`graph\` 显式路径覆盖）。\r
\r
***\r
\r
## 2. 同步路径 \`/api/chat\`\r
\r
\`\`\`\r
ChatService.chat(request)\r
  1. 无 sessionId → sessionService.createSession() 建档\r
  2. agentId 有值 → sessionService.switchAgent()（会话绑定副作用，失败仅告警）\r
  3. resolveRouting(message, routing)         ← 主 Agent 前置分流（显式 routing 优先；绑定专属 Agent 跳过判定）\r
       └─ routeJudge.plan(message) → Graph（≥2 工作流且置信度达标）→ "multi-agent"\r
                                    → Agentic → 会话绑定 Agent（未绑定/失效回退 general）\r
  4. agentService.executeSync(selectedAgent, message, sessionId)\r
       ├─ general（A）→ GeneralAssistantAgent.execute(goal)（薄适配）\r
       │     → AgentChatCaller.callWithAssembly()：按 agent 表 model 取 ChatClient（Registry）\r
       │       + MessageChatMemoryAdvisor 注入历史 + ContextAssemblingAdvisor 裁剪\r
       │       + 流式信道收集完整回复（唯一可中止通道）\r
       └─ multi-agent（B）→ MultiAgentGraphAgent.execute(goal)\r
             → 自研引擎：lead 拆解 → 并行子任务 → 聚合 → 最终回答\r
  4. writeBackContext(sessionId, message, goal)\r
  5. 返回 ChatResponse {sessionId, newSession, goalId, status, summary}\r
\`\`\`\r
\r
***\r
\r
## 3. 流式路径 \`/api/chat/stream\`\r
\r
\`\`\`\r
ChatService.streamReactive(request)\r
  0. streamConnectionLimiter.tryAcquire()     ← 过载保护（超限抛 ConcurrentRequestException → 429）\r
                                               流终结（complete/error/cancel）doFinally 释放\r
                                               （resume 入口同口径；上限 app.chat.max-stream-connections，0=不限制）\r
  1. resolveRouting(message, routing)         ← 主 Agent 分流（规则/LLM/显式覆盖，同步旁路）\r
  2. 无 sessionId → boundedElastic 上 createSession() 建档\r
  3. agentId 有值 → sessionService.switchAgent()（会话绑定副作用，失败仅告警）\r
     agent 选择（显式 routing 优先；绑定专属 Agent 跳过判定并忽略 graph）：\r
       resolveRouting → Agentic  → executeStreamReactive(会话绑定 Agent, ...)\r
                      → Graph    → executeStreamReactive(multi-agent, ...)\r
       └─ AgentService：create(sessionId) → markRunning → 订阅 Agent 流\r
       └─ Agentic 链路不升级 Graph：迭代耗尽（summarizing 有结果按部分完成返回，无结果返回兜底话术）\r
       └─ Graph 编排流异常（非客户端断开）→ 先简化（聚合失败用子任务结果汇总），\r
          再降级为会话绑定 Agent 单模型重答一次\r
         （先发「降级」进度行；重答失败保留「编排失败 + 重答失败」两段错误；\r
          app.chat.complex-fallback.enabled 开关，一层兜底不递归）\r
  4. Flux 管道：data: token… → data: [DONE] → event: meta\r
       （多 Agent 复杂路径：进度行另作 event: progress + data:{stage,detail}；仅内容 token 计入写回摘要）\r
                 （出错改为 event:error + data:msg）\r
  5. doOnComplete → writeBackContext() 写回 session_memory（多轮记忆）\r
\`\`\`\r
\r
> LLM 调用超时兜底（connect/read/stream-idle）经 \`app.chat.timeouts.*\` 参数化：\r
> connect/read 作用于阻塞通道（\`ChatClientFactory\`），stream-idle 作用于流式通道\r
> （\`AgentChatPipeline\` 的 Flux.timeout），未配置回退 10/300/120 秒现值。\r
\r
***\r
\r
## 4. 主 Agent 前置判断（路由分流，核心薄环）\r
\r
> 只做「分流」，不执行具体任务，保持入口薄；不改对外契约。\r
\r
\`\`\`\r
RouteJudge.plan(message)（接口）\r
   └─ LlmRouteJudge.plan(message)\r
       0. 显式 routing=agentic / graph → 直接 OVERRIDE（不询问判定器）\r
       1. message 为空            → Agentic（RULE）\r
       2. 命中多工作流措辞          → Graph 0.9（RULE：分别/各写/多份/同时调研…）\r
       3. 消息 ≤12 字符            → Agentic 0.95（RULE）\r
       4. 灰区：一次轻量 LLM 调用，要求输出\r
          {"route":"agentic|graph","confidence":0~1,\r
           "workstreams":[...],"deliverables":[...],"reason":"..."}\r
       5. 置信度策略：Graph 且 workstreams<2 → Agentic（FALLBACK；近似重复项先去重）\r
                      Graph 且 confidence<0.75 → Agentic（FALLBACK）\r
       兜底（任何失败都 Agentic，「宁可简单」）：\r
           异常 / 超时 / 空 / 非 JSON → Agentic，confidence=0\r
\`\`\`\r
\r
**提示词**：库中 route-judge 行的 prompt（按「可并行工作流/多交付物」组织，明确"多次串行工具调用不算 Graph"）+ 代码统一追加输出契约；判定按任务形态而非难度。\r
\r
**分流点**：同步在建档后执行前；流式在 async 流开始前（旁路）。\`resolveRouting()\` 据此选执行体并写 \`routeRef\`。\r
\r
**降级策略（流式路径）**：Agentic 链路不升级 Graph——迭代耗尽时以 \`GenerateReason.MAX_ITERATIONS\` 判定，有整理结果按「部分完成」返回，无结果返回兜底话术；Graph 编排异常时先在编排层简化（聚合失败用已完成的子任务结果汇总），仍失败再降级会话 Agent 单模型重答一次。\r
\r
**可观测**：日志 \`[route] ... path/confidence/source/reason/agent\`；SSE \`meta.route\` 与同步响应 \`route\`（RouteInfo），Web 消息头路径徽标。\r
\r
***\r
\r
## 5. 会话与目标生命周期\r
\r
| 阶段   | 动作                                                | 表                                   |\r
| ---- | ------------------------------------------------- | ----------------------------------- |\r
| 建档   | 无 sessionId → \`createSession()\`                   | \`session\`                           |\r
| 历史加载 | \`ContextAssemblingAdvisor\` + \`MessageChatMemoryAdvisor\` | 读 \`session_messages\` |\r
| 目标创建 | \`AgentService\` 执行 Goal，\`markRunning()\`            | \`goal\`                              |\r
| 执行   | 同步 \`execute()\` / 响应式 \`stream()\`                   | —                                   |\r
| 记忆写回 | 成功代表 UserMessage+AssistantMessage                 | 写 \`session_messages\`，\`touchSession\` |\r
| 完成   | \`SUCCEEDED\` / 失败 \`FAILED\`                         | \`goal\`                              |\r
\r
阻塞 DB 操作置于 \`Schedulers.boundedElastic()\`，避免阻塞响应式循环。\r
\r
***\r
\r
## 5a. 上下文组装时序（会话加载 → 组装 → 调 LLM）\r
\r
> \`ContextAssemblingAdvisor\` 只整理「已加载的历史 + 当前请求」。\r
> 编排路径同样生效：lead 节点（\`OrchestrationNodes\`）经 \`AgentChatCaller\` + \`MemoryPolicy\` 以完全同构方式挂载本 advisor\r
> （编排里唯一注入会话记忆的角色，历史轮次同预算裁剪）；子任务/聚合不注入记忆、无历史可裁。\r
> 三层 token 控制分工（历史裁剪 / user 侧预算 / 工具结果预算）见 5f 节。\r
\r
\`\`\`\r
GeneralAssistantAgent.execute*(goal)\r
│  声明路径 A 装配差异 Assembly（恒记忆 / 无频率惩罚 / 无工具次数预算 / final 档输出封顶）\r
▼\r
AgentChatCaller.callWithAssembly / streamWithAssembly（执行层单一来源，路径 B 同引擎）\r
├─ AgentRequestSpecFactory.build（统一组装链）\r
├─ .advisors(MessageChatMemoryAdvisor.builder(memoryStore).build())\r
│       → 加载历史：memoryStore.get(conversationId=sessionId)\r
├─ .advisors(new ContextAssemblingAdvisor())\r
│        ① filterNoise()    丢弃空 / 空白 / 系统噪音\r
│        ② normalizeRoles()  system 置前；user/assistant 交替（连续同类保最后）\r
│        ③ trimToBudget()   token 估算，最旧丢弃（保 system），默认 ≤ 4000\r
├─ .system(prompt).user(objective)\r
│  请求 spec → 流式信道（唯一可中止通道）\r
▼\r
③ 调 LLM：阻塞收集（call） / 逐 token 发射（executeStreamReactive，管道核 tokenStream 同源）\r
\`\`\`\r
\r
**关键点**：① 职责解耦（加载 vs 整理）；②纯函数可单测；③保留 system + 最近；④ 同步/流式都覆盖。\r
\r
***\r
\r
## 5b. 路径 B 多 Agent 编排数据流（\`MultiAgentGraphAgent\`）\r
\r
> 自研 \`ParallelOrchestrationEngine\`：「lead 拆解 → 并行子任务 → 聚合」，复杂请求（COMPLEX）执行体。流入 \`{objective}\`，流出 \`final\`。\r
\r
\`\`\`\r
MultiAgentGraphAgent.execute(goal)\r
│  引擎按阶段执行（lead → 并行子任务 → 聚合）\r
│    node：lead → subtask-0..3 → aggregate\r
│    边：START→lead →(fan-out)→ subtasks →(each)→ aggregate → END\r
│\r
│  invoke({objective})\r
│  ┌ lead 节点 ───────────────────────────┐\r
│  │  predictLead() → JSON {"subtasks":[…]} │\r
│  │  → 存 subtaskCount + subtask_0..n-1    │\r
│  └───────────────┬──────────────────────┘\r
│                  │ addEdge(lead, List(4)) 并行\r
│  ┌ subtask-0 ┐ ┌ subtask-1 ┐ … ┌ subtask-3 ┐\r
│  │ 调 LLM    │ │ 调 LLM    │     （lead 未设则短路）\r
│  │ → result_0 │ │ → result_1│     │           │\r
│  └─────┬─────┘ └─────┬─────┘     └─────┬─────┘\r
│        └─────────────┴─────────────┴─────┘\r
│                    ▼\r
│  ┌─ aggregate 节点 ────────────────┐\r
│  │ 收集 result_{i}（跳过空白），调 LLM │\r
│  │ → final                         │\r
│  └───────────────┬────────────────┘\r
│   invoke → value("final")（无则兜底返回 objective）\r
└── 统一出口：AgentService / ChatService 写回多轮记忆\r
\`\`\`\r
\r
**关键点**：① Lead 拆 ≤ \`MAX_SUBTASKS=4\`；② \`addEdge(lead,List)\` 并行；③ 复用 \`AgentChatCaller\`（按路由成员取客户端）；④ 输出 \`final\`。\r
\r
**流式执行（\`executeStreamReactive\`：「主干帧 + 三条旁路」多通道）**：\r
\r
> 动机：并行子任务的「完成」事件需与聚合 token 同流下发，由引擎在取到每个结果后逐条播报。\r
> 于是一路走主干帧覆盖能看到的节点，另一路挂 \`GraphLifecycleListener\` 补齐分支事件，最后 merge 成一条流。\r
\r
\`\`\`\r
MultiAgentGraphAgent.executeStreamReactive(goal)\r
│\r
│ ① 主干：CompiledGraph.stream({objective})（superstep 粒度）\r
│     START 帧      → 进度行「编排 开始拆解复杂目标…」\r
│     lead 帧       → 进度行「拆解 N 个子任务已就绪」\r
│     aggregate 帧  → 进度行「聚合 …」+ 内容行(final 最终回答)\r
│     END 帧        → （aggregate 已发内容则空，否则兜底 objective）\r
│         │ concatMap(toRows)\r
│         ▼\r
│ ② 旁路：BranchProgressListener（本次执行单独 compile 时注入 CompileConfig）\r
│     before(subtask-i)：进入节点的 state 含已布置的 subtask_i → 登记 scheduled\r
│     after (subtask-i)：登记命中才播报（lead 未布置的短路槽位静默，防虚假进度）\r
│         │ ProgressLine.encode("子任务", "第 i+1 个子任务完成")\r
│         │ tryEmitSerialized 加锁串行发射 ← 单播 Sink 拒绝并行线程并发发射\r
│         │   （FAIL_NON_SERIALIZED 会静默丢事件，踩坑记录）\r
│         ▼\r
│ ③ 旁路：聚合 token（liveTokens）——聚合节点流式调用逐 token 直推（含首个 token 前「聚合」进度行）\r
│\r
│ ④ 旁路：子任务工具事件（toolEvents）——子任务节点经 AgentChatCaller 注入「追踪版工具」\r
│     （ToolCallTracer 装饰 ToolCallback：schema 原样透传，仅 call 前后发事件）：\r
│       调用前 → ProgressLine.encode("tool", "WriteFile(/tmp/a.py)")\r
│       调用后 → ProgressLine.encode("tool-done", "WriteFile ✓ 1.2s · +12/-3 行")\r
│       （失败 → "WriteFile ✗ 0.3s"；diff 摘要从入参 old/new 串行数近似，仅文件写入/编辑类可得）\r
│     路径 A（GeneralAssistantAgent.executeStreamReactive）同样以旁路 sink 合并工具事件\r
│\r
│ ⑤ 合流：mainLine.mergeWith(branchEvents).mergeWith(liveTokens).mergeWith(toolEvents)\r
│     ⚠️ 关闸 doFinally(tryCompleteSerialized) 必须挂在 merge **之前**的主干段上：\r
│        merge 要求两源都终结才传 complete，关闸挂 merge 之后会循环等待 → 死锁（踩坑记录）\r
\`\`\`\r
\r
**行级线协议**（\`ProgressLine\`，跨组件统一格式）：\r
\r
| 行类型 | 格式 |\r
| --- | --- |\r
| 进度行 | \`\\u0000stage\\u0001detail\`（首字符 MARK，非打印字符防撞内容） |\r
| 内容行 | 无前缀原文（token / 最终回答 final） |\r
\r
**服务端 → CLI 转换（\`ChatServiceImpl.streamReactive\`）**：\r
\r
| Agent 流出行 | SSE 输出 | 记忆写回 |\r
| --- | --- | --- |\r
| 进度行 | \`event: progress\` + \`data: {"stage":..,"detail":..}\`（Jackson 序列化 \`StageRow\` record） | 排除，不写回 |\r
| 内容行 | \`data: <row>\` | \`doOnNext\` 收集，\`[DONE]\` 后统一写回多轮记忆 |\r
\r
CLI 解析到 \`event: progress\` 按阶段分派渲染：\`编排/聚合\` 转 spinner（原位刷新，完成折叠归档灰色 \`✓ 阶段 · 耗时\`）、\`拆解/子任务\` 直接归档摘要行、\`tool\` 转 \`⏺ 工具名(参数)\` spinner、\`tool-done\` 归档着色结果行（✓ 绿 / ✗ 红，\`+N/-M 行\` diff 着色）；\`agent\` 行（流首，detail=实际路由的 agent 名）供 CLI 在首个回答 token 前渲染「agentName> 」前缀，与用户侧「你> 」提示符对称；最终回答仍按内容流逐 token 呈现打字机效果——**增量直出**（只补打未上屏部分，不依赖终端擦行重绘；2026-09-06 修复：原每 token 整行擦除重绘在 \`\\r\\033[2K\` 失效终端表现为同段文字带渐长尾巴重复），仅有着色收益的行（标题/列表/粗体/行内代码/代码块）完成时才整行重绘升级。\r
\r
**测试**：\`MultiAgentGraphAgentTest\`（mock Registry/ChatClient 固定返回，断言进度阶段时序与子任务事件数量）+ \`ChatServiceImplTest.streamReactive_shouldMapProgressRowToProgressEvent\` + \`ToolCallTracerTest\`（事件组装/装饰行为/schema 透传）+ \`TerminalRendererTest\`（工具行渲染）。\r
\r
***\r
\r
## 5c. 沙箱工具调用数据流（容器级隔离）\r
\r
> 模型生成的代码/命令**只在 Docker 容器内执行，宿主机零暴露**（自研宿主机工具 FileTools/SearchTools/ShellTools 已「重合即退役」，无降级路径）。工具面由 \`SandboxToolProvider\`（agentscope-runtime）提供，\`ToolAssignments\` 按专家硬性分配。\r
\r
\`\`\`\r
专家子任务执行（coder / analyst / general / researcher）\r
│\r
① 工具注入（请求组装期，可插拔装饰链，默认链见 DefaultToolDecorators）\r
   AgentChatCaller.buildSpec()（两路径统一出口）\r
   → ToolAssignments.forAgent(专家名) → ToolSet{annotated(@Tool), callbacks(ToolCallback)}\r
   → AgentRequestSpecFactory 按 Order 升序应用装饰链（装饰器自判适用条件，不适用原样直通）：\r
   → 观测层 ToolObservationDecorator(100)：emitter/recorder 任一非空即装饰——调用前发 tool /\r
     调用后发 tool-done 进度行 + 异步落库 tool_call_log（无 SSE 链路的观测盲区由此覆盖；\r
     schema 原样透传，模型不可见差异）\r
   → 预算层 ToolBudgetDecorator(200)：单次调用内执行次数硬上限（tool-call-limit=8）+\r
     工具结果总量 token 预算（tool-result-budget=5000）——超限不再真执行，返回引导文本收束循环\r
   → 懒加载层 ToolLazyLoadDecorator(300)（最外层，会话级两段式）：未展开工具包轻量态（schema 置空、\r
     仅名称在 PromptAssembler 工具索引段可见）+ 追加 expand_tool 元工具（不经观测/预算——\r
     元工具不产生工具行噪声、不占真实执行额度）；模型先 expand 再正式调用\r
   → 元工具层 SkillMetaToolDecorator(400)：该 agent 有可见技能时追加 load_skill 元工具（同 expand_tool 口径）\r
   → spec.toolCallbacks(装饰后回调)\r
   ⚠️ 服务端硬边界：未分配的工具 schema 不出服务端，模型不可见也无执行注册\r
│\r
② 工具面来源（SandboxToolProvider，懒初始化仅一次）\r
   首次取用 → 双检锁 → SandboxService.start() + BaseSandbox\r
   → Docker 拉起容器（镜像 runtime-sandbox-base，容器 :80 → 宿主随机端口）\r
   → SandboxToolkit 把容器能力包装为 18 个 ToolCallback，按安全类别三组：\r
       执行类×2（RunPythonCode / RunShellCommand）\r
       只读文件类×6（Read / ReadMultiple / ListDir / DirectoryTree / Search / FileInfo）\r
       写入类×4（Write / Edit / CreateDir / Move）\r
   （初始化失败 → 空工具面，warn 不重试，绝不回退宿主机执行）\r
│\r
③④ 模型侧循环（LLM tool-calling）\r
   请求（工具 schema + 任务描述）──────────▶ LLM\r
   ◀────────── tool_call {name, arguments} ─┐\r
   │                                         │\r
   ▼ ④ 服务端执行（Spring AI 框架）           │\r
   解析 tool_call → 匹配 ToolCallback → 调用   │\r
   → agentscope-runtime SDK ──HTTP──▶ 容器内 fastapi\r
                                       └─ 容器内执行 Python/Shell/文件操作（隔离文件系统）\r
   ◀── stdout / returncode / 文件内容 ──────┘\r
   → 执行结果作为 tool 消息回传 LLM（含失败也以模型可读文本回传）\r
│\r
⑤ 循环 ③④，直至 LLM 不再发起 tool_call → 产出最终回答\r
\`\`\`\r
\r
**专家分配表**（\`ToolAssignments\`，最小权限）：\r
\r
| 专家 | @Tool 对象 | Sandbox ToolCallback | MCP ToolCallback |\r
| --- | --- | --- | --- |\r
| researcher | WebTool（轻量静态抓取：jsoup 解析 + 主内容提取 + Markdown 输出，沙箱未覆盖） | 只读文件类 | 是（见 5e 节，扩展工具生态） |\r
| coder | — | 执行类 + 写入类（读→改→跑验证闭环） | — |\r
| analyst | — | 执行类 + 只读类 | — |\r
| general | WebTool | 全量 | 是（见 5e 节） |\r
| writer / 未登记（含 lead、aggregator、multi-agent） | — | 空集（不触发沙箱初始化） | — |\r
\r
**关键点**：① 模型生成的代码/命令只落容器，宿主机零暴露；② 空集专家完全不触碰 Docker；③ 容器随 \`@PreDestroy\` 释放（\`SandboxService.close()\`），宿主机无残留；④ 执行失败包装为模型可读文本回传，由模型自行调整重试。\r
\r
**测试**：\`ToolAssignmentsTest\`（双通道分配语义、EMPTY 不触发沙箱初始化）+ JShell 直连真实验证（容器拉起 → 容器内 Shell returncode=0 → 退出自动删容器，见 \`docs/reports/2026-08-28-sandbox-integration.md\`）。\r
\r
***\r
\r
## 5d. LLM 调用观测数据流（成本账本）\r
\r
> 每次真实 LLM 调用结束后，把「耗时 / token / 成败」异步写 \`llm_call_log\` 表——账本性质，与主链路解耦：观测失败绝不影响调用本身。\r
\r
\`\`\`\r
LLM 调用出口（五类调用点统一收敛）\r
│  ① LlmRouteJudge.plan()          路由判断（阻塞 RestClient，token 取响应 Usage 真实值）\r
│  ② 编排 lead()（OrchestrationNodes）  lead 拆解（经 AgentChatCaller.call）\r
│  ③ 编排子任务（OrchestrationNodes）   专家执行（经 AgentChatCaller.call，工具调用不单独记账）\r
│  ④ 编排聚合（OrchestrationNodes）     AgentChatCaller.stream（逐 token 收集后估算）\r
│  ⑤ GeneralAssistantAgent          路径 A（经 callWithAssembly/streamWithAssembly；\r
│                                    响应式链 executeStreamReactive 自挂终结钩子记录）\r
│\r
▼ AgentChatCaller / GeneralAssistantAgent / LlmRouteJudge 埋点\r
   调用前记 start；成功解析 Usage（真实 token）/ 失败取异常消息\r
        │\r
        ▼ LlmCallRecorder.record(LlmCallLog)          ← 旁路，boundedElastic 异步\r
   落库 llm_call_log：session_id / agent_name / model /\r
   call_kind(SYNC|STREAM) / status(OK|ERROR) /\r
   prompt_tokens / completion_tokens / total_tokens /\r
   tokens_estimated(1=按输出文本近似估算) /\r
   duration_ms / error_msg / created_at\r
   （落库失败仅 warn，不重试不阻塞主链路）\r
        │\r
        ▼ 查询：GET /api/llm-calls?sessionId=&limit=（LlmCallController，按 id 倒序）\r
\`\`\`\r
\r
**关键点**：① 全部调用点收敛埋点（路径 A/B + 路由判断全覆盖）；② 异步旁路（主链路零等待）；\r
③ token 两个口径——**仅 LlmRouteJudge 阻塞调用**取响应 \`Usage\` 真实值（prompt/completion 均有）；\r
其余调用经 \`AgentChatCaller\` 流式背书（2026-09 取消改造，流式是唯一可中止通道），流式响应\r
未开 usage 回传 → \`prompt_tokens=null\`、\`completion_tokens\` 按输出文本近似估算\r
（中文 1 token、其它 (长度+3)/4，与 \`ContextAssemblingAdvisor\` 同口径）；④ 每次调用只查一次 agent 表（观测的 model 与请求组装共用）。\r
\r
**与 Micrometer Tracing 的关系**：本表是成本账本（SQL 可聚合按角色/会话统计花费）；Tracing 是性能显微镜（单次请求耗时瀑布），互补不互替（Tracing 见 TODO「完整链路追踪」）。\r
\r
**测试**：\`LlmCallRecorderTest\`（估算口径 / 异步落库 / 落库失败不影响调用方）。\r
\r
***\r
\r
## 5e. MCP 工具接入数据流（进程内 server ↔ client 闭环）\r
\r
> 通过 Model Context Protocol（MCP）把工具标准化注入 tool-calling 生态。当前为「自建 + 同进程闭环」架构：\r
> 本应用既作为 **MCP Server**（\`McpServerTools\` 暴露工具至 \`/mcp\` 端点），也作为 **MCP Client**\r
> （\`McpToolProvider\` 经 \`/mcp\` 发现工具），全流程 HTTP/Streamable 传输，无需外部服务。\r
\r
\`\`\`\r
① MCP Server（本进程，Streamable-HTTP 传输）\r
   McpServerTools（@Component，3 个 @Tool 演示工具：sum / greet / today）\r
     └─ 内嵌 @Configuration 声明 ToolCallbackProvider bean（mcpServerToolsProvider）\r
           → 把 @Tool 方法转成 MCP tool 规范\r
     └─ spring.ai.mcp.server.protocol=STREAMABLE + streamable-http.mcp-endpoint=/mcp\r
        → McpServerAutoConfiguration 注册端点：tools/list 列出 3 个工具，\r
          tools/call 由 bean 分派执行并回传结果（日志 "Registered tools: 3"）\r
        （protocol 若为默认 SSE，端点不在 /mcp → 客户端连不上，必须显式置 STREAMABLE）\r
\r
② MCP Client（懒连接 + 失败降级，架构与 SandboxToolProvider 同源）\r
   McpToolProvider.toolCallbacks()\r
     ├─ 已缓存 → 直接返回（单例，只连一次）\r
     ├─ server-url 为空 → warn + 返回空工具面（不发起连接）\r
     └─ 首次取用 → 双检锁 → HttpClientStreamableHttpTransport(url)\r
          → McpSyncClient（request/initialization 超时）→ SyncMcpToolCallbackProvider\r
          → 4 端内建 /mcp 工具（连接/发现失败 → warn + 空工具面，绝不抛异常拖垮应用）\r
   ⚠️ client 与 server 同进程：须待 Spring 启动完成 /mcp 才可达，懒连接天然规避启动死锁\r
\r
③ 分配（ToolAssignments，硬边界）\r
   only researcher: mcp.toolCallbacks() 注入其 ToolSet.callbacks\r
   其他专家/编写者（含 lead、aggregator、multi-agent）不注入 MCP 工具 schema，\r
   未分配工具模型不可见、服务端也无执行注册\r
\r
④ 模型侧循环（与 5c③④ 完全同构，由 Spring AI tool-calling 统一驱动）\r
   请求（含 MCP 工具 schema）──▶ LLM\r
   ◀── tool_call {name, arguments} ┐\r
   ▼ 执行：解析 tool_call → 匹配 MCP ToolCallback → McpClient 调 /mcp → tools/call\r
   ◀── 工具结果 ──┘ 转为 tool 消息回传 LLM → 循环直至产出最终回答\r
\`\`\`\r
\r
**专家分配表**（\`ToolAssignments\`，最小权限）：\r
\r
| 专家 | 是否注入 MCP 工具 | 说明 |\r
| --- | --- | --- |\r
| researcher | 是 | 探索者，MCP 工具并入其工具面 |\r
| general | 是 | 全量（执行 + 读写 + 检索 + 网页 + 浏览器 + MCP 工具） |\r
| coder | 否 | — |\r
| analyst | 否 | — |\r
| writer / 未登记（multi-agent） | 否 | 空集 |\r
\r
**关键点**：① server 与 client 同进程，端点 \`/mcp\` 由 STREAMABLE 协议提供；② client 懒连接 + 失败降级，未配置/连不上返回空工具面，不影响主链路启动；③ 单例缓存一次连接，后续取用零重复握手；④ 由 \`ToolAssignments\` 硬性分配（当前 researcher 与 general），未分配 agent 不可见不可调。\r
\r
**测试**：\`McpToolProviderTest\`（未配置→空 / 空白→空 / 不可达→空且不抛错 / 连上运行中 \`/mcp\`→发现 3 工具 sum/greet/today）+ \`ToolAssignmentsTest\`（researcher/general 得 MCP 工具，其余 agent 不得）+ 手动 HTTP 验证（initialize → tools/list → tools/call sum(3,5) 得 sum=8）。\r
\r
***\r
\r
## 5f. Prompt 组装与上下文注入数据流（prompt 包）\r
\r
> 两路径统一的 system prompt 组装管线（\`PromptAssembler\`）+ 会话记忆按角色注入（\`MemoryPolicy\`）\r
> + 思考开关动态注入（\`ThinkingSwitchChatModel\`）。一次 LLM 请求的上下文 = 四部分：\r
> system（分段式）+ 工具 schema（三层装饰后）+ messages（记忆 + 当前输入）+ options（模型参数）。\r
\r
\`\`\`\r
AgentChatCaller.buildSpec()（统一出口）\r
│\r
① system prompt：PromptAssembler.assembleSections(agentName, roleFallback, config, channel)\r
   分段式（按 order 排序、空段跳过、空行分隔）：\r
     1 角色段   → agent 表行 prompt > 调用方兜底 roleFallback（lead/aggregator 兜底词、\r
                  子任务专家 persona）> 默认「执行任务的 AI 助手」\r
     2 用户偏好画像段 → UserProfileSectionProvider（general/lead）\r
     3 工具索引段 → ToolAssignments 用途元数据（名称：用途，仅本 agent 分配面）；\r
                  lazy 开启时追加 expand_tool 使用引导（与 5c 第三层联动）\r
     4 工具纪律段 → 网络类合计 ≤8 次 / 同 URL 只抓一次 / 材料够立即停止（提示词软约束，\r
                  真正兜底是 5c 第二层 8 次硬上限）\r
     5 输出约定段 → 「直接给出完成结果」\r
     6 信道规范段 → channel=qq：IM 短答（≤100 词、只问决策）；cli/web/api：结构化完整交付；\r
                  空渠道不注入（channel 经 ChatRequest → Goal → CallTrace 贯通）\r
     7 skill 段  → SkillSectionProvider 扩展点\r
│\r
② messages：MemoryPolicy 按角色注入会话记忆（同一 memoryStore，经 ContextAssemblingAdvisor\r
   同预算裁剪，与路径 A general 完全同口径）\r
     - 注入：路径 A general / 编排 lead（拆解需要理解多轮指代）\r
     - 不注入：aggregator（忠实汇总子任务结果，不掺对话）、子任务专家、route-judge\r
     - 历史引用：被预算裁掉的消息 ID 由 advisor 汇总成一行「历史引用」追加进 system，\r
       提示模型调用 recall_history 检索归档（见 5j）\r
     - 无会话 ID 自动跳过（无记忆场景零开销）\r
│\r
③ options：模型参数注入\r
     - model ← Agent 绑定路由的首选成员 upstream_name（经 AgentCatalog/ModelResolver）\r
     - frequencyPenalty=0.5（编排调用，抑制复读）\r
     - enable_thinking:false ← 模型 default_params.thinking_mode=off 时由请求选项注入\r
\`\`\`\r
\r
**关键点**：① 组装管线两路径统一（编排节点硬编码 persona/纪律拼接已删除）；② 记忆注入策略\r
集中一处（MemoryPolicy 一个类改策略）；③ skill 段为后续「skill 动态装配」预留接缝。\r
\r
**测试**：\`PromptAssemblerTest\`（段序/来源优先级/空段跳过）+ \`MemoryPolicyTest\`（角色矩阵）+ \`ToolLazyManagerTest\`（两段式/自愈）。\r
\r
***\r
\r
## 5g. Agent 表驱动自动注册与路由（AgentRegistry）\r
\r
> Agent 注册口径从「bean + 表行」收敛为「agent 表一行」：注册一个新 Agent = 插一行数据，\r
> 无需改代码、无需重启（新增行立即可用；已有行的 prompt/tools/route_id 改动最多滞后 30s）。\`is_internal=1\`（multi-agent/lead/aggregator）标记内部编排角色。\r
\r
\`\`\`\r
启动期\r
   AgentConfigProvider.listAgentNames()（只返回 is_internal=0 的对话行）\r
     → AgentRegistry 注册全部对话 Agent（构造 Agent + ChatClient 失败 → fail-safe 跳过该行，\r
       warn 不拖垮启动；general 缺行 → 代码兜底注册，DB 优先不合并）\r
\r
请求期（AgentServiceImpl.requireAgent / getAgentConfig）\r
   注册表命中 → 直取实例（零 DB 查询）\r
   未命中（如 CLI /agent 切到新插入的 nailong）\r
     → 查表惰性注册：ConcurrentHashMap.computeIfAbsent 原子构造（并发请求只构造一次）\r
     → 表中无该行 → 抛「未知 Agent」（CLI 展示可用列表）\r
     ⚠️ 运行中插行即生效（免重启）；已有行的配置改动走 30s 缓存。查表仅依赖 Dao 层，无循环依赖\r
\`\`\`\r
\r
**关键点**：① 单一事实来源（agent 表）；② internal 标记避免编排角色被对话路由选中；\r
③ fail-safe（脏行不影响其它 Agent 与启动）；④ 兜底语义明确（general 代码兜底仅缺行时，两来源不合并）。\r
\r
**测试**：\`AgentRegistryTest\`（自动注册/惰性热注册/internal 排除/兜底/脏数据跳过）+ \`AgentServiceImplTest\` 适配。\r
\r
***\r
\r
## 5h. Goal 执行通道与线程池治理（2026-09-06）\r
\r
> Goal 有三条执行通道，线程归属各不相同；后台执行走受管线程池，不再占用\r
> \`ForkJoinPool.commonPool()\`（CPU-1 线程且被并行流等 JVM 全局共用，分钟级阻塞\r
> LLM 调用占满会拖累无关代码）。\r
\r
\`\`\`\r
① 同步通道 executeSync\r
   Tomcat 请求线程内直接阻塞执行 run()（调用方要的就是同步语义）\r
\r
② 异步通道 submit（POST /api/harness/submit）\r
   goalService.create（PENDING 落库）→ goalExecutor.execute(() -> run(goal, agent))\r
   → 池内线程：markRunning → agent.execute（阻塞 LLM 调用）→ SUCCEEDED/FAILED 落库\r
   客户端轮询 GET /api/harness/goals/{id} 取终态\r
   ⚠️ 拒绝兜底：池满（8 在途 + 50 队列满）时 execute 抛 RejectedExecutionException\r
      → submit 捕获 → goal.fail("执行队列已满…") 落库（终态可见，不留无声排队 PENDING）\r
   ⚠️ run() catch Throwable：Error 逃逸也回写 FAILED，goal 不再永久卡 RUNNING\r
\r
③ 流式通道 executeStreamReactive / resume\r
   MVC 异步请求处理（Flux→SSE 桥接的完成/超时派发）走 applicationTaskExecutor 槽位\r
   （mvc-async- 前缀池，Boot 按 bean 名接线）\r
   业务侧阻塞 DB / Agent 执行 → Schedulers.boundedElastic()（Reactor 自带池）\r
\`\`\`\r
\r
**线程池配置**（\`GoalExecutorConfig\`，两池分离互不挤占）：\r
\r
| 池 | bean 名 | 参数 | 承载 |\r
| --- | --- | --- | --- |\r
| goal 执行池 | \`goalExecutor\` | core=max=8 / 队列 50 / 前缀 \`goal-exec-\` / 优雅停机 30s | 后台 Goal（分钟级阻塞 LLM 调用，线程数=最大并发编排数） |\r
| MVC 异步槽位 | \`applicationTaskExecutor\` | core=8 / 前缀 \`mvc-async-\` / 默认无界队列 | 流式 SSE 的结果/超时派发（任务短小） |\r
\r
⚠️ **Boot 槽位坑**：项目定义任意 \`Executor\` bean 会让 Boot 自动配置的 applicationTaskExecutor\r
让位（\`@ConditionalOnMissingBean(Executor.class)\`），而 MVC 异步按 bean 名\r
\`containsBean("applicationTaskExecutor")\` 接线——槽位缺席时流式请求回退为\r
**每请求开新线程**的 \`MvcSimpleAsyncTaskExecutor\`（无界）并打生产告警（static 闸每 JVM\r
只打一次，易漏）。故两个池必须成对显式定义。\r
\r
**单实例假设**：启动清理 \`failAllRunning\`（RUNNING → FAILED）与本地线程池都基于单实例\r
部署；水平扩展需先解除该假设（见 TODO「多实例水平扩展」）。\r
\r
**Goal 进展心跳与停滞看门狗（2026-11-03）**：\r
- \`goal.last_progress_at\`（V4）：\`markRunning\` 初始化；流式行流按 30 秒节流刷新；编排检查点\r
  （\`LEAD_DONE\` / \`SUBTASKS_DONE\`）保存时同步刷新（\`OrchestrationCheckpointStore\`）。\r
- \`StaleGoalWatchdog\`（\`app.watchdog.enabled\`，默认关闭）：定时扫描 RUNNING 且\r
  \`last_progress_at\` 为空或早于 \`stagnant-minutes\` 的目标，条件更新（WHERE status=RUNNING）\r
  为 FAILED 并写原因；只标失败、不自动重投（重投见 TODO P2）。\r
\r
**测试**：\`AgentServiceImplTest\`（10 并发 submit 全 SUCCEEDED / 队列满拒绝兜底落 FAILED / LinkageError 逃逸回写 FAILED）、\`StaleGoalWatchdogTest\`。\r
\r
***\r
\r
## 5i. RAG 知识检索注入数据流（prompt 组装前旁路）\r
\r
RAG 不是显式指令触发，而是**每次组装 prompt 前的旁路检查**——条件不满足全部静默降级，\r
主链路零感知（入口用法与多知识库绑定见 README「知识库问答」节）。\r
\r
触发决策流程：\r
\r
\`\`\`mermaid\r
flowchart TD\r
    A[用户请求<br/>路径 A 直接答 / 路径 B 编排节点] --> B["AgentRequestSpecFactory<br/>组装 system prompt 前"]\r
    B --> C{"app.knowledge.enabled?"}\r
    C -->|false| X[静默跳过<br/>零副作用]\r
    C -->|true| D{"agent 角色 ∈ 跳过名单?<br/>aggregator：材料是子任务结果"}\r
    D -->|是| X\r
    D -->|否| D2{"agent 绑定知识库?<br/>knowledge 列非空（NULL/空白 = 不检索）"}\r
    D2 -->|否| X\r
    D2 -->|是| E{"user 文本长度 ≥<br/>min-query-chars（8）?"}\r
    E -->|否| X\r
    E -->|是| F["向量检索：user 文本 → DashScope 嵌入<br/>→ pgvector cosine top-k（4）"]\r
    F --> G{"有命中且相关度 ≥<br/>min-score（0.5）?"}\r
    G -->|否| X\r
    G -->|是| H["逐条累加 token：<br/>超过 context-budget（3000）的命中截留"]\r
    H --> I["渲染【出处N】知识块<br/>追加到 system prompt"]\r
    I --> J["模型回答：句末内联【出处N】<br/>+ meta.sources + CLI 来源尾注"]\r
\`\`\`\r
\r
检索注入时序：\r
\r
\`\`\`mermaid\r
sequenceDiagram\r
    participant U as 用户\r
    participant F as AgentRequestSpecFactory\r
    participant R as KnowledgeRetriever\r
    participant S as KnowledgeService\r
    participant E as DashScope 嵌入\r
    participant V as pgvector 向量库\r
    participant L as LLM\r
    U->>F: 请求（路径 A / 路径 B 各节点）\r
    F->>R: buildKnowledgeBlock(agent, sessionId, user)\r
    R->>R: enabled / 角色 / 查询长度 逐层短路\r
    R->>S: search(query)\r
    S->>E: query 嵌入\r
    S->>V: cosine 相似度 top-k\r
    V-->>S: 命中片段\r
    S-->>R: List<KnowledgeHit>\r
    R-->>F: 知识块（预算内渲染【出处N】）或 null\r
    F->>L: system prompt（含知识块）\r
    L-->>U: 回答内联【出处N】+ meta.sources\r
\`\`\`\r
\r
编排子任务并行预取（COMPLEX 路径）：lead 节点出口在拆解出全部子任务、写出状态键之后、扇出之前，对每个已布置子任务并行提交 RAG 预取——此为主提交点，检索延迟借此消化在子任务并发限流（\`subtask-concurrency\`）的排队空档内；subtask 节点入口另做轻量补提交，覆盖断点续跑（lead 不重跑）路径。预取参数与执行完全同源（agentName=实际执行专家名、query=子任务文本、sessionId=编排会话），预取缓存按 \`sessionId + query\` 复合键多条目共存、重复提交幂等；全部提交 fire-and-forget，失败静默降级——该子任务退化为执行期现查或无知识段，编排主流程零感知。\r
\r
触发条件一览（全配置驱动，改 \`application-knowledge.yaml\` 的 \`app.knowledge.*\` 即调）：\r
\r
| 触发条件 | 配置项 | 当前值 | 不满足时 |\r
|---|---|---|---|\r
| 知识库总开关 | \`enabled\` | true | 静默跳过 |\r
| 角色不在跳过名单 | ——（aggregator 策略跳过） | aggregator | 静默跳过 |\r
| 查询长度达标 | \`min-query-chars\` | 8 | 静默跳过 |\r
| 命中相关度达标 | \`min-score\` | 0.5 | 丢弃该命中 |\r
| 注入预算内 | \`context-budget\` | 3000 | 截留超预算命中 |\r
\r
**测试**：\`KnowledgeRetrieverTest\`（角色跳过/预算截断/【出处N】渲染）、\`KnowledgeServiceImplTest\`（增量摄取/删除/分页/检索）。\r
\r
***\r
\r
## 5j. 会话历史归档与链式召回（最小版，2026-11-03）\r
\r
> 目标：上下文窗口裁剪不再等价于永久丢失。快照 item 带稳定 ID；写回裁剪时把移除项转归档；\r
> 请求组装时向模型提示「窗口外还有历史」；模型经 recall_history 按需取回。\r
\r
\`\`\`\r
写回（SessionServiceImpl.saveContext，@Transactional）\r
  session_messages 快照 JSON items\r
    ├─ 存量 item 补稳定 ID（m-<uuid>），新消息写入 ID + role + content + ts\r
    ├─ trimSnapshot：超条数/字符上限 → 移除项收集\r
    │     └─ session_message_archive（part=full；单条超限截断尾部 part=tail）\r
    └─ 快照 JSON 更新（归档与快照同事务，失败一起回滚）\r
\r
请求组装（AgentRequestSpecFactory → ContextAssemblingAdvisor）\r
  loadContext → Message metadata(messageId)\r
  → trimToBudget 收集被丢消息 ID\r
  → 从 system 追加一行「历史引用：更早 N 条已归档，ID 示例 m-xxx，可用 recall_history」\r
\r
工具调用（general / lead，V5 分配）\r
  recall_history(query | messageId, scope, limit)\r
  → ToolContext.sessionId（服务端注入，模型不可见）\r
  → SessionHistoryArchiveService.search(scope=current 默认 / all)\r
      current：WHERE session_id=? + messageId/正文 LIKE\r
      all    ：不加 session_id，按 archived_at 倒序（V11 idx_archive_time）+ 关键词，\r
               结果标注「会话 N / messageId」，非法 scope 直接拒绝\r
      两者均 LEFT(content, 2000) + LIMIT ≤10\r
  → ToolCallBudget 结果 token 预算\r
\`\`\`\r
\r
**边界**：无 Trigger/Thread/Worker 引用图与多跳展开，未做向量化；归档检索按关键词/精确 ID，\r
\`scope=all\` 的跨会话召回靠时间倒序索引提前取够 10 条即停，关键词本身仍是 LIKE 顺序扫描。\r
裁剪丢弃的轮次另由 \`SessionSummaryService\`（P2-3）压缩成 \`session_summary\` 摘要，与归档互补：\r
摘要是常驻的短上下文（每轮注入），归档是按需取回的原文。\r
\r
**测试**：\`SessionServiceImplTest\`（ID 补齐/裁剪归档）、\`ContextAssemblingAdvisorTest\`（引用行）、\r
\`RecallHistoryToolsTest\` + \`SessionHistoryArchiveServiceTest\`（会话隔离 / 条数钳制 / scope=all 跨会话与排序）。\r
\r
## 5k. 显式记忆：记住 / 忘掉 / 查看（2026-11-07）\r
\r
> 用户明说「记住…」的事实落 \`memory\` 表，跨会话注入；与画像（模型推断）分开存、分开注入，\r
> 冲突时以用户明说为准。\r
\r
\`\`\`text\r
写入（两条通道，同一服务）\r
  对话：remember 工具（source=user，sessionId 经 ToolContext 服务端注入）\r
  CLI / 管理接口：POST /api/memories（source=manual）\r
    → MemoryService.remember：同 key 先软删旧条目 → INSERT memory\r
  删：forget 工具（id 精确删 / keyword 定位；多命中不删、返回候选）\r
      DELETE /api/memories/{id} → 软删 deleted_at（保留审计）\r
\r
注入（PromptAssembler，每轮）\r
  ExplicitMemorySectionProvider（order 140，排在画像 150 之前）\r
    → activeForInjection：deleted_at IS NULL AND (expires_at IS NULL OR expires_at > now)\r
    → 全量注入 + 条数/字符上限截断，段末声明「冲突以用户明说为准」\r
\`\`\`\r
\r
**边界**：全量注入（个人记忆量小），未做按相关度检索；过期/软删靠读取时过滤，不维护状态列、\r
也不跑定时清理。\`memory\` 与 \`user-profile\` 是两套存储——前者用户声明、后者模型推断，同名不合并，\r
靠注入顺序（140 先于 150）+ 段内声明保证显式记忆优先。\r
\r
**测试**：\`MemoryServiceImplTest\`（同 key 覆盖 / 软删 / 状态判定）、\`MemoryToolsTest\`（多命中不删）、\r
\`ExplicitMemorySectionProviderTest\`（角色过滤 / 截断 / 优先级）、\`MemoryControllerTest\`（REST 契约）。\r
\r
***\r
\r
## 6. 错误处理\r
\r
| 环节             | 处理                                        |\r
| -------------- | ----------------------------------------- |\r
| 主 Agent 判断失败   | 异常/空/非 JSON → \`SIMPLE\`，不阻塞                |\r
| Agent 执行失败（同步） | \`ChatResponse.failure\`（status=FAILED），不写回 |\r
| Agent 执行失败（流式） | SSE \`event:error\`，末尾 \`meta.status=FAILED\` |\r
| 未知 agentId     | 兜底 \`general\`                              |\r
| 后台 submit 队列已满 | \`RejectedExecutionException\` 捕获 → goal 落 \`FAILED\`（原因=执行队列已满），客户端轮询可见终态，不无声排队 |\r
| 后台执行 Error 逃逸 | \`run()\` catch \`Throwable\` → goal 落 \`FAILED\`（原因=异常摘要），不残留 RUNNING |\r
| lead 拆解失败      | 退化单任务；子任务失败留空，聚合跳过                        |\r
| 流式请求挂死（厂商端静默） | 流上 Reactor \`timeout\` 空闲超时 120s（\`STREAM_IDLE_TIMEOUT\`，覆盖工具执行期最长正常静默 ~8s 的 10 倍余量）→ 快速失败转重试判定（超时不可重试，直接上抛） |\r
| 聚合流式失败       | 带护栏的流式重试一次（聚合只有流式一条语义路径）：未推出任何 token（挂死超时等首 token 前失败）或 0 内容 token → 重试；已推 token 后失败 → 不重试（内容重复风险），以已收内容为准；重试仍失败 → 上抛按失败收尾 |\r
| 沙箱初始化失败（无 Docker） | 空工具面 + warn（不重试、不回退宿主机），其余功能不受影响        |\r
| 工具执行失败         | 包装为模型可读文本回传 LLM，由模型调整重试                    |\r
| 观测落库失败         | warn 单行、不重试、不影响主链路（观测旁路永不阻塞调用）              |\r
\r
## 6a. 客户端断连（cancel）传播链路\r
\r
多 Agent 执行期间客户端断开（CLI 超时/退出）→ Tomcat 连接重置（\`AsyncRequestNotUsableException\` /\r
\`ClientAbortException\`）→ MVC 异步管道向 Reactor 下发 **cancel** 信号（complete/error 均不触发）：\r
\r
\`\`\`\r
客户端断开\r
  → Tomcat 报连接重置（框架层 ERROR 堆栈被 ClientAbortLogFilter DENY 降噪）\r
  → Reactor cancel 沿链传播\r
      ├─ ChatServiceImpl.doOnCancel        → warn 单行（sid），可观测\r
      ├─ AgentServiceImpl.doOnCancel       → goal.fail("客户端断开，编排已取消") + 落库（不残留 RUNNING）\r
      └─ MultiAgentGraphAgent 主干 doFinally(CANCEL)\r
            ├─ 置位 cancelled 标志 + warn\r
            ├─ 三个旁路 sink 关闸（branchEvents/liveTokens/toolEvents）\r
            └─ lead / subtask-i / aggregate 节点执行前检查 cancelled → 短路，不再发起新的 LLM 调用\r
            └─ 三节点把共享 cancelled 标志传入 AgentChatCaller.call()（BooleanSupplier 取消令牌）\r
\`\`\`\r
\r
边界说明：\r
- **在途 LLM 请求可中止（2026-09 补齐）**：\`AgentChatCaller.call()\` 统一流式通道背书\r
  （RestClient 阻塞调用不可中断，JDK HttpClient 不响应线程中断，流式是 Spring AI 2.x +\r
  JDK 连接器下唯一能中止在途 HTTP 请求的通道）——取消令牌置位后调用直接抛取消异常\r
  （零 HTTP 请求）；执行中置位经 \`takeUntil\` 在下一个 token 边界中止订阅，取消向上传播\r
  关闭 HTTP 连接（厂商端停止生成）。取消不重试、部分输出不按成功返回、\`llm_call_log\`\r
  记 \`ok=false\` 且 \`error_msg\` 含 \`client-cancelled\`。副作用：该路径 token 用量从响应\r
  usage 真实值变为按输出文本估算（见 5d 节口径）。\r
- 路径 A（\`GeneralAssistantAgent\`）为纯响应式流，cancel 直达 WebClient 终止拉流，无需额外处理。\r
- 降噪范围收窄：仅框架 logger（\`org.apache.catalina/coyote/tomcat\`、\`org.springframework.web\`）\r
  的 ERROR + 断连特征命中才 DENY；业务代码中的连接类异常保留全量堆栈。\r
\r
***\r
\r
## 7. 组件映射\r
\r
| 环节         | 组件                                                   |\r
| ---------- | ---------------------------------------------------- |\r
| 入口         | \`ChatController\` / \`ChatService\` / \`AgentService\`    |\r
| 会话加载       | \`SessionService.loadContext(sessionId)\`              |\r
| 上下文组装      | \`ContextAssemblingAdvisor\`                           |\r
| 主 Agent 判断 | \`RouteJudge\` / \`LlmRouteJudge\` / \`RouteDecision\`     |\r
| Agent 注册/路由 | \`AgentRegistry\`（表驱动自动注册 + 惰性热注册）+ \`AgentConfigProvider\`（\`is_internal\` 过滤） |\r
| Prompt 组装  | \`PromptAssembler\`（分段式，含信道规范段）+ \`MemoryPolicy\`（角色记忆矩阵）+ \`SkillSectionProvider\`（扩展点） |\r
| 思考开关       | \`LlmCallOptions\`（\`default_params\` / \`agent.params\` 中 \`thinking_mode=off\` → 请求级 \`enable_thinking:false\`） |\r
| 路径 A（简单）   | \`GeneralAssistantAgent\`（薄适配：装配差异声明 + 响应式流终结钩子，执行委托 \`AgentChatCaller\`） |\r
| 路径 B（复杂）   | \`MultiAgentGraphAgent\`（自研引擎：lead→并行→aggregate） |\r
| 统一调用出口     | \`AgentChatCaller\`（路径 A/B 执行层单一来源：流式背书 + 取消令牌 + 重试/配额映射 + 工具装饰链）     |\r
| 统一出口       | \`ChatController\` 同步 + SSE                            |\r
| 兜底         | \`AgentService\` → general                             |\r
| 工具治理       | \`ToolCallTracer\`（进度行）+ \`ToolCallBudget\`（次数/结果 token 预算）+ \`ToolLazyManager\`（两段式延迟加载）+ \`ToolAssignments\`（专家分配硬边界） |\r
| 会话归档/召回 | \`SessionMessageArchiveMapper\`（裁剪转归档）+ \`SessionHistoryArchiveService\`（current 会话内 / all 跨会话）+ \`RecallHistoryTools\`（\`recall_history\`，scope=current\\|all） |\r
| QQ 事件准入 | \`ChannelEventPolicy\`（程序层规则）+ \`SemanticTriggerJudge\` / \`LlmSemanticTriggerJudge\`（语义层，默认关闭） |\r
| Goal 停滞看门狗 | \`StaleGoalWatchdog\`（\`app.watchdog.enabled\`，默认关闭） |\r
| 沙箱工具面      | \`SandboxToolProvider\`（agentscope-runtime 容器）        |\r
| MCP 工具        | \`McpServerTools\`（Server，3 工具→/mcp）+ \`McpToolProvider\`（Client，懒连接发现）+ \`ToolAssignments\` |\r
| 调用观测（LLM/工具/RAG 检索） | \`LlmCallRecorder\` 等（异步落库）+ \`ObserveQueryService\`（三表查询收口）+ 观测三接口（\`/api/llm-calls\`、\`/api/tool-calls\`、\`/api/kb-retrievals\`）   |\r
| 执行通道线程池 | \`GoalExecutorConfig\`（\`goalExecutor\` 后台 Goal 池 + \`applicationTaskExecutor\` MVC 异步槽位） |\r
| CLI 渲染     | \`TerminalRenderer\`（流式增量直出 + 着色行整行重绘升级 + spinner 原位刷新 + 工具行）   |\r
\r
> 更新日期：2026-08-28 / 5e MCP 接入、分配表与组件映射更新：2026-08-29 / 5f Prompt 动态装配、5g Agent 表驱动注册、6a 流式背书取消与 5c/5d/6 口径更新：2026-09-04 / 5h Goal 执行通道线程池治理、6 队列满与 Error 逃逸兜底、CLI 增量直出口径与组件映射更新：2026-09-06 / 观测查询收口 ObserveQueryService、QQ 绑定持久化收编 channel/qq/persistence 与组件映射更新：2026-10-04 / 5j 会话归档与链式召回、Goal 进展心跳与停滞看门狗、信道规范段与 QQ 事件准入更新：2026-11-03。\r
\r
`,qe='# 响应式流式改造设计：Chat 流式通道 WebFlux Flux 穿透（方案 A）\n\n> 日期：2026-08-25\n> 范围：仅改造流式通道；保留老 `/api/chat` 同步端点与兼容层\n\n## 背景与目标\n\n* 现状：流式聊天 `ChatServiceImpl.stream()` 使用 `SseEmitter` + `CompletableFuture.runAsync`（阻塞线程模型硬接流式）。\n\n* 与 ROADMAP P0「响应式流式改造」一致：`ChatServiceImpl.stream()` 由 `SseEmitter` + `CompletableFuture.runAsync` 改造为 **WebFlux +** **`Flux<String>`** 响应式流。\n\n* Spring AI `ChatClient.stream()` 底层即是 Reactor `Flux`，本项目依赖树已含 `reactor-core` 与 `spring-webflux`（传递引入），`Flux` 类型可直接使用，无需新增 Web 依赖。\n\n**已确认决策**\n\n1. 采用**方案 A**：Spring MVC 为主，Controller 直接返回 `Flux<String>`（`text/event-stream`），最小侵入。\n2. 流式端点**沿用同一个** **`/api/chat/stream`** 路径改造（保持 CLI / 前端路径不变）。\n3. `Agent` 接口新增**默认退化方法** `executeStreamReactive(Goal)`，保持接口兼容。\n4. 同步端点 `POST /api/chat` 与旧 `SseEmitter stream()` 保留（兼容现有 CLI）。\n\n## 架构与数据流\n\n```\nPOST /api/chat/stream  (Content-Type: text/event-stream)\n  → ChatController.stream(): Flux<String>            ← 改造：返回 Flux 而非 SseEmitter\n     → ChatService.streamReactive(): Flux<String>    ← 新增：返回 Flux\n        → AgentService.executeStreamReactive(...): Flux<String>\n           → Agent.executeStreamReactive(Goal): Flux<String>\n              → GraphAssistantAgent: 直接返回 LLM 的 spec.stream().content() （去掉 .block()）\n响应格式（与现有一致）：\n  data: token1\n  data: token2\n  ...\n  data: [DONE]\n  event: meta\n  data: {"sessionId":"..","newSession":true,"goalId":"..","status":"SUCCEEDED"}\n```\n\n## 组件改动\n\n| 层                   | 文件                                   | 改动                                                                                                                                                                  |\n| ------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |\n| 接口 Agent            | `agent/Agent.java`                   | 新增 `default Flux<String> executeStreamReactive(Goal)`，默认返回 `Flux.just(execute(goal))`                                                                               |\n| Agent 实现(核心)        | `agent/GraphAssistantAgent.java`     | 覆写 `executeStreamReactive`：去掉 `.block()`，直接返回 `spec.stream().content()`；新增配套的 `compileReactive`（prepare 同步、chat 节点输出 Flux 由外层消费）                                    |\n| Agent 实现(兼容)        | `agent/GeneralAssistantAgent.java`   | 不变（默认退化方法已可满足，若需保留同样可覆写）                                                                                                                                            |\n| 接口 AgentService     | `service/AgentService.java`          | 新增 `Flux<String> executeStreamReactive(String agentName, String objective, String sessionId)`                                                                       |\n| 实现 AgentServiceImpl | `service/impl/AgentServiceImpl.java` | 新增实现：`goal.markRunning` 同步，流式执行，`goal.succeed` 在 `doOnComplete`，异常 `doOnError` 置 FAILED；阻塞 `goalService.update` 放 `Schedulers.boundedElastic()`                     |\n| 接口 ChatService      | `service/ChatService.java`           | 新增 `Flux<String> streamReactive(ChatRequest)`；保留 `SseEmitter stream()`                                                                                              |\n| 实现 ChatServiceImpl  | `service/impl/ChatServiceImpl.java`  | 新增 `streamReactive`：建会话 → `Flux.from` agent 流 → 逐 token → 末尾拼 `[DONE]` + `meta` 事件；DB 写回（createSession / saveContext / touchSession）放 `Schedulers.boundedElastic()` |\n| Controller          | `controller/ChatController.java`     | `stream()` 改为返回 `Flux<String>`，`produces=text/event-stream`                                                                                                         |\n\n## 数据管理 / 并发边界\n\n* **DB 阻塞操作**：`sessionService.createSession`、`saveContext`、`touchSession`、`goalService.create/update` 均为阻塞 JDBC/MyBatis-Plus。按 ROADMAP 注意点，这些调用放入 `Schedulers.boundedElastic()`（在 Flux 流进入 Netty/LLM 线程前完成，或写回阶段切到 boundedElastic），避免阻塞响应式事件循环。\n\n* **会话与 Goal 生命周期**：语义不变——无 sessionId 时自动建档；代理成功 `SUCCEEDED`，失败 `FAILED`。\n\n## 错误处理\n\n* agent 流异常：`onErrorResume` 返回一段 error 事件（`event: error`），并以某个终止事件收尾，保证客户端不会悬挂。\n\n* 流写回阶段 DB 异常：记日志并将 goal 置 FAILED，不中断已发生的 token 推送。\n\n## 测试\n\n* 单测：`ChatServiceImplTest` 增加 `streamReactive` 用例（mock AgentService 返回 Flux，验证 token 依次出现、末尾含 meta、`newSession` 正确）。\n\n* 兼容性回归：既有 26 个测试全绿（不改动同步路径与既有流逻辑的调用）。\n\n## 不在范围（YAGNI）\n\n* 不做 DB 层响应式（MyBatis-Plus 维持阻塞，仅置于调度边界）。\n\n* 不切到纯 WebFlux / Netty（方案 A 保持 MVC 主栈）。\n\n* 不新增依赖；复用已在 classpath 的 `reactor-core` / `spring-webflux` jar（若编译或运行时缺 starter，仅补充 WebFlux starter）。\n\n* 不改 `SseEmitter stream()` 的既有语义供兼容保留。\n\n',Ue=`# 响应式流式改造实现计划（方案 A：MVC + Flux<String> 穿透）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（\`- [ ]\`）语法来跟踪进度。

**目标：** 将流式聊天通道从 \`SseEmitter + CompletableFuture.runAsync\` 改造为返回 \`Flux<String>\` 的响应式流，使用同一 \`/api/chat/stream\` 路径，保持 text/event-stream 格式、保留老同步 \`/api/chat\`。

**架构：** Spring MVC 为主，Controller 返回 \`Flux<String>\`（spring-webmvc 原生的 \`text/event-stream\` 输出）；\`ChatService\`/\`AgentService\` 新增响应式方法；\`GraphAssistantAgent\` 用 \`Flux.create\` 包裹阻塞内核把 token push 到 sink（响应式壳 + 阻塞核，阻塞 DB/内核置于 \`Schedulers.boundedElastic()\`）。

**技术栈：** Spring AI（\`ChatClient.stream()\` 本即 Reactor Flux）、Reactor（已在 classpath）、Spring MVC SSE、JUnit5 + Mockito。

**设计文档：** \`2026-08-25-chat-reactive-streaming-design.md\`（同目录）

---

## 文件结构与职责

| 文件 | 动作 | 职责 |
|------|------|------|
| \`agent/Agent.java\` | 修改 | 新增 \`default Flux<String> executeStreamReactive(Goal)\`，默认退化 \`Flux.just(execute(goal))\` |
| \`agent/GraphAssistantAgent.java\` | 修改 | 覆写 \`executeStreamReactive\`：用 \`Flux.create\` 包裹现有阻塞执行，token 回调 push 到 sink |
| \`agent/GeneralAssistantAgent.java\` | 不变 | 默认退化已满足 |
| \`service/AgentService.java\` | 修改 | 新增 \`Flux<String> executeStreamReactive(String agentName, String objective, String sessionId)\` |
| \`service/impl/AgentServiceImpl.java\` | 修改 | 实现响应式方法：RUNNING → 流式 → doOnComplete SUCCEEDED / doOnError FAILED；DB 写回放 \`Schedulers.boundedElastic()\` |
| \`service/ChatService.java\` | 修改 | 新增 \`Flux<String> streamReactive(ChatRequest)\`；保留 \`SseEmitter stream()\` |
| \`service/impl/ChatServiceImpl.java\` | 修改 | 实现 \`streamReactive\`：建会话 → Flux.from agent 流 → token → [DONE] → meta |
| \`controller/ChatController.java\` | 修改 | \`stream()\` 返回 \`Flux<String>\`（与设计一致，同一路径） |
| 测试 \`ChatServiceImplTest\` | 修改 | 新增 \`streamReactive\` 单测 |
| 测试 \`GraphAssistantAgent\` 相关 | 新增/修改 | 响应式方法单测 |

---

### 任务 1：Agent 接口新增默认退化响应式方法

**文件：**
- 修改：\`src/main/java/com/dark/javaHarness/agent/Agent.java\`
- 测试：\`src/test/java/com/dark/javaHarness/graph/SupportEmailGraphTest.java\`（无直接测试，靠编译）

- [ ] **步骤 1：在 Agent 接口增加默认方法**

\`\`\`java
import org.springframework.ai.chat.messages.??? // 不引入，用 java.util.function 已有
\`\`\`
实际改动：
\`\`\`java
public interface Agent {
    String name();
    String execute(Goal goal);
    default void executeStream(Goal goal, Consumer<String> onToken) {
        String result = execute(goal);
        if (onToken != null) onToken.accept(result);
    }
    /** 响应式流式执行：默认退化为同步 execute 后一次性产出；支持真正流式的实现应覆写。 */
    default Flux<String> executeStreamReactive(Goal goal) {
        return Flux.fromCallable(() -> execute(goal));
    }
}
\`\`\`
需加 import：\`import reactor.core.publisher.Flux;\`

- [ ] **步骤 2：编译确认**

运行：\`mvn -s .mvn/settings.xml compile -q\`
预期：成功

- [ ] **步骤 3：Commit**

\`\`\`bash
git add src/main/java/com/dark/javaHarness/agent/Agent.java
git commit -m "feat: add default reactive stream method to Agent interface"
\`\`\`

---

### 任务 2：GraphAssistantAgent 覆写响应式流式方法

**文件：**
- 修改：\`src/main/java/com/dark/javaHarness/agent/GraphAssistantAgent.java\`
- 测试：\`src/test/java/com/dark/javaHarness/graph/GraphAssistantAgentReactiveTest.java\`（新增）

- [ ] **步骤 1：编写失败的测试**

\`\`\`java
package com.dark.javaHarness.graph;
// 使用 mock ChatClient: 断言 executeStreamReactive 返回的 Flux 能逐 token 产出
@Test
void executeStreamReactive_emitsTokens() {
    // stub ChatClient.prompt().user().call() 返回 "hello"，ChatClientRegistry.get() 返回 mock client
    // agentService.getAgentConfig(general) 返回 mock config(model=qwen)
    // 调用 executeStreamReactive(new Goal(...))，collect & assert equals "hello"
}
\`\`\`

- [ ] **步骤 2：运行测试验证失败**

运行：\`mvn -s .mvn/settings.xml test -Dtest=GraphAssistantAgentReactiveTest\`
预期：FAIL（方法不存在）

- [ ] **步骤 3：实现 executeStreamReactive**

核心：\`Flux.create(sink -> 现有阻塞 run(goal, sink::next))\`——把 \`run(goal, onToken)\` 里 \`onToken.accept(token)\` 的 token 作为 \`sink.next(token)\`，结束后 \`sink.complete()\`；异常 \`sink.error(e)\`。因为阻塞调用不能让 Netty 线程阻塞，\`Flux.create\` 用 \`FluxSink\` + 由 \`subscribeOn(Schedulers.boundedElastic())\` 承载（在 AgentService 层做）。

\`\`\`java
@Override
public Flux<String> executeStreamReactive(Goal goal) {
    return Flux.create(sink -> {
        try {
            runWithSink(goal, sink::next, sink::complete);
        } catch (Exception e) {
            sink.error(e);
        }
    });
}
\`\`\`
重构 \`run\` 以便流式时走 sink。

- [ ] **步骤 4：运行测试验证通过**

运行：\`mvn -s .mvn/settings.xml test -Dtest=GraphAssistantAgentReactiveTest\`
预期：PASS

- [ ] **步骤 5：Commit**

\`\`\`bash
git add src/main/java/com/dark/javaHarness/agent/GraphAssistantAgent.java src/test/java/com/dark/javaHarness/graph/GraphAssistantAgentReactiveTest.java
git commit -m "feat: reactive streaming in GraphAssistantAgent via Flux.create"
\`\`\`

---

### 任务 3：AgentService 新增响应式流式方法

**文件：**
- 修改：\`src/main/java/com/dark/javaHarness/service/AgentService.java\`
- 修改：\`src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java\`
- 测试：\`src/test/java/com/dark/javaHarness/service/impl/AgentServiceImplTest.java\`（新增用例）

- [ ] **步骤 1：接口新增方法**

\`\`\`java
Flux<String> executeStreamReactive(String agentName, String objective, String sessionId);
\`\`\`

- [ ] **步骤 2：实现（含 RUNNING/SUCCEEDED/FAILED + boundedElastic）**

\`\`\`java
@Override
public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId) {
    Agent agent = requireAgent(agentName);
    Goal goal = goalService.create(objective, sessionId);
    goal.markRunning();
    goalService.update(goal);
    return agent.executeStreamReactive(goal)
        .doOnComplete(() -> {
            String summary = /* 由外部收集? */;
            goalService.update(goal);
        });
}
\`\`\`
因 summary 需收集完整文本，在 ChatService 层拼装（token 由 sink 发出，完整文本在 executeStream 已有逻辑）。实际实现：复用现有 \`executeStream\` 的拼装，但把 onToken 接到 sink；此处返回 \`Flux\` 采用 \`Flux.create\` 同任务 2 模式，内部跑现有逻辑并将 token 逐个 next。这样 \`goal.succeed(summary)\` 在收完整后调用，\`doOnComplete\` 写回。阻塞 DB 的 \`goalService.update\` 用 \`subscribeOn\`/\`publishOn(Schedulers.boundedElastic())\` 隔离。

- [ ] **步骤 3：编写测试**

新增用例断言：Flux 依次产出 token，完成后 goal 置 SUCCEEDED（mock GoalService）。

- [ ] **步骤 4：运行测试验证通过**

运行：\`mvn -s .mvn/settings.xml test -Dtest=AgentServiceImplTest\`
预期：PASS

- [ ] **步骤 5：Commit**

\`\`\`bash
git add src/main/java/com/dark/javaHarness/service/AgentService.java src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java src/test/java/com/dark/javaHarness/service/impl/AgentServiceImplTest.java
git commit -m "feat: reactive streaming in AgentService"
\`\`\`

---

### 任务 4：ChatService 新增响应式流式方法 + 实现

**文件：**
- 修改：\`src/main/java/com/dark/javaHarness/service/ChatService.java\`
- 修改：\`src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java\`
- 测试：\`src/test/java/com/dark/javaHarness/service/impl/ChatServiceImplTest.java\`

- [ ] **步骤 1：接口新增方法**

\`\`\`java
Flux<String> streamReactive(ChatRequest request);
\`\`\`

- [ ] **步骤 2：实现 streamReactive**

\`\`\`java
@Override
public Flux<String> streamReactive(ChatRequest request) {
    // 无 sessionId 建会话（DB，放 boundedElastic）
    String sessionId = ...; boolean newSession = ...;
    return agentService.executeStreamReactive(GENERAL_AGENT, request.message(), sessionId)
        .onErrorResume(e -> Flux.just("event:error"))
        .concatWith(Mono.fromCallable(() -> "[DONE]"))
        .concatWithValues(metaEvent(sessionId, newSession));
}
\`\`\`
DB 操作（createSession / saveContext / touchSession）通过 \`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())\` 或在 Flux 链上 \`publishOn\` 隔离。

- [ ] **步骤 3：编写测试**

新增用例：mock AgentService.executeStreamReactive 返回 \`Flux.just("a","b")\`，断言 token 出现 + 末尾含 \`[DONE]\` + meta。

- [ ] **步骤 4：运行测试验证通过**

运行：\`mvn -s .mvn/settings.xml test -Dtest=ChatServiceImplTest\`
预期：PASS

- [ ] **步骤 5：Commit**

\`\`\`bash
git add src/main/java/com/dark/javaHarness/service/ChatService.java src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java src/test/java/com/dark/javaHarness/service/impl/ChatServiceImplTest.java
git commit -m "feat: reactive streaming in ChatService"
\`\`\`

---

### 任务 5：ChatController 返回 Flux

**文件：**
- 修改：\`src/main/java/com/dark/javaHarness/controller/ChatController.java\`
- 测试：全量回归

- [ ] **步骤 1：改造 stream 端点返回 Flux<String>**

\`\`\`java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
    return chatService.streamReactive(request);
}
\`\`\`
（保留同步 \`/api/chat\` 返回 ChatResponse 不变）

- [ ] **步骤 2：全量测试回归**

运行：\`mvn -s .mvn/settings.xml test\`
预期：全部通过（既有 26 + 新增）

- [ ] **步骤 3：Commit**

\`\`\`bash
git add src/main/java/com/dark/javaHarness/controller/ChatController.java
git commit -m "refactor: chat stream endpoint returns Flux<String>"
\`\`\`

---

## 自检

**规格覆盖度**：设计文档每节均有对应任务。架构✓(任务1-5)、数据流✓(任务3-4)、并发边界✓(任务3-4 boundedElastic)、错误处理✓(任务3-4 onErrorResume)、测试✓(任务2-4)。\`GeneralAssistantAgent\` 保持默认退化，符合设计"不变"。

**占位符扫描**：无 "TODO"/"待定"。任务 3 的 summary 收集说明已明确复用现有 executeStream 拼装模式。

**类型一致性**：\`executeStreamReactive\` 签名在 Agent/AgentService/ChatService 一致（\`Flux<String>\`）。\`streamReactive(ChatRequest)\` 在 ChatService/Impl/Controller 一致。`,We='# 主 Agent 前置判断（路由判断器）设计：LlmRouteJudge\r\n\r\n> 日期：2026-08-26\r\n> 范围：roadmap ②（主 Agent 前置判断 / 路由判断器）。仅实现「分流判断」这一薄层，不执行具体任务。\r\n>\r\n> **后续演进（2026-10）**：路由判定已升级为「任务形态 + 两段式判定 + 置信度策略」——\r\n> 规则短路优先（多工作流措辞 → Graph、短消息 → Agentic），灰区由 LLM 返回\r\n> `route/confidence/workstreams/deliverables/reason`，Graph 需 ≥2 工作流且置信度 ≥0.8；\r\n> 另增显式 `routing` 开关（API / CLI / Web）与运行时逃生舱（Agentic→Graph 升级、\r\n> Graph→Agentic 降级）。判定接口统一为 `RouteJudge.plan()`（返回携带置信度与工作流形态的\r\n> `RoutePlan`），决策经 `meta.route` / `ChatResponse.route` 透出。本文档保留为初版设计记录。\r\n\r\n## 背景与目标\r\n\r\n* 现状：`ChatServiceImpl.chat()` / `streamReactive()` 全部直接走 `general` Agent 单次调用（路径 A），没有前置分流。多 Agent Graph（路径 B）尚未实现。\r\n\r\n* 目标：新增一个 **主 Agent 路由判断器**——只判断当前请求是「简单(场景A)」还是「复杂(场景B)」并输出决策，不执行具体任务，保持入口薄。为后续接入路径 B（Spring-AI Graph）预留清晰的分流位点。\r\n\r\n**已确认决策（用户）**\r\n\r\n1. 判断方式：**LLM 判断**——用一次轻量 LLM 调用让模型输出结构化 JSON 决策。\r\n2. 路径 B 落地：当前「复杂路径 B（多 Agent Graph）」尚未实现，因此本次 **complex 分支暂时仍落到现有 general 单次调用**，但代码结构预留 switch 分流位点。\r\n3. 结果可见性：route 决策仅以**日志**体现 + **单测**断言，不改对外 API 契约（`ChatResponse` / SSE meta 不变）。\r\n\r\n## 二、架构与数据流\r\n\r\n```\r\n用户 message\r\n  → RouteJudge.judge(message)   （主 Agent 前置判断，薄环）\r\n       → LlmRouteJudge：一次 LLM 调用 → 结构化 JSON { "route": "simple" | "complex" }\r\n       → 解析失败/超时/异常 → 兜底 SIMPLE\r\n  → 拿到 RouteDecision（SIMPLE / COMPLEX）\r\n      → 打日志 [route] message → SIMPLE/COMPLEX\r\n      → 分流位点（当前两分支都走现有单次调用，预留复杂路径）\r\n          SIMPLE  → general Agent 单次调用   （路径 A）\r\n          COMPLEX → （Graph 未实现，仍 general；接入时换多 Agent 编排）\r\n```\r\n\r\n## 三、组件改动\r\n\r\n| 层 | 文件 | 动作 | 职责 |\r\n|----|------|------|------|\r\n| 枚举 | `domain/RouteDecision.java` | 新增 | `SIMPLE` / `COMPLEX`，附带 LLM 原始返回的归一化解析（含兜底） |\r\n| 接口 | `service/RouteJudge.java` | 新增 | `RouteDecision judge(String message)` |\r\n| 实现 | `service/impl/LlmRouteJudge.java` | 新增 | 用 ChatClient 调 LLM 分类为 JSON，异常/解析失败兜底 SIMPLE |\r\n| 编排 | `service/impl/ChatServiceImpl.java` | 修改 | `chat()` / `streamReactive()` 前置 `judge()` + 日志 + 分流位点 |\r\n| 配置 | —— | 不变 | 对话框（可选路由接线，暂不做） |\r\n| 测试 | `LlmRouteJudgeTest` + `ChatServiceImplTest` | 新增/修改 | 判定正确性 + 兜底 + 注入 agent |\r\n\r\n## 四、LlmRouteJudge 设计细节\r\n\r\n**依赖注入：** `ChatClientRegistry`（按 model 取客户端）+ `ChatClient.Builder` 无关。沿用 GeneralAssistantAgent 的客户端获取方式：`clientRegistry.get(model)`。\r\n\r\n**提示词：** 系统提示词要求模型严格只输出一行 JSON：\r\n\r\n```\r\n你是 Harness 的主路由判断器。判断用户请求属于「简单」还是「复杂」。\r\n只输出 JSON，不要任何解释：{"route":"simple"} 或 {"route":"complex"}。\r\n- 简单(simple)：无需工具、无需拆分子任务，单次回答即可（如问候、闲聊、简短问答、讲笑话）。\r\n- 复杂(complex)：需联网搜索、编码执行、多步骤处理、需拆分多个子任务（如调研竞品并输出报告）。\r\n```\r\n\r\n**判定解析：** 用 `ObjectMapper` 解析返回文本，取 `route` 字段：\r\n- `complex` → `COMPLEX`\r\n- 其它/缺失/解析失败 → `SIMPLE`（兜底，TODO ⑤「宁可简单」）\r\n\r\n**异常兜底：** LLM 调用抛异常 / 超时 / 返回非 JSON → 一律返回 `SIMPLE`，不向调用方抛错（保证请求不被阻塞）。\r\n\r\n**同步/响应式：** 判断是「前置旁路」，只同步调用一次（复用处在一个 `chat()` 与 `streamReactive()` 都能同步短调用、然后进入各自执行体的位置）。为不干扰流式，`streamReactive` 的判断在进入 flux 前同步完成一次；若后续需要 true 非阻塞可改为 `Mono`，本次保持简单。\r\n\r\n**不改变对外契约：** route 决策**只写日志**（`log.info("[route] message->SIMPLE ...")`），不改 `ChatResponse`/SSE 格式。\r\n\r\n## 五、数据管理 / 并发边\r\n\r\n* 判断调用是单独的一次 LLM 调用，与执行各自独立，不共享 Goal。\r\n* 同步调用判断阻塞；在 `streamReactive` 中判断也位于 flux 组装前（前缀阶段），避免进入 Reactor 回调线程后才阻塞。低配判断模型。\r\n\r\n## 六、错误处理\r\n\r\n* 判断阶段任何错误 → 兜底 SIMPLE（记 WARN 日志不抛出）。\r\n* 不分发到路径 B 时不受影响。\r\n\r\n## 七、测试\r\n\r\n* `LlmRouteJudgeTest`：\r\n  - mock `ChatClientRegistry` 返回 mock `ChatClient`（`prompt().system().user().call().content()` 依次 stub）。\r\n  - 合法 JSON `{"route":"complex"}` → 断言 `COMPLEX`；`{"route":"simple"}` → `SIMPLE`。\r\n  - 非法 JSON / 空返回 → `SIMPLE`（兜底）。\r\n  - mock `call` 抛异常 → `SIMPLE`（兜底）。\r\n* `ChatServiceImplTest`：注入 mock `RouteJudge`，断言 `chat()`/`stream()` 调用后调用 `judge` 且分流转入 agent。\r\n\r\n## 八、范围（YAGNI）\r\n\r\n- 不做 URL / 不拆 Graph：路径 B 实现是本次范围外的另一 TODO（④），本次仅预留分流位点。\r\n- 不改变 `ChatResponse`/SSE 对外格式。\r\n- 不新增依赖（复用 spring-ai ChatClient / Jackson）。',Qe='# 多 Agent 编排器设计：MultiAgentGraphAgent（路径 B）\r\n\r\n> 日期：2026-08-26\r\n> 范围：roadmap ④（路径 B — 多 Agent 任务编排，Spring-AI Graph）。仅做「复杂路径」的编排执行器，并接入主 Agent 路由的 `COMPLEX` 分支。\r\n\r\n## 背景与目标\r\n\r\n* 现状：主 Agent 判断器（`LlmRouteJudge`）已能判断 `COMPLEX`，但复杂请求仍落回 `GeneralAssistantAgent` 单次调用——因为路径 B 编排器未实现。\r\n\r\n* 目标：新增 **多 Agent 编排器** `MultiAgentGraphAgent`，用 `spring-ai-alibaba-graph-core` 的 `StateGraph` 编排「拆解 → 并行子任务 → 聚合」，作为复杂请求的执行体。\r\n\r\n**已确认决策（用户）**\r\n\r\n1. 子任务执行方式：**复用 ChatClient**（沿用 `GeneralAssistantAgent` 同款请求组装 + `ChatClientRegistry`）。\r\n2. 子任务并行：**并行执行**（Lead 拆多个子任务后，各子任务节点并行，聚合节点汇总量）。\r\n3. 异构：路径 B 由主 Agent 路由 `COMPLEX` 时切入 `MultiAgentGraphAgent`。\r\n4. 不引入 Checkpointer（TODO ④ 的 Checkpointer 标记为可选——本次不做，不在范围）。\r\n\r\n## 二、架构与数据流\r\n\r\n```\r\n主 Agent 前置判断：message -> COMPLEX\r\n        │\r\n        ▼\r\nAgentService 按 anti agentName（"multi-agent"）路由到 MultiAgentGraphAgent\r\n        │\r\n        ▼\r\nMultiAgentGraphAgent.execute(goal)  → 构建 StateGraph → CompiledGraph.invoke(Map)</x>\r\n   state: { objective, subtasks[?string], results[?string×N], final?string }\r\n        │\r\n        ├─ [START] ──► lead 节点\r\n        │     lead：调一次 LLM，把 objective 拆成 N 条子任务\r\n        │           Map{ subtasks: [...], subtaskCount: N }\r\n        │        └─addConditionalEdges(并行分发到 subTask-0..subTask-N-1)\r\n        │\r\n        ├─ subTask-i 节点（并行）\r\n        │     读 subtasks[i]，调一次 ChatClient 生成该子任务结果\r\n        │     写 Map{ results: append[i] = 结果 }\r\n        │\r\n        └─ aggregate 节点\r\n              读 results[0..N-1]，调一次 LLM 汇总成最终回答\r\n              Map{ final: ... }\r\n        └─ END\r\n```\r\n\r\n**并行接线方式**（graph-core 1.1.2.2 的 `Command` 仅支持单目标，动态并行用固定子任务池实现）：\r\n\r\n- 预注册**固定子任务池** `subtask-0..subtask-{MAX_SUBTASKS-1}`（默认 `MAX_SUBTASKS=4`）。\r\n- `graph.addNode("lead", …)` → `graph.addEdge("lead", List.of("subtask-0",…,"subtask-3"))`：`addEdge(from, List)` 是**并行扇出**，lead 结束后同时派发到 4 个子任务节点。\r\n- 每个 `subtask-k`：从 state 读 `subtask_k`，若 lead 实际拆解数 ≤ k 则无内容，节点快速返回空 Map（短短路）；否则调 ChatClient 生成 `result_k`。\r\n- `graph.addEdge("subtask-k", "aggregate", …)` 全部收拢，`aggregate` 读 `result_0..result_{n-1}` 汇总。\r\n\r\n**状态键约定**（`Map<String,Object>`，按 key 自动合并）：\r\n- lead 写：`subtask_0..subtask_{n-1}`（n = 实际拆解数）+ `subtaskCount: n`\r\n- subtask-k 写：`result_k: 文本`（无任务则写空）\r\n- aggregate 写：`final: 最终回答`\r\n\r\n## 三、组件改动\r\n\r\n| 文件 | 动作 | 职责 |\r\n|------|------|------|\r\n| `agent/MultiAgentGraphAgent.java` | 新增 | `implements Agent`，构建并执行 StateGraph，复用 ChatClientRegistry |\r\n| `config/agent/ChatAgentConfig.java` | 修改 | 注册 `multiAgent` bean |\r\n| `service/impl/ChatServiceImpl.java` | 改为路由 | 主 Agent 判定 COMPLEX 时走 `MultiAgentGraph`，其余走 `General` |\r\n| `service/RouteJudge.java` | 不变 | 已具备 |\r\n| 测试 | 新增 | Graph 构造检查 + 节点 Mock 行为 |\r\n\r\n## 四、MultiAgentGraphAgent 实现要点\r\n\r\n**复用组装链路**（`ChatClient` 单次调用，与 `GeneralAssistantAgent` 保持一致）：\r\n- 依赖：`ChatClientRegistry`、`AgentService`（取 model / 系统提示词）。\r\n- `MultiAgentGraphAgent` 直接复用 `ChatClientRegistry.get(model)` 取到的 ChatClient，各自节点只发一次 `prompt().system(...).user(...).call().content()` 单次调用即可（子任务/汇总为独立短调用，不强制携带多轮记忆）。\r\n- 不抽取共享封装类，避免改动现有 `GeneralAssistantAgent` 组装路径（低风险、YAGNI）。\r\n\r\n**节点组织**：\r\n- Lead 节点：调一次 LLM，提示词要求输出 `{"subtasks":["…","…"]}`；用 Jackson 解析为 `List<String>`；返回 `Map{ "subtasks": list, "subtaskCount": n, "subtask_0"…"subtask_{n-1}" }`。\r\n- 子任务节点 k：从 state 读 `subtask_{k}`，调一次 LLM，返回 `Map{ "results_k": text }`。\r\n- aggregate 节点：从 state 按 `results_0..results_{n-1}` 收集，拼接成 prompt，调一次 LLM，写 `Map{ "final": text }`。\r\n- `execute(goal)`：构造 `OverAllState` from `{objective, sessionId}`，`compiledGraph.invoke(...)` 后 `state.value("final")` 返回。\r\n\r\n**边界与错误**：任一节点 LLM 异常 → 该节点返回失败兜底 fallback 文本写最终结果，不让整个 Goal 失败（宁可降级，不阻塞）。若拆解/汇总失败，兜底把原 objective 作为最终回复返回。\r\n\r\n**Checkpointer**：不引入（TODO 标记可选）。\r\n\r\n## 四、对外契约（统一出口）\r\n\r\n* `MultiAgentGraphAgent.execute(goal)` 返回最终回答 String；执行逻辑落回 `AgentService` 现有 `run()` 写 Goal 生命周期；`ChatService.writeBackContext` 统一写回 session_messages。与路径 A 完全一致，SSE/同步出口不变。\r\n\r\n## 五、测试\r\n\r\n- `agent/MultiAgentGraphAgentTest`：mock ChatClientRegistry + ChatClient → 校验 `execute` 返回最终回答、且确实走 Graph（mock ChatClient 固定返回）。因节点内部用真实 `CompiledGraph.invoke`，用固定返回让 lead/子任务/聚合可测。\r\n- 回归：`LlmRouteJudgeTest`、`ChatServiceImplTest` 保持绿（RouteJudge 已 mock）。\r\n\r\n## 六、范围（YAGNI）\r\n\r\n- 不做垂直线 Graph 状态与 Checkpointer（可选 TODO）。\r\n- 子任务个数按智能限定（lead 拆解上限，避免滚雪球，常量 `MAX_SUBTASKS` 默认 4）。\r\n- 不新增路由外部配置（agent 表一行 `multi-agent` 走默认模型即可）。\r\n- 不新增依赖（`spring-ai-alibaba-graph-core` 已在 pom）。',Ke='# 主动投递可靠性改造：飞书 + 邮箱（规划书）\r\n\r\n> 状态：待实施（v5，2026-11-08 四次修订：① 邮件改为独立模块 `channel-email`，见 6.3；② 去掉多实例假设，见 6.2；③ 删除进程外心跳，见 7.2；④ M3 拆为 M3a 群机器人 webhook（0.5 天）/ M3b 事件订阅（可选），见 8.1）　|　日期：2026-11-08　|　影响模块：`harness-api` / `server` / 新增 `channel-email` / 新增 `channel-feishu` / `channel-qq`\r\n>\r\n> 前置阅读：[project-structure.md](../project-structure.md) 的 Flyway 迁移表（当前基线 V13，本方案从 V14 起）、[data-flow.md](../data-flow.md) 第九节主动推送链路。\r\n\r\n## 1. 问题：不是「渠道不好用」，是「失败没人知道」\r\n\r\n现状的三处硬伤，每一处都有代码位置可查：\r\n\r\n| 症状 | 位置 | 后果 |\r\n| --- | --- | --- |\r\n| 发送失败**永久丢弃** | `ScheduleServiceImpl.deliver()`：无论 `send()` 返回 true/false 都调 `markNotified()` | 一次网络抖动 = 这条日报永远消失，日志里只有一行 WARN |\r\n| 单通道，无降级 | `agent_schedule.notify_channel` 只能存一个 `channel:target` | QQ 挂了（协议端重启/风控/限流），定时任务结果全部静默丢失 |\r\n| 探活有数据但**无告警** | `ChannelHealth` → `NapCatHealth` 只被 `ComponentStatusService` 读给监控页 | 渠道断了要等你自己打开 Web 页面才发现 |\r\n| 进程死了无人知 | 无外部观察者 | 容器 OOM / 磁盘满 / 主机重启，所有通知一起停，而你以为只是「今天没任务」（**本方案不解决**，见 7.2 取舍记录与 12 节） |\r\n\r\n第四点是真实存在的，但**本方案按需跳过**：进程内写的「失败告警」在进程死亡时确实不会执行，要做「沉默检测」必须引入进程外的观察者。本方案只覆盖「进程活着、但渠道发不出去」这一类，第四点作为已知残余风险记录在 12 节，**不假装解决**。\r\n\r\n## 2. 目标与边界\r\n\r\n### 2.1 目标（三句话，逐条可验收）\r\n\r\n1. **每一条主动消息都有记录和终态**：一条推送要么 `SENT`（有渠道确认），要么 `DEAD`（重投耗尽，带最后一次错误原因），不存在「发没发不知道」。\r\n2. **主通道失败能自动走备用通道**：`feishu:main|email:me@x.com` 按序降级，降级本身发生在注册表层，渠道实现互不感知。\r\n3. **渠道不可达能被发现**：渠道连续探活失败时，不依赖你打开监控页，而是通过其它渠道主动告警。（进程死亡不在本方案范围，见 7.2）\r\n\r\n### 2.2 明确不做\r\n\r\n| 不做 | 理由 |\r\n| --- | --- |\r\n| 不改 `MessageSender.send()` 的签名 | 现有 `QqMessageSender` 与契约守卫都依赖它；回执能力用 `default` 方法扩展（见 5.1） |\r\n| 不把任何渠道实现放进 `server` | 判据不是「有没有入站」，是「外部依赖会不会被 core 继承」+「实现会不会被替换」。邮件两条都成立（见 6.3） |\r\n| 不做「消息已读」的自动追问 | 飞书能力支持，但它属于产品增强不是可靠性修复，M3 列为可选 |\r\n| 不做消息去重的持久化 | 飞书事件重推窗口是分钟级，进程内存 LRU 够用；持久化会让每次接收多一次写库 |\r\n| 不做完整的告警平台 | 目标是自己用。告警抑制用内存计数即可，重启重置可接受 |\r\n| 不引入 ShedLock | 单实例部署，本方案不为多实例做任何设计。outbox 的条件更新只用于「发送前原子计数 + 崩溃恢复」，不是并发控制（见 6.2） |\r\n| 不做 HTML 邮件 | 纯文本更不容易被判垃圾，且不必维护模板 |\r\n\r\n## 3. 总体设计：三层各管一段故障\r\n\r\n```text\r\n通道层   飞书（主） + 邮件（备）          → 覆盖「单平台故障」\r\n  ↑ 由 MessageSenderRegistry 按链路由，渠道实现互不知道对方存在\r\n投递层   delivery_outbox + 重投 + 降级     → 覆盖「瞬时失败」\r\n  ↑ 每条消息落库，失败可重试、可审计、可追查\r\n存活层   渠道探活告警（进程内）            → 覆盖「渠道不可达」\r\n  ↑ 进程/主机死亡不在本方案范围，见 7.2 取舍记录\r\n```\r\n\r\n**三层盖不住的**（写在这里避免自我欺骗）：你自己断网、邮件被判垃圾、邮件在垃圾箱里你没看、以及「你看到了但没处理」。这些不是工程问题。\r\n\r\n### 3.1 一条消息的完整生命周期\r\n\r\n```text\r\n调度判定 goal 终态\r\n  → 写 delivery_outbox（PENDING，chain_index=0，next_retry_at=now）\r\n  → 标记 schedule.notified_at（语义从「已通知」改为「已入队」）\r\n  → DeliveryRetryScheduler 扫到期行\r\n      → 条件更新领取（attempts+1，next_retry_at=now+60s）\r\n      → 按 channel_chain[chain_index] 发送\r\n          ├ 成功 → SENT，parent_ref 留空\r\n          ├ 可重试失败 → 保持 PENDING，退避后排下一轮\r\n          └ 重试耗尽 → chain_index+1，换下一个通道\r\n                          └ 链走完 → DEAD + 元告警（走独立路径，见 6.4）\r\n```\r\n\r\n**关键语义变更**：`schedule.notified_at` 从「已送达」降级为「已入队」。送达状态只由 outbox 表达。这样调度侧不必关心投递成功与否，两个关注点彻底分开。\r\n\r\n## 4. 数据模型（V14）\r\n\r\n```sql\r\nCREATE TABLE `delivery_outbox` (\r\n  `id`            BIGINT       NOT NULL AUTO_INCREMENT,\r\n  `tenant_id`     VARCHAR(32)  NOT NULL DEFAULT \'default\',\r\n  `source`        VARCHAR(32)  NOT NULL COMMENT \'来源：schedule / alert / manual\',\r\n  `ref_id`        VARCHAR(64)           DEFAULT NULL COMMENT \'业务指针，如 schedule.id 或 goal.id\',\r\n  `channel_chain` VARCHAR(512) NOT NULL COMMENT \'降级链原文，如 feishu:ou_x|email:a@b.com\',\r\n  `chain_index`   INT          NOT NULL DEFAULT 0 COMMENT \'当前尝试到链上第几个通道（0 起）\',\r\n  `content`       TEXT         NOT NULL,\r\n  `status`        VARCHAR(16)  NOT NULL COMMENT \'PENDING / SENT / DEAD\',\r\n  `attempts`      INT          NOT NULL DEFAULT 0 COMMENT \'总尝试次数（跨通道累加）\',\r\n  `last_error`    VARCHAR(512)          DEFAULT NULL COMMENT \'最后一次失败原因（含渠道原始错误码）\',\r\n  `next_retry_at` DATETIME              DEFAULT NULL COMMENT \'下次可尝试时刻，兼作领取租约\',\r\n  `sent_at`       DATETIME              DEFAULT NULL,\r\n  `created_at`    DATETIME     NOT NULL,\r\n  PRIMARY KEY (`id`),\r\n  KEY `idx_outbox_due` (`status`, `next_retry_at`),\r\n  KEY `idx_outbox_created` (`created_at`)\r\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\r\n```\r\n\r\n设计说明：\r\n\r\n- **`content` 存全文而非摘要**：消息量极小（个人 agent 每天几条到几十条），存全文换来「重投时不需要回查 goal」这个简化，也支持事后核对「上周那条日报原文是什么」。\r\n- **不用 `SENDING` 状态**：用 `next_retry_at` 推到未来 60 秒充当租约，条件更新领取。少一个状态，少一类「卡在 SENDING」的脏数据。\r\n- **`channel_chain` 存原文而不是拆表**：链最多 2–3 跳，拆表会让「看一条消息的完整投递过程」变成 join。原文 + `chain_index` 足够定位。\r\n- **保留期**：`SENT` 行保留 7 天（审计用，正好覆盖「上周的日报发了没」），`DEAD` 保留 30 天。接入已有的 `DataRetentionCleaner`，新增 `app.retention.outbox-sent-days` / `outbox-dead-days`。\r\n\r\n## 5. 端口与接口变更\r\n\r\n### 5.1 `MessageSender`：用 default 方法扩展回执，不动现有实现\r\n\r\n```java\r\npublic interface MessageSender {\r\n\r\n    String channel();\r\n\r\n    /** 保持原签名：失败不抛，返回是否送达 */\r\n    boolean send(String target, String text);\r\n\r\n    /**\r\n     * 带回执的发送：需要 message_id 的场景（已读追查、撤回）用它。\r\n     *\r\n     * <p>默认实现把 send() 折成「成功但无 ID」，现有渠道无需改动；\r\n     * 飞书渠道覆盖它返回真实 message_id。\r\n     */\r\n    default DeliveryReceipt sendForReceipt(String target, String text) {\r\n        return send(target, text)\r\n                ? DeliveryReceipt.sent(null)\r\n                : DeliveryReceipt.failed("unspecified");\r\n    }\r\n\r\n    /**\r\n     * 投递回执：sent=false 时 reason 必须可读（含渠道错误码），\r\n     * 它是 outbox.last_error 的唯一来源，也是「能不能自动补救」的判断依据。\r\n     */\r\n    record DeliveryReceipt(boolean sent, String messageId, String reason) {\r\n        public static DeliveryReceipt sent(String messageId) {\r\n            return new DeliveryReceipt(true, messageId, null);\r\n        }\r\n        public static DeliveryReceipt failed(String reason) {\r\n            return new DeliveryReceipt(false, null, reason);\r\n        }\r\n    }\r\n}\r\n```\r\n\r\n> **契约守卫**：`PortSignatureGuardTest` 的快照需要同步加上 `sendForReceipt` 与 `DeliveryReceipt`。这与 P2-1（`snapshotItems`）、P3-1（`deleteBySessionId`）是同一类变更，测试报错属预期演进。\r\n\r\n### 5.2 `MessageSenderRegistry`：从「单地址」到「通道链」\r\n\r\n地址语法向后兼容——**单值就是长度 1 的链，存量数据零迁移**：\r\n\r\n```text\r\nqq:123456                      # 单通道（现有数据，行为不变）\r\nfeishu:main|email:me@x.com     # 两跳降级；feishu 的 target 在 M3a 是别名，M3b 是 open_id\r\n```\r\n\r\n`feishu:` 之后的 target 有两种形态，由渠道实现自己解释（注册表不管）：M3a 是配置里的 webhook 别名（如 `main`），M3b 是 `ou_` 开头的 `open_id`。注册表只负责「按 `|` 拆、逐个找渠道、失败换下一个」。\r\n\r\n改造点（不新增类，避免又多一层间接）：\r\n\r\n| 方法 | 变更 |\r\n| --- | --- |\r\n| `send(channelRef, text)` | 保留，内部改为「只试第一个可用通道」，供 M1 之前的调用方平滑过渡 |\r\n| `sendWithFallback(channelRef, text)` | 新增，按 `\\|` 拆分逐跳尝试，返回 `List<Attempt>`（每跳的渠道、结果、错误） |\r\n| `chainOf(channelRef)` | 新增，解析并校验链（空段、未知渠道前缀在入队时就拒绝，而不是发送时才发现） |\r\n\r\n关键判断：**校验提前到入队时**。现在 `notify_channel` 写错了（比如 `q:123`），要等真的触发任务才在日志里看到「渠道未注册」。改后在 `ScheduleService.create()` 就拒绝，配置错误当场暴露。\r\n\r\n### 5.3 飞书渠道的错误可编程性（本方案的核心价值）\r\n\r\nQQ 渠道的失败是 `null`——分不清网络、超长、风控。飞书返回结构化错误码，可以分流。**两种接入方式的错误码不同，要分别映射。**\r\n\r\nM3a（群机器人 webhook，出站）：\r\n\r\n| 返回 | 含义 | outbox 处置 |\r\n| --- | --- | --- |\r\n| `code=0` | 成功 | `SENT` |\r\n| `code=9499` | 请求体非法（超 20 KB / JSON 错） | `DEAD`（配置或代码 bug，重试无意义），错误信息带「检查正文长度」 |\r\n| `code=11232` | 触发限流 | `PENDING`，退避 1s → 2s → 4s，本轮内重试 |\r\n| 返回体提示 sign / timestamp | 签名或时钟偏移 | `DEAD` + 提示「检查 webhook-secret 与服务器时钟（timestamp 必须秒级）」 |\r\n| 网络异常 / 5xx | 瞬时 | `PENDING`，退避 60s 重投 |\r\n\r\nM3b（应用 API，双向，仅第二阶段适用）：\r\n\r\n| 返回 | 含义 | outbox 处置 |\r\n| --- | --- | --- |\r\n| 频率限制（99991400 类） | 瞬时 | 退避 1s → 2s → 4s |\r\n| token 失效（99991663 类） | 可自愈 | 刷新 `tenant_access_token` 后立即重试一次 |\r\n| 用户不在可见范围（230002 类） | **配置错** | `DEAD`，提示「检查应用可用范围」 |\r\n| 机器人不在群（230013 类） | **配置错** | `DEAD`，同上 |\r\n| 内容触发审核 | 内容问题 | `DEAD` + 内容存档，不自动重投（重投会再次触发） |\r\n\r\n这两张表是「换飞书」的真正收益：**失败原因可分类，因此补救策略可编程**。QQ 渠道只能统一走兜底。\r\n\r\n## 6. M1：投递层 + 邮件通道（2 人日）\r\n\r\n### 6.1 写入与状态流转\r\n\r\n- `ScheduleServiceImpl.deliver()` 改为 `enqueue()`：写 outbox，成功后 `markNotified()`。语义注释同步改写（现在是「已通知」，改后是「已入队」）。\r\n- 入队前校验链（5.2 的 `chainOf`），非法链直接拒绝写库并抛 `IllegalArgumentException`——配置错误要在创建任务时就报。\r\n- 新增 `DeliveryOutboxService`：`enqueue` / `claimDue` / `markSent` / `markFailed` / `advanceChain`。\r\n\r\n### 6.2 重投与领取（单实例前提）\r\n\r\n**前提先写清：本项目是个人 agent，单实例部署，本方案不为多实例做任何设计**——不引 ShedLock、不加分布式锁、不写并发测试。`project-hardening.md` 的 **H2-3（多实例防护）仍然待办，且与本方案无关**。\r\n\r\n那为什么还要一条带条件更新的 `UPDATE`？理由不是并发，是**「发送前计数」**：\r\n\r\n```sql\r\n-- 领取：先原子地「记账 + 推迟下次重试」，领取成功才发送\r\nUPDATE delivery_outbox\r\n   SET attempts = attempts + 1,\r\n       next_retry_at = DATE_ADD(NOW(), INTERVAL 60 SECOND)\r\n WHERE id = #{id} AND status = \'PENDING\' AND next_retry_at <= NOW()\r\n```\r\n\r\n`attempts` 必须在发送**之前**递增，否则「发送中进程被杀」会永远不计次、无限重试。而「发送前原子递增 + 推迟下次时间」这个动作，本身就写成了一条 `UPDATE`。多出来的 `WHERE` 条件不是为多实例准备的，是为了让这次领取**幂等于「这一行还没被领取」**——它只多一个条件，成本为零，顺带也让「同一行不会被两轮同时处理」变成了数据库层的保证，而不是依赖 `notifying` 那个内存标志。\r\n\r\n它实际保护的两个故障，都发生在单实例上：\r\n\r\n| 故障 | 条件更新的作用 |\r\n| --- | --- |\r\n| 发送中进程被杀（SMTP 卡住时 `docker restart`） | `attempts` 已递增、`next_retry_at` 在未来；重启后到期自动重试，不会永远卡在「发送中」 |\r\n| 上一轮还没跑完就来了下一轮 tick | `notifying` 标志挡住 apply 层，`WHERE` 挡住行级——两处都不依赖对方 |\r\n\r\n- 扫描节拍：`@Scheduled(fixedDelay = 30s)`，每轮 `LIMIT 50`。\r\n- 重试上限：单通道 3 次（60s / 180s / 600s 退避），耗尽后 `chain_index+1`。\r\n- 链耗尽：`status=DEAD`，触发元告警（6.4）。\r\n- **将来若真要扩容**：这套写法天然可用（条件更新本身就是乐观锁），但届时还要处理 `@Scheduled` 的其它任务、事件分发、渠道连接数——那是 H2-3 的范围，不是这里。\r\n\r\n### 6.3 邮件通道（独立模块 `channel-email`）\r\n\r\n**修正一处初稿判断**：初稿写「邮件没有入站需求，放 `server` 的 `message` 包即可」——这是错的，混淆了**运行时角色**和**部署单元**。邮件在运行时就是一个渠道：实现 `MessageSender`、`channel()` 返回 `email`、被注册表按 `email:` 前缀路由、可被 `notify_channel` 指名。既然 `channel-qq` 是模块，它就该是模块。\r\n\r\n真正的判据不是「有没有入站」，是这两条：\r\n\r\n1. **外部依赖是否会被 core 继承**：`spring-boot-starter-mail` 放在 `server/pom.xml`，等于此后所有部署都带着 mail 依赖，哪怕只跑 QQ。\r\n2. **实现是否会变**：邮件服务商可换（QQ 邮箱授权码 / 阿里云邮件推送 / SendGrid / SES），换的时候不该动 core 的依赖树。\r\n\r\n「可选功能」这个理由本身不足——`@ConditionalOnProperty` 放在 core 里也能关。但**依赖隔离**关不掉，只有模块能做到。\r\n\r\n```text\r\nchannel-email/src/main/java/com/dark/javaHarness/channel/email/\r\n├── EmailProperties.java          channel.email.*（host/port/from/username/password/enabled）\r\n├── EmailMessageSender.java       MessageSender，channel() = "email"\r\n└── EmailChannelHealth.java       ChannelHealth（只读最近一次投递结果，不做 SMTP 探活）\r\n\r\nchannel-email/pom.xml             spring-boot-starter-mail + harness-api（不依赖 core）\r\n```\r\n\r\n**分界线（重要）**：编排在 core，实现在模块。\r\n\r\n| 留在 `server` 的 `message` 包 | 去 `channel-email` |\r\n| --- | --- |\r\n| `MessageSenderRegistry`（改造：通道链） | `EmailMessageSender` |\r\n| `DeliveryOutboxService` / `DeliveryRetryScheduler` | `EmailProperties` |\r\n| `AlertGate`（告警抑制） | `EmailChannelHealth` |\r\n\r\n理由：注册表、重投、抑制都是**跨渠道的编排**，它们只认 `MessageSender` 接口，与「发的是邮件还是飞书」无关。一旦某个渠道的实现细节渗进这些类，加第三个渠道时就要改两处。\r\n\r\n**模块登记的三处同步点**（漏一处就编译不过或守卫不过）：\r\n\r\n```text\r\npom.xml          <modules> 增加 channel-email\r\nserver/pom.xml   runtime 依赖 + enforcer 的 bannedDependencies 排除\r\n                 （现有规则：禁止 core 以 compile scope 依赖渠道模块）\r\nchannel-email/pom.xml  jacoco.line.minimum（父 pom 默认 0.00 只出报告，不设门槛也可，但建议设）\r\n```\r\n\r\n**配置走 `channel.email.*` 而非 `spring.mail.*`**：\r\n\r\n- 渠道配置统一在 `channel.*` 命名空间下，和 `channel.feishu.*` 对称。\r\n- 用 `spring.mail.*` 会触发 `MailSenderAutoConfiguration` 的隐式装配——「哪个 Bean 从哪来」变得不好追。自己 new `JavaMailSenderImpl` 多 15 行，换来显式。\r\n- 凭据：`channel.email.username` / `password` 从环境变量取（`MAIL_USERNAME` / `MAIL_PASSWORD`），yaml 只写占位符。**密码不得进仓库**。\r\n\r\n**默认关闭**：`channel.email.enabled: false`。未配置 SMTP 时模块照常打包但不装配任何 Bean，`MessageSenderRegistry` 的日志里也就不会出现 `email` 渠道——**「没配就别喊」**比「配了但发不出去」好排查。\r\n\r\n**为什么不做 SMTP 探活**：每次探活要建一次 TCP + 认证，成本远高于 HTTP，而且会占用服务商的连接额度。改为「用真实流量当探针」——`EmailChannelHealth.snapshot()` 只返回最近一次投递的结果与时间，超过 7 天无投递则报「未知（无近期投递）」，而不是假装健康。\r\n\r\n- 正文：纯文本 + 固定头部（来源、目标、时间、错误原因），便于手机上看清。不做 HTML（更容易被判垃圾，且要维护模板）。\r\n- 失败分流：认证失败 → `DEAD`（配置错，重试只会一直失败且可能触发服务商风控）；连接超时 → 重试；收件人拒绝 → `DEAD`。\r\n\r\n### 6.4 告警抑制与元告警\r\n\r\n**抑制**（`AlertGate`，内存实现）：同一 `source + ref_id` 24 小时内最多一条；`DEAD` 后恢复成功补一条「已恢复」；连续 3 次失败才发第一条。\r\n\r\n**元告警的死结**：如果连邮件都失败，还能发给谁？答案是**发不出去**，只能：\r\n\r\n1. 记 ERROR 日志（含完整链与错误码）\r\n2. 打一个 `harness_delivery_dead_total` 指标（Micrometer 已有）\r\n3. 让 `/actuator/health` 的 `delivery` 组变 DOWN\r\n\r\n第 3 条是留给存活层的接口：**进程内能做的到此为止，剩下的交给外部观察者**。这个边界必须写在代码注释里，否则后面会有人试图在 `deliver()` 里堆重试。\r\n\r\n## 7. M2：渠道探活告警（0.5 人日）\r\n\r\n### 7.1 渠道探活告警（复用现有 `ChannelHealth`）\r\n\r\n现有资产：`ChannelHealth` 端口 + `NapCatHealth`（`get_login_info`，缓存 30s）。缺的只是「把状态变化变成通知」。\r\n\r\n新增 `channel-feishu` 侧 `FeishuHealth implements ChannelHealth`（调 `authen/v1/user_info` 或拉 token 判活），以及 `server` 侧：\r\n\r\n```text\r\nChannelWatchdog  @Scheduled(fixedDelay = 5min)\r\n  → 遍历所有 ChannelHealth\r\n  → 连续 3 次 available=false → 走「非本渠道」的通道发告警\r\n     （QQ 挂了走邮件，飞书挂了走邮件；邮件也挂了只能记日志——此时已无可用通道，属 7.2 的残余风险）\r\n  → 恢复时发「已恢复」\r\n```\r\n\r\n**「走非本渠道」是关键**：不能用 `feishu:xxx` 去告警「飞书挂了」。实现上给 `sendWithFallback` 加一个 `excludeChannel` 参数。\r\n\r\n> **落地时的两处修正（2026-11-08）**\r\n>\r\n> 1. 告警不是「直接发一次」，而是**剔除故障渠道后入队 outbox**（`source=alert`）。理由：outbox 的一行就是一条链，入队前定下来，重投才会遵守「不走坏渠道」；`excludeChannel` 只能管第一次发送。\r\n> 2. `ChannelHealth.Snapshot` 由 `(enabled, available)` 二元组扩成四态 `UP / DOWN / UNKNOWN / DISABLED`。邮件渠道 7 天没投递报「未知」，若按 `available=false` 判故障，会周期性发假警报。旧的三参构造保留，监控页与既有测试不受影响。\r\n>\r\n> 阈值没有新增 `channel-watchdog.fail-threshold`，直接复用 `app.alert.min-failures`——同一语义设两个键必然漂移。\r\n\r\n### 7.2 为什么不做进程外心跳（取舍记录）\r\n\r\n「进程死亡无人知」是真实风险，但**本方案按需跳过**。理由：\r\n\r\n- 单实例个人部署，进程死亡最常见的表现是「你自己会察觉」（机器重启、容器退出、系统卡顿），而不是「安静地跑着但没人知道」。\r\n- 外部观察者本身是一个需要长期维护的依赖：它的配置、凭据、告警渠道都要跟着环境变，而它失效时**没有任何人监控它**——只是把「谁看着谁」的问题往上推一层。\r\n- compose 里已有 `restart: unless-stopped`，覆盖了「进程崩溃自动拉起」这个最常见场景。\r\n\r\n**残余风险**（写进 12 节，不作为验收项）：容器一直起不来（镜像损坏、磁盘满、端口占用）时，所有通知一起停，且没有机制告诉你。发现方式退化为「你注意到今天没收到消息」。\r\n\r\n将来若确实需要，接一个外部探活即可——**它不需要改本方案的任何结构**，只是多一个使用者调用 `/actuator/health`。这也是现在不做的好理由：接入成本不随时间增加。\r\n\r\n## 8. M3：飞书渠道（0.5 + 3 人日，分两阶段）\r\n\r\n### 8.1 两条路线：先做单向，再决定是否双向\r\n\r\n判据只有一句：**投递可靠性只需要「发得出去」，不需要「收得进来」。** 这两件事在飞书上对应两套完全不同的接入方式，成本差 6 倍。\r\n\r\n| 能力 | 接入方式 | 需要域名 | 需要 SDK | 成本 |\r\n| --- | --- | --- | --- | --- |\r\n| **单向推送**（本方案需要） | 群自定义机器人 webhook | 不需要 | 不需要 | 0.5 人日 |\r\n| 双向对话（可选） | 事件订阅 · Webhook 回调 | 需要 HTTPS | 不需要 | 3 人日 |\r\n| 双向对话（备选） | 事件订阅 · 长连接 WS | 不需要 | 需要，Java 25 兼容性待验 | 4 人日 |\r\n\r\nM3a 是 outbox 的自然延伸：它只需要「一个有错误码的出站通道」，webhook 就够。M3b 才有「对话入口」——能不能做、值不值得做，等 M3a 跑一两周再判断。\r\n\r\n### 8.2 M3a：群机器人 webhook（0.5 人日）\r\n\r\n不需要建应用、不需要申请权限、不需要任何审核——在飞书里建一个只有自己的群，加「自定义机器人」，拿到 webhook 地址即可：\r\n\r\n```text\r\nPOST https://open.feishu.cn/open-apis/bot/v2/hook/<token>\r\n{"msg_type":"text","content":{"text":"..."}}\r\n→ {"code":0,"msg":"success"}\r\n```\r\n\r\n约束（每一条都影响 outbox 设计）：\r\n\r\n- **请求体 ≤ 20 KB** → 超长正文必须转邮件，不能截断（见 8.4）\r\n- **频率 100 次/分钟、5 次/秒** → 个人用量无感，但 `11232` 仍要按退避处理\r\n- **没有 message_id** → 不能查已读、不能撤回（那是 M3b 的事）\r\n- **单向**：不能接收消息，不能当对话入口\r\n- **URL 本身就是凭证**：泄露即被滥用，必须开签名校验\r\n\r\n签名：`sign = Base64(HMAC-SHA256(timestamp + "\\n" + secret, 空字符串))`，`timestamp` 用**秒级**（毫秒会导致校验失败）。secret 走环境变量。\r\n\r\n实现：`FeishuWebhookSender implements MessageSender`，就是一个带签名的 HTTP POST，约 80 行，零 SDK 依赖。\r\n\r\n> **落地（2026-11-08）**：`channel-feishu` 只依赖 `harness-api` + `spring-boot-starter` + `jackson-databind`（无 web starter、无 lark SDK）。请求体与错误码用 Jackson 解析，不手写字符串拼接。测试用 JDK `HttpServer` 起本地桩，断言「实际发出的 JSON」与「响应怎么翻译」，不 mock `HttpClient`；签名向量由 Python `hmac` 独立算出后硬编码，避免测试与被测代码共用同一份实现而一起错。HTTP 维度比初稿多分了一层：429 / 5xx 可重试，其余 4xx 终态。\r\n\r\n### 8.3 M3b：事件订阅（3 人日，可选）\r\n\r\n两条路，按「有没有域名」二选一：\r\n\r\n| | Webhook 回调（推荐） | 长连接 WS（备选） |\r\n| --- | --- | --- |\r\n| 前置 | 域名 + 有效 HTTPS 证书（Let\'s Encrypt 免费） | 服务器能出站访问公网即可 |\r\n| 依赖 | 零 SDK，Spring `@PostMapping` | `lark-oapi` 的 WS 客户端 |\r\n| 代码 | URL challenge 握手 + 验签解密 + 路由 | 连接生命周期 + 重连 + 心跳 |\r\n| 攻击面 | **公网暴露端点**，必须验签 | 无入站端口 |\r\n\r\n> 云服务器 + 独立 IP 不等于「有可用回调地址」：飞书要求能通过验证的 **HTTPS 地址**，纯 IP 通常过不了证书校验。没有域名又不想买，就只剩长连接这一条路（回到 SDK 兼容性问题）。\r\n\r\n> **落地（2026-11-09）**：选了长连接（无可用 HTTPS 域名）。`oapi-sdk 2.4.19` 在 JDK 25 上能连，但 SDK 没有 stop、`pingLoop` 吞中断、OkHttp 线程非守护，JVM 不会自然退出——`FeishuWsClient` 断开后延迟 3 秒 `Runtime.halt(0)`，这是已知且必须保留的妥协。入站另做了群聊 @ 扩展：`chatType=group` + `mentions` 非空才处理，回复用 `chat_id`；需要额外权限 `im:message.group_at_msg`。实测单聊与群聊均通。\r\n\r\n**3 秒约束两种模式都有**：回调要求 3 秒内返回 HTTP 200，长连接要求 3 秒内处理完不抛异常，超时都会重推。所以入站结构相同：\r\n\r\n```text\r\n收到事件 → 验签 → 解析 → event_id 去重 → 入队（有界队列）→ 立即 200\r\n                                                        ↓\r\n                                    后台线程池消费（复用 OneBotEventServiceImpl 的\r\n                                    队列 + 背压 + 超时中断结构，不重新发明）\r\n```\r\n\r\n3 秒对聊天链路是致命的（一次对话几十秒），所以「收到即入队」是**硬要求**，不是优化。\r\n\r\n### 8.4 推送目标与长文处理\r\n\r\n- **M3a 的目标是「群」**：webhook URL 本身指向那个群，`notify_channel` 写 `feishu:main`（别名），别名到 URL 的映射在配置里，**不把 URL 存进业务表**——凭证不该躺在数据库里。\r\n- **M3b 的目标是 `open_id`**（`ou_` 前缀）：需要一次配对——用户给机器人发一句话，日志打印其 `open_id`，复制进 `notify_channel`。不做自动发现（否则任何能给机器人发消息的人都能收你的通知）。\r\n- `allowed_users` 白名单：不在白名单的消息直接丢弃。个人 agent 的默认姿态是拒绝，不是接受。\r\n- **长文分流**：webhook 上限 20 KB。入队时按内容长度决定链的顺序——超过阈值（建议 6000 字符）就把 `email` 排到前面。**截断会丢信息，转邮件不丢**；这条也顺带解释了为什么邮件不只是兜底通道。\r\n\r\n### 8.5 事件去重与幂等（M3b）\r\n\r\n- 飞书事件头带唯一 `event_id`，重推时不变。\r\n- `MessageDedup` 用 `LinkedHashMap` + 5 分钟 TTL（复用 `MessageDedupCache` 的写法），命中直接返回 200 不处理。\r\n- 取舍：不持久化。进程重启后飞书可能重放分钟级内的事件，接受少量重复（会看到两条，但不会丢消息）。持久化会让每次接收多一次写库，不值。\r\n\r\n### 8.6 模块与依赖（分阶段，M3a 不需要 SDK）\r\n\r\nM3a 阶段：\r\n\r\n```text\r\nchannel-feishu/pom.xml                          harness-api + spring-boot-starter-web（无 lark-oapi）\r\nchannel-feishu/.../FeishuProperties.java        enabled / webhook-url / webhook-secret / allowed-users\r\nchannel-feishu/.../FeishuWebhookSender.java     MessageSender：签名 + POST + 错误码映射\r\nchannel-feishu/.../FeishuHealth.java            最近一次投递结果（同 email，不做主动探活）\r\n```\r\n\r\nM3b 阶段追加：\r\n\r\n```text\r\nchannel-feishu/.../event/FeishuEventController.java   回调入口（长连接路线则是 ws/FeishuWsClient.java）\r\nchannel-feishu/.../event/FeishuEventRouter.java        事件 → 验签 → 入队\r\nchannel-feishu/.../event/MessageDedup.java             event_id 去重\r\nchannel-feishu/.../reply/FeishuReplyService.java       复用 core 的 ChatService\r\n```\r\n\r\n模块登记的三处同步点见 6.3（根 pom modules / server pom runtime + enforcer 排除 / 自身 pom 的 jacoco 门槛）。`ArchitectureTest` 的分层守卫要跑一遍确认。\r\n\r\n### 8.7 可选增强（M3a 稳定后再评估）\r\n\r\n| 项 | 价值 | 前置条件 |\r\n| --- | --- | --- |\r\n| 已读追查 | 日报发出 2 小时未读则追一条 | M3b（webhook 模式没有 message_id） |\r\n| 卡片按钮 | 「要不要执行」类确认可点选 | M3b + 卡片回传回调 |\r\n| 长文转邮件 | 超阈值自动走邮件 | M3a 即可（见 8.4） |\r\n\r\n**默认不做**，除非 M3a 上线后确认主链路稳定。\r\n\r\n## 9. 配置项清单\r\n\r\n```yaml\r\napp:\r\n  delivery:\r\n    chain-default: "feishu:ou_xxx|email:me@example.com"  # 未显式配置 notify_channel 时的默认链\r\n    retry: 3                          # 单通道重投次数上限\r\n    backoff-seconds: [60, 180, 600]   # 重投退避\r\n    lease-seconds: 60                 # 领取租约：发送中崩溃时，到期后自动重试\r\n    batch: 50                         # 单轮扫描上限\r\n    scan-interval-ms: 30000\r\n  retention:\r\n    outbox-sent-days: 7               # SENT 保留（审计窗口）\r\n    outbox-dead-days: 30\r\n  alert:\r\n    min-failures: 3                   # 连续失败几次才告警\r\n    throttle-hours: 24                # 同类告警抑制窗口\r\n  channel-watchdog:\r\n    interval-ms: 300000               # 探活扫描节拍\r\n    fail-threshold: 3                 # 连续几次不可达才算故障\r\n\r\nchannel:\r\n  email:\r\n    enabled: false                    # 未配 SMTP 就不装配，注册表里也不会出现 email 渠道\r\n    host: smtp.example.com\r\n    port: 465\r\n    from: me@example.com\r\n    username: ${MAIL_USERNAME}        # 环境变量注入，密码不得进仓库\r\n    password: ${MAIL_PASSWORD}\r\n    ssl: true\r\n    connect-timeout-ms: 10000\r\n  feishu:\r\n    enabled: false\r\n    # —— M3a：群机器人 webhook（单向推送，无需建应用/审核） ——\r\n    webhook-alias: main               # notify_channel 里写 feishu:main\r\n    webhook-url: ${FEISHU_WEBHOOK_URL}\r\n    webhook-secret: ${FEISHU_WEBHOOK_SECRET}   # 签名校验；URL 泄露时这是唯一防线\r\n    # —— M3b（可选）：事件订阅 ——\r\n    event-mode: callback              # callback（需域名 + HTTPS）/ websocket（需 lark-oapi）\r\n    app-id: ${FEISHU_APP_ID}\r\n    app-secret: ${FEISHU_APP_SECRET}\r\n    verification-token: ${FEISHU_VERIFICATION_TOKEN}\r\n    encrypt-key: ${FEISHU_ENCRYPT_KEY}\r\n    allowed-users: []                 # open_id 白名单，空 = 全部拒绝\r\n```\r\n\r\n## 10. 测试策略\r\n\r\n| 层 | 测试类 | 覆盖 |\r\n| --- | --- | --- |\r\n| 链路解析 | `MessageSenderRegistryTest`（改造） | 单值兼容 / 两跳 / 空段拒绝 / 未知前缀拒绝 / `excludeChannel` |\r\n| outbox | `DeliveryOutboxServiceTest` | 入队 / 领取幂等（同一行第二次领取不命中）/ 重投退避 / 链推进 / DEAD |\r\n| 重投调度 | `DeliveryRetrySchedulerTest` | 到期扫描 / 失败分流 / 不重复发 |\r\n| 抑制 | `AlertGateTest` | 连续 N 次才发 / 24h 抑制 / 恢复补发 |\r\n| 邮件（`channel-email`） | `EmailMessageSenderTest` | 成功 / 认证失败→DEAD / 超时→重投 / 未启用时不装配 |\r\n| 探活 | `ChannelWatchdogTest` | 连续不可达触发 / 走非本渠道 / 恢复通知 |\r\n| 飞书 M3a（`channel-feishu`） | `FeishuWebhookSenderTest` | 签名用秒级 timestamp / `0`·`9499`·`11232` 三分支 / 超长转邮件的链顺序 |\r\n| 飞书 M3b（入站） | `FeishuEventRouterTest` | 3 秒内返回 / 重复 event_id 丢弃 / 队列满背压 / 验签失败拒绝 |\r\n| 集成 | `DeliveryOutboxIntegrationTest`（真实 MySQL） | 领取条件更新的原子性（这条只能真库验） |\r\n\r\n**必做的有效性验证**（沿用项目习惯）：\r\n\r\n- 把 `deliver()` 里的 `markNotified` 改回「失败也标记」，`DeliveryOutboxServiceTest` 必须失败。\r\n- 把领取 SQL 的 `status=\'PENDING\'` 条件去掉，重复领取用例必须失败（证明幂等生效）。\r\n\r\n## 11. 验收标准\r\n\r\n| # | 阶段 | 验收项 | 判据 |\r\n| --- | --- | --- | --- |\r\n| 1 | M1 | 一条推送有终态 | 手动把 SMTP 配置改错 + 飞书 target 改成非法别名 → outbox 出现 `DEAD` 行，`last_error` 含渠道原始错误 |\r\n| 2 | M1 | 降级生效 | 停 NapCat 但保留邮件 → 定时任务结果从邮箱收到，outbox 该行 `chain_index=1`、`status=SENT` |\r\n| 3 | M1 | 调度语义不串轮 | 发送期间 dispatch 换 `last_goal_id`，旧行不被新轮标记（现有 `markNotified` 条件更新测试继续通过） |\r\n| 4 | M1 | 发送中崩溃可恢复 | 把 SMTP 指向黑洞地址（连接挂起）后 `docker restart`，重启后该行 `attempts` 已递增并在租约到期后重试，不会卡在「发送中」 |\r\n| 5 | M2 | 探活告警走非本渠道 | 停 NapCat，5 分钟内邮箱收到「QQ 渠道不可达」；恢复后收到「已恢复」 |\r\n| 6 | M3a | 限流退避 | 用假 webhook 固定返回 `11232` → outbox 保持 `PENDING`，按 1s/2s/4s 退避，3 次后转下一个通道 |\r\n| 7 | M3a | 长文转邮件 | 正文 8000 字符 → 链顺序变为 `email` 优先，webhook 不被调用；正文 500 字符 → 正常走 `feishu:main` |\r\n| 8 | M3b | 3 秒不超时 | 长连接/回调下发一条需要 30 秒生成的消息 → 飞书无「超时重推」，回复正常送达 |\r\n| 9 | M1 | 存量零迁移 | 现有 `qq:xxx` 单值配置不改也能正常推送 |\r\n\r\n## 12. 风险与取舍\r\n\r\n| 风险 | 判断 | 缓解 |\r\n| --- | --- | --- |\r\n| 飞书账号归属 | M3a 完全不需要建应用。M3b 才需要，而个人账号可建「企业自建应用」（组织=你自己），个人未认证账号发布免审 | 若当前账号属于某公司且无建应用权限，用另一手机号注册独立账号；发布时可用范围选「仅自己」 |\r\n| webhook URL 泄露 | 真实：URL 本身就是凭证，泄露即被冒名发消息 | 必须开签名校验（HMAC-SHA256 + 秒级 timestamp），secret 走环境变量，URL 不进数据库（`notify_channel` 只存别名） |\r\n| `lark-oapi` SDK 与 Java 25 | 仅**长连接**路线需要；Webhook 回调与 M3a 都不依赖它 | M3a 先做（零 SDK），把 SDK 风险推迟到 M3b 且只在「没有域名」时才需要面对 |\r\n| 3 秒约束下队列积压（M3b） | 个人用量极低（自己一个人发消息），队列满的概率接近 0 | 队列满时回一句「忙，稍等」而不是静默丢弃 |\r\n| 回调端点暴露公网（M3b） | 任何人知道 URL 就能伪造事件 | 校验 `verification_token` + `encrypt_key` 解密 + `allowed_users` 白名单，三关缺一不可；不为「先跑通」而临时关掉验签 |\r\n| 邮件被判垃圾 | 真实存在 | 用邮箱服务商授权码 SMTP + 发件人白名单；**把「邮件是否进垃圾箱」写进首次部署检查清单**，不假设它一定到达 |\r\n| outbox 表膨胀 | 消息量极小；SENT 7 天清理 | 接入 `DataRetentionCleaner`，与归档/goal 同一套机制 |\r\n| 进程/主机死亡无人知 | 真实残余风险，本方案不解决（见 7.2） | `restart: unless-stopped` 覆盖崩溃自动拉起；剩余场景靠「你注意到没收到消息」发现。将来接外部探活不需要改本方案结构 |\r\n| 新增两个模块的维护成本 | 真实：每个模块有 3 处登记点（根 pom / server pom + enforcer / 自身 pom），漏一处就是构建失败 | 接受。换来的是「加渠道不动 core 依赖树」——邮件和飞书都不启用时，`server` 的依赖里没有任何 mail / lark 相关的库 |\r\n| 渠道默认关闭导致「以为配好了」 | 真实 | `MessageSenderRegistry` 启动日志打印已注册渠道；`channel.email.enabled=false` 时该渠道不出现，配错的 target 会在入队校验阶段被拒绝（5.2） |\r\n\r\n## 13. 里程碑\r\n\r\n| 阶段 | 内容 | 人日 | 交付物 |\r\n| --- | --- | --- | --- |\r\n| M1 | outbox + 重投 + 降级链 + `channel-email` 模块 | 2 | 失败可追踪；QQ 挂了邮件兜底 |\r\n| M2 | 渠道探活告警 | 0.5 | 渠道断了会主动通知你，不用打开监控页 |\r\n| M3a | 群机器人 webhook（单向推送 + 错误码映射 + 长文转邮件） | 0.5 | 飞书可当主通道，失败原因可分类 |\r\n| —— | **M1 + M2 + M3a 小计** | **3** | 可靠投递闭环，够用就可停在这里 |\r\n| M3b | 事件订阅（对话入口：回调 3 / 长连接 4） | 3~4 | 1:1 对话、已读追查、卡片交互 |\r\n| 合计（含 M3b） | | 6~7 | |\r\n\r\n**执行顺序的理由**：M1 之后，「消息丢了会知道」已经成立，与主通道是 QQ 还是飞书无关。**M3a 之后，「主通道可换、失败原因可分类」也成立了**——而它只要半天。M3b 的收益（对话入口、查已读、卡片）是产品升级，不是可靠性修复，所以用一到两周的真实数据再判断。\r\n\r\n## 14. 与现有清单的关系\r\n\r\n- **H2-3（多实例防护）仍待办，本方案不涉及**：单实例是既定前提，outbox 的领取写法只是不做额外假设，不构成对 H2-3 的部分完成。\r\n- `personal-agent-gap.md` 的 **P3-2（微信/企微渠道）** 由本方案的飞书渠道替代：M3a 的群机器人 webhook **不需要建应用、不需要审核、不需要公网回调**，就实现了「国内直连 + 主动推送」这个原目标。建议在 P3-2 条目下注明「改由飞书 webhook 实现（M3a），理由：零审核 + 结构化错误码」。\r\n- `NapCatHealth` 已存在，M2 不重写探活，只补「告警」这一段。\r\n',Ve="# CLI 终端探测：为什么曾卡 40 秒（2026-11-11）\r\n\r\n> JLine 在判断终端类型之前跑外部命令且 `waitFor` 无超时；排查手法与最终取舍。\r\n\r\n## cli 终端探测：为什么会卡 40 秒（2026-11-11）\r\n\r\n`TerminalInput.open()` 原来直接调 `TerminalBuilder.terminal()`。JLine 3.30 的 `doBuild()`\r\n在判断终端类型**之前**，会先让每个 provider 判断「系统流归不归我管」；\r\n`ExecTerminalProvider.isPosixSystemStream` 的实现是执行外部命令 `test -t 0`，然后\r\n`Process.waitFor()` **不带超时**。\r\n\r\n在 Surefire fork 里那个子进程（MSYS 的 `sh`）要 39~49 秒才退出，于是 `TerminalInputTest`\r\n单类耗时 57 秒。两个看起来该管用的开关都无效：`org.jline.terminal.dumb=true` 与\r\n`org.jline.terminal.type=dumb` 实测仍要 39 秒——探测发生在它们生效之前（实测，非推断）。\r\n\r\n现在的做法是**先判断再探测**：`System.console() == null` 且 `TERM` 未设或为 `dumb` 时，\r\n本就读不到按键，直接走行式降级，不进入 JLine。两个信号的取舍：\r\n\r\n- 有控制台（cmd / Windows Terminal / 另开的终端宿主）→ 走 JLine。JDK 22+ 即使重定向了\r\n  标准流，只要有控制台 `System.console()` 就非 null。\r\n- 没有控制台但 `TERM` 是 `xterm-*` 之类 → 仍走 JLine。Windows 下的 mintty（Git Bash）\r\n  是 POSIX 伪终端，只有 TERM 能反映出来，靠 exec provider 工作，不能误判成管道。\r\n- 两者都没有 → 管道 / CI / Surefire，直接降级。\r\n\r\n效果：`TerminalInputTest` 57.2s → 1.2s，全量 `mvn verify` 3:18 → 2:09。\r\n\r\n排查手法也留在这里：`jstack` 附不上 Surefire fork（`AttachNotSupportedException: 拒绝访问`），\r\n改成在 `open()` 里挂一个 8 秒的看门狗线程打 `Thread.getAllStackTraces()`，一次就定位到\r\n`ProcessImpl.waitForInterruptibly ← isPosixSystemStream ← TerminalBuilder.doBuild`。\r\n",Je='# 外部工具：三层模型与实现边界（2026-11-11）\r\n\r\n> core（需运行时上下文）/ external（MCP + 声明式 HTTP）/ skill（编排）。\r\n> 记录为什么这样切、哪些决定已经收盘、以及实测踩到的坑。\r\n\r\n## 外部工具：清单驱动，而不是给每个接口写一个类（2026-11-11）\r\n\r\n工具面分三层，边界由「是否需要运行时上下文」决定：\r\n\r\n| 层 | 放什么 | 为什么 |\r\n| --- | --- | --- |\r\n| core | `history` / `memory` / `sandbox.*` / artifact（`send_file`、TTS、点歌） | 要 `turnId`、容器句柄、工件库，外部进程拿不到 |\r\n| external | MCP（第三方/多语言）+ 声明式 HTTP（自家 REST） | 接新系统不改 Java、不重打包 |\r\n| skill | 编排与说明 | 把「十个近义工具」压成「一个工具 + 一份说明书」 |\r\n\r\n声明式 HTTP 的形状是 `tools/http/*.yaml`：一个清单 = 一个工具 + N 个资源。\r\n资源绑定**固定路径**与**参数白名单**，模型只能选资源名和参数值，不能拼路径——\r\n这是它和通用 `http_request` 的关键区别，后者等于给所有 agent 一条无约束的出站通道。\r\n\r\n为什么不「一个端点一个工具」：WMS 有二十个只读端点，逐个做成工具会让工具面塞满近义名字\r\n（`wms_search_stock` / `wms_search_stock_in_orders` / `wms_get_stock_in_order`…），\r\n模型在选工具这一步就开始出错。收敛成 `wms_query` 后，「查什么」是枚举，「怎么查」在 skill 里。\r\n\r\n响应整形也放在清单里：`format: jeecg` 拆 `result` 包装（records 分页 / 数组 / 单对象三种形态），\r\n`drop-fields` 剔除 `createBy` 这类审计字段。这些是数据，不该每个接入方写一遍代码。\r\n\r\nWMS 这一步走了两版，值得记下来：\r\n\r\n- **V18** 先把十个工具换成 `tools/http/wms.yaml`（`wms.query` pack），组合查询下沉到 skill。\r\n- 落地后确认适配器本身更适合承载这段逻辑：它是 MCP 服务，有进程隔离、调用方鉴权，\r\n  而且「商品名换 productId」本来就在服务端做。于是 **V19** 把分发器做进 `WmsTools`：\r\n  只暴露一个 `wms_query(resource, params)`，十六个资源在内部映射到原有的十段查询逻辑。\r\n- `tools/http/wms.yaml` 删除——同一批端点写两遍必然漂移。HTTP 机制保留给\r\n  「不值得起一个服务」的轻量集成。\r\n\r\n代价与收益：模型仍然只看到一个工具、一份说明书；不同的是组合查询回到服务端，一次调用完成，\r\n不用模型先查商品再查库存。参数白名单从 yaml 移进 `WmsTools.ALLOWED`，\r\n`WmsQueryDispatchTest` 与 `WmsToolListGuardTest` 一起守着（后者原来守十个工具名，现在守资源契约）。\r\n\r\n**实测发现的一个坑**：模型会把嵌套对象序列化成字符串\r\n（`"params":"{\\"productId\\":\\"p1\\"}"`），只接受 Map 的实现会在绑定阶段直接失败。\r\nHTTP 工具和 MCP 分发器现在都用 `Object` 接参数、两种形态都认，\r\n`HttpToolCallbackTest` 与 `WmsQueryDispatchTest` 各有一条用例守着。\r\n这类问题单测造不出来——只有真跑一次、看服务端收到的 query string 才会暴露。\r\n\r\n能力清单：`GET /api/capabilities` 按来源聚合（core/sandbox/mcp/http/skill），\r\n每条带定位符（源文件、清单路径、URL、技能目录）与工具名；`POST /api/capabilities/reload`\r\n重载两类外部来源：HTTP 清单与 MCP server 配置。\r\n\r\nMCP 重载的做法：把「规格 + 连接状态」收成一份不可变快照，reload 时原子换引用；\r\n旧连接延迟 30 秒关闭——在途调用跑完，新请求立刻走新连接。失败仍按 server 隔离，\r\n不影响其他 server。改 `mcp-config.json` 不再需要重建容器。\r\n\r\n`wms-mcp-adapter` 是 WMS 的唯一实现，不再删除：它是 MCP 服务，进程隔离、调用方鉴权、\r\n组合查询都在它那边；声明式 HTTP 机制留给「不值得起一个服务」的轻量集成。\r\n',ze='# 飞书渠道：设计决策与根因（2026-11-11）\r\n\r\n> 记录「为什么这样做」和「改哪里会坏」。配置项、权限申请与使用方式见\r\n> [channel-feishu/README.md](../../channel-feishu/README.md)；2026-11-10 那轮审计的\r\n> 八项加固是一次性记录，见 [archive](../archive/2026-11-11-dev-history.md)。\r\n\r\n## 飞书长连接（M3b 事件订阅）\r\n\r\n与 M3a 的群 webhook 是两条独立链路：webhook 是「往固定群发通知」，长连接是「收消息 + 回复」。\r\n开关也分开：`CHANNEL_FEISHU_ENABLED`（出站）/ `CHANNEL_FEISHU_WS_ENABLED`（入站）。\r\n\r\n接入准备（环境变量、三项权限、事件订阅配置）见\r\n[channel-feishu/README.md](../../channel-feishu/README.md#接入准备)——那里是唯一的操作口径，\r\n本文只保留设计取舍。\r\n\r\n**配对**：`allowed-users` 为空时进入配对模式——收到消息只把 `open_id` 打进日志，不回复、不调模型。\r\n把日志里的 `open_id` 加进白名单并重启，才能对话。这是刻意的：长连接没有回调端点，\r\n白名单是机器人被拉进陌生群后唯一能拦住「对所有人开口」的闸。\r\n\r\n**群聊**：群里必须 @ 机器人（依赖飞书只推送 @ 消息的语义），回复走 `receive_id_type=chat_id`；\r\n会话按 `(open_id, chat_id)` 绑定，同一个人在单聊和不同群里各自独立上下文。\r\n\r\n必须知道的坑：\r\n\r\n- **SDK 无法停止**：oapi-sdk 2.4.19 的 `Client` 没有公开 stop，`pingLoop` 是死循环且吞中断，\r\n  OkHttp 线程也不是守护线程——JVM 不会自然退出。`FeishuWsClient.stop()` 在断开后延迟 3 秒\r\n  调用 `Runtime.halt(0)`；升级 SDK 后必须重跑一次「启动 → Ctrl+C」确认行为。\r\n\r\n## 飞书入站：群聊全量接收与图片多模态\r\n\r\n群聊要「每条都回」，靠的是后台开 `im:message.group_msg`（**敏感权限**，需审核）。只有这个权限\r\n才会把群里的普通消息推给应用；`im:message.group_at_msg` 只推 @ 机器人的。\r\n\r\n两条容易踩的：\r\n\r\n- **自回环**：开了 group_msg 后，事件里若带上机器人自己的消息，会自己回自己。入站按\r\n  `sender_type` 过滤（非 `user` 一律丢）。飞书文档说不推 bot 消息，但这是权限配置正确时的前提，\r\n  闸门不能省。\r\n- **图片要三层才到模型**：飞书 `image_key` → 下载字节（`im:resource`）→ `ChatAttachment` →\r\n  AgentScope `ImageBlock`。任何一层没接上，模型只收到一段文字，**不报错**。所以验证必须看\r\n  模型能不能说出图里的内容，而不是看有没有异常日志。\r\n\r\n已知限制：多 Agent 编排路径（`MultiAgentGraphAgent`）走 `Agent.execute` 的默认实现，附件被丢弃；\r\n图片也不进会话快照（`saveContextBatch` 只存文本），下一轮看不到上一轮的图。\r\n\r\n## 飞书指令：只有一个人能用\r\n\r\n`FeishuChatHandler` 在入队之前拦 `/new` `/reset` `/help` `/history` `/agent`：不建会话、\r\n不进会话队列、不调模型。三个设计点：\r\n\r\n- **授权是单个 open_id，不是白名单**。白名单回答「谁能和机器人说话」，指令授权回答\r\n  「谁能重置上下文」。群里也响应指令，而 `/new` 重置的是「这个人 + 这个群」的会话——\r\n  放开给所有人，任何成员都能把别人正在进行的对话打散。\r\n- **非授权用户不交给模型**。模型会顺着斜杠编一份并不存在的命令清单，比不回复更糟。\r\n  回一句固定提示即可。\r\n- **不认识的 `/xxx` 不拦**。用户可能只是发了条以斜杠开头的普通消息，指令层不该吞掉它。\r\n\r\n配置 `FEISHU_WS_COMMAND_USER`（yaml: `channel.feishu.ws.command-user`）；留空回退\r\n`allowed-users` 首位。**它必须在 `allowed-users` 里**，否则入站闸门先挡掉消息，\r\n表现为「发指令没反应」——启动日志会打一条 warn。\r\n\r\n## 飞书表情包：为什么和 QQ 各写一份\r\n\r\n标记约定（`[emoji:分类]`）、数据（`emojis/` 目录 + 索引）都一样，代码却各留一份——\r\n`channel-feishu` 看不见 `channel-qq`，抽公共模块又要为百来行代码新增一个反应堆成员，\r\n还得同步 Dockerfile 的模块拷贝。共享的是**数据**，不是代码。\r\n\r\n飞书侧比 QQ 多一步：图片必须先 `POST /im/v1/images` 换 `image_key`，再用\r\n`msg_type=image` 发送——飞书不接受内联字节。上传与下载图片同属 `im:resource` 权限。\r\n\r\n两个容易踩的点：\r\n\r\n- **标记一律剥离**：即使 `emoji.enabled=false` 也要剥，否则关掉开关反而让正文里露出\r\n  `[emoji:happy]`。关闭只意味着不选图、不发图。\r\n- **模型不知道就不会发**：约定写在 `PromptAssembler` 的飞书信道规范段里。如果某次改动\r\n  把它删了，表现是「表情功能突然不工作」，而不是报错。\r\n\r\n## 飞书回复形态（引用 / @ / 撤回 / 回复模式）\r\n\r\n四个都在渠道层，不动 core：\r\n\r\n- **引用回复**：改走 `POST /im/v1/messages/{id}/reply`，群里显示引用块。失败（原消息被撤回、\r\n  删除）自动退回普通发送——宁可丢引用，不能丢回复。`reply-in-thread=true` 可切话题形式。\r\n- **@ 提问者**：群聊文本前加 `<at user_id="ou_xxx"></at>`，与 QQ 的 `at-asker` 同名同义。\r\n- **`/recall`**：按 `openId+chatId` 记最近一条发出的消息 id（上限 512，超出清空），\r\n  `DELETE /im/v1/messages/{id}` 撤回。只记对话回复与表情图，不记指令回执。\r\n- **`reply-mode`**：`all` / `mention` / `probability`。只作用于群聊的普通消息：\r\n  单聊不限制，指令也不受限制（显式操作不该被概率吞掉）。`mention` 依赖入站事件里的\r\n  `mentions`，所以 `FeishuInboundMessage` 新增了 `mentioned` 字段。\r\n\r\n## 飞书语音回复：为什么必须过一道 ffmpeg（2026-11-11）\r\n\r\ncore 的 TTS 产出 mp3，飞书语音条（`msg_type=audio`）只认 opus，且上传\r\n（`POST /im/v1/files`）必须带 `duration`（毫秒）——两个约束都不是可选的，\r\n所以链路上必然有一次转码。QQ 不需要：它直接内联 mp3 当 `record` 段发。\r\n\r\n不引纯 Java 方案的理由：JDK 既没有 mp3 解码器也没有 opus 编码器，为一件事引两个原生库\r\n不如调 ffmpeg（运维也熟）。代价是**运行环境必须有 ffmpeg**：没有时 `toOpus` 返回空，\r\n本条退回文字，日志里只 warn 一次（不刷屏）。\r\n\r\n时长从 ffmpeg 的 stderr（`Duration: 00:00:03.48`）里取，不再单独起 ffprobe——\r\n为了一个字段多一次进程启动不值当。`-nostdin` 不能省：服务端没有终端，ffmpeg 默认会去读\r\n标准输入，可能直接挂住。\r\n\r\n## 文件下发：字节为什么必须经服务端中转（2026-11-11）\r\n\r\n沙箱容器**不挂宿主卷**（这是隔离的主防线，见 `SandboxGuards`），文件只在容器里；渠道又只\r\n认识 HTTP。两边都不动的前提下，只剩一条路：服务端把文件读出来，放\r\n`FileArtifactStore`，渠道按 `file:<id>` flag 取走上传。\r\n\r\n读取走容器内的 `stat -c %s` + `base64 -w0`，**只读**，边界复用写文件的同一套\r\n（`SandboxGuards.requireWorkspacePath`）：越界读和越界写一样危险。三个刻意的取舍：\r\n\r\n- **单文件上限 2MB**（`app.sandbox.send-file-max-bytes`）：内存里放一份、base64 再翻\r\n  1.33 倍，无上限就是把容器里的文件大小变成服务的堆压力。超限直接拒绝，不截断。\r\n- **路径含单引号一律拒绝**：路径要拼进 shell 命令，单引号内无法再转义；换个文件名即可。\r\n- **`base64` 用 MIME 解码器**：busybox 版本按 76 列折行，严格解码器会直接报错。\r\n\r\n工具的 shell 调用抽成 `SendFileTool.Shell` 函数接口，单测注入假实现，不必起 Docker。\r\n',Xe="# 渠道侧按会话串行：决策与坑（2026-11-11）\r\n\r\n> 同一会话连发两条消息为什么会串味，以及锁为什么必须加在读上下文之前。\r\n> 飞书侧实现见 [channel-feishu/README.md](../../channel-feishu/README.md)。\r\n\r\n## 渠道侧的「按会话串行」（飞书已做，QQ 待跟）\r\n\r\n同一会话连发两条消息，第二条会基于**旧上下文**回答，历史顺序也按完成时间排。原因不在写回：\r\n`SessionSnapshotStore.appendBatch` 用 `SELECT ... FOR UPDATE` 锁会话行，读-改-写全程串行，\r\n**不会丢消息**。问题出在「读上下文」发生在模型调用之前、且没有锁——两条并发的消息各自读到\r\n同一份旧上下文，后完成的把历史覆盖成「B 问、B 答、A 问、A 答」。**锁必须加在「读上下文之前」，\r\n而不是「写回」的时候**；否则你保护了数据，没保护语义。\r\n\r\n`FeishuChatHandler` 的做法（`handleAsync`）：\r\n\r\n- 入站线程只做「查绑定 + 入队」就返回，不再阻塞——旧实现在入站线程上 `future.get()`，\r\n  一个慢会话会把入站池占住。\r\n- `ConcurrentHashMap<Long, Slot>` 按 sessionId 维护 `running` + `pending`（容量 1）。\r\n  入口与收尾都在 `map.compute` 里改 slot：那是按 key 原子的，**`computeIfAbsent` + `synchronized`\r\n  挡不住「收尾时移除 map、同时新线程已拿到旧 slot」的竞态**。\r\n- 被拒收（已有 1 条排队）时回一句「上一条还在处理」，同一会话 30 秒内只提示一次，\r\n  避免刷屏时回一长串相同的话。\r\n- 收尾的 `finally` 必须推进队头：**漏了这一步，一次模型异常会让该会话永久卡死**——\r\n  后续消息全部进队列然后被拒收，用户只会觉得机器人死了。\r\n\r\n两个坑：\r\n\r\n- **不能在聊天池内部再 `submit` 到同一个池然后 `get`**。池满时所有线程都在等自己提交的任务，\r\n  没有线程去执行 → 死锁。所以执行放在虚拟线程上（`newVirtualThreadPerTaskExecutor`），\r\n  模型调用仍提交到 2 线程的聊天池，超时 `cancel(true)` 的原有逻辑不变。\r\n- **串行不等于限频**。QQ 的 `UserRateLimiter` 是防刷屏，串行是防错序，两者都得有。\r\n\r\n测试用 `CountDownLatch` 把第一条卡在模型调用里，再断言第二条在 300ms 观察窗口内**没有**启动。\r\n这个用例做过变异验证：把入口改成「无条件发射」，用例确实失败——否则它只是看起来在测并发。\r\n",Ye=`# LLM 供应商 / 模型 / 路由与 Agent 绑定设计\r
\r
> 状态：已实施（2026-11-12；验收记录见 [docs/task-checklist/llm-routing.md](../task-checklist/llm-routing.md)）\r
> 日期：2026-11-12\r
> 范围：\`deerfect-server\` 的模型解析、客户端构建、Agent 绑定、失败切换与观测\r
> 前提：按全新设计落地，不保留与现有 \`model_provider\` 表及按名查找接口的兼容\r
\r
---\r
\r
## 一、背景与问题\r
\r
当前实现把"供应商凭证、模型名、端点地址、思考开关"混在一张 \`model_provider\` 表里，Agent 通过 \`model_provider_id\` 绑定。主链路方向正确，但仍有四类结构性问题：\r
\r
1. **名字被当成 key 使用**：\`model_provider.model\` 没有唯一约束，但注册表仍保留"模型名 → id"的索引，供画像、摘要、路由判定、语义触发等内部调用使用。不同供应商存在同名模型时（如 deepseek 直连与腾讯代理都叫 \`deepseek-v4-flash\`），按名查找只能取先加载的一行，可能用错端点与凭证。\r
2. **缺少引用完整性**：\`agent.model_provider_id\` 没有外键，模型行被删除或禁用后，客户端查找失败会静默回退默认 DashScope 客户端，表现为"换了个供应商继续跑"。\r
3. **内部调用绕过 Agent 绑定**：路由判定、语义触发、画像提炼、会话摘要直接读配置里的模型名，与 Agent 的 id 绑定形成两套解析口径。\r
4. **没有显式失败链**：主模型不可用时只能整体报错，无法按数据配置切换到备用部署。\r
\r
---\r
\r
## 二、目标与非目标\r
\r
### 目标\r
\r
- Agent、内部系统调用、外部请求走**唯一一条**模型解析路径。\r
- 代码中的一切查找只认 id，名字仅用于展示。\r
- 主模型不可用时可**按数据配置**切换到备用部署，切换过程可观测。\r
- 配置错误在**启动期**暴露，不静默回退到未声明的默认供应商。\r
- 增加供应商 / 模型 / 路由只改数据，不改代码。\r
\r
### 非目标\r
\r
- 多租户、按用户隔离模型配额。\r
- 计费与成本分摊。\r
- 按权重灰度（预留 \`strategy\` 字段，本期只实现 \`priority\`）。\r
- 多模态编排（只做模型能力标记与调用前校验）。\r
\r
---\r
\r
## 三、核心概念\r
\r
| 概念 | 表 | 回答的问题 | 谁维护 |\r
|---|---|---|---|\r
| 供应商 | \`llm_provider\` | 调哪个端点、用哪份凭证 | 运维 |\r
| 模型部署 | \`llm_model\` | 发哪个上游模型名、支持哪些能力、默认参数是什么 | 运维 |\r
| 路由 | \`llm_route\` | 这类用途现在优先用谁、坏了换谁 | 运维 |\r
| 路由成员 | \`llm_route_member\` | 具体候选模型及优先级 | 运维 |\r
| Agent | \`agent\` | 我是谁、什么提示词/工具、用哪条路由 | 开发 |\r
\r
对应关系：\r
\r
\`\`\`text\r
agent.route_id ──> llm_route ──> llm_route_member ──> llm_model ──> llm_provider\r
                     (别名)        (有序候选)         (部署)        (凭证/端点)\r
\`\`\`\r
\r
名字（\`display_name\`、\`agent.name\`）只用于界面展示，不参与查询与缓存键。\r
\r
---\r
\r
## 四、数据模型\r
\r
### 4.1 DDL\r
\r
\`\`\`sql\r
CREATE TABLE llm_provider (\r
  id             BIGINT       NOT NULL AUTO_INCREMENT,\r
  code           VARCHAR(32)  NOT NULL COMMENT '供应商代码，如 dashscope / deepseek / tecent',\r
  base_url       VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点根地址',\r
  credential_ref VARCHAR(128) NOT NULL COMMENT '凭证引用（环境变量名或密钥别名），不落明文',\r
  protocol       VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '协议类型，当前仅 openai',\r
  enabled        TINYINT      NOT NULL DEFAULT 1,\r
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,\r
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,\r
  PRIMARY KEY (id),\r
  UNIQUE KEY uk_provider_code (code)\r
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 供应商：端点与凭证';\r
\r
CREATE TABLE llm_model (\r
  id             BIGINT       NOT NULL AUTO_INCREMENT,\r
  provider_id    BIGINT       NOT NULL,\r
  upstream_name  VARCHAR(128) NOT NULL COMMENT '请求体中下发的模型名',\r
  display_name   VARCHAR(128) NOT NULL COMMENT '界面展示名，不参与查找',\r
  capabilities   JSON         NOT NULL COMMENT '{tool_calling,vision,thinking,json_mode,context_window}',\r
  default_params JSON         NULL COMMENT '{temperature,max_tokens,top_p,...}',\r
  enabled        TINYINT      NOT NULL DEFAULT 1,\r
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,\r
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,\r
  PRIMARY KEY (id),\r
  UNIQUE KEY uk_model_provider_name (provider_id, upstream_name),\r
  KEY idx_model_provider (provider_id),\r
  CONSTRAINT fk_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider (id)\r
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型部署：一个供应商下的一个可调用模型';\r
\r
CREATE TABLE llm_route (\r
  id          BIGINT       NOT NULL AUTO_INCREMENT,\r
  code        VARCHAR(64)  NOT NULL COMMENT '稳定别名：main / fast / judge / vision ...',\r
  description VARCHAR(255) NULL,\r
  strategy    VARCHAR(16)  NOT NULL DEFAULT 'priority' COMMENT '当前仅 priority',\r
  enabled     TINYINT      NOT NULL DEFAULT 1,\r
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,\r
  updated_at  DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,\r
  PRIMARY KEY (id),\r
  UNIQUE KEY uk_route_code (code)\r
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型路由：Agent 唯一绑定的稳定别名';\r
\r
CREATE TABLE llm_route_member (\r
  route_id   BIGINT  NOT NULL,\r
  model_id   BIGINT  NOT NULL,\r
  priority   INT     NOT NULL COMMENT '越小越优先',\r
  enabled    TINYINT NOT NULL DEFAULT 1,\r
  PRIMARY KEY (route_id, model_id),\r
  UNIQUE KEY uk_route_priority (route_id, priority),\r
  KEY idx_member_model (model_id),\r
  CONSTRAINT fk_member_route FOREIGN KEY (route_id) REFERENCES llm_route (id),\r
  CONSTRAINT fk_member_model FOREIGN KEY (model_id) REFERENCES llm_model (id)\r
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由成员：有序候选与失败链';\r
\r
-- Agent 绑定改为路由\r
ALTER TABLE agent\r
  ADD COLUMN route_id BIGINT NULL AFTER description,\r
  ADD COLUMN params JSON NULL COMMENT 'Agent 级参数覆盖 {temperature,max_tokens,thinking_mode,show_thinking}',\r
  ADD KEY idx_agent_route (route_id),\r
  ADD CONSTRAINT fk_agent_route FOREIGN KEY (route_id) REFERENCES llm_route (id);\r
\`\`\`\r
\r
### 4.2 不变量\r
\r
1. \`llm_provider.code\`、\`llm_route.code\`、\`agent.name\` 全局唯一。\r
2. \`llm_model\` 唯一键是 \`(provider_id, upstream_name)\`；同名模型允许存在于不同供应商，各自有独立 id。\r
3. 一条路由至少有一个 \`enabled=1\` 的成员；同一路由内 \`priority\` 唯一。\r
4. \`agent.route_id\` 非空；系统 Agent（\`is_system=1\`）同样必须绑定路由。\r
5. 任何被引用的 model / provider 不得物理删除，只能禁用。\r
6. 路由成员的能力必须覆盖其绑定 Agent 的需求；不满足时启动失败（见第七节）。\r
\r
---\r
\r
## 五、解析与调用链\r
\r
### 5.1 唯一入口\r
\r
\`\`\`text\r
LlmCaller.call(agentCode, messages, options)\r
LlmCaller.stream(agentCode, messages, options)\r
        │\r
        ├─ AgentCatalog.get(agentCode)            // 按 agent.name，唯一\r
        ├─ ModelResolver.resolve(agent.routeId)   // 返回有序可用成员\r
        ├─ ClientRegistry.get(modelId)            // 按 model id 缓存客户端\r
        ├─ RouteExecutor.execute(members, request) // 失败切换、重试、观测\r
        └─ CallRecorder.record(...)               // 每次尝试一条记录\r
\`\`\`\r
\r
约束：\r
\r
- 除 \`LlmCaller\` 外，任何类不得直接注入 \`ChatClient\`、不得直接查 \`llm_model\` / \`llm_provider\`。\r
- \`ClientRegistry\` 的缓存键、失效键一律是 \`model_id\`，禁止以模型名或供应商标识为键。\r
- 路由解析结果可缓存（按 \`route_id\` + 版本号），配置热更新时整体替换。\r
\r
### 5.2 解析规则\r
\r
\`\`\`text\r
resolve(routeId):\r
  1. route.enabled = 1                   否则报错\r
  2. 取 route_member.enabled = 1，按 priority 升序\r
  3. 过滤 llm_model.enabled = 1 且 llm_provider.enabled = 1\r
  4. 结果为空                          报错（不兜底默认客户端）\r
\`\`\`\r
\r
### 5.3 客户端构建\r
\r
- 一个 \`model_id\` 对应一个客户端实例（连接池、超时、协议封装）。\r
- 凭证按 \`provider.credential_ref\` 在启动/热加载时解析，密钥不落库。\r
- 构建失败（凭证缺失、URL 非法）在启动校验阶段直接失败。\r
- 客户端失效（连接黑洞、OOM、端点重启）按 \`model_id\` 重建，与路由成员无关。\r
\r
---\r
\r
## 六、失败切换策略\r
\r
### 6.1 判定矩阵\r
\r
| 情况 | 行为 |\r
|---|---|\r
| 连接失败、DNS 失败、连接超时、读超时、首帧超时 | 尝试下一成员 |\r
| HTTP 5xx | 尝试下一成员 |\r
| HTTP 429 | 同一成员指数退避重试（默认 2 次），仍失败则下一成员 |\r
| HTTP 4xx（除 408 / 429） | 不重试、不切换，直接失败 |\r
| HTTP 408 | 尝试下一成员 |\r
| 用户取消 / 客户端断连 | 立即终止，不切换 |\r
| 流式响应已输出首个 token 后失败 | 不切换，错误上抛 |\r
| 所有成员耗尽 | 错误上抛，不静默回退 |\r
\r
**流式切换边界**：只有"尚未输出任何内容"时允许切换；一旦有 token 到达浏览器，切换会造成两段不同模型的回答拼接，因此禁止。实现上由 \`RouteExecutor\` 在首个内容帧到达时锁定当前成员。\r
\r
### 6.2 重试预算\r
\r
- 单个成员内：网络类错误重试 1 次（可配置），429 退避最多 2 次。\r
- 整条路由：最多遍历全部启用成员一次，不循环。\r
- 整体受请求级超时与编排预算约束（沿用现有 \`ChatTimeoutProperties\` 与 \`BudgetLedger\`）。\r
\r
### 6.3 降级语义\r
\r
- 默认**不允许能力降级**：成员能力不满足 Agent 需求时，该成员不能加入路由（启动校验失败）。\r
- 如确需降级，在路由上显式配置 \`allow_capability_degrade=1\`，切换时禁用缺失能力（例如关闭工具）并记录一条降级观测事件。\r
\r
---\r
\r
## 七、参数与能力\r
\r
### 7.1 能力字段\r
\r
\`\`\`json\r
{\r
  "tool_calling": true,\r
  "vision": false,\r
  "thinking": true,\r
  "json_mode": true,\r
  "context_window": 131072\r
}\r
\`\`\`\r
\r
使用规则：\r
\r
- \`agent.tools\` 非空 → 路由所有启用成员必须 \`tool_calling=true\`；\r
- 请求携带图片 → 成员必须 \`vision=true\`；\r
- Agent 要求关闭思考 → 成员必须 \`thinking=true\`，否则配置错误；\r
- \`context_window\` 用于启动期校验"模型上下文 + 输出上限是否装得下 Agent 预算"。\r
\r
### 7.2 参数合并\r
\r
\`\`\`text\r
model.default_params  <  agent.params\r
\`\`\`\r
\r
- 模型层放"这个端点的默认值"（temperature、max_tokens、top_p）；\r
- Agent 层只覆盖差异（例如 coder 要更大 max_tokens、judge 要 temperature=0）；\r
- 请求组装时合并后显式下发，不再散落代码常量。\r
\r
### 7.3 思考配置\r
\r
| 配置 | 层 | 含义 |\r
|---|---|---|\r
| \`capabilities.thinking\` | 模型 | 该端点是否支持思考开关/预算 |\r
| \`agent.params.thinking_mode\` | Agent | \`off\` / \`auto\` / 预算值，决定请求参数 |\r
| \`agent.params.show_thinking\` | Agent | 是否把思考内容透传给前端 |\r
\r
三层语义分开，避免现在"端点关思考"和"前端是否展示思考"共用相近命名的混淆。\r
\r
---\r
\r
## 八、校验规则\r
\r
### 8.1 启动校验（失败即启动失败）\r
\r
1. 每个 \`agent.route_id\` 存在且 \`enabled=1\`。\r
2. 每条启用路由至少有一个可用成员（模型与供应商均启用）。\r
3. 每个被引用 \`provider.credential_ref\` 能解析到非空凭证。\r
4. \`provider.base_url\` 合法；\`model.upstream_name\` 非空。\r
5. 成员能力覆盖 Agent 需求：工具、视觉、思考、上下文预算。\r
6. 不存在指向禁用模型/供应商的启用成员。\r
\r
### 8.2 运行时校验\r
\r
- 配置热更新先跑完整校验，通过后原子替换；失败保留旧配置并记录错误。\r
- 请求侧只做轻量断言（Agent 存在、路由已解析），不重复查库。\r
\r
---\r
\r
## 九、观测\r
\r
\`llm_call_log\` 增加/明确以下字段：\r
\r
| 字段 | 含义 |\r
|---|---|\r
| \`agent_id\` / \`agent_name\` | 发起调用的 Agent |\r
| \`route_id\` / \`route_code\` | 命中的路由 |\r
| \`model_id\` | 实际使用的模型部署 |\r
| \`provider_code\` | 实际供应商 |\r
| \`upstream_name\` | 实际请求的模型名 |\r
| \`attempt\` | 该路由内的第几次尝试 |\r
| \`fallback_from_model_id\` | 从哪个模型切换而来（无则为空） |\r
| \`error_class\` | 超时 / 连接 / 5xx / 4xx / 取消 |\r
| \`first_token_ms\`、\`duration_ms\`、token 用量 | 沿用现有口径 |\r
\r
指标：\r
\r
- \`llm_route_fallback_total{route, from_model, to_model}\`；\r
- \`llm_model_error_total{model_id, error_class}\`；\r
- \`llm_model_latency{model_id}\`（首 token 与总耗时）。\r
\r
---\r
\r
## 十、管理接口\r
\r
\`\`\`text\r
GET    /api/admin/providers                 供应商列表\r
POST   /api/admin/providers                 新增供应商（只存 credential_ref）\r
PUT    /api/admin/providers/{id}            修改端点/启用状态\r
GET    /api/admin/models                    模型列表（含能力与默认参数）\r
POST   /api/admin/models                    新增模型部署\r
PUT    /api/admin/models/{id}               修改模型/能力/启用状态\r
GET    /api/admin/routes                    路由及成员\r
POST   /api/admin/routes                    新增路由\r
PUT    /api/admin/routes/{id}               修改路由/成员/优先级\r
POST   /api/admin/routes/{id}/test          按成员顺序做一次真实连通性测试\r
PUT    /api/admin/agents/{id}/route         调整 Agent 绑定\r
\`\`\`\r
\r
所有写操作：先校验，再落库，再触发配置热加载。响应中返回校验失败原因，不允许"部分成功"。\r
\r
---\r
\r
## 十一、代码结构\r
\r
\`\`\`text\r
llm/\r
├── catalog/\r
│   ├── AgentCatalog.java          # agent 配置读取（按 name 唯一）\r
│   └── ProviderCatalog.java       # provider/model 读取与凭证解析\r
├── routing/\r
│   ├── ModelResolver.java         # route -> 有序可用成员\r
│   ├── RouteExecutor.java         # 失败切换、重试、首 token 锁定\r
│   └── RoutePlan.java             # 解析结果值对象\r
├── client/\r
│   ├── ClientFactory.java         # provider + model -> ChatClient\r
│   └── ClientRegistry.java        # Map<modelId, ChatClient>，按 id 失效\r
├── call/\r
│   ├── LlmCaller.java             # 唯一对外门面（同步/流式）\r
│   └── CallOptions.java           # 合并后的请求参数\r
├── validation/\r
│   └── BindingValidator.java      # 启动与热更新校验\r
└── admin/\r
    └── LlmAdminService.java       # 管理接口的业务实现\r
\`\`\`\r
\r
ArchUnit 守卫：\r
\r
1. \`ChatClient\` 只允许出现在 \`llm.client\` 与 \`llm.call\` 包；\r
2. \`llm_provider\` / \`llm_model\` 的 Mapper 只允许 \`catalog\`、\`admin\`、\`validation\` 使用；\r
3. 除 \`LlmCaller\` 外，不允许其他类调用 \`ClientRegistry\`。\r
\r
---\r
\r
## 十二、示例数据\r
\r
\`\`\`sql\r
INSERT INTO llm_provider (id, code, base_url, credential_ref) VALUES\r
  (1, 'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 'DASHSCOPE_API_KEY'),\r
  (2, 'deepseek',  'https://api.deepseek.com',                      'DEEPSEEK_API_KEY'),\r
  (3, 'tecent',    'https://chatapi.weixin.qq.com/openai',          'TENCENT_API_KEY');\r
\r
INSERT INTO llm_model (id, provider_id, upstream_name, display_name, capabilities, default_params) VALUES\r
  (11, 1, 'qwen3.8-27b',                 'Qwen 3.8 27B（轻量）', '{"tool_calling":true,"thinking":true,"context_window":131072}', '{"temperature":0.3}'),\r
  (12, 1, 'deepseek-v4.1-flash-1017',    'DeepSeek V4.1 Flash（DashScope）', '{"tool_calling":true,"thinking":true,"context_window":131072}', '{"max_tokens":4096}'),\r
  (13, 2, 'deepseek-v4-flash',           'DeepSeek V4 Flash（官方）', '{"tool_calling":true,"context_window":65536}', '{"max_tokens":4096}'),\r
  (14, 3, 'deepseek-v4-flash',           'DeepSeek V4 Flash（腾讯代理）', '{"tool_calling":true,"context_window":32768}', NULL);\r
\r
INSERT INTO llm_route (id, code, description) VALUES\r
  (1, 'main',  '主对话模型：dashscope 优先，腾讯代理备用'),\r
  (2, 'fast',  '轻量内部调用'),\r
  (3, 'judge', '路由判定');\r
\r
INSERT INTO llm_route_member (route_id, model_id, priority) VALUES\r
  (1, 12, 10),\r
  (1, 14, 20),\r
  (2, 11, 10),\r
  (3, 11, 10);\r
\`\`\`\r
\r
对应 Agent：\r
\r
\`\`\`sql\r
UPDATE agent SET route_id = 1 WHERE agent_name IN ('general', 'nailong');\r
UPDATE agent SET route_id = 3 WHERE agent_name = 'route-judge';\r
-- summary / profile / trigger-judge 为按本方案新建的系统 Agent（is_system=1）\r
UPDATE agent SET route_id = 2 WHERE agent_name IN ('summary', 'profile', 'trigger-judge');\r
\`\`\`\r
\r
注意 14 若实际不支持工具调用（部分代理只透传文本），则不能进入带工具的 \`main\` 路由，启动校验会失败；上面的示例按"代理支持工具"填写，落地时以真实能力为准。\r
\r
---\r
\r
## 十三、落地步骤（不保留兼容）\r
\r
1. **V20 迁移**：创建四张新表，\`agent\` 增加 \`route_id\` / \`params\`；回填现有 12 条模型、13 个 Agent 的路由映射。\r
2. **代码替换**：实现 \`llm\` 包，\`LlmCaller\` 接管同步/流式调用；删除 \`ChatClientRegistry\` 的按名接口与旧 \`model_provider\` 读写路径。\r
3. **内部调用收口**：路由判定、语义触发、画像、摘要改为系统 Agent + 路由。\r
4. **管理接口**：供应商/模型/路由/Agent 绑定四个管理面，带校验与热加载。\r
5. **删除旧表**：代码切换并验证后，V21 删除 \`model_provider\` 与 \`agent.model_provider_id\`。\r
6. **文档与观测**：更新多模型接入指南、排障手册、指标看板。\r
\r
---\r
\r
## 十四、测试策略\r
\r
- **单元**：\`ModelResolver\` 的顺序、禁用过滤、空路由；\`RouteExecutor\` 的失败矩阵（含首 token 前后边界、取消、429）；参数合并优先级；能力校验。\r
- **集成**：Flyway 建表 + 管理接口 + MySQL 外键约束。\r
- **架构**：ArchUnit 三条守卫。\r
- **故障注入**：Mock 端点分别返回超时、5xx、4xx、429，断言切换次数与观测字段。\r
- **回归**：全量测试保持现有用例数不下降。\r
\r
---\r
\r
## 十五、风险与取舍\r
\r
| 风险 | 说明 | 应对 |\r
|---|---|---|\r
| 流式中途不可切换 | 首个 token 后换模型会拼接两段回答 | 只允许首 token 前切换，错误上抛 |\r
| 备用模型回答质量差异 | 切换后用户可能感知风格变化 | 备用成员尽量同族；观测记录切换事件 |\r
| 多两张表 | 解析链路变长 | 路由解析结果缓存 + 原子热更新 |\r
| 能力矩阵维护成本 | 新模型需填能力 | 管理接口新增模型时给出校验与默认值 |\r
| 凭证轮换 | 客户端持有旧凭证 | 热加载时按 modelId 重建客户端 |\r
\r
---\r
\r
## 十六、待决问题\r
\r
1. 429 是否计入"成员失败"：当前设计为同成员退避后仍失败才切换，需确认退避次数与总预算。\r
2. 是否允许路由成员级参数覆盖（例如备用成员限制 max_tokens），还是只保留模型级与 Agent 级两层。\r
3. 灰度路由（按权重/按用户）是否纳入下一期，当前只实现 \`priority\`。\r
4. 管理接口的权限模型：沿用现有 \`X-API-Token\` / 登录口令，还是单独的管理员口令。\r
`,$e='# Agentic 路径接入 AgentScope ReAct 可行性分析\r\n\r\n> 日期：2026-10-28（评估） / 2026-10-29（落地）　|　状态：已实施　|　目标：Agentic 路径改为 AgentScope ReAct 执行，Graph 编排路径保留自研并行引擎\r\n>\r\n> 术语说明：本文写于改造前，文中的 `SIMPLE` 路径即现在的 **Agentic 路径**，`COMPLEX` 即 **Graph 编排路径**。\r\n>\r\n> 评估依据：仓库现有代码与依赖（`mvn dependency:tree` 实测）、AgentScope Java 2.0.4 官方文档。\r\n> 结论先行：**技术可行且已落地**——只引 `agentscope-core`，工具经 Spring AI 桥接，MCP/Victools 冲突已排除；落地结果与剩余观察项见第 12 节。\r\n\r\n---\r\n\r\n## 1. 结论摘要\r\n\r\n| 问题 | 结论 |\r\n|---|---|\r\n| AgentScope 能实现 agentic SIMPLE 路径吗？ | 能。`ReActAgent` 提供推理-行动循环、工具调用、流式事件、中断与多会话隔离 |\r\n| 能否与本项目现有架构共存？ | **可以**。只引 `agentscope-core`，排除其 MCP SDK 0.17.2，工具全部走 Spring AI 桥接；MCP 冲突由「不用 AgentScope MCP」规避 |\r\n| 当前 SIMPLE 路径是否已经算 agentic？ | 已有**工具调用循环**（Spring AI ChatClient 内部 ReAct），但没有显式迭代/计划/反思/任务清单 |\r\n| 推荐做法 | 方案 A：AgentScope `ReActAgent` + Spring AI 工具桥接 + feature flag；模型层二选一（见 5.1） |\r\n| 主要剩余风险 | 模型适配（AgentScope 需要自己的 `Model`）与事件/记忆/观测对等，不再是 MCP 版本 |\r\n| PoC 状态 | ✅ 2026-10-29 已落地：SIMPLE 路径默认由 `AgentScopeGeneralAgent`（ReAct）执行，工具/模型/记忆/观测复用 Spring AI 侧设施；`app.agentic.enabled=false` 可回退。详见第 12 节 |\r\n\r\n---\r\n\r\n## 2. 现状基线（代码证据）\r\n\r\n### 2.1 两条路径现状\r\n\r\n- **路由分流**：`RouteJudge` 输出 `SIMPLE` / `COMPLEX`，异常兜底 SIMPLE（[LlmRouteJudge.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/route/LlmRouteJudge.java)）。\r\n- **SIMPLE（路径 A）**：`GeneralAssistantAgent` → `AgentChatCaller` → Spring AI `ChatClient`，已具备：\r\n  - 多轮工具调用（`ToolCallback` 由 Spring AI 内部循环执行）\r\n  - 工具调用硬上限（`tool-call-limit: 4`）、工具结果预算（5000 token）、懒加载 Schema、技能索引\r\n  - 流式 token、思考内容透传、工具起止进度行\r\n  - 重试（`LlmRetry` 3 次）、取消、`llm_call_log` 观测、MCP/沙箱/RAG 工具\r\n  - 见 [GeneralAssistantAgent.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/GeneralAssistantAgent.java#L27-L45) 与 [AgentChatCaller.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L163-L214)\r\n- **COMPLEX（路径 B）**：自研 `ParallelOrchestrationEngine`（Lead 拆解 → 子任务并行 → 聚合 → 检查点续跑），见 [ParallelOrchestrationEngine.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java#L28-L98)。\r\n\r\n### 2.2 项目里的 AgentScope 是什么（同名不同物）\r\n\r\n- 当前依赖树里只有一个 AgentScope 构件：`io.agentscope:agentscope-runtime-sandbox-core:1.0.2`。\r\n  实测命令：`mvn -pl server dependency:tree -Dincludes=io.agentscope:*`，只输出该一行。\r\n- 它属于 **AgentScope Runtime**（容器沙箱运行时，仓库 `agentscope-runtime-java`），POM 传递依赖是\r\n  docker-java、aliyun-sdk-oss、agentbay-sdk、Jackson 2 等，**不包含 `agentscope-core`**。\r\n- 本文讨论的 `ReActAgent` / `Toolkit` / `streamEvents` 位于另一条产品线 **AgentScope Java**\r\n  （artifactId `agentscope-core`，仓库 `agentscope-java`）。两者同 groupId，但依赖树互不包含。\r\n\r\n| 构件 | 作用 | 当前是否在项目里 |\r\n|---|---|---|\r\n| `agentscope-runtime-sandbox-core:1.0.2` | Docker 容器沙箱运行时（`SandboxToolProvider` 使用） | ✅ 已引入 |\r\n| `agentscope-core:2.0.4` | ReActAgent / Toolkit / 事件 / 状态框架 | ❌ 未引入 |\r\n| `agentscope-extensions-model-*` | DashScope / OpenAI 等模型扩展 | ❌ 未引入 |\r\n\r\n- 版本属性 `agentscope.version=1.0.2` 仅为沙箱模块服务（[pom.xml](../../pom.xml#L42)、[server/pom.xml](../../deerfect-server/pom.xml#L41-L48)）。\r\n- 因此引入 `agentscope-core` 属于**新增第二套 Agent 执行框架**，不是复用已有能力。\r\n\r\n### 2.3 关键依赖实测（`mvn dependency:tree -pl server`）\r\n\r\n| 依赖 | 项目当前 | AgentScope 2.0.4 期望 | 判断 |\r\n|---|---|---|---|\r\n| `io.modelcontextprotocol.sdk:mcp` | **2.1.0** | **0.17.2** | ⛔ 主版本冲突（硬风险） |\r\n| `reactor-core` | 3.8.8 | 3.8.2 | ✅ 兼容，项目更新 |\r\n| `okhttp-jvm` | 5.5.0 | 5.3.2 | ✅ 同主版本，可被根 BOM 覆盖 |\r\n| `jackson-databind`（Jackson 2） | 2.21.7（已存在） | 2.21.1 | ✅ 可对齐到 2.21.7 |\r\n| `jackson`（Jackson 3 / `tools.jackson`） | Boot 4 自带 | 不涉及 | ✅ 包名不同可共存，但多一套 JSON 栈 |\r\n| `snakeyaml` | 2.6 | 2.6 | ✅ 一致 |\r\n| `tree-sitter` / `tree-sitter-bash` | 无 | 原生依赖 | ⚠️ 新增 native 体积与供应链面 |\r\n\r\n**依赖体量澄清**（回应「AgentScope 已引入、不会太重」的直觉）：\r\n\r\n- `agentscope-core:2.0.4` 的多数传递依赖项目已有：Reactor、Jackson 2（当前 2.21.7）、OkHttp、SLF4J、SnakeYAML。\r\n- 真正新增的构件主要是：`victools:jsonschema-generator` / `jsonschema-module-jackson`、\r\n  `networknt:json-schema-validator`、`tree-sitter` 与 `tree-sitter-bash` 原生库、以及\r\n  **`io.modelcontextprotocol.sdk:mcp:0.17.2`（与项目 2.1.0 冲突）**。\r\n- 所以「重量」不是主要问题；**版本冲突 + 适配层工作量**才是。若只用 `agentscope-core`、不引\r\n  `agentscope-harness`、不用 AgentScope 自带 MCP/沙箱，新增依赖面是可控的。\r\n\r\n---\r\n\r\n## 3. AgentScope 2.0.4 能力盘点\r\n\r\n来源：[Quickstart](https://java.agentscope.io/v2/zh/docs/quickstart.html)、[Agent](https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html)、[Tool](https://java.agentscope.io/v2/en/docs/building-blocks/tool)、[Middleware](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html)、[Message & Event](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)、[Model](https://java.agentscope.io/v2/en/docs/building-blocks/model)。\r\n\r\n| 能力 | 说明 | 对本项目价值 |\r\n|---|---|---|\r\n| `ReActAgent` | 推理-行动循环，`maxIters` 默认 10，支持 `call` / `streamEvents` / `observe` | ★★★ 显式 agentic 主循环 |\r\n| `Toolkit` | `@Tool` 反射注册、`ToolBase` 自定义、ToolGroup、MCP、Skill | ★★ 与现有工具体系重叠 |\r\n| 流式事件 | `TextBlockDelta` / `ThinkingBlockDelta` / `ToolCallStart` / `ToolResult*` / `AgentEnd` / `ExceedMaxIters` | ★★★ 可映射现有 SSE |\r\n| 用量 | assistant `Msg.getUsage()`（`ChatUsage`） | ★★ 可补 `llm_call_log` |\r\n| Middleware | `onAgent` / `onReasoning` / `onModelCall` / `onActing` / `onSystemPrompt` | ★★★ 观测与预算适配点 |\r\n| 中断 | `agent.interrupt(userId, sessionId)`，下一个检查点生效 | ★★★ 对应现有取消 |\r\n| 权限系统 | 工具确认 / 外部执行，支持 ASK 暂停 | ★ 与现有白名单重叠，暂不需要 |\r\n| 状态持久化 | `AgentStateStore` 按 `(userId, sessionId)` 自动存取 | ⚠️ 与现有 MySQL 会话记忆冲突 |\r\n| 模型扩展 | `agentscope-extensions-model-dashscope/openai/deepseek/...` | ★★ 需按 `model_provider` 表重建模型 |\r\n| HarnessAgent | 工作区 / 长期记忆 / 子 agent / 压缩 / 沙箱 | ⚠️ 与现有架构大面积重叠 |\r\n\r\n---\r\n\r\n## 4. 目标架构（两条路径）\r\n\r\n```\r\nChatService / RouteJudge\r\n├─ SIMPLE → AgenticAgentAdapter（可切换 provider）\r\n│    ├─ provider=spring-ai（现状，默认）\r\n│    └─ provider=agentscope（ReActAgent + 适配层）\r\n└─ COMPLEX → ParallelOrchestrationEngine（保持不变）\r\n```\r\n\r\n设计原则：\r\n\r\n1. **路由与出口不变**：仍由 `RouteJudge` 决定路径，SSE 协议、`meta`、`llm_call_log`、会话写回保持一致。\r\n2. **COMPLEX 零改动**：本次只替换路径 A 的执行体，避免编排引擎与 AgentScope 状态模型互相污染。\r\n3. **可回退**：通过 `app.agentic.provider` 开关切换，AgentScope 不可用时自动回退 Spring AI。\r\n\r\n---\r\n\r\n## 5. 集成设计（要写哪些适配层）\r\n\r\n### 5.1 模型适配（两条可选路线）\r\n\r\nAgentScope `ReActAgent` 需要自己的 `Model`（接口：`stream(messages, tools, options) → Flux<ChatResponse>`，`ChatResponse` 内含文本/思考/工具调用块与 `ChatUsage`）。\r\n\r\n**路线 1：AgentScope 模型扩展（PoC 首选，改动少）**\r\n\r\n- 用 `model_provider` 表的 `provider / model / api_url` 构造显式 Model：\r\n  - DashScope → `DashScopeChatModel.builder().baseUrl(api_url).apiKey(...)`\r\n  - DeepSeek / 其他 OpenAI 兼容 → `OpenAIChatModel.builder().baseUrl(api_url)`（官方文档确认支持自定义 `baseUrl`）\r\n- 复用现有 API Key 解析规则（`app.providers.*` > `<PROVIDER>_API_KEY` 环境变量）。\r\n- 关闭 `ModelRegistry` 字符串 id 与 Spring Boot Starter，避免引入第二套自动配置。\r\n- 代价：多一套模型客户端与配置装配，但 `Msg.getUsage()` 可回传真实 token。\r\n\r\n**路线 2：Spring AI 模型适配器（生产推荐，单一模型栈）**\r\n\r\n- 实现 `SpringAiModelAdapter implements io.agentscope.core.model.Model`，内部委托 Spring AI `ChatModel`：\r\n  - AgentScope `Msg` / `ToolResultBlock` → Spring AI `Message` / `Prompt`\r\n  - AgentScope `ToolSchema` → Spring AI `ToolDefinition`（只传定义，不执行工具，工具循环仍由 AgentScope 驱动）\r\n  - Spring AI `ChatResponse`（文本/工具调用/usage）→ AgentScope `ChatResponse` 内容块\r\n- 复用现有 `ChatClientRegistry`、模型路由、超时与 Key 解析，观测口径统一。\r\n- 代价：消息/工具 Schema/流式块的双向映射代码量更大，需要单测覆盖。\r\n\r\n### 5.2 工具适配（Spring AI `ToolCallback` → AgentScope `ToolBase`）\r\n\r\n一个桥接类即可覆盖全部现有工具（含 MCP、沙箱、技能）：\r\n\r\n```\r\nSpringAiToolBridge extends ToolBase\r\n  - name/description/inputSchema ← ToolCallback.getToolDefinition()\r\n  - callAsync(param) → ToolCallback.call(param.input(), ToolContext)\r\n  - 结果 → ToolResultBlock.success/error\r\n```\r\n\r\n要点：\r\n\r\n- 桥接**装饰后的** ToolCallback（保留 `ToolCallBudget`、`ToolCallTracer`、`ToolLazyManager`、`expand_tool`、`load_skill` 语义）。\r\n- 工具行只保留一处发射：现有 `ToolCallTracer` 已发 `tool`/`tool-done`，AgentScope 的 `ToolCallStartEvent` 不要再重复发进度行。\r\n- 不注册 AgentScope 自带 MCP client，继续用 Spring AI 的 MCP 工具桥接，规避 SDK 版本冲突。\r\n- 权限系统统一配置为 allow（工具可见性已由 `agent.tools` 白名单在服务端约束），不启用 ASK/外部执行事件。\r\n\r\n### 5.3 记忆适配（避免双写）\r\n\r\n- 现有会话记忆以 MySQL `session_messages` 为唯一事实源，由 `ChatService` 统一写回。\r\n- AgentScope 侧**不启用 `AgentStateStore`**，每次请求把裁剪后的历史转成 `Msg` 列表注入上下文，再发起 `streamEvents`。\r\n- 需要 PoC 验证：`observe(history)` + `streamEvents(userMsg)` 的行为是否符合预期（历史不重复追加、上下文不跨请求残留）。\r\n- 若必须用 `AgentStateStore`，需实现 MySQL 实现并与现有快照双向同步，复杂度显著上升，不建议一期做。\r\n\r\n### 5.4 Prompt / RAG / 画像\r\n\r\n- 沿用现有 `PromptAssembler` 与 `AgentRequestSpecFactory` 产出的 system prompt。\r\n- RAG 检索仍在组装 prompt 前旁路完成（`KnowledgeRetriever`），AgentScope 只接收最终 prompt，不改检索链路。\r\n- 用户画像段继续经 `UserProfileSectionProvider` 注入 system prompt。\r\n\r\n### 5.5 流式事件映射（AgentScope → 现有 SSE）\r\n\r\n| AgentScope 事件 | 现有输出 |\r\n|---|---|\r\n| `TextBlockDeltaEvent.delta` | `event: token` |\r\n| `ThinkingBlockDeltaEvent.delta` | `ProgressLine("思考 · <agent>", delta)` |\r\n| `ToolCallStartEvent` | 不重复发（由 `ToolCallTracer` 负责） |\r\n| `ToolResultEndEvent` | `tool-done` 进度行（同上） |\r\n| `ExceedMaxItersEvent` | `ProgressLine("agentic", "达到最大迭代轮次")` |\r\n| `AgentEndEvent` + `Msg.getUsage()` | `meta`（status/usage/sessionId） |\r\n| `RequireUserConfirmEvent` / `RequireExternalExecutionEvent` | 一期不启用；出现即按错误降级 |\r\n\r\n### 5.6 观测与预算\r\n\r\n- **观测**：自定义 `MiddlewareBase.onModelCall` 包裹每次模型调用，记录耗时/异常；最终 `Msg.getUsage()` 写 `llm_call_log`（若事件层拿不到逐次 usage，则退化为「一轮一行」口径，需接受与现状差异）。\r\n- **迭代上限**：`maxIters` 与现有 `tool-call-limit` 对齐（建议 `maxIters = tool-call-limit + 1`，避免语义双算）。\r\n- **输出上限**：通过 `GenerateOptions.maxTokens` 注入 `max-tokens-final`。\r\n- **工具预算**：由桥接后的 `ToolCallBudget` 装饰器继续生效，AgentScope 侧不重复实现。\r\n- **重试**：二选一——关闭 AgentScope `ModelConfig` 重试、沿用项目 `LlmRetry`；或启用 AgentScope 重试并在 Middleware 记账。**不建议两套同时开**。\r\n\r\n### 5.7 取消、超时与并发\r\n\r\n- 外层 `StreamConnectionLimiter`（默认 50）不变。\r\n- Flux `cancel` → 从 `RuntimeContext` 定位 `(userId, sessionId)` → `agent.interrupt(...)`；流空闲/首帧超时仍由适配层 watchdog 兜底。\r\n- AgentScope 同一 `(userId, sessionId)` 会串行化；项目当前同一会话也基本串行，需确认并发策略一致性。\r\n\r\n### 5.8 需要新增/改动的模块\r\n\r\n| 模块 | 改动 |\r\n|---|---|\r\n| `server` | 新增 `agent/agentic` 包：`AgentScopeAgentAdapter`、`SpringAiToolBridge`、`AgentScopeModelFactory`、`ObservabilityMiddleware` |\r\n| `server` | `GeneralAssistantAgent` 增加 provider 选择（或新增 `AgenticAgent` 委托类） |\r\n| `harness-api` | 无改动（SSE / DTO 契约不变） |\r\n| `pom.xml` | 新增 `agentscope-core` + 模型扩展；排除冲突依赖；版本锁定 |\r\n| 配置 | 新增 `app.agentic.*`（provider / max-iters / 是否启用） |\r\n\r\n---\r\n\r\n## 6. 依赖冲突与兼容性（本方案的关键风险）\r\n\r\n### 6.1 MCP SDK 主版本冲突（已选定规避方案）\r\n\r\n- 项目：`io.modelcontextprotocol.sdk:mcp:2.1.0`（Spring AI 2.0.2 管理）\r\n- AgentScope 2.0.4 ~ 2.1.0-BETA1：均为 `0.17.2`\r\n- **选定方案**：只引 `agentscope-core`，从依赖中排除 `io.modelcontextprotocol.sdk:mcp:0.17.2`，\r\n  保留项目 2.1.0；**不使用 AgentScope 的 MCP client / MCP 相关 API**，所有 MCP 工具继续由\r\n  Spring AI 获取为 `ToolCallback`，再桥接成 AgentScope `ToolBase`。\r\n- 残余验证点：`Toolkit` 类加载与工具注册是否会触发旧版 MCP API（预期不会，因其 MCP 代码路径不执行）。\r\n  PoC 若出现 `NoSuchMethodError`，退路是隔离类加载/独立进程，或放弃 AgentScope 路径。\r\n- **PoC 结论（2026-10-28）**：`Toolkit` 创建、`registerTool`、真实工具调用均正常，未出现\r\n  `NoSuchMethodError` / `NoClassDefFoundError`；`mvn dependency:tree` 确认 classpath 上只有\r\n  `io.modelcontextprotocol.sdk:mcp:2.1.0`，0.17.2 已被完全排除。\r\n- 风险等级：已由「硬风险」降为「已规避（基础工具场景）」，MCP 工具桥接仍需真实 server 联调。\r\n\r\n### 6.2 其他依赖\r\n\r\n| 风险 | 说明 | 缓解 |\r\n|---|---|---|\r\n| Jackson 2 + Jackson 3 并存 | AgentScope 用 Jackson 2.21，Boot 4 用 Jackson 3 | 可共存；统一 Jackson 2 版本到 2.21.7，避免两套 `ObjectMapper` 配置混用 |\r\n| Reactor 版本 | 项目 3.8.8 > AgentScope 3.8.2 | 由 Boot BOM 统一，不降级 |\r\n| OkHttp | 5.5.0 > 5.3.2 | 根 BOM 锁定 5.5.0 |\r\n| tree-sitter native | 新增操作系统相关二进制 | 评估镜像体积、CI 构建平台、安全扫描 |\r\n| Spring Boot Starter | AgentScope 提供 starter，但会引入自己的自动配置与模型装配 | 只用 `agentscope-core`，不用 starter |\r\n\r\n---\r\n\r\n## 7. 风险与缓解清单\r\n\r\n| # | 风险 | 影响 | 缓解 | 验证方式 |\r\n|---|---|---|---|---|\r\n| R1 | MCP SDK 冲突 | 启动/工具注册 `NoSuchMethodError` | 排除 0.17.2、不用 AgentScope MCP，工具全走 Spring AI 桥接 | PoC 启动 + 调用 MCP 工具 |\r\n| R2 | 工具桥接语义偏差 | 预算/懒加载/追踪失效 | 桥接装饰后的 callback，保持单点计数 | 对照现有 `ToolCallBudgetTest` 行为 |\r\n| R3 | 记忆双写/串会话 | 上下文污染 | 禁用 stateStore，每请求注入历史 | 两轮会话 + 并发两会话测试 |\r\n| R4 | 逐次 usage 缺失 | 观测口径退化 | `Msg.getUsage()` + Middleware 记账；路线 2 可在模型适配器内拿真实 usage | 对比 `llm_call_log` |\r\n| R5 | 取消不生效 | 客户端断开后仍烧 token | interrupt + watchdog 双保险 | 中途 abort 压测 |\r\n| R6 | 事件重复发射 | 前端出现双份工具行/token | 明确单一发射源 | 前端回归 |\r\n| R7 | AgentScope 版本演进快 | BETA/不兼容 | 锁定 2.0.4，建立升级评估门禁 | 依赖升级记录 |\r\n| R8 | 双框架维护成本 | 两套 prompt/工具/观测 | 工具与模型统一走 Spring AI 适配层（模型路线 2），AgentScope 只保留 ReAct 循环 | 代码边界与开关检查 |\r\n\r\n---\r\n\r\n## 8. 方案对比与推荐\r\n\r\n| 方案 | 描述 | 优点 | 缺点 | 建议 |\r\n|---|---|---|---|---|\r\n| **A. AgentScope ReActAgent** | 只引 `agentscope-core`，SIMPLE 路径用 `ReActAgent`；工具与模型走 Spring AI 桥接 | 显式 agentic、事件/中断/中间件成熟，MCP 冲突可规避 | 需要工具桥接与模型适配层 | ✅ **选定方向** |\r\n| **B. AgentScope HarnessAgent** | 使用工作区/长期记忆/子 agent/沙箱 | 开箱即用的长任务能力 | 与现有 MySQL 记忆、RAG、skills、沙箱大面积重叠 | ❌ 不建议 |\r\n| **C. 现有链路上自建显式 ReAct** | 在 `AgentChatCaller` 外层增加迭代循环、任务清单工具、步骤可见化 | 零新增框架依赖、复用全部护栏与观测、风险最低 | 需要自己实现循环控制与中断 | 作为 A 的回退 |\r\n| **D. 维持现状** | 继续用 Spring AI 内部工具循环 | 零改动 | 无显式计划/反思，agentic 叙事弱 | 作为基线 |\r\n\r\n**推荐路径（按已确认方向）**：方案 A——只引 `agentscope-core`，MCP 工具经 Spring AI `ToolCallback` 桥接，模型层 PoC 先用路线 1、生产优先路线 2；通过 `app.agentic.provider` 开关灰度，保留方案 C 作为回退。HarnessAgent 不引入。\r\n\r\n---\r\n\r\n## 9. 分阶段落地建议（不含工期承诺）\r\n\r\n- **阶段 0 · 隔离 PoC**\r\n  - 在独立 profile/模块引入 `agentscope-core:2.0.4`，排除 MCP SDK 0.17.2\r\n  - 验证：Spring Boot 4 启动、工具桥接、`streamEvents` 映射、`interrupt`、usage 记账、两轮记忆\r\n  - 产出：依赖冲突结论 + 事件字段实测\r\n- **阶段 1 · 适配层**\r\n  - `AgentScopeAgentAdapter` + `SpringAiToolBridge` + `ObservabilityMiddleware`\r\n  - 开关 `app.agentic.provider=spring-ai|agentscope`，默认 `spring-ai`；异常自动回退\r\n- **阶段 2 · 对等验收**\r\n  - 工具/预算/RAG/记忆/观测/取消/错误六项与现状对齐\r\n  - 全量回归 + 前端 SSE 回归\r\n- **阶段 3 · 决策**\r\n  - 指标达标且维护成本可接受 → 灰度放量\r\n  - 任一硬指标不达标 → 回退方案 C，移除 AgentScope 核心依赖\r\n\r\n**回退策略**：`app.agentic.provider` 切回 `spring-ai` 即恢复现状；AgentScope 依赖只存在于 `server` 的可选 profile，默认构建不引入。\r\n\r\n---\r\n\r\n## 10. 验收标准（若实施）\r\n\r\n- [ ] SIMPLE 请求端到端可用，SSE `token/progress/meta` 与现状一致\r\n- [ ] 工具调用行、思考行、归属 agent 与现状一致，无重复事件\r\n- [ ] `llm_call_log` 有记录，耗时/token 口径可解释\r\n- [ ] 两轮会话记忆正确；并发两会话不串上下文\r\n- [ ] 客户端中途断开后 1 个 token 边界内停止模型调用\r\n- [ ] `general` 最小权限工具集在 AgentScope 路径同样生效\r\n- [ ] MCP 工具（Tavily/WMS）可调用，且未引入两个 MCP SDK 版本\r\n- [ ] 回退开关切换无需重启或一次重启内完成\r\n- [ ] 新增依赖不破坏 COMPLEX 路径全量测试\r\n\r\n---\r\n\r\n## 11. 待确认问题\r\n\r\n1. **agentic 的定义**：是「多步工具调用」（现状已具备），还是需要计划清单、反思、子 agent、工作区？\r\n2. 是否接受新增一套 Agent 框架依赖与 native 依赖（tree-sitter）？\r\n3. SIMPLE 与 COMPLEX 是否允许使用不同模型/观测口径？\r\n4. AgentScope 版本策略：锁 2.0.4，还是跟随 2.1.x/BETA？\r\n5. 若 PoC 发现 MCP SDK 无法共存，是否接受「AgentScope 不使用 MCP，全部走 Spring AI 桥接」？\r\n\r\n---\r\n\r\n## 12. PoC 实测结果（2026-10-28）\r\n\r\n**分支**：`feat/agentscope-agentic-poc`（`agentscope-core` 与模型扩展均为 **test scope**，不影响运行期产物）\r\n\r\n**依赖验证**\r\n\r\n- `agentscope-core:2.0.4` + `agentscope-extensions-model-openai:2.0.4`，显式排除\r\n  `io.modelcontextprotocol.sdk:mcp`。\r\n- `mvn -pl server dependency:tree -Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:mcp`\r\n  结果：`agentscope-core:2.0.4:test`、`agentscope-extensions-model-openai:2.0.4:test`、\r\n  `io.modelcontextprotocol.sdk:mcp:2.1.0:compile`（无 0.17.2）。\r\n\r\n**用例与结果**：[AgentScopeReActPoC](../../deerfect-server/src/test/java/com/dark/javaHarness/agent/agentic/AgentScopeReActPoC.java)\r\n\r\n| 用例 | 验证内容 | 结果 |\r\n|---|---|---|\r\n| `reactLoopCallsSpringRegisteredStyleToolAndStreamsEvents` | 真实 DeepSeek 模型 + `@Tool` 反射工具 + `streamEvents` | ✅ 2.69s，工具被调用，事件流完整 |\r\n| `springAiToolCallbackBridgesIntoAgentScopeToolkit` | Spring AI `ToolCallback` → `ToolBase` 桥接 + `Toolkit.registerTool` + 调用回填 | ✅ 参数/结果双向转换正常 |\r\n\r\n**实测事件序列**：\r\n\r\n```\r\nAGENT_START → MODEL_CALL_START → TOOL_CALL_START → TOOL_CALL_DELTA → TOOL_CALL_END\r\n→ MODEL_CALL_END → TOOL_RESULT_START → TOOL_RESULT_TEXT_DELTA → TOOL_RESULT_END\r\n→ TEXT_BLOCK_START → TEXT_BLOCK_DELTA → TEXT_BLOCK_END → AGENT_RESULT → AGENT_END\r\n```\r\n\r\n**已确认**\r\n\r\n- AgentScope 与 Spring Boot 4 / Spring AI 2 / MCP SDK 2.1.0 同 classpath 可启动；\r\n- ReAct 循环、工具调用、文本增量事件可用；\r\n- Spring AI 工具（含未来 MCP 工具）可通过一个 `ToolBase` 桥接类进入 AgentScope，无需第二套 MCP client。\r\n\r\n**落地结果（2026-10-29）**\r\n\r\n- 新增 `AgentScopeGeneralAgent`：SIMPLE 路径默认 ReAct；`AgentRegistry` 按 `app.agentic.enabled` 选择，\r\n  初始化失败回退既有 `GeneralAssistantAgent`（流开始后不切换）；\r\n- 新增 `AgentScopeModelFactory`：按 `model_provider` 行构建 OpenAI 兼容 Model，Key 解析与 ChatClient 同源，\r\n  `disable_thinking=1` 映射为 `enable_thinking=false`；\r\n- 新增 `SpringAiToolBridge`：工具仍由 Spring AI 提供并经装饰链（观测→预算→懒加载→元工具），\r\n  trace 标识经 `RuntimeContext` → `ToolContext` 透传；\r\n- 记忆不启用 AgentStateStore，每请求注入裁剪后的 MySQL 历史；system 复用 `PromptAssembler` + RAG；\r\n- 观测记一轮 `llm_call_log`，usage 按 `MODEL_CALL_END` 逐次累加（多轮工具调用不低估）；取消映射为 `agent.interrupt`；\r\n- `AgentRequestSpecFactory` 抽出 `assembleSystem` / `loadHistory` / `resolveTools`，两条路径共用装配面。\r\n\r\n**依赖冲突补充（实测）**\r\n\r\n- 第二个冲突：AgentScope 自带 `victools:jsonschema-generator/module-jackson:4.38.0` 会将项目\r\n  Spring AI 需要的 **5.0.0** 降级，导致 `ToolDefinitions` 缺 `JacksonSchemaModule`、注解工具 Schema 生成失败；\r\n  已在 `agentscope-core` / 模型扩展上排除这两个 artifact，最终 classpath 为 5.0.0。\r\n- 第三个冲突（MySQL 可用后暴露）：AgentScope 自带 `com.networknt:json-schema-validator:2.0.0`（仅 Jackson 2 API）\r\n  会覆盖 MCP SDK 2.1.0 需要的 **3.0.6**（Jackson 3 API），`mcpSyncServer` 初始化时\r\n  `Schema.validate(tools.jackson.databind.JsonNode)` 报 `NoSuchMethodError`；已在两个 AgentScope\r\n  artifact 上排除 `json-schema-validator`，最终使用 3.0.6（其保留 Jackson 2 方法，AgentScope\r\n  `ToolValidator` 的 `SchemaRegistry.getSchema` / `Schema.validate(String, InputFormat)` 仍可解析）。\r\n- 最终依赖树：`agentscope-core:2.0.4`、`agentscope-extensions-model-openai:2.0.4`、\r\n  `io.modelcontextprotocol.sdk:mcp:2.1.0`、`com.github.victools:*:5.0.0`、`com.networknt:json-schema-validator:3.0.6`。\r\n- 测试：`AgentScopeReActPoC` 2 例 + `AgentScopeGeneralAgentTest` 3 例（真实模型/回退/历史+工具桥接）通过；\r\n  MySQL 可用时 server 全量 697 例通过（含 `JavaHarnessApplicationTests` 上下文加载）。\r\n\r\n**仍未验证（生产观察项）**\r\n\r\n- 真实 MCP server 工具（Tavily/WMS）经桥接后的调用与错误结构（当前验证的是合成 Spring AI ToolCallback）；\r\n- 客户端断连 `interrupt` 的真实链路（单测未覆盖取消时序）；\r\n- DashScope 等其他供应商的 `api_url` 兼容性（当前仅 DeepSeek 官方端点实测）；\r\n- 历史裁剪只对历史段做预算，未把 system/本轮合并裁剪（与 ChatClient 路径存在轻微口径差异）。\r\n\r\n---\r\n\r\n## 13. 参考\r\n\r\n**仓库代码**\r\n- [GeneralAssistantAgent.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/GeneralAssistantAgent.java)\r\n- [AgentChatCaller.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java)\r\n- [ParallelOrchestrationEngine.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java)\r\n- [server/pom.xml](../../deerfect-server/pom.xml#L41-L48)\r\n- [tech-stack.md](../tech-stack.md)\r\n\r\n**AgentScope Java 文档**\r\n- [Quickstart](https://java.agentscope.io/v2/zh/docs/quickstart.html)\r\n- [Agent（ReActAgent / interrupt / RuntimeContext）](https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html)\r\n- [Tool（Toolkit / ToolBase / MCP / Skill）](https://java.agentscope.io/v2/en/docs/building-blocks/tool)\r\n- [Middleware（onModelCall / onActing）](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html)\r\n- [Message & Event（AgentEvent / usage）](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)\r\n- [Model（扩展模块 / Spring Boot Starter）](https://java.agentscope.io/v2/en/docs/building-blocks/model)\r\n\r\n**Maven 元数据**\r\n- `io.agentscope:agentscope-core` 最新稳定 2.0.4（2.1.0-BETA1 仍依赖 MCP SDK 0.17.2）\r\n',Ze='# Channel/Core 模块拆分设计：Maven 多模块边界与通用 SPI\r\n\r\n> 范围：把原单体工程拆为「契约（`harness-api`）— 内核（`server`）— 渠道（`channel-qq`）」三层\r\n> Maven 模块，用编译期依赖方向 + enforcer 规则固化边界，并新增渠道无关的回复后处理 SPI。\r\n> 关键词：单向依赖、编译期隔离、`ReplyPostProcessor`、`ChatResponse.flags`。\r\n\r\n## 一、三模块依赖图\r\n\r\n```\r\n                 harness-api（契约：端口接口 + DTO；不含任何渠道实现）\r\n                       ▲                              ▲\r\n       compile         │                              │         compile\r\n                       │                              │\r\n                  ┌────┴────┐                    ┌────┴─────────┐\r\n                  │  server │                    │  channel-qq  │\r\n                  │（core + │                    │（QQ / NapCat │\r\n                  │  web /  │                    │  OneBot 11） │\r\n                  │ Spring  │                    └──────────────┘\r\n                  │ Boot app│\r\n                  └────┬────┘\r\n                       │  runtime（编译期不可见，仅运行期装配到 fat jar）\r\n                       └──────────────────────────────────────────▶\r\n```\r\n\r\n- `harness-api` ←── compile ── `server`：内核编译期只认契约，不认渠道。\r\n- `harness-api` ←── compile ── `channel-qq`：渠道编译期只认契约。\r\n- `server` ──── runtime ───▶ `channel-qq`：**仅运行期**装配；`server` 的编译期 classpath 里没有渠道类。\r\n\r\n模块清单见根 `pom.xml` 的 `<modules>`：`harness-api, channel-qq, server, cli, wms-mcp-adapter`。\r\n\r\n## 二、依赖规则（强制）\r\n\r\n### 规则 ①：core(server) 不得 compile 依赖 channel-qq\r\n\r\n由 `server/pom.xml` 的 maven-enforcer `bannedDependencies` 守护：\r\n\r\n```xml\r\n<plugin>\r\n  <artifactId>maven-enforcer-plugin</artifactId>\r\n  <executions>\r\n    <execution>\r\n      <id>ban-core-on-channel</id>\r\n      <goals><goal>enforce</goal></goals>\r\n      <configuration>\r\n        <rules>\r\n          <bannedDependencies>\r\n            <!-- core(server) 不得 compile 依赖渠道模块 -->\r\n            <excludes>\r\n              <exclude>com.dark:channel-qq:*:*:compile</exclude>\r\n            </excludes>\r\n            <searchTransitive>false</searchTransitive>\r\n          </bannedDependencies>\r\n        </rules>\r\n        <fail>true</fail>\r\n      </configuration>\r\n    </execution>\r\n  </executions>\r\n</plugin>\r\n```\r\n\r\n- 渠道依赖 `channel-qq` 必须以 `runtime` scope 声明（`server/pom.xml` 中即如此），编译期无法 `import`\r\n  任何 `com.dark.javaHarness.channel.qq.*` 类型——把「内核耦合渠道」变成编译错误。\r\n- 若有人把 `runtime` 改成 `compile`，enforcer 立即让构建失败：隔离被破坏时**构建直接红**，\r\n  而不是等到运行期才发现。\r\n\r\n### 规则 ②：channel-qq 只依赖 harness-api\r\n\r\n`channel-qq/pom.xml` 中仅有两类依赖：\r\n\r\n- `com.dark:harness-api`（契约）；\r\n- 渠道自身所需的第三方库（spring-boot-starter-web、mybatis-plus、okhttp、jackson 等）。\r\n\r\n渠道**不依赖** `server`，因此渠道代码不得引用任何 core 实现类——渠道只能面向 `harness-api` 编程。\r\n\r\n### 规则 ③：新增渠道输出控制必须走 ReplyPostProcessor，不得改 core\r\n\r\n渠道要对自己的回复做「清洗 / 打标」（例如剥离渠道私有控制标记并声明 flag），一律走通用 SPI：\r\n\r\n1. 在**渠道侧**新增一个 `ReplyPostProcessor` 实现（`@Component`），负责剥离渠道私有标记并声明 flag；\r\n2. 若还需新增出参字段，在**契约侧** `ChatResponse`（`harness-api`）上扩展通用字段（如已有的\r\n   `flags`），而不是塞进渠道专有字段；\r\n3. **不得修改 core** 去识别任何渠道私有约定。\r\n\r\nSPI 定义（`harness-api`，`com.dark.javaHarness.contract.spi.ReplyPostProcessor`）：\r\n\r\n```java\r\npublic interface ReplyPostProcessor {\r\n    /** 处理结果：@param text 清洗后的文本 @param flags 声明出的标志（如 "voice"） */\r\n    record Processed(String text, Set<String> flags) { }\r\n\r\n    /** 处理原始回复文本；返回清洗后的文本与标志。入参非 null。 */\r\n    Processed process(String rawReply);\r\n}\r\n```\r\n\r\n约定：实现必须是**无副作用、幂等**的纯文本处理，不得抛异常（core 捕获并跳过，保证渠道故障不拖垮回复）。\r\ncore（`server` 的 `ChatServiceImpl`）用 `ObjectProvider<ReplyPostProcessor>` 收集全部实现，\r\n在「写回会话记忆」与「构造 `ChatResponse`」之前按序调用；**无实现时零行为**（后处理为空、`flags` 为空集）。\r\n\r\n渠道可基于该 SPI 定义自己的私有标记；core 不认识渠道私有约定，只把 `flags` 透出到\r\n`ChatResponse`，由渠道自行决定怎么用。当前 QQ 语音已改为工具直接产生 `voice:<id>` flag，\r\n不再使用 `[[voice]]` 标记（原 `QqReplyProcessor` 已删除）。\r\n\r\n## 三、`cli` 与 `wms-mcp-adapter` 与本次拆分无关\r\n\r\n- **`cli`** 是纯 HTTP 客户端：通过 REST 调 `server`，并**自带一份 DTO 副本**\r\n  （如 `cli/.../domain/dto/ChatResponse.java`，与 `harness-api` 的同名 record）。\r\n  它不依赖 `harness-api`、不依赖 `channel-qq`，本次拆分不改其代码。\r\n  DTO 副本与 `harness-api` 的差异（例如 `flags` 字段）**对旧客户端无影响**：Jackson 反序列化\r\n  默认忽略未知字段，服务端多透出一个 `flags` 不会破坏 `cli` 的解析——**向后兼容**。\r\n- **`wms-mcp-adapter`** 独立于本次拆分，未受影响。\r\n\r\n> 结论：本重构只动 `harness-api` / `channel-qq` / `server` 三个模块，`cli` 与 `wms-mcp-adapter` 零改动。\r\n',er=`# NapCat 接入计划\r
\r
> 目标：QQ 消息（私聊/群聊）→ NapCat → java-harness AI Agent → AI 回复 → QQ\r
> 通信方式：**HTTP POST 上报 + HTTP API 下行**（OneBot 11 协议）\r
>\r
> 本计划写于当时的项目基线（376 测试、OkHttp 4.12 → 归档时 830 测试、OkHttp 5.5.0、Java 25；2026-11-06 主干已为 1086 测试），\r
> 属设计存档，现状以 [features.md](../features.md) / [tech-stack.md](../tech-stack.md) 为准。\r
> 渠道适配代码按 **channel/qq 垂直包隔离**：core 单向不依赖渠道包。\r
\r
---\r
\r
## 1. 现状\r
\r
| 项 | 状态 |\r
| --- | --- |\r
| NapCat 容器 | 运行中（\`napcat\`，映射端口 3000/3001/6099），QQ 账号 \`3896564418\` 配置已生成 |\r
| NapCat OneBot 服务 | **全部未启用**（\`onebot11_3896564418.json\` 中 httpServers/websocketServers 等均为空） |\r
| java-harness | Spring Boot 3.5.14 + Java 17；\`ChatService.chat(ChatRequest)\` 同步聊天（自动建档、多轮记忆、SIMPLE/COMPLEX 路由）、\`SessionService.createSession(creator, firstQuestion)\`、OkHttp 4.12 依赖已有 |\r
| Agent 能力 | agent 表支持 per-agent \`tools\`（V10）、\`internal\`（V9）、**\`knowledge\` 知识库绑定**（V12，多库隔离检索） |\r
| Flyway | 已用到 **V13**，本计划新表用 **V14** |\r
| 宿主机 firewalld | 已放行：20/21/22/80/443/8888/41127/39000-40000；**3000/3001/6099/8080 均未放行** |\r
\r
### 部署拓扑建议（避免改 firewalld）\r
\r
两者都跑在本机时全部走本机回环/bridge 网关，无需对公网放行任何新端口：\r
\r
\`\`\`\r
NapCat 容器(3000)  ←──宿主机 127.0.0.1:3000──  java-harness 发消息\r
NapCat 容器       ──上报 http://172.17.0.1:8080/onebot/event──▶ java-harness\r
\`\`\`\r
\r
- java-harness → NapCat：用 \`http://127.0.0.1:3000\`（容器端口映射）\r
- NapCat → java-harness：上报地址写 \`http://172.17.0.1:8080\`（容器内访问宿主机 bridge 网关）\r
- 若 java-harness 部署在其他机器，才需要在 firewalld 放行对应端口\r
\r
---\r
\r
## 2. 总体架构\r
\r
\`\`\`\r
QQ用户消息\r
   │\r
   ▼\r
┌─────────────┐  HTTP POST /onebot/event   ┌──────────────────────────────┐\r
│   NapCat    │ ─────────────────────────▶ │  java-harness                │\r
│ (OneBot 11) │  {message, user_id, ...}   │  OneBotEventController       │\r
│             │                            │    │ 幂等去重 → 立即 200 ACK │\r
│             │                            │    ▼ onebotExecutor 异步     │\r
│             │                            │  OneBotEventService          │\r
│             │                            │    │ 过滤/会话绑定           │\r
│             │                            │    ▼ 进程内直调（非 HTTP 自调）│\r
│             │                            │  ChatService.chat(...)       │\r
│             │                            │    │ 多轮记忆+知识库+多Agent  │\r
│             │                            │    ▼                        │\r
│             │ ◀───────────────────────── │  NapCatApiClient             │\r
└─────────────┘  POST /send_private_msg     └──────────────────────────────┘\r
                 POST /send_group_msg\r
      │\r
      ▼\r
   QQ回复用户\r
\`\`\`\r
\r
**关键决策：进程内直调，不走 HTTP 自调。** 直接注入 \`ChatService\`/\`SessionService\`\r
调用，绕过回环网络、鉴权与序列化开销；\`/api/harness/sessions\` 等 HTTP 端点仍保留给外部客户端。\r
\r
**分层定位：渠道适配层。** QQ 接入属应用层渠道适配，不属 harness 核心（编排/多 Agent/RAG/预算）。\r
代码按方案 B 收拢到 \`channel/qq\` 垂直包，对 core 的依赖仅限 \`ChatService\`/\`SessionService\`\r
两个端口，core 反向 \`import channel\` 禁止。渠道数 ≥ 2 或需独立部署时，再升级为独立\r
Maven module / 进程（仿 CLI 走 HTTP 的先例），core 一行不动。\r
\r
---\r
\r
## 3. 阶段一：NapCat 侧启用 OneBot 服务\r
\r
### 3.1 修改 \`onebot11_3896564418.json\`（容器内 \`/app/napcat/config/\`）\r
\r
\`\`\`json\r
{\r
  "network": {\r
    "httpServers": [\r
      {\r
        "name": "java-harness",\r
        "enable": true,\r
        "host": "0.0.0.0",\r
        "port": 3000,\r
        "enableCors": false,\r
        "enableWebsocket": false,\r
        "messagePostFormat": "array",\r
        "token": "<自定义token，如 napcat-token-123>",\r
        "debug": false\r
      }\r
    ],\r
    "httpClients": [\r
      {\r
        "name": "report-to-harness",\r
        "enable": true,\r
        "url": "http://172.17.0.1:8080/onebot/event",\r
        "messagePostFormat": "array",\r
        "reportSelfMessage": false,\r
        "token": "",\r
        "debug": false\r
      }\r
    ],\r
    "httpSseServers": [],\r
    "websocketServers": [],\r
    "websocketClients": [],\r
    "plugins": []\r
  }\r
}\r
\`\`\`\r
\r
关键点：\r
\r
- \`httpServers\`：java-harness 调用的下行 API（\`/send_private_msg\`、\`/send_group_msg\` 等），token 必填\r
- \`httpClients\`：NapCat → java-harness 的消息上报，\`url\` 指向宿主机 bridge 网关\r
- \`messagePostFormat: array\` → 上行/下行统一用消息段数组；**@ 触发解析优先查 \`message\` 数组里的\r
  \`at\` 段（\`data.qq == self_id\`），\`raw_message\` 的 CQ 码字符串仅作兜底**，不作为主判据\r
- 上报端 \`token\` 先留空，联调通过后再加验签（见 4.6）\r
\r
### 3.2 操作步骤\r
\r
1. 改配置：\`docker exec\` 直接改文件，或通过 WebUI（\`http://<IP>:6099/webui\`，token \`df4aef91da42\`）→ 网络配置 → 添加 HTTP 服务器 / HTTP 客户端\r
2. 重启容器：\`docker restart napcat\`\r
3. 验证：\`curl -H "Authorization: Bearer <token>" http://127.0.0.1:3000/get_login_info\` 返回 \`{"data":{"user_id":3896564418,...}}\` 即正常\r
\r
### 3.3 前置检查\r
\r
- [ ] QQ 已扫码登录成功（\`docker logs napcat\` 无登录等待提示）\r
- [ ] NapCat 容器与宿主机 8080 连通（\`docker exec napcat curl -s -o /dev/null -w '%{http_code}' http://172.17.0.1:8080/api/harness/agents\`）\r
\r
---\r
\r
## 4. 阶段二：java-harness 侧新增代码\r
\r
包路径沿用 \`com.dark.javaHarness\`。**先复用，再新增**：\r
\r
### 4.0 直接复用的现有能力（不重复造）\r
\r
| 现有能力 | 用法 |\r
| --- | --- |\r
| \`ChatService.chat(ChatRequest)\` | 同步聊天主链路：无 sessionId 自动建档、写回多轮记忆、SIMPLE/COMPLEX 自动路由、失败返回 \`status=FAILED\` |\r
| \`SessionService.createSession(creator, firstQuestion)\` | QQ 首次消息建会话，\`creator\` 传 \`qq:<uid>\`（区别于 chat 自动建档的 \`anonymous\`），返回字符串形式的自增主键 |\r
| \`ChatRequest(message, sessionId, agentId)\` | \`agentId\` 对应 agent 表主键，可空走默认 Agent —— napcat 全局 \`agent-id\` 配置直接对上 |\r
| agent 表 \`knowledge\` 列（V12） | 绑定的 agent 自动获得多库隔离 RAG 检索，QQ 问答零改造带知识库能力 |\r
| \`ChatResponse.sources\` | 知识命中出处（docName/title/score），QQ 回复可选拼接「出处」尾巴 |\r
| \`GoalExecutorConfig\` 双池先例 | 新增 \`onebotExecutor\` 照此模式：ThreadPoolTaskExecutor + 有界队列 + 拒绝策略 |\r
| \`\${ENV_VAR:default}\` 密钥约定 | \`napcat.api-token: \${NAPCAT_API_TOKEN:}\`，不落 git |\r
| DTO record 风格 | OneBot DTO 一律 record（对齐 ChatRequest/ChatResponse） |\r
| \`KnowledgeProperties\` 先例 | \`NapCatProperties\` 用 \`@Component + @ConfigurationProperties(prefix = "napcat")\` 纯绑定载体 |\r
\r
### 4.1 新增文件（channel/qq 垂直包隔离）\r
\r
渠道适配代码全部收拢在 \`channel/qq\` 包内，core 包（controller/service/agent/...）保持不动：\r
\r
\`\`\`\r
src/main/java/com/dark/javaHarness/\r
├── channel/qq/                        # 渠道适配层（单向依赖：只进不出）\r
│   ├── NapCatChannelConfig.java       # 装配门控（napcat.enabled）+ onebotExecutor 线程池\r
│   ├── NapCatProperties.java          # @ConfigurationProperties("napcat")\r
│   ├── OneBotEventController.java     # POST /onebot/event：去重 → 200 ACK → 异步\r
│   ├── OneBotEventService.java        # 事件处理接口（过滤/去重/限频/绑定）\r
│   ├── OneBotEventServiceImpl.java\r
│   ├── NapCatApiClient.java           # NapCat HTTP API 客户端接口\r
│   ├── NapCatApiClientImpl.java       # OkHttp 调用\r
│   └── dto/\r
│       ├── OneBotEvent.java           # 上报事件 record（post_type/message_type/user_id/...）\r
│       ├── MessageSegment.java        # 消息段 record {type, data}\r
│       ├── SendMsgRequest.java        # /send_*_msg 请求体\r
│       └── ApiResult.java             # {status, retcode, data}\r
├── domain/entity/\r
│   └── OneBotSessionBinding.java      # QQ会话 ↔ harness session 映射（按项目惯例放共享位置）\r
└── mapper/\r
    └── OneBotSessionBindingMapper.java# 同上：@MapperScan 固定扫 mapper 包\r
src/main/resources/db/migration/\r
└── V14__create_onebot_session_binding.sql\r
\`\`\`\r
\r
**依赖规则**：\`channel/qq → ChatService/SessionService 端口 ✓\`；core 任何包\r
\`import channel ✗\`（包边界即文档，暂不引入 ArchUnit）。\r
\r
**实体/Mapper 放共享位置的理由**：绑定表虽是渠道私有状态，但\r
\`@MapperScan("com.dark.javaHarness.mapper")\` 固定扫描 mapper 包（JavaHarnessApplication），\r
且 KbDocumentEntity 已开「功能私有表放共享位置」先例，为一个表改扫描配置得不偿失。\r
\r
### 4.2 配置（application.yaml）\r
\r
\`\`\`yaml\r
napcat:\r
  enabled: true\r
  api-base-url: http://127.0.0.1:3000   # NapCat HTTP API 地址\r
  api-token: \${NAPCAT_API_TOKEN:}       # 与 onebot11 httpServers.token 一致，走环境变量不落 git\r
  self-id: "3896564418"                 # 机器人自身 QQ 号\r
  agent-id:                             # 空 = 默认 Agent；填 agent 表主键（该 agent 的 knowledge 绑定自动生效）\r
  event-secret: \${NAPCAT_EVENT_SECRET:} # 上报验签密钥，联调后再启用\r
  group-trigger:\r
    mode: at            # at=仅@机器人 | prefix=命令前缀 | all=全部响应\r
    prefix: "/ai"\r
  rate-limit:\r
    per-user-seconds: 10  # 同一用户回复最小间隔（群聊防风控）；0 = 不限制（项目口径）\r
  reply:\r
    max-length: 3000      # 超长按段落边界分段\r
\`\`\`\r
\r
配置类 \`NapCatProperties\` 只做绑定载体（数值唯一来源 application.yaml，\`0 = 不限制\` 遵循项目口径）；\r
\`napcat.enabled=false\` 时整链不装配（对齐 KnowledgeProperties 的开关语义）。\r
\r
### 4.3 事件接收流程（OneBotEventController + Service）\r
\r
\`\`\`\r
POST /onebot/event\r
  1. message_id 幂等去重（内存过期 Set，TTL 5min；NapCat 上报失败会重发，\r
     不去重会重复回复）——重复上报直接 200 丢弃\r
  2. 立即返回 200（NapCat 上报有超时约 4~10s，必须快速 ACK）\r
  3. 交给 onebotExecutor 异步处理：\r
     a. 过滤：post_type != "message" 忽略；user_id == self-id 忽略（防自循环，\r
        配合 reportSelfMessage=false 双保险）\r
     b. 群聊触发判断：mode=at 时 message 数组含 {type:"at", data:{qq:<self-id>}}\r
        才处理，且剥掉 at 段拼接剩余 text 段得到纯文本；无 @ 直接忽略\r
     c. 私聊：全量响应\r
     d. 空文本忽略\r
  4. 群聊限频：rate-limit.per-user-seconds > 0 时，内存滑动窗\r
     （ConcurrentHashMap<uid, lastTs>）间隔内直接丢弃，零外部依赖\r
  5. 会话绑定：\r
     私聊 key = "qq:private:<user_id>"\r
     群聊 key = "qq:group:<group_id>:<user_id>"（群内按用户独立上下文）\r
     binding 表查 session_key：命中 → 复用 sessionId；未命中 →\r
     sessionService.createSession("qq:<uid>", text) 建档并落库\r
  6. 构造 ChatRequest(message=text, sessionId=绑定会话, agentId=napcat.agent-id)\r
     调 chatService.chat(request)，拿完整回复（含 sources）\r
  7. 调 NapCatApiClient 发送回复；超长按段落边界按 max-length 分段发送，\r
     可选群聊带 reply 段引用原消息；异常捕获 + 日志，失败不打断主流程\r
\`\`\`\r
\r
### 4.4 NapCatApiClient（OkHttp，依赖已有）\r
\r
| API | 用途 |\r
| --- | --- |\r
| \`GET /get_login_info\` | 启动自检（enabled 时 ApplicationRunner 校验，失败仅告警不阻断启动） |\r
| \`POST /send_private_msg\` \`{user_id, message:[{type:"text",data:{text}}]}\` | 私聊回复 |\r
| \`POST /send_group_msg\` \`{group_id, message:[{type:"reply",...},{type:"text",...}]}\` | 群聊回复（reply 段可选） |\r
| \`POST /delete_msg\` \`{message_id}\` | 可选：撤回 |\r
\r
- 鉴权：\`Authorization: Bearer <api-token>\`\r
- 超时：连接 3s / 读取 10s，失败重试 1 次（消息送达是尽力而为，不打断主流程）\r
\r
### 4.5 上报验签（后续加固）\r
\r
NapCat httpClients 配置 \`token\` 后，上报头带 \`X-Signature: sha1=<HMAC-SHA1(secret, body)>\`，\r
Controller 用 \`@RequestBody byte[]\` 接 raw body 校验（不能先反序列化再验签）；secret 为空跳过校验，\r
校验失败直接 403。联调阶段先留空。\r
\r
### 4.6 数据库迁移（Flyway V14）\r
\r
\`\`\`sql\r
CREATE TABLE onebot_session_binding (\r
  id BIGINT AUTO_INCREMENT PRIMARY KEY,\r
  session_key VARCHAR(128) NOT NULL COMMENT 'qq:private:<uid> 或 qq:group:<gid>:<uid>',\r
  session_id BIGINT NOT NULL COMMENT 'session 表自增主键（DTO 层以字符串交换）',\r
  qq_user_id VARCHAR(32) NOT NULL,\r
  group_id VARCHAR(32) NULL,\r
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,\r
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\r
  UNIQUE KEY uk_session_key (session_key)\r
) COMMENT 'OneBot QQ会话与 harness 会话绑定';\r
\`\`\`\r
\r
绑定查询走 MyBatis-Plus QueryWrapper（项目既有风格），无需 XML。\r
\r
---\r
\r
## 5. 阶段三：联调测试\r
\r
1. **单测**（JUnit5 + Mockito，对齐现有 376 用例风格，不依赖网络）\r
   - \`OneBotEventServiceImplTest\`：事件过滤、message 数组 @ 触发、自消息过滤、\r
     message_id 去重、限频窗口、会话绑定复用/新建、分段发送\r
   - \`NapCatApiClientImplTest\`：请求组装、鉴权头、分段切分、错误处理\r
2. **模拟上报**（不依赖 QQ，直接测 java-harness）：\r
\r
\`\`\`bash\r
curl -X POST http://127.0.0.1:8080/onebot/event -H "Content-Type: application/json" -d '{\r
  "post_type":"message","message_type":"private","user_id":10001,\r
  "raw_message":"你好，介绍一下你自己","message":[{"type":"text","data":{"text":"你好，介绍一下你自己"}}],\r
  "sender":{"user_id":10001,"nickname":"tester"},"time":1700000000,"self_id":3896564418\r
}'\r
\`\`\`\r
\r
3. **真机验证**：\r
   - 私聊机器人发消息 → 收回复；连续发多条无重复回复（幂等生效）\r
   - 群内 @机器人 → 收回复；未 @ → 无响应；同用户 10s 内连发只回一次（限频生效）\r
   - **知识库联动**：给 agent-id 对应的 agent 配 \`knowledge=java\` → QQ 里问 Spring 问题\r
     回复带【出处】；问 Vue 问题检索不到（多库隔离在 QQ 链路同样生效）\r
4. **稳定性**：COMPLEX 路径耗时长的消息，确认 200 先行后 AI 回复仍能送达（异步池承接）\r
\r
## 6. 任务清单\r
\r
| # | 任务 | 涉及 |\r
| --- | --- | --- |\r
| 1 | NapCat 启用 OneBot HTTP Server + HTTP 上报 | 容器配置 + 重启 |\r
| 2 | 验证容器 ↔ 宿主机连通 | curl 冒烟 |\r
| 3 | V14 迁移 + 绑定实体/Mapper + NapCatChannelConfig（enabled + onebotExecutor）+ application.yaml | java-harness |\r
| 4 | channel/qq 包骨架 + OneBot DTO（record）+ OneBotEventController（去重 + 快速 ACK + 异步） | java-harness |\r
| 5 | OneBotEventServiceImpl（过滤/@ 触发/限频/绑定/直调 ChatService） | java-harness |\r
| 6 | NapCatApiClientImpl（OkHttp 发消息客户端 + 启动自检） | java-harness |\r
| 7 | 单元测试（事件流 + API 客户端） | java-harness |\r
| 8 | 模拟上报联调 → 真机验证 → 知识库联动验证 | 全链路 |\r
\r
## 7. 风险与注意事项\r
\r
- **上报超时**：NapCat HTTP 上报有超时（约 4~10s），Controller 必须先 200 再异步处理；\r
  COMPLEX 多 Agent 编排耗时长也没问题（onebotExecutor 有界队列 + 拒绝策略兜底，\r
  拒绝时记日志——对应 QQ 侧表现为该条消息无回复）\r
- **重复回复**：NapCat 上报失败会重发，**message_id 幂等去重必须做在 ACK 之前**\r
- **风控**：自动回复频率过高易触发 QQ 风控（限流/冻结）；群聊按 per-user-seconds 限频\r
  （yaml 配置、0=不限），上线初期建议置 enabled=false 灰度观察\r
- **自循环**：过滤 \`user_id == self_id\` + \`reportSelfMessage=false\` 双保险，缺一不可\r
- **内容安全**：AI 输出直接进 QQ 群，注意规避敏感内容；max-length 分段兼作输出上限\r
- **token 安全**：\`api-token\`/\`event-secret\` 走 \`\${NAPCAT_API_TOKEN:}\`/\`\${NAPCAT_EVENT_SECRET:}\`\r
  环境变量，不落 git（对齐 QWEN_API_KEY 等既有约定）\r
- **部署位置**：若 java-harness 不在本机，上报地址改为其内网 IP 并放行 8080；\r
  NapCat 3000 端口建议仅限对端 IP 访问\r
`,rr="# Spring AI 2.0 迁移方案（去 Spring AI Alibaba）\r\n\r\n> 状态：已完成（代码迁移 2026-10-25；2026-10-27 本机真机启动、Flyway 迁移与真实模型对话冒烟均通过）　|　硬性目标：Spring AI 2.0（取最新稳定 2.0.2）　|　日期：2026-10-25\r\n>\r\n> 本文为迁移设计存档；当前技术基线以 [tech-stack.md](../tech-stack.md) 为准（Spring Boot 4.1.2 / Spring AI 2.0.2 / Java 25）。\r\n\r\n## 1. 目标与边界\r\n\r\n**硬性要求**：Spring AI 2.0（选 2.0.2，当前最新稳定版）。由于 Spring AI 2.0 的基线是 Spring Boot 4.0/4.1 + Spring Framework 7 + Jackson 3，Boot 4 与 Java 升级是**被动必选**。\r\n\r\n**同时移除**：`spring-ai-alibaba`（BOM、graph-core、sandbox）。理由：其 2.0 仅有里程碑版（2.0.0-M1.1），且 1.1.2.2 与 Spring AI 2.0 / Boot 4 不兼容。\r\n\r\n**必须保留的行为**（验收底线）：\r\n- 单 Agent 直答（路径 A）、多 Agent 编排（路径 B：lead 拆解 → 并行子任务 → 聚合）\r\n- SSE 进度行、token 打字机、meta 收尾\r\n- `/resume` 断点续跑（lead 不重跑、已完成子任务不重跑）\r\n- 编排预算熔断、子任务并发限流、子任务墙钟超时、失败占位\r\n- RAG（pgvector + BM25 混合）、工具装饰链、MCP client/server、用户画像、观测落库\r\n- QQ 渠道、CLI、Web 三端协议不变\r\n\r\n**明确取舍**：\r\n- 编排引擎由自研替换（不再依赖 StateGraph）。\r\n- 沙箱保留能力，但改为直接依赖 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`，自维护薄封装。\r\n- 不升级前端（Vue/Vite/TS 与本次无关）。\r\n\r\n**版本选择原则**：只取**最新稳定版**，不取 RC / 里程碑；同一大版本取最新补丁。截至 2026-10-25，Spring Boot 4.2.0-RC1、Spring AI 2.1.0-RC1 均属预发布，不纳入。\r\n\r\n## 2. 目标版本矩阵\r\n\r\n| 组件 | 当前 | 目标 | 说明 |\r\n|---|---|---|---|\r\n| Java | 17 | **25（LTS）** | 本机存放 `D:\\env\\jdk-25`（WSL `/mnt/d/env/jdk-25`）；Boot 4.1.2 支持 17–27，异常时回退 21 |\r\n| Spring Boot | 3.5.14 | **4.1.2** | 2026-10-23 最新稳定补丁 |\r\n| Spring AI | 1.1.4 | **2.0.2** | 2026-10-23 最新稳定版（2.1.0 仍 RC） |\r\n| Spring AI Alibaba | 1.1.2.2 | **移除** | graph-core / sandbox 全部替换 |\r\n| MyBatis-Plus | 3.5.7 | **3.5.17** | 当前最新；使用 `mybatis-plus-spring-boot4-starter` |\r\n| Jackson | 2.x | **3.x** | databind/core 包名 `com.fasterxml.jackson` → `tools.jackson`；注解仍为 `com.fasterxml.jackson.annotation` |\r\n| MCP SDK | 0.17.0（手动锁） | 由 Spring AI 2.0.2 传递（实际 2.1.0） | 已删除根 pom 的 0.17.0 版本锁定 |\r\n| agentscope runtime | 由 SAA 传递 | **1.0.2 直接依赖** | `io.agentscope:agentscope-runtime-sandbox-core` 当前最新 |\r\n| JLine | 3.27.1 | **3.30.17** | 最新 3.x；4.x 为破坏性大版本，本次不跨 |\r\n| OkHttp | 4.12.0 | **5.5.0** | 5.x 为 KMP，Maven 使用 `okhttp-jvm` 制品；CLI/渠道 HTTP 客户端已回归 |\r\n| jsoup | 1.21.1 | **1.22.1** | 最新稳定，API 兼容 |\r\n| Lombok | 由 Boot 3.5 BOM 管理 | 由 Boot 4.1.2 BOM 管理 | 若 Java 25 注解处理报错，显式升到支持 25 的版本 |\r\n| MySQL / pgvector | 8.4 / pg16 | 不变 | — |\r\n| Docker 基础镜像 | temurin-17 | **temurin-25** | 构建与运行阶段同步；回退方案用 temurin-21 |\r\n\r\n## 3. 架构变化\r\n\r\n```\r\n现状：\r\n  MultiAgentGraphAgent ── StateGraph/CompiledGraph ── MysqlSaver(GRAPH_* 表)\r\n                       └─ GraphLifecycleListener ── 进度旁路\r\n  SandboxToolProvider ── SAA ToolkitInit ── agentscope runtime\r\n\r\n目标：\r\n  MultiAgentOrchestrator ── 自研 ParallelOrchestrationEngine ── OrchestrationCheckpointStore(新表)\r\n                          └─ EngineEvent(progress/token/final) ── SSE\r\n  SandboxToolProvider    ── 自研 SandboxToolkit ── agentscope runtime(直接依赖)\r\n```\r\n\r\n**新增包**：`com.dark.javaHarness.graph.engine`\r\n- `OrchestrationEngine`：`String execute(Goal)` / `Flux<String> stream(Goal)` / `Flux<String> resume(Goal)`\r\n- `ParallelOrchestrationEngine`：lead → 并行 subtask（信号量）→ aggregate\r\n- `OrchestrationState`：`Map<String,Object>` 包装（替代 `OverAllState`）\r\n- `EngineEvent`：`Progress` / `Token` / `Final` 三类事件\r\n- `OrchestrationCheckpointStore`：读写检查点（MyBatis-Plus）\r\n- 复用：`OrchestrationNodes`（把 `OverAllState` 改为 `Map`）、`OrchestrationBudget`、`LeadOutputParser`、`ThinkingThrottle`、`OrchestrationPrompts`\r\n\r\n**检查点设计**（替代 MysqlSaver）：\r\n- 新表 `orchestration_checkpoint(goal_id, checkpoint_id, stage, state_json, created_at)`，Flyway V41 建表（压平前的历史编号；该表已并入 `V1__init_schema.sql` 基线，现行迁移为 V1–V11）。\r\n- 落点：`LEAD_DONE`（含 objective/subtaskCount/agents/briefs）、`SUBTASKS_DONE`（含全部 result_*）、`DONE`（含 final）。\r\n- 续跑选择：`DONE` → 直接回放 final；`SUBTASKS_DONE` → 只补 aggregate；`LEAD_DONE` → 补子任务 + aggregate；无检查点 → 报错（与现行为一致）。\r\n- `threadId` 语义保留：`goal.id()`。\r\n\r\n## 4. 分阶段任务\r\n\r\n> 执行顺序调整：本方案保留 P0–P5 的阶段划分，但**实施计划改为「先去 SAA（保持 Boot 3.5 全绿）→ 再升 Boot 4/Spring AI 2」**。这样编排重写与 API 大迁移不同时爆发，回归定位更容易；详见 `docs/superpowers/plans/2026-10-25-spring-ai-2-dependency-migration.md`。\r\n\r\n### P0 基线冻结（独立分支）\r\n1. 建分支 `refactor/spring-ai-2`，不合并 master。\r\n2. 记录基线：`mvn test` 结果、Web 单聊/轨迹、CLI 单聊/`/resume`、一次 COMPLEX 编排截图/日志。\r\n3. 把本方案落入 `docs/design/spring-ai-2-migration.md`。\r\n\r\n### P1 构建基线切换（先让编译器说话）\r\n1. 根 `pom.xml`：\r\n   - parent → `spring-boot-starter-parent:4.1.2`\r\n   - `java.version` → 25\r\n   - `spring-ai.version` → 2.0.2\r\n   - 删除 `spring-ai-alibaba.version` 与 alibaba BOM\r\n   - `mybatis-plus.version` → 3.5.17\r\n   - 删除 MCP SDK `0.17.0` 的显式锁定（mcp/mcp-core/mcp-json 三处）\r\n   - JLine → 3.30.17、OkHttp → 5.5.0、jsoup → 1.22.1\r\n2. `server/pom.xml`：删除 `spring-ai-alibaba-graph-core`、`spring-ai-alibaba-sandbox`；`mybatis-plus-spring-boot3-starter` → `mybatis-plus-spring-boot4-starter`；新增 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`。\r\n3. `channel-qq/pom.xml`、`harness-api/pom.xml`：MyBatis-Plus 构件切换到 3.5.17 对应坐标（api 仍用 `extension`，optional）。\r\n4. Docker：`docker/Dockerfile`、`docker-compose.yml`、`README` 的 JDK 17 → **25**；本机 JDK 25 安装到 `D:\\env\\jdk-25`（WSL `/mnt/d/env/jdk-25`），任务 10 起设置 `JAVA_HOME`；阶段一仍用 JDK 17。若 Java 25 注解处理或 JLine 终端异常，整体回退 21。\r\n5. 运行 `mvn -DskipTests compile`，把编译错误按模块归档为迁移清单（此阶段不要求绿）。\r\n\r\n### P2 编排引擎替换（去 graph-core）\r\n1. 新建 `engine` 包与 `OrchestrationEngine` 接口；`MultiAgentGraphAgent` 改名为 `MultiAgentOrchestrator`（保留 `Agent` 接口实现）。\r\n2. `OrchestrationNodes`：方法签名 `OverAllState` → `Map<String,Object>`；逻辑不动。\r\n3. `ParallelOrchestrationEngine`：\r\n   - lead 阶段：复用现有 lead 节点逻辑。\r\n   - 子任务：`Semaphore(subtask-concurrency)` + `CompletableFuture`；取消标志、墙钟超时、失败/预算占位沿用现有常量与文案。\r\n   - aggregate：复用现有聚合逻辑；流式 token 直接写 `EngineEvent.Token`。\r\n4. 流式管线：`MultiAgentStreamPipeline` 改为消费 `Flux<EngineEvent>`；`ProgressLine` 协议不变；`BranchProgressListener` 删除，进度由引擎在阶段边界发射。\r\n5. 检查点：新增实体/Mapper/Store + V41 迁移；`ChatAgentConfig` 的 `BaseCheckpointSaver` Bean 替换为 `OrchestrationCheckpointStore`。\r\n6. 续跑：实现「DONE 回放 / SUBTASKS_DONE 补聚合 / LEAD_DONE 补子任务」三级选择。\r\n7. 测试：重写 `MultiAgentGraphAgentTest` 为 `MultiAgentOrchestratorTest`；新增引擎单测（并行、预算、超时、失败占位、取消、续跑）。\r\n\r\n### P3 沙箱替换（去 SAA ToolkitInit）\r\n1. 新建 `SandboxToolkit`，直接基于 `io.agentscope.runtime.sandbox.box.*`，实现当前实际使用的工具（base 10 个 + browser 6 个；其余按需）。\r\n   - 参考 SAA `ToolkitInit`（Apache-2.0，保留出处注释）。\r\n2. `SandboxToolProvider` 仅替换 `ToolkitInit.*` 调用；懒初始化、失败降级、`@PreDestroy` 逻辑不动。\r\n3. 新增配置 `app.sandbox.enabled`（默认 true）；Docker 不可用时的降级路径保持现状。\r\n4. 单测：Mockito 模拟 sandbox 返回；真实 Docker 冒烟可选。\r\n\r\n### P4 Spring AI 2.0 API 迁移（编译驱动，按清单推进）\r\n1. **Jackson 3**（27 个文件）：`com.fasterxml.jackson.*` → `tools.jackson.*`；`JsonProcessingException` → Jackson 3 异常类型；`TypeReference` 包名替换。MyBatis-Plus 等仍用 Jackson 2 的库允许共存。\r\n2. **OpenAI 适配**：\r\n   - `ChatClientFactory`、`KnowledgeConfig` 的 `OpenAiApi.builder()` 按 2.0 OpenAI SDK 调整。\r\n   - `OpenAiChatOptions` 改为不可变 + builder；`ThinkingSwitchChatModel` 的 `copy()/setExtraBody()` 改为 builder 注入。\r\n   - `retryTemplate` 关闭内置重试的接线按 2.0 的 `ChatClient`/`ChatModel` API 调整。\r\n3. **Advisor / tool loop**：自定义 `ContextAssemblingAdvisor`、`PromptBudgetAdvisor`、`LlmRequestLogAdvisor` 适配 2.0 `Advisor` 链；工具循环由 `ToolCallingAdvisor` 接管，现有工具预算/观测装饰链保持顺序语义。\r\n4. **MCP 2.0**：`McpToolProvider` 的 `io.modelcontextprotocol.*` 导入、transport、JSON mapper 适配；删除 0.17.0 pin 后验证 server/client 两端。\r\n5. **Response/记忆**：`ChatResponse`/`Generation`/metadata 构造点逐个适配；`SessionService implements ChatMemory` 保持不变（自定义实现）。\r\n6. **RAG/向量**：`Document`、`SearchRequest`、`VectorStore`、`EmbeddingModel` 的 2.0 签名核对。\r\n7. **测试 triage**：按模块修复 Mockito 桩与断言；保持测试意图不变。\r\n\r\n### P5 收尾\r\n1. 全量 `mvn test` 绿；`mvn -DskipTests package` 产 fat jar。\r\n2. Web 真机：单聊 + 轨迹 + COMPLEX 编排 + `/resume`。\r\n3. CLI：单聊 + `/resume`。\r\n4. 沙箱：有 Docker 时 python/shell/文件工具可用；无 Docker 时降级不阻断。\r\n5. `mvn dependency:tree | grep alibaba` 必须为空（除无关传递项，需逐项确认）。\r\n6. 更新 README / tech-stack / docs 的版本矩阵与启动说明。\r\n\r\n## 5. 验收标准\r\n\r\n- `mvn test`：0 Failures / 0 Errors。\r\n- 真实聊天：路由 + 直答 + 轨迹统计正常。\r\n- COMPLEX：lead 拆解 ≥2 子任务并行、聚合、进度行、预算/超时/失败占位均有单测覆盖。\r\n- `/resume`：中断后从 `SUBTASKS_DONE` 续跑，lead 与已完成子任务不重跑。\r\n- 沙箱：Docker 可用时工具可执行；不可用时启动无失败、工具组为空。\r\n- 依赖树中无 `com.alibaba.cloud.ai`。\r\n- Web / CLI 协议不变（`SseProtocol` 字面量不变）。\r\n\r\n## 6. 风险与应对\r\n\r\n| 风险 | 影响 | 应对 |\r\n|---|---|---|\r\n| Spring AI 2.0 API 变动超出预期 | 编译/行为回归 | 以 2.0.2 Javadoc + upgrade notes 为准，逐模块提交；每步跑聚焦测试 |\r\n| Jackson 3 与 MyBatis-Plus/SAA 生态冲突 | 序列化异常 | 允许 Jackson 2/3 共存；仅迁移自有代码；必要时保留 `jackson2` 兼容模块 |\r\n| 自研编排语义不等价 | `/resume`、流式、预算行为偏差 | 先写行为对照测试（lead/parallel/aggregate/resume/取消/超时），再替换实现 |\r\n| agentscope runtime API 漂移 | 沙箱工具失效 | 固定 1.0.2；封装隔离；Docker 不可用时降级 |\r\n| MCP 2.0 破坏性变更 | 外部工具接入失败 | 单独 P4.4 小步验证 server/client 两端 |\r\n| Boot 4 生态（Flyway/MyBatis） | 启动失败 | P1 先 compile，P4 前跑上下文测试；必要时锁定兼容版本 |\r\n\r\n## 7. 回滚策略\r\n\r\n- 全程在 `refactor/spring-ai-2` 分支，`master` 不动。\r\n- 回滚以分支为粒度：引擎与沙箱均为直接替换，不保留运行时 feature flag；如需回退，切回 master 或逐文件回滚。\r\n- 若 P4 受阻，可保留 P1+P2+P3 成果（去 SAA + Boot 4 + Spring AI 2 基线），在分支上继续；不合并即不影响生产。\r\n\r\n## 8. 工作量判断\r\n\r\n- **P1**：小（版本与依赖切换 + JDK 25 工具链准备）。\r\n- **P2**：大（约 700 行编排/流式/检查点重写 + 测试重写）。\r\n- **P3**：中（约 18 个工具薄封装 + 单测）。\r\n- **P4**：大（Jackson 3 + Spring AI 2 API 面 75 个文件，编译驱动）。\r\n- **P5**：中（回归与真机验证）。\r\n\r\n整体属于「一个独立里程碑」，不建议与业务功能混做。\r\n\r\n## 实施进展（2026-10-25）\r\n\r\n**阶段一：去 Spring AI Alibaba（保持 Boot 3.5 / Spring AI 1.1）**\r\n- 移除 `spring-ai-alibaba-graph-core`：自研 `ParallelOrchestrationEngine` 接管执行/流式/续跑；\r\n  检查点改落 `orchestration_checkpoint`（V41 迁移，阶段 `LEAD_DONE`/`SUBTASKS_DONE`/`DONE`）。\r\n- 移除 `spring-ai-alibaba-sandbox`：`SandboxToolkit` 本地薄封装直接调用 `io.agentscope:agentscope-runtime-sandbox-core:1.0.2`\r\n  （18 个工具，名称/描述/入参 schema/结果结构不变）。\r\n- 删除 `spring-ai-alibaba-bom` 与版本属性；依赖树无 `com.alibaba.cloud.ai`。\r\n\r\n**阶段二：Boot 4 / Spring AI 2 / Java 25 / Jackson 3**\r\n- 根 pom：Boot 4.1.2、Spring AI 2.0.2、Java 25、MyBatis-Plus 3.5.17（boot4 starter + jsqlparser）、\r\n  JLine 3.30.17、OkHttp 5.5.0（`okhttp-jvm`）、jsoup 1.22.1。\r\n- OpenAI 接入迁移到官方 SDK（`com.openai.client.OpenAIClient`，经 `OpenAiClients` 统一构建\r\n  OkHttp 传输/超时/关闭隐式重试）；Embedding 同源改造。\r\n- `ChatClientRequestSpec.options(...)` 改为接收 Builder；`OpenAiChatOptions` 配置改用 `mutate()`/builder。\r\n- 错误分类从 `NonTransientAiException` 改为 `OpenAIServiceException.statusCode()`。\r\n- MCP SDK 2.1.0：`McpJsonMapper` 改由 `JacksonMcpJsonMapper` 提供；`JsonNode.fields()` → `properties()`。\r\n- Jackson 3 全量迁移（server / channel-qq / cli / wms-mcp-adapter）。\r\n- Boot 4 包迁移：`DataSourceProperties` → `org.springframework.boot.jdbc.autoconfigure`。\r\n- JDK 25 安装于 `D:\\env\\jdk-25`（WSL `/mnt/d/env/jdk-25`），`run-wsl.sh` 的 `JAVA_HOME` 已指向该目录。\r\n\r\n**验证（2026-10-25，MySQL 已恢复）**\r\n- `mvn test`：server 678（含 `JavaHarnessApplicationTests`）/ channel-qq 72 / cli 28 / wms-mcp-adapter 43，全绿。\r\n- 真机：\r\n  - 单聊 `/api/chat`：真实模型调用成功，会话/观测落库正常；\r\n  - COMPLEX 编排：路由 → 拆解 → 并行子任务 → 聚合，进度行/思考行/工具行正常；\r\n  - 中断后 `/api/chat/resume` 从 `LEAD_DONE` 续跑：lead 不重跑、子任务补齐、聚合完成，meta SUCCEEDED；\r\n  - CLI：非交互管道下单聊正常（JLine 3.30.17 + JDK 25）；\r\n  - Web：Vite 页面可访问，Web 调用链 API（agents / sessions / messages / llm-calls / kb-retrievals）正常；\r\n  - 无 Docker：沙箱工具面为空，应用启动与其余功能不受影响（按约定不做 Docker 验证）。\r\n- 迁移中修复的运行时差异：\r\n  1. OpenAI 官方 SDK 的 baseUrl 需包含版本前缀，已在 `OpenAiClients` 补齐 `/v1`（DB 中 api_url 沿用 1.x 根地址语义）；\r\n  2. Boot 4 的 Flyway 自动配置拆到独立模块，补 `spring-boot-flyway` starter 后迁移才执行（V41 已落库）；\r\n  3. `/resume` 入口原先调用的是全新执行方法，已改为 `Agent.resumeStreamReactive`（默认退化 + 编排器覆写）。\r\n",nr=`# Super-agent 思想在本项目的落地对照\r
\r
> 来源文章：《Super-agent 设计探索：迈向更自主的 Agent 系统》——一篇面向工程实践的架构思路探讨，不是对某个具体产品的功能说明。\r
>\r
> 本文只记录**已经在本项目落地**的部分，并标注应用程度（完整 / 部分），用来回答「这篇文章里哪些思想我们已经用上了」。未采纳的架构（双层 Router / Worker、常驻事件路由、全双工 ReAct Loop、端侧 daemon）不在此文范围内。\r
>\r
> 判定口径：以当前代码与配置为准。**完整** = 文章该机制的主要结构已存在；**部分** = 只实现了其中一个侧面，或缺少关键一环。\r
\r
---\r
\r
## 一、对照总表\r
\r
| 文章概念 | 文章核心机制 | 本项目落点 | 应用程度 |\r
|---|---|---|---|\r
| Fast / Slow Path（路由） | 确定性规则先筛，模型只处理灰区 | \`LlmRouteJudge\`：规则短路 → 灰区 LLM → 置信度收敛 → 兜底 | 完整 |\r
| Fast / Slow Path（事件） | 程序层准入滤噪，语义层再判断 | QQ 渠道：\`ChannelEventPolicy\` 规则表 + \`SemanticTriggerJudge\` 语义扩展点 | 完整（语义层默认关闭） |\r
| 投机执行 | 结果未定前后台先做，有价值就交付 | \`RagPrefetcher\`：入口预取 + judge 并行 + 汇合窗口 | 完整 |\r
| Inline & Spill | 窗口内只留高频信息，其余留引用按需读取 | 会话/提示词/工具结果三级预算 + 工具 Schema 两段式 | 部分（有 Spill） |\r
| 链式召回（最小版） | 窗口外历史留引用，按需取回 | 消息稳定 ID + 归档表 + system 引用行 + \`recall_history\` 工具 | 部分（无引用图/多跳/向量化） |\r
| 计算、状态与环境分离 | 计算无状态、状态落库、执行环境独立 | Goal / 会话 / 检查点全部落库 + 断点续跑 | 部分（状态已外置，执行环境未解耦） |\r
| Watchdog | 主系统之外独立的外部控制回路 | 启动清理 + 流超时 + 调用重试 + \`StaleGoalWatchdog\` 停滞扫描 | 部分（无独立 Agent 介入） |\r
| 面向人的多信道交互 | 输出方式匹配信道带宽与习惯 | 渠道独立模块 + \`ReplyPostProcessor\` SPI + 按渠道输出参数 + 信道 prompt 段 | 完整 |\r
| 事件驱动 | 环境事件作为 Agent 的主要输入 | NapCat 事件入口 + 会话绑定 + 程序层规则 + 语义层候选 | 部分（无 Thread 订阅/主动默认关闭） |\r
\r
---\r
\r
## 二、逐项落地\r
\r
### 2.1 Fast / Slow Path：确定性规则先行，模型只处理灰区\r
\r
**文章观点**：环境事件「高噪音、低触发率」，全部交给大模型判断成本过高；把稳定高频的判断固化为确定性代码（Fast Path），只把需要语义理解的候选交给模型（Slow Path）。\r
\r
**本项目已应用（完整）**——\`LlmRouteJudge.plan()\` 是同一结构的两段式判定：\r
\r
- 空消息 → 直接 Agentic（规则）；\r
- 命中多工作流措辞（\`GRAPH_SIGNAL\`）→ 直接 Graph，置信度 0.9（规则短路）；\r
- 消息长度 ≤ 12 字符 → 直接 Agentic，置信度 0.95（规则短路）；\r
- 其余灰区才发起一次轻量 LLM 判定，输出 \`route / confidence / workstreams / deliverables / reason\`；\r
- 置信度策略收敛（Graph 需 ≥ 2 个工作流且置信度 ≥ 0.75），任何异常一律兜底 Agentic。\r
\r
代码：[LlmRouteJudge.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/route/LlmRouteJudge.java#L139-L165)\r
\r
这与文章 3.1 的「程序策略 + 语义策略」同构：规则层零成本、可预测；模型层只处理规则无法覆盖的灰区；失败时宁可走轻量路径，不阻塞请求。\r
\r
### 2.2 投机执行：先算后验，无价值即丢弃\r
\r
**文章观点**：在结果是否被采用尚不确定时，根据环境信号在后台提前尝试，用额外后台算力换人类的等待时间；有用就交付，无用就丢弃。\r
\r
**本项目已应用（完整）**——RAG 入口预取就是标准的投机执行：\r
\r
- \`RouteDecider\` 在路径判定**之前**提交 RAG 预取，判定与检索并行执行，互不依赖；\r
- 判定完成后进入汇合窗口（2 秒）等待预取；窗口内未完成则取消任务、放弃结果；\r
- 预取池饱和时静默丢弃；任何失败都退化为「组装期现查」，主流程零感知。\r
\r
代码：[RagPrefetcher.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/RagPrefetcher.java#L26-L97)、[RouteDecider.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/RouteDecider.java#L66-L98)\r
\r
编排路径同样应用了该思想：lead 拆解出子任务后、扇出之前，对每个子任务并行提交 RAG 预取，把检索延迟消化在子任务并发限流的排队空档里（见 [data-flow.md](../data-flow.md) 5i 节）。\r
\r
### 2.3 Inline & Spill：把有限的上下文窗口当作 Cache 管理\r
\r
**文章观点**：注入过多会挤占窗口、稀释注意力，注入过少会迫使模型反复调工具；应把近期高频信息 Inline 进窗口，低频/超预算内容 Spill 到窗口外，仅保留可读取的入口。\r
\r
**本项目已应用（部分）**——「按预算注入 + 超限外置」已形成三层结构：\r
\r
| 层次 | 落点 | 行为 |\r
|---|---|---|\r
| 会话历史 | [ContextAssemblingAdvisor.java](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L82-L89) | 过滤噪声、归一化 role 顺序、按 \`app.context.history-budget\` 从最旧裁剪，system 始终保留 |\r
| Prompt 分段 | [PromptBudgetAdvisor.java](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/PromptBudgetAdvisor.java) | 历史 / 当前 user / 工具结果三段分别设预算 |\r
| 工具结果 | [ToolCallBudget.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/budget/ToolCallBudget.java) | 单次调用的工具结果总量上限，超限不再真执行并返回引导文本 |\r
| 知识注入 | [KnowledgeRetriever.java](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java) | top-k 命中按 token 预算截断后再渲染【出处N】 |\r
| 工具 Schema | [ToolLazyManager.java](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/ToolLazyManager.java#L18-L39) | 首轮只注入「名称 + 用途」，完整参数 Schema 留在窗口外，模型调 \`expand_tool\` 按需展开 |\r
\r
其中 \`ToolLazyManager\` 最接近文章 3.3 的 Inline & Spill：窗口内放索引（Inline），完整 Schema 放窗口外（Spill），用一次元工具调用换取参数细节。网页抓取缓存（[WebTools.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/WebTools.java) 的 30 分钟内容缓存）也是同一思路：被裁掉的长内容可重新读取，而不是要求模型重新抓取。\r
\r
上下文构成还可观测：[ContextCompositionCalculator.java](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionCalculator.java) 把 system 各段、RAG、工具 Schema、历史消息的 token 占比算成快照，便于确认预算花在哪。\r
\r
**边界**：已补齐「保留引用 + 按需展开」的最小版（见 2.8），但没有 Trigger / Thread / Session 的引用图与多跳召回，归档内容也未向量化。\r
\r
### 2.4 计算、状态分离与断点续跑\r
\r
**文章观点**：把推进 Agent Loop 的计算、需要持久保存的会话状态、重状态的执行环境三者解耦，使 Agent 摆脱单机生命周期限制，并能在异常后恢复。\r
\r
**本项目已应用（部分）**——前两层已经分开：\r
\r
- 持久状态全部在 MySQL：\`session_messages\`、\`goal\`、\`orchestration_checkpoint\`、四张观测表；\r
- 进程内没有权威状态：重启后从库中恢复会话、目标与检查点；\r
- Goal 有完整生命周期 \`PENDING → RUNNING → SUCCEEDED / FAILED\`，异常退出由启动清理兜底（[GoalServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/GoalServiceImpl.java#L55-L73)）；\r
- 复杂编排按 \`LEAD_DONE / SUBTASKS_DONE / DONE\` 三阶段落检查点，\`/api/chat/resume\` 从最近检查点续跑，已完成阶段不重跑（[OrchestrationCheckpointStore.java](../../deerfect-server/src/main/java/com/dark/javaHarness/state/persistence/OrchestrationCheckpointStore.java)、[resume.md](../guides/resume.md)）；\r
- 计算通过受管线程池承载：后台 Goal 走 \`goalExecutor\`（8 线程 + 50 队列，满则显式落 FAILED），MVC 异步派发走独立槽位（[GoalExecutorConfig.java](../../deerfect-server/src/main/java/com/dark/javaHarness/config/GoalExecutorConfig.java#L27-L43)、[AgentServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java#L56-L69)）。\r
\r
**边界**：只完成了「计算与状态」的分离，没有完成「计算与执行环境」的分离——工具执行仍在同机 Docker 沙箱内，调度仍是进程内线程池，\`failAllRunning\` 与本地队列都基于单实例假设（见 [roadmap.md](../roadmap.md) 的「多实例水平扩展」）。\r
\r
### 2.5 外部控制回路（Watchdog 的轻量形态）\r
\r
**文章观点**：长期在线的系统很难只靠主系统内部逻辑保证可靠，需要在主系统之外引入独立的外部控制回路；而且 Agent 的异常不只有进程崩溃，还包括执行陷入无效循环或偏离目标。\r
\r
**本项目已应用（部分）**——已有若干「主链路之外的兜底回路」：\r
\r
- 启动恢复：非正常退出残留的 \`RUNNING\` Goal 在启动时批量标记 FAILED；\r
- 运行中超时：[AgentChatPipeline.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatPipeline.java#L195) 对流做首帧/空闲双重超时，厂商端静默挂死时快速失败；\r
- 调用重试：[LlmRetry.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/LlmRetry.java) 对可重试错误做有限次数退避；\r
- 断连收尾：客户端取消时 Goal 落 FAILED，编排节点共享取消标志，不再发起新的 LLM 调用；\r
- 定时任务：观测表保留期清理（[ObservabilityLogCleaner.java](../../deerfect-server/src/main/java/com/dark/javaHarness/observability/ObservabilityLogCleaner.java#L52)）、用户画像静默提炼（[UserProfileService.java](../../deerfect-server/src/main/java/com/dark/javaHarness/memory/UserProfileService.java#L109)）。\r
\r
**边界**：已新增 \`StaleGoalWatchdog\`（\`app.watchdog.enabled\`，默认关闭）扫描「RUNNING 且长时间无进展」的 Goal 并条件标记 FAILED，补上了任务级进展心跳；但仍没有文章所说的独立 Watchdog Agent Session，不会判断任务方向是否偏离目标。\r
\r
### 2.6 面向人的多信道交互\r
\r
**文章观点**：不同工作信道的信息密度与交互习惯不同，系统输出应匹配信道带宽；不应把面向单人终端的冗长推理与工具密集输出原样搬进群聊。\r
\r
**本项目已应用（部分）**——渠道与 core 已经分层：\r
\r
- \`channel-qq\` 是独立 Maven 模块，core 编译期不依赖渠道（\`maven-enforcer-plugin\` 守护），渠道只依赖 \`harness-api\`；\r
- 渠道侧输出控制走通用 SPI [ReplyPostProcessor.java](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/spi/ReplyPostProcessor.java)：core 在写回记忆与构造响应前调用，渠道可清洗文本、声明 flag（如语音工件）；\r
- SSE 协议与进度行（[SseProtocol.java](../../deerfect-api/src/main/java/com/dark/javaHarness/view/SseProtocol.java)、[ProgressLine.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/ProgressLine.java)）让 CLI / Web 拿到 token、阶段进度、meta，而 QQ 只收到最终文本；\r
- 渠道侧有独立的输出参数：长度上限、是否 @ 提问者、引用回复、表情发送、语音 flag、限频与聊天超时（[NapCatProperties.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatProperties.java)）。\r
\r
**边界**：已通过 \`ChatRequest.channel\` → \`Goal\` → \`CallTrace\` 把来源渠道贯通到 \`PromptAssembler\`，按 qq / cli / web / api 生成「信道规范」段（IM 限 100 词、异步端允许结构化交付）；发送层的长度、表情、语音仍由各渠道自行控制。\r
\r
### 2.7 事件驱动入口：主动性的起点\r
\r
**文章观点**：驱动 Agent 的主要输入应从「人类逐条键入的提示词」转向「环境中的半结构化事件」，并通过过滤、路由、上下文组织把事件送到正确的执行单元。\r
\r
**本项目已应用（部分）**——QQ 渠道已经是一个真实的环境事件入口：\r
\r
- NapCat 上报的 OneBot 事件进入 \`OneBotEventController → OneBotEventServiceImpl.handle()\`；\r
- 程序层准入：自消息过滤、私聊白名单、按用户限频、指令路由（\`/new /reset /history /agent /recall\`）、群聊 @/回复触发判定；\r
- 会话绑定：\`qq:private:<uid>\` / \`qq:group:<gid>:<uid>\` 绑定到 harness 会话，命中复用多轮记忆，未命中建档落库；\r
- 失败语义：超时/异常/FAILED 一律静默不回复；聊天队列满时背压丢弃本条；超时中断底层调用，防止僵尸任务占满聊天池。\r
\r
代码：[OneBotEventServiceImpl.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java#L146-L221)、[OneBotEventController.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventController.java)\r
\r
**边界**：程序层已抽成 \`ChannelEventPolicy\` 规则表（respond / candidate / ignore），候选事件可由 \`SemanticTriggerJudge\` 语义层判断（默认关闭）；仍没有文章 3.2 的 Thread 订阅与直通——同一个群/话题下的连续事件不会构成一条 Thread，也不会被直接路由给已订阅的 Worker。\r
\r
### 2.8 链式召回（最小版）\r
\r
**文章观点**：窗口内只保留摘要/ID 等引用，低频或超预算内容留在窗口外，模型按需沿引用关系逐跳展开。\r
\r
**本项目已应用（部分）**——在不引入引用图的前提下先做「可定位、可取回」：\r
\r
- 快照 item 写入稳定消息 ID（\`SessionServiceImpl\`），读取时放进 Spring AI \`Message\` metadata；\r
- 快照裁剪移除的整条消息与单条超限截断的尾部先写入 \`session_message_archive\`（\`V3\`）；\r
- 上下文裁剪时把被丢弃消息的条数与 ID 示例追加到 system（\`ContextAssemblingAdvisor\`），提示模型可用 \`recall_history\`；\r
- \`recall_history\` 工具（\`RecallHistoryTools\`）按关键词或精确消息 ID 检索归档，\`sessionId\` 经 ToolContext 注入；默认 \`scope=current\` 服务端强制本会话过滤，\`scope=all\` 跨全部历史会话按归档时间倒序召回并标注来源会话（2026-11-06，V11 \`idx_archive_time\`）；授予 \`general\` 与编排 \`lead\`（\`V5\`）。\r
\r
代码：[SessionServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L190-L343)、[SessionHistoryArchiveService.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionHistoryArchiveService.java)、[RecallHistoryTools.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/history/RecallHistoryTools.java)\r
\r
**边界**：没有 Trigger / Thread / Worker 的引用图与多跳展开，归档检索靠关键词/精确 ID（跨会话 \`scope=all\` 走时间倒序 + 关键词，仍是 LIKE 全表扫描），未做摘要与向量化。\r
\r
---\r
\r
## 三、未覆盖的部分（一句话清单）\r
\r
以下属于文章的核心机制，但本项目目前**没有**落地，列出是为了避免把「部分应用」误读成「完整对齐」：\r
\r
- 引用图与多跳召回：只有 ID + 归档 + 单跳检索，没有沿 Trigger/Thread/Worker 逐跳展开；\r
- 主动观察：语义层与候选规则已就位但默认关闭，且没有 Thread 订阅与直通；\r
- 独立 Watchdog Agent：\`StaleGoalWatchdog\` 只做停滞标记，不判断方向偏离、不自动介入修复；\r
- 全双工 ReAct：路径 A 的工具调用仍是同步循环，QQ 的超时 + 线程池是阻塞等待而非 \`start()/stop()\`；\r
- 执行环境解耦（BYOM / 端侧 daemon）：工具执行仍在同机容器内；\r
- Router / Worker 双层架构：编排是「按请求」的 Lead → 并行 → 聚合，没有常驻 Router 层，也没有跨会话的 Thread 订阅。\r
\r
---\r
\r
## 四、相关文档\r
\r
- [architecture.md](../architecture.md)：两路径架构总览\r
- [data-flow.md](../data-flow.md)：路由、上下文组装、工具调用、观测的数据流\r
- [guides/context-optimization.md](../guides/context-optimization.md)：Token 预算与上下文优化口径\r
- [guides/resume.md](../guides/resume.md)：检查点与断点续跑\r
- [guides/qq-channel.md](../guides/qq-channel.md)：QQ 渠道接入与触发规则\r
- [roadmap.md](../roadmap.md)：未完成能力与优先级\r
`,tr="---\r\ntitle: \"本地开发环境\"\r\norder: 8\r\n---\r\n\r\n# 本地开发环境\r\n\r\n> 只记录三类知识：**环境事实**（这台机器与这个项目是什么）、**本机操作**（在本机怎么做）、  \r\n> **FAQ**（实测踩过的坑）。全文按这三类分节，不设第四类标题。\r\n>\r\n> 不重复 [README](../README.md) 的快速开始；首次部署见 [guides/setup.md](./guides/setup.md)，  \r\n> 运维口径见 [ops.md](./ops.md)。设计决策见 [design/](./design/)  \r\n> 渠道 how-to 见 [channel-feishu/README.md](../channel-feishu/README.md)、  \r\n> [channel-qq/README.md](../channel-qq/README.md)。\r\n>\r\n> 改端口、改启动方式、换依赖服务时，同一次提交内更新本文件；版本号与路径每季度核对一次。\r\n>\r\n> 最后按代码核对：2026-11-11\r\n\r\n## 一、环境事实\r\n\r\n### 工具链（Windows 侧实测）\r\n\r\n| 工具         | 版本                                   | 说明                             |\r\n| ---------- | ------------------------------------ | ------------------------------ |\r\n| JDK        | 25（Microsoft build，`D:\\env\\jdk-25`）  | `JAVA_HOME` 必须指向它              |\r\n| Maven      | 3.9.4（`D:\\apache-maven-3.9.4`）       | 仓库没有 `mvnw`，直接用 `mvn`          |\r\n| Node / npm | v22.20.0 / 10.9.3                    | 只给 `deerfect-web/` 用；经 nvm 安装，见「前端运行要求」 |\r\n| MySQL      | 8.4.5（`D:\\mysql\\mysql-8.4.5-winx64`） | 见「端口与依赖服务」                     |\r\n\r\n`.mvn/settings.xml` 是本机文件，**不在仓库里**（`.gitignore` 排除了 `.mvn/`）：它把本地仓库  \r\n指向 `.mvn-repo/`（仓库内）并配置阿里云镜像。干净 clone、CI 与镜像构建都没有它——直接 `mvn`  \r\n会走默认 `~/.m2` 和公网 central，能构建但慢，不是错误。下文命令按本机有该文件来写。\r\n\r\n### 覆盖率门禁（jacoco）\r\n\r\n- 阈值是**行覆盖率（LINE）**，按模块独立设置：父 pom 只出报告、不设门槛，各模块在自己的\r\n  `<properties>` 里用 `jacoco.line.minimum` 覆盖，取「实测值下方约 2 个点」。\r\n  **具体数值以各模块 `pom.xml` 为准**——改阈值只改 pom，这里不维护数字。\r\n- `report` / `check` 绑在 `verify` 上：只跑 `test` 能过、`verify` 红，就是覆盖率跌破模块阈值——  \r\n  报告在 `target/site/jacoco/index.html`，日志会打印实际值与阈值。\r\n- 调整方式：先跑 `verify` 看实际值，确认下降合理后再改模块属性；**不要放宽规则本身**。\r\n\r\n### 端口与依赖服务\r\n\r\n| 组件            | 地址                            | 是否必需   | 说明                                                                                                  |\r\n| ------------- | ----------------------------- | ------ | --------------------------------------------------------------------------------------------------- |\r\n| server        | `8080`                        | 必需     | REST + SSE；MCP 端点在同端口 `/mcp`                                                                        |\r\n| Web 开发服务器     | `80`                          | 只在调前端时 | vite，proxy 到 8080；Windows 监听 80 需管理员权限（或改 `vite.config.ts` 的 `server.port`）                     |\r\n| MySQL         | `127.0.0.1:3306`，库 `harness`  | 必需     | 配置见 `application-datasource.yaml`                                                                   |\r\n| PgVector      | `127.0.0.1:5432`，库 `postgres` | 可选     | 仅知识库（RAG）；未启用时 `/api/knowledge*` 返回 503                                                             |\r\n| Docker daemon | `127.0.0.1:2375`              | 可选     | 沙箱工具；**必须写 `127.0.0.1`，不要写 `localhost`**（IPv6 解析会超时）                                                |\r\n| NapCat        | `127.0.0.1:3000`              | 可选     | QQ 渠道；远程联调见「QQ 远程 NapCat 联调」；`napcat.enabled=false` 时整链不装配                                          |\r\n| Edge TTS      | Python 解释器（`TTS_PYTHON`）      | 可选     | 语音合成（QQ / 飞书）；需 `pip install edge-tts`。飞书还要 ffmpeg 转 opus（`CHANNEL_FEISHU_WS_VOICE_FFMPEG`），缺了只退回文字 |\r\n\r\n### 数据库迁移（Flyway）\r\n\r\nFlyway 随 deerfect-server 启动自动执行 `deerfect-server/src/main/resources/db/migration/` 下的脚本；\r\n新增迁移取**目录里当前最大版本号 +1**，已应用的脚本不可再改。\r\n\r\n这里不记录「当前版本是多少」——数字每次加迁移都会变，权威来源就是那个目录。\r\n各版本做了什么见 [project-structure.md](./project-structure.md) 的「数据库迁移」一节。\r\n\r\n### 前端运行要求（`deerfect-web/`）\r\n\r\nNode 版本以 `deerfect-web/.nvmrc` 为准（22，本机 nvm 装的是 22.20.0）：vite 7 / jsdom 29 最低要求 20.19，  \r\nvitest 5 要求 ≥ 22.12——旧 Node 20.18 跑 `npm test` 会报 `ERR_REQUIRE_ESM`，属于版本不满足而非  \r\n代码缺陷。\r\n\r\nPWA 只在构建产物里生效：`npm run dev` 不注册 service worker（`vite-plugin-pwa` 的 `devOptions`  \r\n默认关闭），本地验证 manifest / SW / 离线门用 `npm run build && npm run preview`。手机端必须  \r\nHTTPS 或 localhost 才能注册 SW，局域网 `http://IP:端口` 无法「添加到主屏」。\r\n\r\n### 密钥与环境变量\r\n\r\n- 本地密钥与远程地址放仓库根 `.env.local`（未纳入版本控制）：`QWEN_API_KEY`、`DEEPSEEK_API_KEY`、  \r\n  `NAPCAT_REMOTE_HOST`、`TTS_PYTHON`、`CHANNEL_FEISHU_WS_VOICE_FFMPEG`。\r\n- 邮件渠道用 `MAIL_HOST`（QQ 邮箱为 `smtp.qq.com`）、`MAIL_USERNAME`（完整发件地址）、  \r\n  `MAIL_PASSWORD`（**授权码，不是登录密码**）、`MAIL_FROM`（可省略，默认等于 `MAIL_USERNAME`），  \r\n  开关 `CHANNEL_EMAIL_ENABLED`；飞书推送渠道（webhook）用 `FEISHU_WEBHOOK_URL` /  \r\n  `FEISHU_WEBHOOK_SECRET` + `CHANNEL_FEISHU_ENABLED`。  \r\n  两者默认关闭，关闭时对应渠道不会出现在注册表里。\r\n- 飞书**对话渠道**（长连接）的 `FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `CHANNEL_FEISHU_WS_ALLOWED_USERS`  \r\n  见 [channel-feishu/README.md](../channel-feishu/README.md) 的「接入准备」。\r\n- 不要把密钥写进 yaml、脚本或提交记录。\r\n\r\n### 流水线（CI 与镜像）\r\n\r\n[.github/workflows/ci.yml](../.github/workflows/ci.yml) 三个 job，push 到 `master` 与 PR 时触发：\r\n\r\n| job       | 内容                                                                                                                                                                                                                                 |\r\n| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |\r\n| `backend` | temurin 25 + MySQL 8.4 service；`mvn -Dmaven.repo.local=$HOME/.m2/repository -B verify`（不依赖 `.mvn/settings.xml`）。上下文测试启动时会跑全量 Flyway 迁移，迁移校验失败即红；`verify` 同时跑覆盖率门禁，日志出现 `Rule violated` 即覆盖率跌破模块阈值，报告作为 `jacoco-reports` 产物上传（失败也传） |\r\n| `web`     | Node 22；`npm ci` → `npm run lint`（ESLint flat config）→ `npm run build`（含 vue-tsc 类型检查）→ `npm test`（vitest）                                                                                                                         |\r\n| `e2e`     | Node 22 + Chromium；`npm run test:e2e`（mock 模式，不需要后端与数据库，失败上传 `playwright-report`）                                                                                                                                                  |\r\n\r\n前端 lint 同样是硬门禁：`npm run lint` 0 error 才放行。规则取向是「只拦会出错或会腐化的」——  \r\n8 条纯排版规则（属性换行、自闭合空格等）已关闭，避免每次改动都产生无关 diff。\r\n\r\n分支保护（required status checks = `backend` / `web` / `e2e`）需仓库管理员在 GitHub  \r\n`Settings → Branches`（或 Gitee 保护分支）勾选，CLI 无法自动配置。\r\n\r\n镜像构建 [docker-image.yml](../.github/workflows/docker-image.yml)：仅当 docker/deerfect-server/pom 等  \r\n路径有变化时触发。`master` 推送 `ghcr.io/cxknotjj/deerfect-harness:sha-<7位>` 与 `latest`，  \r\n`v*` tag 推送同名版本；PR 只构建不推送。镜像仅 linux/amd64，使用 buildx + gha 层缓存。  \r\n镜像名固定 `deerfect-harness`（展示名 DeerFect-harness）：Docker/GHCR 仓库名强制小写，  \r\n所以不能用仓库名 `Deerfect_harness` 原样命名。\r\n\r\nGHCR 包首次推送后默认私有；部署机不登录也能拉取时，在仓库 Packages 设置里改为 Public。  \r\n私有包拉取需 `docker login ghcr.io`（用户名 = GitHub 账号，密码 = 勾选 `read:packages` 的 PAT）。\r\n\r\n## 二、本机操作\r\n\r\n### 加载 `.env.local`\r\n\r\n启动脚本已移除，手工 `java -jar` / `mvn spring-boot:run` **不会**自动加载 `.env.local`：\r\n\r\n```bash\r\nset -a; source .env.local; set +a\r\n```\r\n\r\n`set -a` 让变量自动 `export` 给子进程，否则只存在于当前 shell，JVM 读不到。长期变量用 `setx`  \r\n写入用户环境，或直接在启动命令行前内联。\r\n\r\n### Maven 常用命令\r\n\r\n```bash\r\nmvn -s .mvn/settings.xml -DskipTests install          # 全量构建\r\nmvn -s .mvn/settings.xml -pl deerfect-server -am test          # 单模块测试（必须 -am）\r\nmvn -s .mvn/settings.xml verify                       # 全量测试 + 覆盖率门禁（提交前跑这个）\r\nmvn -s .mvn/settings.xml -pl deerfect-server spring-boot:run   # 只起服务\r\nmvn -s .mvn/settings.xml -pl <module> -am verify -Djacoco.line.minimum=0.99   # 临时验证会被拦\r\n```\r\n\r\n- `-am` 不能省：省略会用本地仓库里旧的 `deerfect-api`，改接口后容易出「明明改了却没生效」。\r\n- 没有 `.mvn/settings.xml` 时直接 `mvn`，其余参数不变。\r\n\r\n### 启动服务\r\n\r\n启动脚本在 `scripts/` 下（`scripts/run-win.bat` / `scripts/run-wsl.sh`），也可以两步手动启动：\r\n\r\n```bash\r\nmvn -s .mvn/settings.xml -DskipTests install       # 首次，或改了模块依赖\r\njava -jar deerfect-server/target/deerfect-server-0.0.1-SNAPSHOT.jar  # 默认 8080\r\n```\r\n\r\n- 服务就绪判据：`GET http://localhost:8080/api/harness/agents` 能返回。\r\n- 只改 `channel-*`、server 源码没变时，`package` 可能复用旧 fat jar——加 `clean`  \r\n  （见 [design/2026-11-11-feishu-channel-design.md](./design/2026-11-11-feishu-channel-design.md)）。\r\n\r\n### 启动 CLI\r\n\r\n必须先 `compile` 再 `exec:exec`，否则 `target/classes` 是旧的（曾导致乱码回归）：\r\n\r\n```bash\r\nmvn -s .mvn/settings.xml -pl deerfect-cli -Pcli compile exec:exec\r\n```\r\n\r\n### 检查依赖服务连通\r\n\r\n跑测试 / 起服务前先确认 3306 在监听：\r\n\r\n```powershell\r\nTest-NetConnection 127.0.0.1 -Port 3306\r\n```\r\n\r\n### 前端开发与验证\r\n\r\n```bash\r\ncd deerfect-web\r\nnpm install\r\nnpm run dev        # 80，proxy 到 8080\r\nnpm run typecheck  # vue-tsc --noEmit\r\nnpm run build      # typecheck + vite build\r\nnpm run build:pages # 子路径产物（base=/Deerfect_harness/），供 GitHub Pages 项目页部署\r\nnpx vite preview --mode pages  # 本地预览子路径产物，地址带 /Deerfect_harness/\r\nnpm test           # vitest run（Node ≥ 22.12）\r\nnpx playwright install chromium   # 首次跑 E2E 前装浏览器\r\nnpm run test:e2e   # Playwright 冒烟（mock 模式，不需要后端）\r\n```\r\n\r\n本地不想下载 Playwright 的 Chromium 时，可复用系统浏览器（CI 不设该变量，仍用自带 Chromium）：\r\n\r\n```powershell\r\n$env:PLAYWRIGHT_CHANNEL='chrome'; npm run test:e2e   # 系统 Chrome；Edge 用 'msedge'\r\n```\r\n\r\n### 前端路由与静态托管\r\n\r\n- 前端用 History API 路由，地址形如 `/dashboard`、`/chat/trace`、`/knowledge`，**不带 `#`**；\r\n  刷新、直达链接与浏览器前进/后退都按 `pathname` 还原页面，旧 hash 书签（`/#/mcp`）启动时\r\n  会就地改写成 `/mcp`。\r\n- `npm run dev` / `npm run preview` 自带 SPA fallback，无需额外配置。\r\n- 用 nginx 等静态服务器托管 `dist/` 时，必须把未命中的路径回落到 `index.html`，否则直接访问\r\n  或刷新 `/knowledge` 会 404：\r\n\r\n  ```nginx\r\n  location / {\r\n    try_files $uri $uri/ /index.html;\r\n  }\r\n  ```\r\n\r\n- `/api` 仍由反向代理转给 8080，与前端路由不冲突；`/assets`、`/icons` 等静态文件正常直出。\r\n\r\n### 启用邮件推送渠道\r\n\r\n五个环境变量（见「密钥与环境变量」），配置在 `application-channel.yaml` 的 `channel.email.*`，  \r\n占位符已接好，**不用改 yaml**；关闭时 `server` 的依赖树里没有 mail starter（渠道模块是 runtime  \r\n依赖）。首次启用按这个顺序检查，别跳过第 3 步：\r\n\r\n1. `MAIL_FROM` 不设时自动等于 `MAIL_USERNAME`；若单独设置，两者必须一致（多数服务商强制要求，不一致会被拒信）\r\n2. 发件人加入服务商白名单；主题避免「中奖 / 免费」这类词\r\n3. **发一封测试邮件到你手机上的邮箱客户端，确认它不在垃圾箱**——「邮件能发出去」和  \r\n   「你会看到它」是两件事，后者才是告警的意义\r\n4. 打开监控页确认渠道状态：邮件渠道的状态来自「最近一次投递结果」，从未投递时会显示  \r\n   「未知（尚无投递记录）」，这是预期行为，不是故障\r\n\r\n### 启用飞书推送渠道（webhook）\r\n\r\n单向推送（M3a），不是对话入口——对话渠道见 [channel-feishu/README.md](../channel-feishu/README.md)。\r\n\r\n1. 飞书里建一个只有自己的群 → 群设置 → 群机器人 → 添加「自定义机器人」\r\n2. 复制 webhook 地址，开启**签名校验**并复制密钥（`secret`）\r\n3. `channel.feishu.enabled: true`，`notify_channel` 写 `feishu:main`——**别名**，不是 URL；  \r\n   URL 与 secret 是凭证，只放环境变量，不进业务表也不进 git\r\n4. 缺 secret 时应用会**拒绝启动**：不签名的 webhook 等于对任何拿到 URL 的人开放\r\n\r\nM3a 不能接收消息、不能当对话入口，也没有 `message_id`（查不了已读、撤不回）。  \r\n请求体上限 20 KB，超长正文由投递层自动改走邮件（不截断）。\r\n\r\n### QQ 远程 NapCat 联调\r\n\r\nNapCat 跑在远程 Docker 时，用两条隧道打通「本机 server ↔ 远程 NapCat」：\r\n\r\n```powershell\r\n# 终端 A（本机，保持不关）：拉回 NapCat API/WebUI，并把远程上报反向隧道回本机 8080\r\n# 主机地址只存 .env.local（已 gitignore），文档与提交里不出现真实 IP\r\n$env:NAPCAT_REMOTE_HOST = (Select-String -Path .env.local -Pattern '^NAPCAT_REMOTE_HOST=').Line -replace '^NAPCAT_REMOTE_HOST=',''\r\nssh -i \"C:\\Users\\23220\\id_remote_ali_service\" -N -L 3000:127.0.0.1:3000 -L 6099:127.0.0.1:6099 -R 8080:127.0.0.1:8080 \"root@$env:NAPCAT_REMOTE_HOST\"\r\n```\r\n\r\n```bash\r\n# 终端 B（ssh 登录远程主机后执行，保持常驻）：把 Docker 网桥网关的 8080 转到反向隧道落点\r\nsocat TCP-LISTEN:8080,bind=172.17.0.1,fork,reuseaddr TCP:127.0.0.1:8080\r\n```\r\n\r\n- `NAPCAT_REMOTE_HOST`：远程 NapCat 主机地址，只写仓库根 `.env.local`（已 gitignore），文档/提交里用环境变量引用，不落真实 IP。\r\n\r\n\r\n- `-L 3000` / `-L 6099`：本机访问 `127.0.0.1:3000`（NapCat HTTP API）与 `127.0.0.1:6099`（WebUI）实际连到远程；`application-qq.yaml` 的默认地址无需改。\r\n- `-R 8080`：远程 `127.0.0.1:8080` 回连本机 harness；NapCat 在容器内只能访问宿主机 Docker 网桥网关，故用 socat 监听 `172.17.0.1:8080` 再转发到反向隧道落点。\r\n- NapCat 的 OneBot HTTP 上报地址填 `http://172.17.0.1:8080/onebot/event`；本机 harness 需先启动（8080 在监听），否则上报失败。\r\n- `172.17.0.1` 是默认 bridge 网关；使用自定义 Docker 网络时按 `docker network inspect` 实际网关调整。\r\n\r\n### 拉取与本地构建镜像\r\n\r\n部署机用 CI 镜像（compose 已支持 `IMAGE_REPO`，不触发本地构建）：\r\n\r\n```bash\r\nIMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness docker compose -f docker/docker-compose.yml pull app\r\nIMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness IMAGE_TAG=sha-xxxxxxx \\\r\n  docker compose -f docker/docker-compose.yml up -d --no-build\r\n```\r\n\r\n```\r\n\r\n本地仍可用 `docker/build-image.ps1` / `.sh` 构建（tag 为 `sha-<8位>`）；CI 的 tag 是 7 位短 sha，\r\n回滚以实际 tag 为准。\r\n\r\n## 三、FAQ（本机实测）\r\n\r\n- **GitHub 推送经常 408 超时**：默认上游是 `origin/master`（GitHub），Gitee 是备用远端；GitHub 失败不要反复重试，可先推 Gitee 或改用 SSH。\r\n|  |  |\r\n| --- | --- |\r\n- **8080 被占用**：`netstat -aon | findstr :8080` 找 PID，`taskkill /F /PID <pid>` 结束；没有脚本会自动清理。\r\n- **只改 `channel-*` 后跑起来还是旧代码**：`deerfect-server` 源码没变时 `mvn -pl deerfect-server -am package`\r\n  会复用旧 fat jar，加 `clean`。\r\n- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。\r\n- **改了接口却编译不过**：先确认命令带了 `-am`（`-s .mvn/settings.xml` 只有本机有，不是必需）。\r\n- **`mvn verify` 里 cli 测试卡几十秒**：JLine 探测终端的副作用，已修（详见\r\n  [design/2026-11-11-cli-terminal-probe.md](./design/2026-11-11-cli-terminal-probe.md)）；\r\n  新环境若复现，先看 `deerfect-cli/target/surefire-reports` 里 `TerminalInputTest` 的 `time`。\r\n- **`nvm use` 报权限不足**：nvm-windows 切换版本需要管理员权限；切换失败时 `D:\\nodejs` 链接\r\n  可能被移除，重新 `nvm use` 即可重建。\r\n```\r\n",ar='---\r\ntitle: "项目功能介绍"\r\norder: 2\r\n---\r\n\r\n# 项目功能介绍\r\n\r\n> 面向首次接触项目的读者，一页说明「现在能做什么、还没做什么」。\r\n> 本文按 2026-11-06 的代码与配置核对；技术基线为 **Java 25 / Spring Boot 4.1.2 / Spring AI 2.0.2**（详见 [tech-stack.md](./tech-stack.md)）。\r\n\r\n---\r\n\r\n## 一、项目定位\r\n\r\nDeerfect Harness 是一个基于 Spring AI 的双路径 Agent 平台：**Agentic 路径**由单 Agent 显式 ReAct 循环自主执行；**Graph 编排路径**自动拆解、专家并行、聚合交付，全过程流式可视。所有能力默认只依赖 MySQL，Docker 仅用于沙箱工具。\r\n\r\n## 二、使用入口\r\n\r\n| 入口 | 已实现能力 | 文档 |\r\n|---|---|---|\r\n| REST API | 同步聊天、SSE 流式聊天、断点续跑、Agent/Goal/会话管理、观测查询 | [api.md](./guides/api.md) |\r\n| 终端 CLI | 多轮聊天、新建会话、切换 Agent、断点续跑、流式 Markdown 渲染 | [cli.md](./guides/cli.md) |\r\n| Web 控制台 | 会话列表、流式打字机、思考折叠、Trace 观测、知识库管理页 | [README](../README.md) |\r\n| QQ 机器人 | NapCat/OneBot 11、群聊 @/回复触发、指令交互（/new /reset /history /agent /recall）、引用回复、表情包、防刷 | [qq-channel.md](./guides/qq-channel.md) |\r\n| MCP Server | 主服务内置 Streamable HTTP MCP 端点，可对外暴露内置工具 | [tech-stack.md](./tech-stack.md) |\r\n\r\n## 三、双路径执行：Agentic 与 Graph 编排\r\n\r\n- **路径判定按任务形态**：`RouteJudge` 判的是「是否存在可独立并行的工作流 / 多份交付物」，而非消息长短或主观难度；内部枚举 `SIMPLE` / `COMPLEX` 分别对应 Agentic 与 Graph 两条路径。\r\n- **两段式判定**：先规则短路（多工作流措辞 → Graph；≤12 字短消息 → Agentic），灰区再走一次轻量 LLM 判定，返回 `route` / `confidence` / `workstreams` / `deliverables` / `reason`。\r\n- **置信度策略**：Graph 需同时满足「≥2 个可独立且互不重叠的工作流（近似重复项先去重）」且「置信度 ≥0.75」，否则收敛为 Agentic。阈值取 0.75 是因为 Agentic 取消运行时升级后漏判没有补救，而误判 Graph 有编排层降级兜底；判定异常一律兜底 Agentic。多次串行工具调用、单交付物、强顺序依赖都不算 Graph。\r\n- **降级策略**：Agentic 链路不升级 Graph——ReAct 迭代耗尽时按 `GenerateReason.MAX_ITERATIONS` 判定：有整理结果按「部分完成」返回，无结果返回兜底话术。Graph 编排异常时先在编排层简化（聚合失败用已完成子任务结果汇总），仍失败再降级为会话绑定 Agent 单模型重答一次，不递归。\r\n- **Agentic 路径**：`AgentScopeGeneralAgent` 以 ReActAgent 执行显式推理-行动循环（默认开启，`app.agentic.enabled`），工具仍由 Spring AI 提供并经桥接进入 Toolkit；初始化失败可回退 ChatClient 单次调用路径。\r\n- **Graph 编排路径**：自研 `ParallelOrchestrationEngine` 执行「Lead 拆解 → 专家并行 → Aggregator 聚合」；子任务至多 4 条，禁止凑数。\r\n- **失败不连坐**：单个子任务失败写占位结果，聚合照常执行并注入降级说明。\r\n- **产品侧显式开关**：请求体 `routing`（`auto` / `agentic` / `graph`）可覆盖判定；Web 端「路径」下拉、CLI `/routing` 均走同一字段，绑定专属 Agent 的会话忽略 `graph`（保留领域工具直答）。\r\n- **决策可观测**：判定结果（路径 / 置信度 / 工作流 / 来源）写入日志、SSE `meta.route` 与同步响应 `route`，Web 消息头显示路径徽标，悬浮可见来源与理由。\r\n- **断点续跑**：按 `LEAD_DONE` / `SUBTASKS_DONE` / `DONE` 三阶段落检查点，已完成阶段不重跑（[resume.md](./guides/resume.md)）。\r\n\r\n## 四、Agent 与模型体系\r\n\r\n- **全表驱动**：`agent` 表一行即一个 Agent（prompt / route_id / tools / knowledge / thinking）；`llm_provider` → `llm_model` → `llm_route` 三层描述端点、部署与失败链。\r\n- **零代码扩展**：新增 Agent 只加数据行；接入第三方 OpenAI 兼容供应商通过 `/api/admin/*` 登记，服务端校验后热更新（[models.md](./guides/models.md)）。\r\n- **内置角色**：`general`、`deepseek` 聊天 Agent，`multi-agent` / `lead` / `aggregator` 内部编排角色，`researcher` / `coder` / `analyst` / `writer` 四类专家，另可按表扩展领域专员（如 `java-tutor`、`wms`）。\r\n- **按 Agent 配置**：模型、系统提示词、工具集、知识库绑定、思考内容显示均按行生效。\r\n\r\n## 五、工具体系\r\n\r\n| 能力 | 说明 |\r\n|---|---|\r\n| 自研工具 | 网页抓取（jsoup 正文提取、Markdown 输出、错误分类与缓存）、时间等本地工具 |\r\n| MCP 工具 | stdio / Streamable HTTP 多 server，懒连接、按 server 故障隔离，配置见 `mcp-config.json`（[mcp-tools.md](./guides/mcp-tools.md)） |\r\n| 容器沙箱 | Python / Shell / 文件读写 / 浏览器操作只在 Docker 容器内执行，宿主机零暴露；无 Docker 时该组工具自动禁用 |\r\n| 技能（Skill） | `skills/<skill>/` 目录式技能：入口 `SKILL.md`（或 `<skill>.md`）+ front-matter，按 Agent 可见集注入索引，`load_skill` 按需取正文 |\r\n| 最小权限 | `general` 收敛为只读探索面；执行 / 写入只授予 `coder` / `analyst` |\r\n| Schema 延迟加载 | 首轮只暴露工具名称与用途，模型调 `expand_tool` 后再注入完整参数 Schema |\r\n| 领域适配范例 | 独立 `wms-mcp-adapter`：只读 GET、分页钳制、双侧鉴权、审计字段过滤（[wms-mcp.md](./guides/wms-mcp.md)） |\r\n\r\n## 六、知识与记忆\r\n\r\n- **RAG 知识库**：pgvector 向量库 + DashScope text-embedding-v4，`knowledge/` 目录增量摄取，多知识库按 Agent 绑定。\r\n- **混合检索**：向量召回 + BM25 关键词召回，RRF 融合排序；回答带【出处N】内联引用与来源尾注。\r\n- **知识库增强**：目录监听自动摄取、上传 / 删除 / 同步 / 调试检索、单文件管理页 `/knowledge.html`（[knowledge-rag.md](./guides/knowledge-rag.md)）。\r\n- **会话记忆**：多轮上下文自动组装并按 token 预算裁剪，超长历史从最旧丢弃。\r\n- **会话滚动摘要**：裁剪丢弃的旧轮次异步压缩为一段摘要（消息游标增量合并、失败不推进游标），下一轮作为 system 段注入——长会话问「开头说过的约束」仍能答上。\r\n- **用户画像**：空闲会话按消息游标**增量**提炼偏好（LLM 只输出新增/变更补丁、代码按 `[key]` 合并，既有条目不靠提示词自觉保留），写盘前备份 `.bak`；全局 Markdown 注入 `general` / `lead` 的 system prompt（默认关闭，`app.memory.profile-enabled` 开启）。\r\n- **历史归档召回**：快照裁剪的消息带稳定 ID 转存 `session_message_archive`；`recall_history` 默认 `scope=current` 只搜本会话，`scope=all` 跨全部历史会话按归档时间倒序召回并标注来源会话（V11 `idx_archive_time` 支撑排序）。\r\n- **显式记忆**：用户说「记住…」时由 `remember` 工具落 `memory` 表（跨会话），`forget` 软删、`list_memories` 查看；同 key 以最新为准（旧条目自动软删），过期条目不注入。注入段排在画像之前并声明「冲突以用户明说为准」——画像推断的旧值压不住用户明确记住的事实。CLI `/remember` `/memories` `/forget` 与 `GET/POST/DELETE /api/memories` 同源。\r\n- **Goal 台账与主动化**：目标全生命周期 `PENDING → RUNNING → SUCCEEDED/FAILED` 落库，失败按 1→2 分钟退避自动重投（上限 2 次）；定时调度、QQ 主动推送、对话内活跃目标注入，以及沙箱容器探活自愈与写/执行边界拦截均已落地。\r\n\r\n## 七、可靠性护栏\r\n\r\n| 护栏 | 默认值 |\r\n|---|---|\r\n| 流式连接上限 | 50（超限 429） |\r\n| 子任务并发 | 2（信号量错峰） |\r\n| 子任务墙钟超时 | 600 秒 |\r\n| 单次编排 token 上限 | 60000（超限跳过剩余子任务，聚合照常） |\r\n| 输出封顶 | Lead 4000 / 专家 4000 / 最终回答 8000 |\r\n| 单次工具调用上限 | 4 次，工具结果注入 5000 token |\r\n| 超时梯队 | 连接 10s / 读 300s / 流空闲 40s / 首帧 20s / 路由判定 10s |\r\n\r\n另有：客户端断连后零新增 LLM 调用、失败连接丢池重建、LLM 调用 3 次退避重试、Graph 路径失败降级重答。配置口径见 [context-optimization.md](./guides/context-optimization.md)。\r\n\r\n## 八、观测与成本\r\n\r\n- **四张观测表**：LLM 调用、工具调用、RAG 检索、MCP 连接事件，全部异步落库。\r\n- **查询接口**：`/api/llm-calls`、`/api/tool-calls`、`/api/kb-retrievals`，Web Trace 页可视化。\r\n- **会话上下文构成**：Web 监控页按会话查看最近一次请求的 system 分段 / RAG / 工具 Schema / 历史 / 当前消息占比（`GET /api/monitor/context`，内存快照 TTL 30 分钟）；估算口径 `TokenEstimator`，不含轮内动态工具结果，多 Agent 只保留最近一次调用。\r\n- **链路标识**：`turnId` / `traceId` / `parent_span` 贯通路由、子任务与聚合。\r\n- **保留期治理**：观测数据默认保留 90 天，每日凌晨低峰分批清理。\r\n- **轻量追踪**：自建 span 关系；Micrometer 已接入（`/actuator/prometheus` 暴露运行指标），OpenTelemetry 分布式链路追踪尚未接入。\r\n\r\n## 九、安全与部署\r\n\r\n- **双通道鉴权**：机器通道 `X-API-Token`（常量时间比较）、登录通道口令换 HttpOnly Cookie（168 小时滑动续期，连续失败 5 次锁定 60 秒）；两通道均未配置则本地直通。\r\n- **密钥管理**：API Key 不落库，`.env.local` 与 `mcp-config.json` 均不入 git。\r\n- **数据库演进**：Flyway 版本化迁移，启动自动建表 / 升级。\r\n- **模块化**：`deerfect-api` / `channel-qq` / `channel-email` / `channel-feishu` / `deerfect-server` / `deerfect-cli` / `wms-mcp-adapter` 模块 + 独立 `deerfect-web`。\r\n- **容器化**：Dockerfile + docker-compose（app + MySQL + pgvector），见 [docker-deploy.md](./guides/docker-deploy.md)。\r\n- **持续集成与质量门禁**：`.github/workflows/ci.yml`（backend / web / e2e 三 job）与 `docker-image.yml`（镜像发布）已落地。backend 跑 `mvn verify`，含 jacoco 行覆盖率门禁（按模块设阈，数值以各模块 `pom.xml` 的 `jacoco.line.minimum` 为准）与真实 MySQL 仓储集成测试；web 跑 ESLint 再类型检查与单测。CD 自动部署与分支保护强制校验仍需人工配置，见 [dev.md](./dev.md) 的「流水线」一节。\r\n- **测试**：后端 JUnit 5 + Mockito，前端 vitest / Playwright；用例数以各模块 `target/surefire-reports` 与 CI 报告为准，详见 [functional-testing.md](./guides/functional-testing.md)。\r\n\r\n## 十、尚未实现（路线图未勾选项）\r\n\r\n接口级限流防刷、CD 自动部署与分支保护强制校验、OpenTelemetry 分布式链路追踪、Testcontainers 集成测试、Swagger/OpenAPI、Redis 会话缓存、消息队列、多实例水平扩展、子任务依赖编排、子任务失败重规划、对话历史向量化召回（跨会话关键词召回已支持，向量检索未做）、PDF 导出、多模态输入、Web 端分页与独立域名部署。\r\n\r\n> 完整清单与验收口径见 [roadmap.md](./roadmap.md)。\r\n\r\n## 十一、延伸阅读\r\n\r\n| 文档 | 内容 |\r\n|---|---|\r\n| [README](../README.md) | 项目速览与快速开始 |\r\n| [architecture.md](./architecture.md) | 架构总览与两路径数据流 |\r\n| [data-flow.md](./data-flow.md) | 请求生命周期、预算记账、观测落库 |\r\n| [tech-stack.md](./tech-stack.md) | 技术栈明细与当前版本 |\r\n| [guides/api.md](./guides/api.md) | REST 接口全表 |\r\n| [guides/functional-testing.md](./guides/functional-testing.md) | 测试全景 |\r\n| [roadmap.md](./roadmap.md) | 路线图（未完成 / 已完成存档） |\r\n\r\n---\r\n\r\n[⬅ 返回文档索引](./index.md)\r\n',or='# REST 接口\r\n\r\n> 全部 REST 端点、知识库管理页与 RAG 问答说明。\r\n\r\n---\r\n\r\n**聊天**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `POST` | `/api/chat` | 💬 同步聊天：`{"message":"你好","agentId":1}` |\r\n| `POST` | `/api/chat/stream` | 📺 流式聊天（SSE）：同请求体，逐 token 推送 |\r\n| `POST` | `/api/chat/resume?goalId=` | 🔁 复杂编排断点续跑，响应格式与 `/stream` 一致 |\r\n| `GET` | `/api/chat/goal-status?goalId=` | 🔎 查询单个 goal 的执行状态 |\r\n\r\n**会话与 Agent**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `GET` | `/api/harness/agents` | 🧩 已注册的 Agent 列表 |\r\n| `GET` | `/api/harness/sessions?page=&size=` | 📋 会话分页列表（默认 page=1 / size=10） |\r\n| `POST` | `/api/harness/sessions?name=` | 🆕 新建会话（可选 `name`），返回 sessionId/name |\r\n| `DELETE` | `/api/harness/sessions/{sessionId}` | 🗑 删除会话（软删 + 快照清理，调用日志与 goal 保留） |\r\n| `POST` | `/api/harness/sessions/{sessionId}/agent?agentId=` | 🎭 切换会话绑定的 Agent |\r\n| `GET` | `/api/harness/sessions/{sessionId}/messages` | 📜 会话历史消息（无历史返回空列表） |\r\n\r\n**记忆**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `GET` | `/api/memories?status=&limit=` | 🧠 显式记忆列表：`status=active`（默认）/ `expired` / `deleted`，含来源、时间与状态 |\r\n| `POST` | `/api/memories` | 🧠 记住一件事（body：`content` 必填，`key` / `expiresAt` 可选；同 key 覆盖旧记忆，来源记为 `manual`） |\r\n| `DELETE` | `/api/memories/{id}` | 🧠 忘掉一条记忆（软删保留审计，不再注入；不存在返回 400） |\r\n\r\n**目标与定时任务**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `POST` | `/api/harness/submit?agent=&objective=` | 📤 提交一个异步目标（「现在跑一次」） |\r\n| `GET` | `/api/harness/goals` | 🎯 目标（含聊天记录）与状态 |\r\n| `GET` | `/api/harness/goals/{id}` | 🎯 查询单个目标状态 |\r\n| `GET` | `/api/harness/goals/active?sessionId=` | 📌 会话当前活跃的长期目标（无目标返回空列表） |\r\n| `POST` | `/api/harness/goals/active` | 📌 登记长期目标（body：`sessionId` + `objective`，每轮注入 system） |\r\n| `POST` | `/api/harness/goals/{id}/complete` | ✅ 标记长期目标完成（只接受 ACTIVE，重复完成 400） |\r\n| `GET` | `/api/schedules` | ⏰ 定时任务列表 |\r\n| `POST` | `/api/schedules` | ⏰ 新建定时任务（body：`objective` / `agentName` / `cron` / `notifyChannel`；cron 为 Spring 六段表达式，间隔须 ≥30 秒） |\r\n| `DELETE` | `/api/schedules/{id}` | ⏰ 删除定时任务（已触发的 goal 不受影响） |\r\n\r\n**观测与监控**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `GET` | `/api/llm-calls?sessionId=&limit=` | 🧮 LLM 调用观测：耗时 / token / 成败（默认 50 条） |\r\n| `GET` | `/api/tool-calls?sessionId=&limit=` | 🔧 工具调用观测 |\r\n| `GET` | `/api/kb-retrievals?sessionId=&limit=` | 📚 RAG 检索观测 |\r\n| `GET` | `/api/monitor/context?sessionId=` | 📊 最近一次请求的上下文构成快照（分段占比；无快照 404） |\r\n| `GET` | `/api/monitor` | 📈 运行时指标快照（与 `/actuator/prometheus` 同源） |\r\n\r\n**配置、鉴权与渠道**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `GET` / `POST` / `PUT` | `/api/admin/providers` | 🔌 供应商管理：列表 / 新增 / 修改（写操作先校验再热更新） |\r\n| `GET` / `POST` / `PUT` | `/api/admin/models` | 🧠 模型部署管理（能力与默认参数） |\r\n| `GET` / `POST` / `PUT` | `/api/admin/routes` | 🗺️ 路由与成员优先级管理 |\r\n| `POST` | `/api/admin/routes/{id}/test` | 🩺 路由成员连通性测试（逐成员返回延迟与错误分类） |\r\n| `PUT` | `/api/admin/agents/{id}/route` | 🔗 调整 Agent 绑定的路由 |\r\n| `GET` | `/api/skills` | 🧰 已注册技能列表 |\r\n| `POST` | `/api/auth/login` | 🔐 口令登录（换 HttpOnly Cookie） |\r\n| `GET` | `/api/auth/me` | 🔐 当前登录态 |\r\n| `POST` | `/api/auth/logout` | 🔐 登出 |\r\n| `POST` | `/onebot/event` | 🤖 QQ 渠道 OneBot 11 上报端点（`channel-qq` 模块，NapCat 调用） |\r\n\r\n> [!IMPORTANT]\r\n> **管理面鉴权**：`app.security.api-token`（请求头 `X-API-Token`）与 `app.security.password`（登录 Cookie）配任意一个即启用，过滤器覆盖全部 `/api/**`——包括 `/api/admin/**` 与 `/api/admin/routes/{id}/test`。\r\n> 两者都为空时管理面**不鉴权**，仅适合本机；对外部署请设置口令，并把 `app.security.require-configured` 设为 `true`，缺口令会直接启动失败而不是静默裸奔。\r\n> 目前管理面没有内置限流，`/routes/{id}/test` 会真实调用上游模型，请只在可信网络暴露。\r\n\r\n**知识库**\r\n\r\n| 方法 | 路径 | 说明 |\r\n|---|---|---|\r\n| `POST` | `/api/knowledge/sync` | 📚 知识库增量摄取：扫描 knowledge/ 目录，mtime 变更文档重嵌入，并清理磁盘已删文档的孤儿向量 |\r\n| `GET` | `/api/knowledge/documents?page=&size=` | 📚 知识库摄取台账分页 |\r\n| `GET` | `/api/knowledge/search?q=&kb=&full=` | 📚 调试检索：检索命中片段与相关度（不注入 prompt）；`full=true` 额外返回片段原文 |\r\n| `POST` | `/api/knowledge/upload` | 📚 multipart 上传 .md/.txt（≤1MB，可选 `kb` 表单字段归库），只落盘不摄取，重名覆盖 |\r\n| `DELETE` | `/api/knowledge/documents/{name}` | 📚 删除指定知识文档（向量 chunk + 台账） |\r\n\r\n> 定时任务与长期目标的差别：`/api/schedules` 是「到点自己开始」（一次触发产生一个 goal），\r\n> `/goals/active` 是「记住要长期推进」（每轮对话注入 system，手动标记完成）。\r\n\r\n> [!NOTE]\r\n> `agentId` 可选（对应 agent 表主键）：为空走默认 Agent（general）。\r\n> 知识库端点需 `app.knowledge.enabled=true`（默认 true）且配置好 pgvector/嵌入端点；未启用时返回 503。\r\n> 知识库另有单文件 Web 管理页：浏览器打开 **`http://localhost:8080/knowledge.html`**（台账/删除/同步/上传/调试检索，未启用时展示降级指引）。\r\n\r\n### 📚 知识库问答（RAG）\r\n\r\n把文档放进 `knowledge/` 目录（`.md` / `.txt`，支持 front-matter `title:`），摄取后路径 A/B 回答自动检索注入，带【出处N】内联引用与来源尾注。触发是组装 prompt 前的旁路检查，五层条件任一不满足即静默降级、主链路零感知。\r\n\r\n> [!TIP]\r\n> 多知识库与 agent 绑定、目录监听 / BM25 混合检索等增强配置见 **[`docs/guides/knowledge-rag.md`](knowledge-rag.md)**；决策流程与时序图见 [`docs/data-flow.md` 5i 节](../data-flow.md#5i-rag-知识检索注入数据流prompt-组装前旁路)。\r\n\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n',sr='# CLI 使用\r\n\r\n> CLI 纯 HTTP 客户端的使用方式与全部命令。\r\n\r\n---\r\n\r\nCLI 是纯 HTTP 客户端（**不监听任何端口**），通过 REST 调用主服务：\r\n\r\n```text\r\n你> 你是谁\r\n千问> 我是通义千问，一个AI助手...\r\n```\r\n\r\n| 命令 | 作用 |\r\n|---|---|\r\n| 直接输入文本 | 与当前 Agent（默认 general）聊天，多轮记忆自动延续 |\r\n| `/new [名称]` | 🆕 新建会话并切换（旧会话保留） |\r\n| `/agent <id>` / `/agent off` / `/agent` | 🎭 切换到指定 Agent（agent 表主键，同步会话绑定）/ 恢复智能分流 / 查看当前；绑定非 general 的专属 Agent 走 Agentic 直答 |\r\n| `/routing [auto\\|agentic\\|graph]` | 🧭 查看或固定执行路径，不传参查看当前；`auto` 恢复服务端自动判定 |\r\n| `/resume [goalId]` | 🔁 复杂编排断点续跑：不传参恢复当前会话最近一次编排任务，传参续跑指定 goal（从检查点继续，已完成节点不重跑） |\r\n| `/provider [add <provider> <apiUrl> <模型1,模型2,...>]` | 🔌 查看模型-服务商映射 / 新增供应商（服务端热刷新） |\r\n| `/schedule [list\\|add "<cron>" <agent> <目标>\\|del <id>]` | ⏰ 定时任务增删查，不传子命令等同 `list`；cron 为 Spring 六段表达式（秒 分 时 日 月 周），间隔须 ≥30 秒 |\r\n| `/goal [查看\\|<目标描述> 登记\\|done <id> 完成]` | 📌 长期目标：登记后每轮注入 system；`done` 手动收口（不做 LLM 自评完成） |\r\n| `/remember <内容>` | 🧠 记住一件事（跨会话，之后每轮自动带上；来源记为 cli） |\r\n| `/memories [active\\|expired\\|deleted]` | 🧠 查看记忆，含 id / 标签 / 来源 / 时间 / 状态 |\r\n| `/forget <id>` | 🧠 忘掉指定 id 的记忆（软删保留审计，不再注入） |\r\n| `/help` / `/exit` / `/quit` | ❓ 帮助 / 🚪 退出 |\r\n\r\n> `/goal` 与 `/schedule` 是两套独立机制：前者是「记得住你在追什么」（常驻上下文），\r\n> 后者是「到点自己开始」（到点自动提交一个 goal，结果可经 QQ 主动推送）。\r\n\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n',ir='# 提交规范\r\n\r\n> 本项目遵循 [Conventional Commits](https://www.conventionalcommits.org/) 格式，**描述用中文**。\r\n> 与 [.trae/rules/git-workflow.md](../../.trae/rules/git-workflow.md) 配套使用。\r\n\r\n## 格式\r\n\r\n```\r\n<type>(<scope>): <中文描述>\r\n\r\n<body>\r\n\r\n<footer>\r\n```\r\n\r\n- **type**（必填，英文）：见下表\r\n- **scope**（可选，英文）：影响范围，如 `mcp`、`agent`、`cli`、`sandbox`、`route`\r\n- **subject**（必填，中文）：一句话说明改了什么\r\n- **body**（可选，中文）：说明**为什么**改，而非罗列做了什么\r\n- **footer**（可选）：`Closes #123`、`BREAKING CHANGE: ...`\r\n\r\n## 中文乱码规避（重要）\r\n\r\nWindows 控制台默认 GBK 代码页，**命令行内联中文**（`git commit -m "中文"`）易乱码。\r\n规避方式（二选一）：\r\n\r\n1. **推荐：文件方式提交**——把 message 写入 UTF-8 文本文件后 `git commit -F <文件>`（AI 助手执行提交时一律用此方式）\r\n2. 配置编码后仍可用内联：`git config --global i18n.commitEncoding utf-8` + 终端切 UTF-8 代码页（`chcp 65001`）\r\n\r\n`git log` 中文显示乱码时，追加配置：`git config --global i18n.logOutputEncoding utf-8`。\r\n\r\n## Type 一览\r\n\r\n| type | 用途 | 示例 |\r\n|------|------|------|\r\n| `feat` | 新功能 | `feat(mcp): 支持 stdio 传输与工具名去重` |\r\n| `fix` | 缺陷修复 | `fix(route): 路由判断请求补 model 参数避免 400` |\r\n| `refactor` | 重构（不改行为） | `refactor(tools): 按名称去除重复工具回调` |\r\n| `perf` | 性能优化 | `perf(cli): 复用 http 客户端减少连接开销` |\r\n| `docs` | 文档 | `docs: readme 重写为 github 风格` |\r\n| `test` | 测试 | `test(assignments): 覆盖重名工具去重场景` |\r\n| `chore` | 构建/工具/杂项 | `chore: gitignore 增加 cli 临时文件` |\r\n| `revert` | 回滚 | `revert: 回滚 feat(mcp) 因握手超时` |\r\n| `remove` | 删除文件/功能 | `remove: 清理调试临时文件` |\r\n\r\n## Subject 规则\r\n\r\n1. 简洁动宾短语，不加句号：`支持断点续跑` 而非 `支持了断点续跑。`\r\n2. 一行不超过 72 字符（中文按 2 字符宽估算，约 30+ 汉字）\r\n3. 不写「更新代码」「修改一些东西」这类无信息量的描述\r\n\r\n## Body 规则\r\n\r\n- 解释**动机与取舍**（为什么），改动内容看 diff 即可\r\n- 空一行后接 body（git 工具依赖此分隔）\r\n- 多段时用 `-` 列表\r\n\r\n## 提交纪律（硬性）\r\n\r\n1. **只提交明确在任内的文件**，禁止 `git add -A` / `git add .` 盲加\r\n2. **单一功能**：一个 commit 只承载一个功能或修复，禁止多个独立改动混入同一 commit；\r\n   同轮完成多件事时按功能边界拆成多个 commit 依次提交，type/scope 各自准确；\r\n   确因文件交叉无法干净拆分而合并提交时，body 必须分条说明各部分改动及合并理由\r\n3. **绝不提交**：`.mvn-repo/`、`.cli-history`、`target/`、`.idea/`、`.vscode/`、\r\n   `in.txt`、`cli-*.txt`、`server.log` 等临时/生成文件（已在 `.gitignore`）\r\n4. 未获用户明示（「提交」/「commit」/「push」等指令）**不得执行任何 git 提交类操作**\r\n5. 推送必须**双远程**：`origin`（GitHub）与 `gitee` 缺一不可\r\n6. 含密钥/凭据的文件永不入库\r\n\r\n## 示例\r\n\r\n单行（多数场景够用）：\r\n\r\n```\r\nfix(mcp): 对齐 mcp sdk 至 0.17.0 解决 graph-core 依赖冲突\r\n```\r\n\r\n带 body：\r\n\r\n```\r\nfix(cli): 显式声明 token 事件修复 SSE 事件粘滞吞字\r\n\r\nSSE 规范中 event 字段按块粘滞：progress 块之后未声明事件的 data 行\r\n会被误归入 progress 通道，导致所有回答 token 静默丢失（CLI 显示\r\n输出约 0 字）并产生空白 spinner 行。\r\n\r\n服务端与客户端现在统一显式使用 token 事件，并移除无事件的兜底分支。\r\n```\r\n',lr=`# 上下文管理优化方案

> 状态：**已实现**（2026-09-06 核对；2026-09-08 增补消费侧预算——输出 maxTokens 分档与编排全程消费上限熔断，见第五节）——\`app.context.*\` 预算配置（\`config/ContextBudgetProperties\`）、静态 prompt 预算拦截器（\`advisor/PromptBudgetAdvisor\`，lead/聚合节点接入）、\`tool/ToolCallBudget\` 均已落地，由 \`MultiAgentGraphAgent\` / \`GeneralAssistantAgent\` 消费；本文保留为设计依据与参数口径说明。
> 关联代码：\`advisor/ContextAssemblingAdvisor\`、\`advisor/PromptBudgetAdvisor\`、\`tool/budget/ToolCallBudget\`、\`tool/TokenEstimator\`、\`graph/MultiAgentGraphAgent\`、\`agent/AgentChatCaller\`
> 背景：262 万 prompt token 事故复盘后，输入侧防线已补齐（工具预算/内容过滤/白名单），本方案补齐最后一个缺口——**静态 prompt 无预算**；消费侧（输出生成与编排累计）防线见第五节。

---

## 一、现状盘点：三条路径，两层防御

### 1.1 上下文入口全景

| 路径 | 节点 | 上下文来源 | 现有防线 |
|------|------|-----------|---------|
| A 简单对话 | general 直答 | 会话历史（DB 逐轮重建） | \`MessageChatMemoryAdvisor\` + \`ContextAssemblingAdvisor\`（4000） |
| B 编排 | lead 拆解 | 用户目标 + 内置约束 | ❌ 无 |
| B 编排 | 子任务 ×N | persona + 任务（一次性，零记忆） | ✅ \`ToolCallBudget\`（12 次硬封顶 + 5k 结果预算） |
| B 编排 | 聚合 | **全部子任务结果顺序拼接** | ❌ **无（最大缺口）** |
| B 续跑 | checkpoint 恢复 | 执行状态（非对话） | ✅ 语义正确，无需预算 |
| 工具层 | 工具 schema | tools 字段 | ✅ MCP 白名单收窄（12 → ≤4） |
| 工具层 | 工具结果内容 | 循环内追加 | ✅ URL 去重 + 3 层内容过滤 + query 提取 |

### 1.2 已确认的问题

1. **聚合节点输入无预算**：子任务的最终回答文本不受 5k 工具预算约束（那只管工具结果），N 路答案在 \`MultiAgentGraphAgent\` 聚合处 StringBuilder 直拼，单个 prompt 可达 30k~100k+ token——与 262 万事故同构的残留风险。
2. **lead 输入无预算**：用户目标超长时直接进 prompt（低风险，但无上界）。
3. **token 估算口径分裂**：\`ContextAssemblingAdvisor\` 自带估算，未接入统一的 \`TokenEstimator\`（目前仅 \`ToolCallBudget\`、\`LlmCallRecorder\` 使用），观测日志与预算数值不可比。
4. **预算硬编码**：4000 写死在构造器（代码注释自认"后续可配置注入"）。

### 1.3 防御分层原理（为什么是两个组件而非一个）

Spring AI 的调用管线分两段：

\`\`\`
prompt().call() ──→ 【Advisor 链】──→ ChatModel.call ──→ 【工具循环】──→ 模型
                      ↑ 每次调用只进一次          ↑ 每轮工具执行后消息追加，advisor 不可见
\`\`\`

| 层 | 组件 | 管什么 | 时机 |
|----|------|--------|------|
| Advisor 层 | \`ContextAssemblingAdvisor\`（已有）/ \`PromptBudgetAdvisor\`（新增） | **静态 prompt**：历史装配、聚合拼接、lead 目标 | 进工具循环之前，拦一次 |
| 工具层 | \`ToolCallBudget\`（已有） | **动态追加**：工具循环内的工具结果 | 循环内每轮，包装在工具回调上 |

实证：412 报错的生产堆栈中子任务调用已穿过 \`ChatModelCallAdvisor.adviseCall\`——编排路径的 advisor 机制现成可用，只是链上没有管 prompt 预算的成员。

### 1.4 记忆读写现状：编排 × session memory（不对称）

核实 \`ChatServiceImpl.toSseBody → writeBackContext\`（流式与 resume 共用）后的精确结论：

| 方向 | 内容 | 说明 |
|------|------|------|
| ✍️ 写入 | \`user = 用户目标\` + \`assistant = 聚合最终回答\` | 流结束后服务层一次性写回；**进度行显式过滤**，lead 拆解 JSON / 子任务结果等中间产物不进 memory（只活在 graph state 与 checkpoint） |
| ✍️ 不写 | FAILED 编排 | 出错走 \`onErrorResume\`，\`doOnComplete\` 不触发，失败不污染记忆 |
| ⚠️ 重复写 | resume 重放 | 续跑成功会把「目标+新回答」再写一遍；聚合结果与中断前相同时产生重复条目（做摘要注入时顺手去重） |
| 👁️ 读取 | 仅 general 读 | 编排节点（lead/子任务/聚合）**不挂 memory advisor，执行时读不到任何历史**——包括历史编排写回的记录 |

两个推论：

1. **「编排零记忆」的准确表述**是「只写不读」：session memory 里已有历史编排的目标+结论，唯一的读者是 general（编排完成后问 general「刚才的报告说了什么」，它能看到）。
2. **lead 复用记忆的数据基础已存在**——补「只读摘要注入」不需要任何存储改造，实现成本比预想低。

---

## 二、方案设计

### 2.1 新增 \`PromptBudgetAdvisor\`（P0 核心）

**职责**：请求级拦截，对本次调用的静态 prompt 做 token 预算约束。

**挂载方式**：请求级（\`spec.advisors(new PromptBudgetAdvisor(budget))\`），**不用 default advisor**——聚合与子任务共用同一个 \`ChatClient\`（同路由到 qwen3.7-flash），default 挂载会连坐。

**挂载点与预算**：

| 调用点 | 预算 | 截断语义 |
|--------|------|---------|
| 聚合 \`aggregate()\` | 12k | **按子任务边界等份额截断**：每份保留头尾，中间截断带 \`[内容已截断]\` 标记；禁止先到先得 |
| lead \`lead()\` | 4k | 目标文本超长时尾部截断（通用语义） |
| Agentic 路径历史 | 4k（维持现状） | \`ContextAssemblingAdvisor\` 继续负责 |

**实现要点**：

\`\`\`java
public final class PromptBudgetAdvisor implements CallAdvisor {
    private final int maxTokens;
    // adviseCall: 读 prompt → 估算总 token → 未超预算直通；
    // 超预算则按策略改写 user/system 内容（聚合份额截断 / 通用尾截）→ 放行
    // 截断发生时 log.warn（含原始/预算后 token 数），并在 LlmCallRecorder 观测中可见
}
\`\`\`

- 截断策略通过构造参数注入（\`ProportionalSectionTruncator\` / \`TailTruncator\`），advisor 本体不含业务结构知识——**聚合的子任务边界识别**由调用点传入分隔协议（复用聚合拼接时的既有分隔格式），避免 advisor 反向依赖 agent 包。
- 单位统一走 \`TokenEstimator.estimateTokens\`，全项目唯一口径。

### 2.2 口径统一与配置化（P1）

1. \`ContextAssemblingAdvisor\` 的估算改用 \`TokenEstimator\`（约 3 处替换）。
2. 预算全部挪进 \`application-chat.yaml\`，集中管理：

\`\`\`yaml
app:
  context:
    history-budget: 4000      # Agentic 路径历史裁剪
    lead-budget: 4000         # lead 拆解 prompt
    aggregate-budget: 12000   # 聚合 prompt
    tool-result-budget: 5000  # 单次调用工具结果总预算（现有）
    tool-call-limit: 12       # 单次调用工具执行次数上限（现有）
\`\`\`

### 2.3 明确不做的事

- **不硬截断子任务最终输出**：截断聚合输入已足够兜底；截模型输出可能切掉结论，且抑制表达质量。
- **不上 LLM 摘要压缩**（P2 备选）：先用机械截断观察聚合质量，数据证明需要再上 map-reduce 摘要（每个子任务先压 ≤1k 摘要再聚合，成本 +N 次调用）。
- **不给 checkpoint 加对话预算**：它存执行状态不存对话，语义不同不混管。

---

## 三、实施与验收

### 3.1 实施顺序

| 步骤 | 内容 | 改动面 |
|------|------|--------|
| 1 | \`PromptBudgetAdvisor\` + 两种截断策略 + 单测 | 新增 2 类 + 1 测试类 |
| 2 | 聚合/lead 调用点请求级挂载 | \`MultiAgentGraphAgent\` 2 处 |
| 3 | \`ContextAssemblingAdvisor\` 接入 TokenEstimator + 预算配置化 | 1 类 + yaml |
| 4 | 全量回归（179 用例）+ 新增用例 | — |

### 3.2 新增测试用例清单

- \`PromptBudgetAdvisorTest\`
  - 预算内直通（内容不改写）
  - 聚合语义：多段超预算 → 每份等额、头尾保留、带截断标记
  - 通用语义：单段超预算 → 尾部截断
  - 超限行为：输出 token ≤ 预算（估算口径自洽）
- \`MultiAgentGraphAgentTest\` 补充：聚合调用携带预算 advisor、lead 调用携带预算 advisor

### 3.3 验收标准

1. 编排链路每个进入模型的 prompt 都有明确 token 上界：lead ≤ 4k、子任务 ≤ 5k（工具结果）+ persona 常量、聚合 ≤ 12k、Agentic 路径 ≤ 4k（历史）+ 当轮消息。
2. \`LlmCallRecorder\` 观测到的 prompt token 与预算组件估算同口径、可比对。
3. 聚合超预算时每个子任务的结果在 prompt 中至少保留等额份额，截断有标记可追溯（log.warn）。
4. 全部预算参数可在 \`application-chat.yaml\` 调整，无需改代码。

---

## 四、路线图总览

| 优先级 | 事项 | 状态 |
|--------|------|------|
| ~~P0~~ | 工具结果 5k 预算 + 12 次硬封顶 | ✅ 已完成（\`ToolCallBudget\`） |
| ~~P0~~ | 工具 schema 白名单收窄 | ✅ 已完成（\`ToolAssignments\`） |
| P0 | \`PromptBudgetAdvisor\`（聚合 12k + lead 4k） | 📋 本方案 |
| P1 | 口径统一 + 预算配置化 | 📋 本方案 |
| P2 | lead 只读摘要注入：从 session memory 取最近 N 轮压缩 ≤1k 进 lead prompt（只读不写），补「编排丢会话上下文」缺口；实现时顺带处理 resume 重写造成的 memory 重复条目 | 📋 本方案 |
| P2 | map-reduce 分级摘要聚合 | 观察 P0 效果后决定 |
| P2 | 会话/观测表按天归档 | 低优先级 |

### P2 补充设计：lead 只读摘要注入

\`\`\`
lead prompt = persona（拆解约束）
            + 会话摘要（≤1k：session memory 最近 N 轮压缩，只读不写）
            + 用户目标
\`\`\`

- **只读不写**：memory 的唯一写入方保持为服务层 \`writeBackContext\`，lead 不回写，避免拆解 JSON 污染 general 对话历史
- **机械压缩优先**：保留用户消息 + assistant 回答首句，不引入额外 LLM 调用；质量不足再升级为 LLM 摘要
- **收益**：编排入口能感知之前聊定的偏好/口径与历史编排结论，「按我们刚才说好的做」这类指代目标不再失效

---

## 五、消费侧预算（输出封顶 + 编排消费上限熔断，2026-09-08 已实现）

输入侧防线（前四节）只管「喂给模型多少」，不管「模型吐出多少、一次编排总共烧多少」。本节补齐消费侧两道防线。全部预算数值的唯一来源是 \`application-chat.yaml\` 的 \`app.context\` 块（代码零默认，\`ContextBudgetProperties\` 只做绑定载体）；全键统一 **0 = 不限制**——该层预算关闭，行为等同无预算，暂时不需要某项限制时在 yaml 置 0 即可，无需改代码。

### 5.1 输出封顶：maxTokens 按角色分档（生成侧防失控）

在生成侧设 \`maxTokens\` 防失控（复读循环/跑题长文），**不做事后裁剪**（截断已生成文本会切掉结论、伤表达质量）。\`AgentChatCaller.buildSpec\` 按调用角色落档：

| 配置键 | 生产默认 | 适用调用 | 档位依据 |
|--------|---------|---------|---------|
| \`max-tokens-lead\` | 2000 | 编排 lead 拆解 | JSON 中间产物，本就该短 |
| \`max-tokens-final\` | 8000 | 编排聚合 + 路径 A 直出对话（\`GeneralAssistantAgent\`） | 两者同为直出用户的最终回答，共用一档 |
| \`max-tokens-expert\` | 4000 | 编排子任务专家（researcher/coder/analyst/writer/general） | 单路子任务结果 |

- 0 = 不限制：不写入 maxTokens，保持模型默认（全键统一口径，也是配置类缺省值）。
- 生效机制：请求级 \`OpenAiChatOptions.maxTokens\`，模型在生成侧截停在限额内；model 未配置时同样生效（只覆盖 maxTokens 单字段，不干扰客户端默认模型）。

### 5.2 编排全程消费上限：熔断（\`orchestration-budget\`）

单次编排（lead + N 子任务 + 聚合）的全程 token 消费上限，默认 60000：

\`\`\`
input 注入账本（AtomicLong 累计 + AtomicBoolean 估算标记，Replace 策略注册进 state）
  → lead：caller 门控句柄——发起调用前检查 + 每轮 roundtrip usage 帧增量记账并复检
  → 子任务（并行扇出限并发 subtask-concurrency，信号量排队错峰）：同上门控；
    未发起的在预检拒绝（零 HTTP），已在途的在下一轮 roundtrip 熔断断流
    （BudgetExceededException，Reactor 取消关闭 HTTP 连接），result 写
    「预算超限跳过」占位（与「子任务失败无结果」区分）
  → 聚合必发不受熔断（record-only 句柄，仅记账）：占位不计入真实结果，
    prompt 前置【预算降级说明】（N 个子任务未执行 + 已消耗/上限数字 + 消耗口径），
    聚合产出降级版最终回答，编排正常终态（非 FAILED）
\`\`\`

- **熔断下沉到 caller 的 roundtrip 粒度（2026-09-08 C1 评审修正）**：\`AgentChatCaller.BudgetLedger\` 在两个时点检查——发起调用前（零 HTTP 短路）与每轮 LLM roundtrip 的 usage 帧到达时（含单次 call 内部工具循环的每轮，超限即断流）。原「子任务批前检查」在并行扇出下各调用共享账本但检查只见 lead 消耗，生产默认配置下熔断结构性不可达，且单次 call 内多轮工具循环可绕过检查烧穿预算——roundtrip 粒度同时消除两个盲区（并行扇出下任一调用的消耗入账后，其余调用的下一轮 roundtrip 立即可见）。
- **同步累计，不依赖落库时序**：按 roundtrip 增量记账（真实 usage 帧 \`delta = total - prevTotal\`，同一调用栈内可读），\`llm_call_log\` 异步落库只承担观测，不承担熔断判定。
- **口径**：真实 usage 优先（streamUsage 末帧）；无 usage 回包按输出文本估算并置估算标记（与 \`llm_call_log.tokens_estimated\` 语义一致），降级说明注明「含估算值」或「真实 usage 统计」。流式与同步路径同口径（流式同样在 usage 帧增量记账 + 无 usage 时估算兜底）。
- **续跑**：断点续跑时新账本随 input 注入覆盖旧值（Replace 策略），已复用的历史节点消耗不计入本次账本（近似口径，可接受）。
- **并行竞态**：并行子任务共享同一账本实例（\`AtomicLong\` 写安全），熔断为近似判定——检查与累加非原子，超限瞬间在途调用至多再消耗一轮 roundtrip 即被断流；配合 \`subtask-concurrency\` 限并发错峰，在途超额窗口从「整批」收窄到「至多 N 个在途调用」。熔断目的是止损不是精确计量。

### 5.3 会话累计不设硬配额（评估结论）

单次消耗已被输入预算 + maxTokens 双侧封顶、单次编排已被 orchestration-budget 封顶，会话累计只反映正常用量增长。硬拦截（聊着聊着突然不能聊）体验伤害大且无安全收益（换 sessionId 即可绕过）。消费量观测由既有 \`llm_call_log\` / \`GET /api/llm-calls\` 承担，零新增代码。

### 5.4 验收对照

- 模拟编排内超限：剩余子任务零 LLM 调用（mock 零交互断言），聚合带降级说明正常终态 → \`MultiAgentGraphAgentTest.execute_overBudget_*\` / \`execute_withinBudget_*\`（同步 + 流式）
- 真实部分跳过（lead + 前序子任务累计超限 → 后续子任务跳过、前序结果保留进聚合）：\`MultiAgentGraphAgentTest.execute_partialBudgetExhaustion_skipsOnlyRemainingSubtasks\`（限并发=1 使跳过数确定）
- caller 门控/记账：发起前超限零 HTTP、usage 帧真实入账、无 usage 估算兜底、流式与同步同口径 → \`AgentChatCallerTest.call_ledgerPreCheckOverBudget_zeroHttp\` / \`call_realUsageFrame_recordsActualTokens\` / \`stream_reportsRealUsageToLedger\` / \`stream_ledgerPreCheckOverBudget_zeroHttp\`
- 档位映射：lead/final/expert 三档捕获 \`ChatOptions.maxTokens\` 断言；0 时不写入 → \`AgentChatCallerTest.call_maxTokens*\`

## 六、裁剪后的历史召回（2026-11-03）

预算裁剪不再等于永久丢失：

- 写回快照时每条消息带稳定 ID；\`session_messages\` 快照超条数/字符上限被移除的消息与单条截断尾部，
  先写入 \`session_message_archive\`（与快照更新同事务）。
- \`ContextAssemblingAdvisor\` 裁剪时把被丢消息的条数与 ID 示例追加到 system，提示模型「窗口外还有历史」。
- \`general\` / \`lead\` 分配 \`recall_history\` 工具：按关键词或精确消息 ID 检索归档；\`sessionId\`
  由 ToolContext 服务端注入，结果再经 \`ToolCallBudget\` 做 token 预算。默认 \`scope=current\` 只搜本会话；
  传 \`scope=all\` 跨全部历史会话按归档时间倒序召回，结果标注「会话 N / messageId」（2026-11-06，V11 \`idx_archive_time\` 支撑排序，非法 scope 直接拒绝而非静默退回 current）。
- 边界：单跳检索，无引用图/多跳展开；跨会话仍是关键词 LIKE（未向量化），未做滚动摘要；归档行不参与上下文预算的自动回填，
  只有模型显式调用工具时才占用预算。
`,cr="# Docker 部署\r\n\r\n> README「Docker 部署」一节的完整版：镜像构建、三件套启动、`.env` 配置、ACR 免构建拉取与离线 tar 部署。\r\n\r\n---\r\n\r\n\r\n不想装 JDK/Maven/数据库？`docker/` 目录提供三件套一键起：**app + MySQL 8.4（主库）+ pgvector（RAG 知识库）**，数据库连接等配置全部经环境变量注入，`application.yaml` 零改动。主库建表由应用内置 Flyway 在启动时自动完成（镜像自包含 DDL，DB 容器无需挂 init 脚本）；pgvector 扩展由 `docker/pg-init/` 在首次建库时自动启用。镜像获取有三种方式：**本地构建**（1️⃣）、**阿里云 ACR 拉取**（4️⃣，免构建推荐）、**离线 tar 导入**（5️⃣，无外网环境）。\r\n\r\n**1️⃣ 构建镜像**\r\n\r\n```bash\r\n# Docker 部署\r\n./docker/build-image.sh            # Linux / WSL\r\n.\\docker\\build-image.ps1          # Windows PowerShell\r\n# Docker 部署\r\nALIAS_LATEST=1 ./docker/build-image.sh 0.1.0\r\n\r\n# Docker 部署\r\ndocker build -f docker/Dockerfile -t deerfect-harness:local .\r\n\r\n# Docker 部署\r\ndocker build \\\r\n  --build-arg BUILD_BASE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-25 \\\r\n  --build-arg RUN_BASE=docker.m.daocloud.io/library/ubuntu:24.04 \\\r\n  -f docker/Dockerfile -t deerfect-harness:local .\r\n```\r\n\r\n三阶段构建：构建阶段在容器内 `mvn package`（`dependency:go-offline` 单独一层，pom 不变时命中 CI 的 gha 层缓存）→ `jlink` 按 jdeps 结果裁剪出最小 JRE（约 54MB，完整 JRE 200MB+）→ 运行阶段仅「裁剪运行时 + jar + `skills/knowledge/tools`」。\r\n\r\nMaven 默认走中央仓库；国内构建可加 `--build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public`（compose 侧同名环境变量），只镜像 central，不引入项目内的 `.mvn/settings.xml`（该文件不入仓库，镜像里也没有）。\r\n\r\n体积参考（2026-11）：fat jar 207MB（已排除 playwright `driver-bundle` 163MB 与 `tea-openapi` 误带的 jacoco agent）+ 运行时 54MB，整镜像比旧版（完整 JRE + 370MB jar）小约 280MB。\r\n\r\n**2️⃣ 启动三件套**\r\n\r\n```bash\r\n# Docker 部署\r\ndocker compose -f docker/docker-compose.yml up -d\r\n\r\n# Docker 部署\r\nIMAGE_TAG=sha-1c59846 docker compose -f docker/docker-compose.yml up -d --no-build\r\n\r\n# Docker 部署\r\n# Docker 部署\r\ndocker compose -f docker/docker-compose.yml logs -f app\r\n```\r\n\r\n**docker/.env 说明**\r\n\r\ncompose 启动时自动读取 compose 文件同目录的 `docker/.env`（`-f` 指定路径或 `cd docker` 两种方式都会读），配好后日常启动无需手动传 key。注意事项：\r\n\r\n- **优先级**：shell 环境变量 > `docker/.env` > compose 内默认值——临时覆盖某个值时前缀即可：`QWEN_API_KEY=sk-xxx docker compose ...`\r\n- **与 application.yaml 的关系**：jar 内 yaml 的库口令等敏感项本身就是 `${MYSQL_ROOT_PASSWORD:harness123}` 占位符写法（环境变量优先、冒号后为兜底默认值）；compose 把 `.env` 里的值同时喂给数据库容器（root 密码）和 app 容器（连接密码），两边天然一致，改口令只动 `.env` 一处（配合上条删卷注意）\r\n- **安全**：`.env` 含 API key 与数据库口令，已在 `.gitignore` 排除，**禁止提交**；换机器部署时把它一起拷走（变量清单与逐项注释见 `docker/.env` 本体）\r\n- **改库口令**：`MYSQL_ROOT_PASSWORD` / `PGVECTOR_PASSWORD` 修改后必须 `docker compose down -v` 删数据卷再 up，已初始化的旧卷不会自动应用新口令\r\n- **基础镜像**：`BUILD_BASE` / `RUN_BASE` 仅在 `--build` 重建镜像时生效，默认是官方地址（`maven:3.9-eclipse-temurin-25` / `ubuntu:24.04`）；Docker Hub 不可达时再覆盖为加速通道地址\r\n- **QQ 渠道**：不用 QQ 就设 `NAPCAT_ENABLED=false`；`NAPCAT_API_TOKEN` / `NAPCAT_EVENT_SECRET` 必须与 NapCat 侧配置一致，否则消息收不到/上报被拒\r\n- **邮件渠道**：不用就保持 `CHANNEL_EMAIL_ENABLED=false`（默认）；启用需设 `MAIL_HOST`（如 `smtp.qq.com`）、`MAIL_USERNAME` / `MAIL_PASSWORD`（**授权码，不是登录密码**），`MAIL_FROM` 可省略（默认等于 `MAIL_USERNAME`）。首次启用先发一封测试邮件确认收件箱而非垃圾箱——「能发出去」和「你会看到」是两件事\r\n- **飞书渠道**：不用就保持 `CHANNEL_FEISHU_ENABLED=false`（默认）；启用需设 `FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET`（群自定义机器人，开启签名校验）。`notify_channel` 写 `feishu:main` 别名，URL 不进业务表；缺 secret 时应用拒绝启动，这是刻意的不容错；长连接（在群里 @ 机器人对话）另设 `CHANNEL_FEISHU_WS_ENABLED=true` + `FEISHU_APP_ID` / `FEISHU_APP_SECRET`，并在自建应用里开通 `im:message` / `im:message.group_at_msg` / `im:message:send_as_bot` 后发布\r\n\r\n| 服务 | 容器名 | 说明 |\r\n|---|---|---|\r\n| app | `java-harness` | 8080 对外；挂载 docker.sock 沙箱可用（不需要可删该挂载） |\r\n| mysql | `harness-mysql` | 口令 `MYSQL_ROOT_PASSWORD`（默认 `harness123`）；3306 映射仅供宿主机管理工具，不需要可删 |\r\n| pgvector | `harness-pgvector` | 口令 `PGVECTOR_PASSWORD`（默认 `postgresql`）；宿主机 5432 被占时删映射（容器间走服务名直连） |\r\n\r\n**compose 相对路径规则**：volumes 里的相对路径一律以 **docker-compose.yml 所在目录（`docker/`）为基准**，与在哪个目录执行命令无关（项目根 `-f docker/docker-compose.yml` 跑也一样）。因此 `../knowledge` = 项目根/knowledge（`../` 从 docker/ 上跳一级到项目根），`./config` = `docker/config`、`./pg-init` = `docker/pg-init`；容器内挂载点全部对齐 WORKDIR `/workspace`，与 jar 内相对路径配置（`app.knowledge.dir: knowledge`、`napcat.emoji.dir: emojis`）正好衔接。`docker/.env` 能被自动读取也是同一条规则（compose 固定在自己的目录找 `.env`）。\r\n\r\n常用环境变量：`DEEPSEEK_API_KEY`；QQ 渠道 `NAPCAT_API_TOKEN` / `NAPCAT_EVENT_SECRET`，不用 QQ 设 `NAPCAT_ENABLED=false`；NapCat 在远端机器时设 `NAPCAT_BASE_URL=http://<host>:3000`（默认经 host-gateway 连宿主 Docker 里的 NapCat）；可选推送渠道 `CHANNEL_EMAIL_ENABLED` + `MAIL_USERNAME` / `MAIL_PASSWORD`、`CHANNEL_FEISHU_ENABLED` + `FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET`。\r\n\r\n**3️⃣ 验证与知识库初始化**\r\n\r\n```bash\r\ncurl -X POST http://localhost:8080/api/knowledge/sync   # 需真实 QWEN_API_KEY；向量数据入 pgvector\r\n```\r\n\r\n> [!NOTE]\r\n> - 全新 MySQL 由 Flyway 建表 + 种子 agent（当前基线 V13，见 [project-structure.md](../project-structure.md)）；**仅压平前旧库**的数据搬迁（自调的 agent 行、聊天历史）用：`mysqldump -uroot harness --no-create-info --skip-triggers --ignore-table=harness.flyway_schema_history > seed.sql`，再 `docker exec -i harness-mysql mysql -uroot -p<口令> harness < seed.sql`\r\n> - 上述 `mysqldump` 只导数据、不含 schema 与 `flyway_schema_history`，适用于 2026-10-30 压平前的旧库；当前新库直接由 Flyway V1–V11 建表升级，不要手工导入 schema\r\n> - 知识库/技能/表情/MCP 配置已**默认外挂**（compose volumes：`../knowledge`、`../skills`、`../emojis`、`../mcp-config.json`），改宿主机文件免重打镜像：`knowledge/` 改完调 sync 增量摄取（mtime 对比，免重启）；表情映射表改后 `restart app`、新增图片即时生效；`mcp-config.json` 改后 `up -d --force-recreate app`；批量调参写 `docker/config/application.yaml`（只写要改的键，改后 `restart app`，用法见该文件头注释）\r\n\r\n**镜像版本与回滚（版本策略）**\r\n\r\n- **镜像名**：统一 `deerfect-harness`（展示名 DeerFect-harness）；Docker/GHCR 仓库名强制小写，不能用大写或仓库名 `Deerfect_harness`\r\n- **tag 规则**：每次构建打 `sha-<git短sha>`；正式发布用语义化版本（如 `0.1.0`），可选同时打 `latest`。`latest` 只表示最近一次正式发布，**不能作为回滚依据**\r\n- **版本可追溯**：`docker inspect --format '{{json .Config.Labels}}' deerfect-harness:sha-xxx` 可看到 `org.opencontainers.image.version` 与 `revision`\r\n- **升级**：构建或拉取新 tag 后 `IMAGE_TAG=<新tag> docker compose -f docker/docker-compose.yml up -d --no-build`\r\n- **回滚**：目标机保留至少上一个版本的镜像（不要用 `docker image prune -a` 清掉），把 `IMAGE_TAG` 改回旧 tag 后执行同一条 up 命令\r\n- **数据库注意**：Flyway 只前进不后退（当前 V1–V11），镜像回滚前确认旧版本能跑新库（新增列/表通常兼容；删列/改类型不兼容）\r\n- **保留策略建议**：本地与仓库保留最近 3 个 `sha-*` 加全部语义化版本 tag\r\n\r\n**4️⃣ 从阿里云镜像仓库拉取（免构建、免传 tar）**\r\n\r\n镜像已托管在阿里云个人版 ACR **公开仓库**（国内服务器直连快，无需登录、无需 registry-mirrors），多台部署或频繁更新时比离线 tar 省事：\r\n\r\n```bash\r\nREG=crpi-udfqmnkb69y8dx8r.cn-hangzhou.personal.cr.aliyuncs.com/java-harness/harness\r\nTAG=sha-$(git rev-parse --short=8 HEAD)     # 与本地构建出的 tag 一致\r\n\r\n# Docker 部署\r\ndocker tag deerfect-harness:$TAG $REG:$TAG\r\ndocker push $REG:$TAG\r\n# Docker 部署\r\n\r\n# Docker 部署\r\ndocker pull $REG:$TAG\r\ndocker tag $REG:$TAG deerfect-harness:$TAG\r\nIMAGE_TAG=$TAG docker compose -f docker/docker-compose.yml up -d --no-build\r\n```\r\n\r\n> [!TIP]\r\n> 目标机仍需 `docker/` 目录（compose + pg-init + config）与两样不进 git 的文件——`mcp-config.json` 和 `emojis/` 下的表情图片（映射表由容器首次启动自动创建，无需补传），拷贝清单见 5️⃣。\r\n\r\n**5️⃣ 离线部署：导出镜像到目标 Linux 服务器**\r\n\r\n构建机与运行机不同（如构建在 Windows Docker Desktop、运行在 Linux 服务器）时，镜像包内含完整镜像层，目标机无需源码/Maven/JDK：\r\n\r\n```bash\r\n# Docker 部署\r\nTAG=sha-$(git rev-parse --short=8 HEAD)\r\ndocker save deerfect-harness:$TAG -o deerfect-harness-$TAG.tar\r\nscp deerfect-harness-$TAG.tar user@服务器IP:/opt/java-harness/\r\n```\r\n\r\n```bash\r\n# Docker 部署\r\nTAG=sha-xxxxxxxx                      # 与构建机导出的版本一致\r\ndocker load -i deerfect-harness-$TAG.tar\r\n# Docker 部署\r\nQWEN_API_KEY=sk-你的key \\\r\nIMAGE_TAG=$TAG docker compose -f docker/docker-compose.yml up -d --no-build\r\n```\r\n\r\n> [!IMPORTANT]\r\n> - 镜像包是给 `docker load` 读取的 OCI 格式（内含 blobs/、index.json、manifest.json），**不是压缩包——别用解压软件打开它**，`load` 直接读原封的 .tar / .tar.gz；Linux 构建机可 `docker save deerfect-harness:$TAG | gzip > deerfect-harness-$TAG.tar.gz` 减小传输体积，load 同样直接读\r\n> - **回滚依赖镜像包**：目标机保留上一版 tar 或本地镜像，回滚时 load 旧包并 `IMAGE_TAG=<旧tag>` 重启即可（`latest` 不参与回滚判断）\r\n> - **目标机必须同时拷贝 `docker/docker-compose.yml` 和 `docker/pg-init/`**——compose 把 `pg-init/` 挂载进 pgvector 容器，首次建库时预装 vector 扩展，缺了它知识库起不来；`Dockerfile`/`Dockerfile.dockerignore` 仅重建镜像时需要（整个 `docker/` 目录才几 KB，直接全拷也行）\r\n> - mysql/pgvector 基础镜像仍在目标机现拉：国内服务器先配 `/etc/docker/daemon.json` 的 `registry-mirrors` 并重启 docker，或同样 save/load 带过去\r\n> - compose 默认挂载的项目相对路径（`../knowledge`、`../skills`、`../emojis`）目标机项目里天然就有（git 跟踪）；但两样**不进 git** 的需补传：`mcp-config.json`（不传则 up 时 Docker 自动创建同名空目录占位，MCP 读取异常——需删掉目录放真文件）与 `emojis/` 下的表情图片（不传则表情功能静默失效，只会在日志里刷「表情文件缺失」）\r\n> - 架构需一致：Docker Desktop（WSL2）产物为 `linux/amd64`，x86_64 服务器通用；ARM 服务器用不了此镜像包，需在目标机重新构建\r\n\r\n\r\n\r\n## 镜像来源与版本号（与构建脚本 / CI 实际配置对照）\r\n\r\n| 来源 | 触发方式 | tag 形式 |\r\n| --- | --- | --- |\r\n| 本地构建 | `docker/build-image.sh`（或 `.ps1`） | `sha-<8 位短 sha>`（`git rev-parse --short=8`） |\r\n| GitHub Actions → GHCR | push `master` / `v*` | `sha-<7 位短 sha>`（metadata-action `type=sha,format=short`）+ `latest`（仅 master）+ `v*` 版本号 |\r\n| 阿里云 ACR | 手动 `docker push` | 沿用本地构建的 tag |\r\n\r\n> **短 sha 长度确实不同**：本地 8 位、CI 7 位，都是各自工具的真实输出。选版本时不要假设固定长度，用 `docker images` 或 `docker inspect --format '{{json .Config.Labels}}'` 里的 `org.opencontainers.image.revision` 核对。\r\n>\r\n> GHCR 包默认公开（匿名可拉）；若在 Packages 设置里改成私有，目标机需先 `docker login ghcr.io`。\r\n\r\n## 升级与回滚\r\n\r\n```bash\r\n# Docker 部署\r\ndocker pull ghcr.io/cxknotjj/deerfect-harness:sha-1c59846\r\nIMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness IMAGE_TAG=sha-1c59846 \\\r\n  docker compose -f docker/docker-compose.yml up -d --no-build\r\n\r\n# Docker 部署\r\nIMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness IMAGE_TAG=sha-<旧版本> \\\r\n  docker compose -f docker/docker-compose.yml up -d --no-build\r\n```\r\n\r\n- **Flyway 只前进不后退**：回滚镜像前确认旧版本能跑新库结构（加列/加表通常兼容；删列/改类型不兼容）。不确定时先在副本库验证。\r\n- **`latest` 只代表最近一次 master 构建**，不能作为回滚依据；保留最近 3 个 `sha-*` 与全部语义化版本。\r\n- **不要 `docker image prune -a`**：目标机上删掉旧 tag 就没有回滚点了。\r\n\r\n## 卷挂载与网络（逐项对齐 compose）\r\n\r\n| 宿主机 | 容器内 | 模式 | 用途 |\r\n| --- | --- | --- | --- |\r\n| `/var/run/docker.sock` | 同路径 | rw | 沙箱拉容器（不需要沙箱可删这一行） |\r\n| `../knowledge` | `/workspace/knowledge` | ro | RAG 语料，改完调 `POST /api/knowledge/sync` |\r\n| `../skills` | `/workspace/skills` | ro | skill 目录，mtime 热重载 |\r\n| `../tools` | `/workspace/tools` | ro | 声明式 HTTP 工具清单，改完调 `POST /api/capabilities/reload` |\r\n| `../emojis` | `/workspace/emojis` | rw | 表情图片与映射表（映射表缺失时自动创建） |\r\n| `../user-profile` | `/workspace/user-profile` | rw | 长期画像，应用写入 |\r\n| `../mcp-config.json` | `/workspace/mcp-config.json` | ro | MCP server 配置，改完 `up -d --force-recreate app` |\r\n| `./config` | `/workspace/config` | ro | Spring 外置覆盖，改完 `restart app` |\r\n| `./pg-init` | `/docker-entrypoint-initdb.d` | ro | 仅 pgvector 首次建库时执行 |\r\n\r\n| 端口 | 说明 |\r\n| --- | --- |\r\n| `8080 → 8080` | 应用 HTTP / SSE |\r\n| `3306 → 3306` | MySQL，仅供宿主机管理工具；不需要可删映射 |\r\n| `5432 → 5432` | pgvector，宿主机端口被占时删映射（容器间走服务名） |\r\n\r\n网络：默认 bridge；`extra_hosts: host.docker.internal:host-gateway` 让容器访问宿主机（默认可连宿主 NapCat 的 3000 端口）。\r\n\r\n## 故障排查\r\n\r\n| 现象 | 判断依据 | 处理 |\r\n| --- | --- | --- |\r\n| 容器反复重启，日志有 `绑定校验失败` / 路由无可用成员 | 库是全新初始化，迁移版本低于 V25 | 用新镜像重启触发 V25；或手工核对 `llm_route` 每条路由是否有启用成员 |\r\n| `/api/chat` 返回 401 / 403 | `llm_call_log.error_msg` 或日志里的供应商原文 | 401 = key 错/缺；403 `AllocationQuota` = 账号额度或「仅免费额度」开关 |\r\n| 健康检查 `db` DOWN | 容器到宿主 3306 不通，或口令不一致 | 核对 `MYSQL_ROOT_PASSWORD` 与 `SPRING_DATASOURCE_PASSWORD`；`docker compose down -v` 后旧卷不会应用新口令 |\r\n| 健康检查 `vectorDataSource` DOWN | pgvector 未起或口令不一致 | `docker compose ps` 看 pgvector；首次建库需 `pg-init` 挂载 |\r\n| 日志 `Failed to connect to Docker` | 单跑 `docker run` 未挂 socket | 挂 `/var/run/docker.sock`，或用 compose |\r\n| 8080 被占用 | `netstat`/`lsof` | 改 compose 端口映射，或停掉占用进程 |\r\n| 容器被 OOM kill | `docker inspect` 的 OOMKilled | compose 已限制 600m + 收紧 JVM；更小内存机需下调 `-Xmx` |\r\n| 时间显示 UTC | 容器无时区数据 | 镜像已内置 zoneinfo + `TZ=Asia/Shanghai`；自建镜像勿删该层 |\r\n| 拉取镜像慢 | — | 用 ACR 公开仓库，或离线 tar |\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n",dr=`# 首次可用性走查报告\r
\r
> 走查日期：2026-09-11。方法：以「第一次打开产品、零配置、不了解内部术语」的目标用户为起点，\r
> 围绕核心任务「**让机器人端到端回答第一句话**」做逐步走查；实测在隔离环境执行（独立端口 18080 +\r
> 独立测试库 \`harness_it\`，dummy key、无 NapCat、无 Docker、无 PostgreSQL——完整复刻全新机器状态），\r
> 未触碰真实 8080 实例与既有配置，未执行真实授权/对外发送/真实 LLM 调用，实测后环境已清理。\r
> 本报告区分三类结论：**【观察】**=隔离环境实测所见；**【推断】**=代码/数据佐证的推论；**【未验证】**=需真实环境确认。\r
>\r
> **历史提示（2026-10-28）**：本报告为时点记录，其中「Web Search / 前端 / 容器化 / 接口鉴权未开始」\r
> 等条目现已全部落地（Web Search 经 Tavily MCP、Web 控制台、Docker 三件套、双通道鉴权）；\r
> 当前功能面以 [features.md](../features.md) 为准；报告里的测试数、模块数同为时点值，当前测试与迁移基线见 [README](../../README.md) 与 [dev.md](../dev.md)。\r
\r
## 一、模块阶段判定\r
\r
| 模块 | 阶段 | 说明 |\r
|---|---|---|\r
| 双路径对话（RouteJudge + 编排/直答） | **开发（可运行）** | 首请求即达 LLM，链路完整 |\r
| CLI / REST / SSE 契约 | **开发（可运行）** | SSE 事件序（progress→error→meta）清晰 |\r
| 参数校验与统一错误体 | **开发（可运行）** | 中文文案明确 |\r
| 会话 / Goal / 观测落库 | **开发（可运行）** | llm_call_log 实测可定位根因（观测能力的正面验证） |\r
| QQ 渠道（NapCat） | **开发（可运行）** | 默认开启与「可选依赖」定位冲突（见问题 5） |\r
| 知识库 RAG | **开发，反馈面未闭环** | 检索降级 ✓；sync 失败反馈被吞（问题 3） |\r
| 沙箱工具 | **开发，降级未生效** | Docker 缺失时假就绪（问题 4） |\r
| Web Search | **设计（未开始）** | roadmap P1 待办 |\r
| 前端 / 容器化 / CI / Swagger / 接口鉴权 | **设计（未开始）** | roadmap P3 |\r
| 编排智能化（重规划/依赖编排） | **设计（未开始）** | roadmap P2 |\r
\r
## 二、核心任务的准备条件三分类\r
\r
| 类别 | 内容 | 现状 |\r
|---|---|---|\r
| **必须由用户决定/授权** | LLM API Key（QWEN_API_KEY，或改绑其他 provider 后对应 key）；一次性环境：JDK 25+Maven+MySQL | Key 有 README 三方式指引 ✓；但种子 agent 绑错端点使「只配 QWEN」走不通（问题 1） |\r
| **可用合理默认值** | 端口 8080、模型档位、token 预算、超时、限频、Flyway 建库表 | 全部已有合理默认 ✓ |\r
| **可稍后补充（不应成为首启负担）** | PostgreSQL 知识库、NapCat/QQ 渠道、Docker 沙箱、MCP、skills、多 agent 定制 | PG 检索降级 ✓；但 NapCat 默认 enabled=true 违背此原则（问题 5）；沙箱假就绪误导（问题 4） |\r
\r
## 三、问题清单（按对核心任务的影响排序）\r
\r
### 问题 1 【观察·高】种子数据把默认 agent 绑到 DeepSeek 端点——只配 QWEN key 走不通\r
\r
- **用户处境**：新用户按 README 快速开始只配置 \`QWEN_API_KEY\`（README 第 4 步只提 QWEN/DeepSeek，主推 QWEN）。\r
- **操作**：\`curl POST /api/chat {"message":"你好"}\`。\r
- **看到的反馈**：HTTP 200 + \`status:FAILED\` + 英文错误，\`llm_call_log\` 实证：route-judge→qwen3.8-27b(dashscope) 401 兜底 SIMPLE 后，general→**deepseek-v4-flash** 401。链路终止于 DeepSeek 鉴权失败。\r
- **为什么会卡住**：全新空库经迁移链（V1 种子 \`qwen3.7-plus\` → V4 改名 model_provider → V6 按 model 名回填 JOIN 不命中 → 后续迁移产生的绑定）最终落位 \`general.model_provider_id=8\`（deepseek-v4-flash）。README 的 key 指引与种子端点脱节——**这是首次使用走不通的根因**。\r
- **最小修改**（二选一，需决策）：\r
  - A. 新增迁移：仅当 \`general\`/\`researcher\` 绑定在 \`provider='deepseek'\` 行时改绑 \`qwen3.7-flash\` 行（存量库上用户自定义绑定的 general 会被此条件误改，需评估）；\r
  - B. 只修文档：README 快速开始明确「首次使用需检查 agent 表 general 行绑定的 model_provider 指向已配 key 的服务商」+ 提供一条核对 SQL。\r
- **验收**：全新库 + 仅配 QWEN_API_KEY → 首请求成功返回中文回答。\r
\r
### 问题 2 【观察·高】LLM 鉴权失败的反馈无下一步指引\r
\r
- **用户处境**：key 未配/配错，首次对话失败。\r
- **操作**：同步或流式首请求。\r
- **看到的反馈**：同步 \`HTTP 200\` + \`{"status":"FAILED","error":"401 - {\\"error\\":{\\"message\\":\\"Authentication Fails...\\"}}"}\`（上游英文原文）；流式 \`event:error\` + 同样英文原文。\r
- **为什么会卡住**：HTTP 200 让调用方以为成功；error 无中文引导；「配 QWEN_API_KEY 并重启」只存在于启动日志 WARN 与 README——不看日志的新用户无从下手。\r
- **最小修改**：错误映射层对 401/invalid_api_key 归一化，error 前缀追加中文指引（如「大模型鉴权失败：请配置对应服务商 API key 环境变量并重启服务，详见 README 快速开始」），上游原文保留其后；goal 表仍落原始错误。\r
- **验收**：零配置首请求的响应 error 首句为中文可执行指引。\r
\r
### 问题 3 【观察·中】知识库 sync 在 PG 不可用时返回 500「服务器内部错误」\r
\r
- **用户处境**：没装 PostgreSQL 的新用户看到 README 知识库章节，把文档放进 knowledge/ 后调 sync。\r
- **操作**：\`POST /api/knowledge/sync\`。\r
- **看到的反馈**：\`{"code":500,"message":"服务器内部错误"}\`——内部可读的 IllegalStateException（「向量库不可用」语义）被 GlobalExceptionHandler 兜底吞掉，与代码注释「同步报清晰错误」的设计意图脱节。\r
- **最小修改**：GlobalExceptionHandler 对该路径（或 IllegalStateException 总类）透出异常 message 作为 response message（保留 500 或改 503）。\r
- **验收**：PG 未配置时 sync 返回含「PostgreSQL 未就绪/未启用」语义的中文 message。\r
\r
### 问题 4 【观察·中】Docker 缺失时沙箱假就绪并注册 12 个工具\r
\r
- **用户处境**：无 Docker 的机器（README 标 Docker 为「沙箱必需」⚠️）。\r
- **操作**：启动应用后让 agent 执行代码。\r
- **看到的反馈**：启动日志 Docker 连接 ERROR 两条后紧跟 INFO「[sandbox] 容器级沙箱就绪: 执行类=2 只读=6 写入=4」——工具面注册成功；实际调用才会暴露深层 Docker 异常。与 README「无 Docker 时仅沙箱类工具不可用，其余功能正常」的承诺矛盾。\r
- **为什么会卡住**：降级设计（失败降级空工具面）未生效于「连接失败」分支，模型会持续尝试调用必失败的工具。\r
- **最小修改**：SandboxToolProvider 初始化时探测 Docker 可达性，不可达则降级空沙箱工具面 + WARN 明示「未检测到 Docker，沙箱类工具未启用（安装 Docker 后重启恢复）」。\r
- **验收**：无 Docker 启动日志不再出现「沙箱就绪」，且模型工具索引段不含沙箱工具。\r
\r
### 问题 5 【观察·低】NapCat 默认开启使可选依赖变成首启必现告警\r
\r
- **操作**：全新机器启动（napcat.enabled 默认 true，api-base-url 127.0.0.1:3000）。\r
- **看到的反馈**：自检 WARN 三连「get_login_info 调用异常（第 1/2 次）…启动自检失败：retcode=-1（QQ 发送将不可用，检查 NapCat/token）」。\r
- **认可点**：不阻断启动、文案有一定可读性。\r
- **卡点**：不用 QQ 渠道的新用户不知道这是可关闭的可选组件（无「设 napcat.enabled=false 可消除」的出口提示）。\r
- **最小修改**：README 快速开始加一行「不接入 QQ 渠道时在 \`application-qq.yaml\` 设 napcat.enabled=false」；自检失败 WARN 尾部追加同一提示。\r
- **验收**：无 NapCat 全新启动后，用户能从告警或 README 一眼找到关闭方式。\r
\r
### 问题 6 【推断·低】非法 agentId 静默回退默认 agent\r
\r
- **操作**：\`agentId=999\`（不存在）请求 → 返回与默认请求相同的结构，无「agent 不存在」提示（AgentService 兼容兜底回退 general，有 javadoc 背书，行为本身合理）。\r
- **最小修改（可选）**：响应体增加 \`agentResolved\` 字段透出实际使用的 agent 名，让「回退」可观察。\r
- **验收**：传入无效 agentId 时响应可区分「用了哪个 agent」。\r
\r
### 未验证项\r
\r
- 配置真实 key 后的端到端对话（本次走查不执行真实 LLM 调用/对外发送）；按数据推断：仅配 QWEN key 时因问题 1 仍失败，双 key（QWEN+DEEPSEEK）可走通——与真实环境「能跑通」的现象吻合。\r
- CLI 端在服务端未启动/错误事件下的终端渲染细节（代码走查确认 ChatApiClient 抛 ApiException 含服务端 message，渲染文案未实测）。\r
- 真实用户测试未做：本报告为 AI 走查 + 隔离实测，不等于用户认可。\r
\r
## 四、认可的已有设计（保持不动）\r
\r
- 快速开始文档的环境要求分级（必需/沙箱/可选）与 key 三方式指引；\r
- 空参数校验反馈（\`{"code":400,"message":"message 不能为空"}\`，中文、准确）；\r
- SSE 错误契约（progress→error→meta 事件序完整，错误后 meta 终态不悬挂）；\r
- 启动期 dummy key WARN 的中文指引；NapCat 自检失败不阻断启动；PG 检索静默降级不影响启动与聊天；\r
- llm_call_log 观测：本次走查正是靠它完成根因定位（agent_name/model/error 三列直指问题）。\r
\r
## 五、首次使用流程规则（新增功能复查清单）\r
\r
后续新增功能/入口合入前，按此清单自查：\r
\r
1. 新功能的**前置条件**按「用户授权/默认值/稍后补充」三分类标注在 README；可选项不得默认开启成首启告警源；\r
2. 新增 DB 种子/迁移后，必须在**全新空库**上走一遍首请求冒烟（参考本报告隔离实例方法），核对实际绑定的 provider 与文档 key 指引一致；\r
3. 所有失败路径的用户可见反馈必须含：**普通语言的原因 + 可执行的下一步**；HTTP 状态与业务状态不得矛盾（失败不用 200 包装——现有结构经 \`status\` 字段表达，需保证调用方能程序化识别）；\r
4. 可选依赖缺失时：功能面**不注册**（而非注册后调用失败），启动日志给关闭/安装出口；\r
5. 回退/降级行为（agent 回退、检索降级、工具降级）在响应或日志中可观察，不静默；\r
6. 每次走查结论追加到本报告「六、走查记录」。\r
\r
## 六、走查记录\r
\r
- 2026-09-11：首次全量走查（隔离实例 18080 + harness_it 库 + dummy key + 无 NapCat/Docker/PG）。实测项：启动日志、同步/流式首请求、参数校验、非法 agentId、knowledge sync；问题 1-6 如上；实测环境已清理（实例停止、测试库删除）。修复情况：全部待修复。\r
- 2026-09-11（下午）：首轮问题修复落地。决策：问题 1 采用**方案 B 仅改文档**（迁移改绑会波及真实库既有绑定，用户决策）；问题 6 一并修复。明细与验收：\r
  - **问题 2**：新增 \`ModelAuthException\`（镜像 \`ModelQuotaException\` 模式），AgentChatCaller 阻塞/流式两处识别 401 并转可执行指引（配哪个 key、重启、README 入口，厂商原文保留其后），GlobalExceptionHandler 映射 502。单测 \`ModelAuthExceptionTest\`（4 例）✓\r
  - **问题 3**：GlobalExceptionHandler 新增 \`IllegalStateException\` → 503 透出 message，知识库 sync 的「向量库未装配/写入失败」不再被兜底吞成「服务器内部错误」。单测 \`GlobalExceptionHandlerTest\`（6 例）✓\r
  - **问题 4**：\`SandboxToolProvider.init()\` 前置 Docker 真实 dial（复用 agentscope 同源 docker-java 客户端 \`connect()\` = docker info，无容器/镜像副作用），不可达降级空沙箱工具面 + WARN 出口提示，不再假就绪。单测 \`SandboxToolProviderTest.dockerUnreachable\` ✓\r
  - **问题 5**：NapCat 自检失败 WARN 尾部追加「设 napcat.enabled=false 关闭」出口提示；README 快速开始同款 NOTE ✓\r
  - **问题 6**：\`ChatResponse\` 新增 \`agent\` 字段透出实际使用的 agent 名（含显式 agentId 未命中回退场景），同步路径成功/失败响应均透出 ✓\r
  - **问题 1（B）**：README 快速开始新增「首次对话前核对 agent 绑定服务商」IMPORTANT 块 + 核对/改绑 SQL（agent_name / model_provider_id / provider）✓\r
  - 回归：全量 \`mvn test\` 435 例 0 失败 0 错误（1 例环境跳过）✓\r
  - 未验证项不变：真实 key 端到端对话、CLI 渲染细节、真实用户测试（本报告仍为 AI 走查 + 隔离实测，不代表用户认可）。\r
`,mr='# 功能测试说明\r\n\r\n> 本文档介绍 Harness 各模块的功能测试内容：每个模块测什么、怎么测、边界在哪。\r\n> 测试代码位于 `src/test/java`，与业务代码同步演进；本文以实际编码为准。\r\n> 更新日期：2026-11-06。\r\n\r\n***\r\n\r\n## 一、总览\r\n\r\n> 2026-11-12 全量实跑 1474 例（1 skip）：deerfect-api 52、deerfect-server 1082、channel-qq 85、channel-email 15、channel-feishu 152、deerfect-cli 30、wms-mcp-adapter 58。类数与逐类明细以各模块 `target/surefire-reports` 为准，下方表格为历史整理版本。\r\n\r\n**入口与异常**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `ChatControllerTest` | 入口层 SSE HTTP 输出契约 | 2 |\r\n| `HarnessControllerTest` | 管理接口（新建会话/切换会话 Agent） | 4 |\r\n| `KnowledgeAdminControllerTest` | 知识库管理接口（列表/上传/删除/同步） | 12 |\r\n| `GlobalExceptionHandlerTest` | 全局异常响应结构（含流式超限/断连） | 8 |\r\n| `ModelQuotaExceptionTest` | 配额类硬错误识别与人话转换（402/403） | 6 |\r\n| `ModelAuthExceptionTest` | 鉴权类硬错误识别（401） | 4 |\r\n| `JavaHarnessApplicationTests` | Spring 容器冒烟（contextLoads） | 4 |\r\n| `ContextCompositionControllerTest` | 会话上下文构成监控端点 | 2 |\r\n| `SkillControllerTest` | 技能管理端点 | 1 |\r\n| `MemoryControllerTest` | 显式记忆接口（状态过滤 / 来源固定 manual / 删除校验） | 7 |\r\n\r\n**配置与安全**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `ChatTimeoutPropertiesTest` | 聊天超时配置绑定与兜底 | 4 |\r\n| `ContextBudgetPropertiesTest` | 上下文预算配置（clamp / 0=不限） | 3 |\r\n| `StreamConnectionLimiterTest` | 流式连接数上限计数 | 4 |\r\n| `ClientAbortLogFilterTest` | 断连日志降噪过滤器 | 8 |\r\n| `ApiTokenFilterTest` | 机器通道鉴权（X-API-Token 双通道） | 8 |\r\n| `AuthControllerTest` | 口令登录/登出（失败限流锁定） | 8 |\r\n| `AuthControllerFailureCleanupTest` | 登录失败计数按窗口清理（过期清除 / 锁定中保留） | 3 |\r\n| `LoginStateStoreTest` | 登录态存储（滑动续期/吊销） | 6 |\r\n| `ChatClientFactoryTest` | ChatClient 工厂 API Key 解析（yaml 映射/环境变量回退） | 5 |\r\n| `LlmClientRegistryTest` | 按 model_id 缓存/失效/热替换（不按模型名查找） | 5 |\r\n| `ModelResolverTest` | 路由成员顺序、禁用过滤、空路由 | 5 |\r\n| `LlmAdminServiceTest` | 管理面校验、热更新失败、路由连通性分类 | 11 |\r\n| `ThinkingSwitchChatModelTest` | 思考开关模型层注入（enable_thinking 显式声明优先） | 5 |\r\n| `ArchitectureTest` | 分层依赖、LLM 表访问边界与客户端隔离守卫 | 8 |\r\n\r\n**契约层（deerfect-api）**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `AgentServiceContractTest` | AgentService 端口行为契约 | 6 |\r\n| `GoalStateMachineTest` | Goal 状态机迁移合法性 | 9 |\r\n| `PortSignatureGuardTest` | 端口方法签名快照（防契约漂移） | 3 |\r\n| `SessionServiceContractTest` | SessionService 端口契约（含内存替身） | 10 |\r\n| `ViewDtoJsonContractTest` | 视图 DTO 的 JSON 序列化契约 | 12 |\r\n\r\n**Service 层**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `ChatServiceImplTest` | 聊天用例编排（会话/记忆/SSE/路由/进度/会话 Agent 同步） | 47 |\r\n| `AgentServiceImplTest` | Agent 执行路由与 Goal 生命周期 | 19 |\r\n| `GoalServiceImplTest` | 启动清理僵尸 RUNNING 目标（保证 /resume 不被 409 拦截） | 7 |\r\n| `SessionServiceImplTest` | 会话切换 Agent + 删除会话（软删/快照清理/渠道绑定端口清理） | 28 |\r\n| `AgentConfigProviderTest` | agent 表配置读取（id→名称映射/模型+提示词） | 21 |\r\n| `ProviderAdminServiceImplTest` | 供应商映射管理（新增/更新/热刷新） | 7 |\r\n| `LlmRouteJudgeTest` | 主 Agent 前置判断（LLM 分流） | 18 |\r\n| `RagPrefetcherTest` | RAG 入口预取 | 12 |\r\n| `LlmCallRecorderTest` | LLM 调用观测（token 估算/异步落库/失败隔离） | 10 |\r\n| `ObservabilityLogCleanerTest` | 观测表保留期清理 | 5 |\r\n| `ObserveQueryServiceImplTest` | 观测查询收口（三表过滤/limit 收敛/id 倒序） | 6 |\r\n| `GoalRetrySchedulerTest` | 失败 Goal 退避重投排期 | 5 |\r\n| `MessageSenderRegistryTest` | 主动推送渠道路由（channel:target / 降级） | 7 |\r\n| `ProviderRulesTest` | 服务商配置规则 | 4 |\r\n| `ScheduleServiceImplTest` | 定时任务扫描 / cron 校验 / 推进 | 19 |\r\n| `StaleGoalWatchdogTest` | 停滞 Goal 标记 FAILED | 3 |\r\n| `DataRetentionCleanerTest` | 业务数据保留期（归档分批 / 目标级联删检查点 / 失败不抛） | 5 |\r\n| `MemoryServiceImplTest` | 显式记忆：同 key 覆盖 / 软删 / 状态判定 / 关键词定位 | 18 |\r\n| `MemoryRepositoryIntegrationTest` | 显式记忆仓储集成（真实 MySQL：软删 / 过期 / 同 key 覆盖的 SQL 行为） | 8 |\r\n\r\n**Agent 层（调用与编排）**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `AgentChatCallerTest` | 编排调用器（工具幻觉容错重试/异常透传） | 22 |\r\n| `AgentChatCallerRetryTest` | 编排调用器失败自动重试（指数退避） | 5 |\r\n| `AgentChatPipelineTest` | 流管道核（watchdog/帧记账/空响应防御） | 6 |\r\n| `AgentRegistryTest` | Agent 注册表（表驱动自动注册/内部角色排除） | 11 |\r\n| `GeneralAssistantAgentTest` | Agentic 路径回退（ChatClient）真·逐 token 透传契约 | 7 |\r\n| `GeneralAssistantAgentScaffoldTest` | Agentic 路径回退装配脚手架 | 7 |\r\n| `LlmCallObserverTest` | 调用观测值对象（估算标记/usage 帧） | 2 |\r\n| `LlmErrorClassifierTest` | 4xx 客户端错误分类（连接池失效判定） | 4 |\r\n| `LlmRequestLogAdvisorChainTest` | 请求日志 advisor 链 | 1 |\r\n| `LlmRetryTest` | LLM 重试策略（可重试判定/退避/耗尽抛错） | 12 |\r\n| `ProgressLineTest` | 进度行线协议编解码 | 4 |\r\n| `MultiAgentGraphAgentTest` | Graph 编排路径多 Agent 编排 + 流式进度 + 记忆注入 + 断点续跑 | 43 |\r\n| `BranchProgressListenerTest` | 并行分支钩子旁路（结局登记/串行发射） | 4 |\r\n| `LeadOutputParserTest` | lead 拆解 JSON 解析（专家白名单/brief） | 13 |\r\n| `ThinkingThrottleTest` | 子任务思考内容合帧节流 | 5 |\r\n| `AgentScopeGeneralAgentTest` | Agentic ReAct 路径装配 | 3 |\r\n| `CheckpointSelectorTest` | 断点续跑检查点选择 | 4 |\r\n| `OrchestrationContractTest` | 编排端口契约 | 2 |\r\n| `OrchestrationNodesResolvePacksTest` | 子节点工具包解析 | 5 |\r\n| `OrchestrationStateTest` | 编排状态流转 | 2 |\r\n| `ParallelOrchestrationEngineTest` | 并行编排引擎调度 | 4 |\r\n\r\n**Advisor 与 Prompt**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `ContextAssemblingAdvisorTest` | 上下文组装（过滤/归一化/token 预算） | 12 |\r\n| `PromptBudgetAdvisorTest` | 静态 prompt 预算（超限尾部截断+截断标记） | 8 |\r\n| `LlmRequestLogAdvisorTest` | 请求日志拦截器 | 3 |\r\n| `PromptAssemblerTest` | system 提示词分段组装（固定次序/优先级） | 19 |\r\n| `MemoryPolicyTest` | 会话记忆注入的角色策略（lead/general 注入，其余不注入） | 8 |\r\n| `SkillManagerTest` | skill 装配管理（索引段/热重载） | 22 |\r\n| `SkillRepositoryTest` | skill 文件仓库（front-matter 解析） | 19 |\r\n| `ToolLazyManagerTest` | 工具 Schema 延迟加载（轻量包装/同请求自愈） | 13 |\r\n| `ActiveGoalSectionProviderImplTest` | 活跃目标段渲染 | 3 |\r\n| `ContextCompositionAdvisorChainTest` | 上下文构成 advisor 链 | 1 |\r\n| `ContextCompositionAdvisorTest` | 上下文构成快照采集 | 1 |\r\n| `ContextCompositionCalculatorTest` | 分段 token 占比计算 | 2 |\r\n| `ContextCompositionStoreTest` | 上下文构成内存快照 | 4 |\r\n| `TokenEstimatorTest` | token 估算口径 | 2 |\r\n\r\n**知识库与记忆**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `KnowledgeRetrieverTest` | 检索注入（混合检索/出处渲染/降级） | 31 |\r\n| `KnowledgeServiceImplTest` | 增量摄取/删除/分页/检索 | 38 |\r\n| `KnowledgeDocumentScannerTest` | 知识目录扫描 | 7 |\r\n| `MarkdownChunkerTest` | 段落感知切分 | 7 |\r\n| `KnowledgeBm25IndexTest` | BM25 内存索引 | 11 |\r\n| `KnowledgeDirectoryWatcherTest` | 目录监听自动摄取（防抖） | 7 |\r\n| `UserProfileServiceTest` | 用户画像增量提炼（消息游标 / 按 key 合并 / 备份恢复 / 降级） | 15 |\r\n| `UserProfileSectionProviderTest` | 画像段注入 | 5 |\r\n| `RecallHistoryToolsTest` | recall_history 工具（scope / 关键词 / ID / 预算） | 9 |\r\n| `SessionHistoryArchiveServiceTest` | 归档检索（会话内 / 跨会话 / 排序 / 上限） | 6 |\r\n| `SessionSummaryServiceTest` | 会话滚动摘要（游标增量合并 / 失败不推进 / 空输出跳过） | 8 |\r\n| `ExplicitMemorySectionProviderTest` | 显式记忆段注入（角色过滤 / 截断 / 优先级提示） | 6 |\r\n\r\n**工具层**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `ToolAssignmentsTest` | 工具分配表（双通道/最小权限） | 17 |\r\n| `DefaultToolDecoratorsTest` | 默认装饰链装配 | 2 |\r\n| `SkillMetaToolDecoratorTest` | load_skill 元工具 | 3 |\r\n| `ToolDecorationContextTest` | 装饰上下文 | 1 |\r\n| `ToolLazyLoadDecoratorTest` | 懒加载装饰器 | 2 |\r\n| `ToolBudgetDecoratorTest` | 工具预算装饰器 | 5 |\r\n| `ToolCallBudgetTest` | 工具调用硬预算（超限拦截，返回引导文本） | 7 |\r\n| `ToolCallTracerTest` | 工具调用事件追踪（装饰/schema 透传） | 22 |\r\n| `ToolObservationDecoratorTest` | 工具观测装饰器 | 3 |\r\n| `McpToolProviderTest` | MCP 工具懒连接与失败降级 | 17 |\r\n| `McpConfigParserTest` | MCP 配置解析与 URL 日志脱敏 | 4 |\r\n| `WebToolsTest` | 网页抓取工具 | 16 |\r\n| `SandboxToolProviderTest` | 沙箱探活自愈与工具面重建（无 Docker 不阻塞请求线程） | 17 |\r\n| `EdgeTtsClientTest` | Edge TTS 子进程调用（超时 / 并发） | 8 |\r\n| `FileNameRulesTest` | 文件名安全规则 | 5 |\r\n| `InMemoryVoiceArtifactStoreTest` | 语音工件内存存储 | 4 |\r\n| `SandboxGuardsTest` | 沙箱路径归一化与危险命令拦截 | 12 |\r\n| `TextSanitizerTest` | 文本清洗 | 9 |\r\n| `TextToSpeechToolsTest` | text_to_speech 工具入口 | 7 |\r\n| `MemoryToolsTest` | remember / forget / list_memories（多命中不删 / 参数校验） | 11 |\r\n\r\n**QQ 渠道（channel/qq）**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `OneBotEventServiceImplTest` | 事件处理（自消息过滤/@ 触发/限频/会话绑定/分段） | 44 |\r\n| `OneBotEventControllerSignatureTest` | 上报端点签名校验 | 5 |\r\n| `NapCatApiClientImplTest` | NapCat HTTP 客户端 | 11 |\r\n| `EmojiRepliesTest` | 表情包匹配与发送 | 8 |\r\n| `ChannelEventPolicyTest` | 渠道事件准入规则 | 4 |\r\n| `CommandParserTest` | QQ 指令解析 | 4 |\r\n| `MessageDedupCacheTest` | 上报消息去重 | 3 |\r\n| `SentMessageTrackerTest` | 已发送消息追踪（防自回环） | 4 |\r\n\r\n\r\n**CLI 与 WMS 适配器**\r\n\r\n| 测试类 | 覆盖功能域 | 用例数 |\r\n| --- | --- | --- |\r\n| `CallerTokenFilterTest` | WMS 适配器调用方 token 校验 | 6 |\r\n| `ResumeStateStoreTest` | CLI 断点续跑状态读写 | 4 |\r\n| `TerminalInputTest` | CLI 输入（无 TTY 降级） | 5 |\r\n| `TerminalRendererTest` | CLI 流式 Markdown 渲染 | 19 |\r\n| `WmsRestClientTest` | WMS 只读 REST 客户端 | 13 |\r\n| `WmsToolListGuardTest` | WMS 工具白名单守卫 | 4 |\r\n| `WmsToolsTest` | WMS 语义工具集 | 20 |\r\n\r\n**合计：1474 个用例（2026-11-12 全量实跑，1 skip；分模块见「总览」）。下方明细表为历史整理版本，类数/用例数以 surefire 报告为准。除 `JavaHarnessApplicationTests` 需本机 MySQL 外，其余不依赖真实 DB/外网。**\r\n\r\n***\r\n\r\n## 二、运行方式\r\n\r\n```bash\r\n# 功能测试说明\r\nmvn -s .mvn/settings.xml test\r\n\r\n# 功能测试说明\r\nmvn -s .mvn/settings.xml test -Dtest=MultiAgentGraphAgentTest\r\n\r\n# 功能测试说明\r\nmvn -s .mvn/settings.xml test -Dtest=ChatServiceImplTest#streamReactive_shouldMapProgressRowToProgressEvent\r\n```\r\n\r\nIDEA 中直接点测试方法旁的绿色按钮即可；报告输出在 `target/surefire-reports/`。\r\n\r\n***\r\n\r\n## 三、分模块说明\r\n\r\n> 本节只展开**代表性模块的核心用例**（非全量清单）：全量以「一、总览」表和 surefire 报告为准，新增用例请先同步总览表。\r\n\r\n### 1. `LlmRouteJudgeTest` —— 主 Agent 前置判断\r\n\r\n验证「路径判定」这个核心薄环：规则短路 → 灰区 LLM 返回富 JSON（`route/confidence/workstreams/deliverables/reason`）→ 置信度策略收敛，最终决定走 Agentic 还是 Graph 编排（内部枚举 `SIMPLE` / `COMPLEX`）。\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `plan_whenLlmReturnsGraph_shouldKeepGraph` | 返回 `{"route":"graph",...}` → 判定 Graph 路径 |\r\n| `plan_whenLlmReturnsAgentic_shouldReturnAgentic` | 返回 `agentic` → 判定 Agentic 路径 |\r\n| `plan_whenMessageShort_shouldShortCircuitToAgentic` | ≤12 字短消息 → 规则短路 Agentic，**不发起 LLM 调用** |\r\n| `plan_whenMultiWorkstreamPhrase_shouldShortCircuitToGraph` | 多工作流措辞 → 规则短路 Graph，**不发起 LLM 调用** |\r\n| `plan_whenEnglishParallelPhrase_shouldShortCircuitToGraph` | 英文并行措辞（separately/for each 等）→ 规则短路 Graph |\r\n| `plan_whenLlmGraphWithEnoughWorkstreamsAndConfidence_shouldKeepGraph` | Graph + ≥2 工作流 + 置信度达标 → 保持 Graph，字段完整透出 |\r\n| `plan_whenGraphConfidenceBelowThreshold_shouldCollapseToAgentic` | Graph 但置信度 <0.75 → 收敛 Agentic（FALLBACK） |\r\n| `plan_whenGraphConfidenceAtLoweredThreshold_shouldKeepGraph` | 置信度 0.78（新阈值区间）→ 保持 Graph |\r\n| `plan_whenWorkstreamsNearDuplicates_shouldCollapseToAgentic` | 近重复工作流（空格/标点差异）去重后 <2 → 收敛 Agentic |\r\n| `plan_whenGraphHasSingleWorkstream_shouldCollapseToAgentic` | Graph 但工作流 <2 → 收敛 Agentic（FALLBACK） |\r\n| `plan_whenLlmReturnsInvalidJson_shouldFallbackAgentic` | 非 JSON 输出 → 兜底 Agentic |\r\n| `plan_whenCallThrows_shouldFallbackAgenticAndDropPool` | LLM 调用抛异常 → 兜底 Agentic，丢弃黑洞连接池 |\r\n| `plan_whenMessageBlank_shouldReturnAgenticWithoutCall` | 消息为空 → 直接 Agentic，**不发起 LLM 调用** |\r\n\r\n**核心设计**：任何失败都兜底 Agentic——「宁可轻量，不阻塞请求」；Graph 只在「多工作流 + 高置信度」时启用。\r\n\r\n### 2. `MultiAgentGraphAgentTest` —— Graph 编排路径\r\n\r\nmock `AgentCatalog` / `ModelResolver` / `LlmClientRegistry` 与 `ChatClient` 固定 content（lead 固定返回两条子任务的 JSON），整图执行不真实出网。\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `execute_shouldBuildGraphAndReturnFinalAnswer` | 同步路径：引擎执行返回最终回答非空 |\r\n| `execute_returnsNonEmptyForAggregate` | 聚合节点把子任务结果汇总为最终回答并原样返回 |\r\n| `executeStreamReactive_emitsProgressRowsAndContent` | 流式路径：进度行（`MARK` 前缀）与内容行混合发射，两类都有 |\r\n| `executeStreamReactive_streamsStageEventsInOrder` | **阶段时序断言**：首行=「编排」→ 存在「拆解」→ ≥2 条「子任务完成」（钩子补齐并行分支，且都在最终回答之前）→ 内容行前紧邻「聚合」，流以最终回答收尾 |\r\n\r\n**难点覆盖**：「子任务完成」事件靠 `BranchProgressListener` 在引擎取到结果后逐条播报；时序用例专门防回归。\r\n\r\n### 3. `ProgressLineTest` —— 进度行线协议\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `encode_isProgress_shouldBeTrue` | encode 产物以 MARK 开头，被识别为进度行 |\r\n| `encode_decode_shouldRoundTrip` | encode → decode 往返还原 stage/detail |\r\n| `isProgress_shouldBeFalseForPlainContent` | 普通内容 token 不误判为进度行 |\r\n| `decode_shouldReturnNullForPlainContent` | 解码普通内容返回 null（不抛异常） |\r\n\r\n### 4. `ChatServiceImplTest` —— 聊天用例编排\r\n\r\n覆盖 `/api/chat` 与 `/api/chat/stream` 的完整业务规则。\r\n\r\n**会话建档与记忆写回：**\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `chat_withoutSessionId_shouldCreateNewSession` | 无 sessionId → 自动建档，`newSession=true` |\r\n| `chat_withSessionId_shouldReuseSession` | 带 sessionId → 复用不重复建档 |\r\n| `chat_syncSuccess_shouldWriteBackContext` | 同步成功后 user/assistant 写回 `session_messages` |\r\n| `chat_failure_shouldNotWriteBackContext` | FAILED 不污染多轮记忆 |\r\n| `streamReactive_success_shouldWriteBackContext` | 流式成功后同样写回（收集完整回复后统一写入） |\r\n| `streamReactive_onError_shouldNotWriteBackContext` | 流式出错不写回 |\r\n\r\n**SSE 契约与分流：**\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `streamReactive_emitsSseTokensAndMeta` | 逐 token `data:` → `data: [DONE]` → `event: meta` 收尾顺序 |\r\n| `streamReactive_onError_emitsErrorEvent` | 出错改发 `event: error` + `meta.status=FAILED`，调用方不悬挂 |\r\n| `chat_shouldInvokeMainAgentRouteJudge` | 同步入口确实经过 RouteJudge 分流 |\r\n| `streamReactive_shouldInvokeMainAgentRouteJudge` | 流式入口同样经过分流 |\r\n| `streamReactive_withAgentId_shouldRouteByAgentId` | 显式 agentId 优先于路由结果 |\r\n\r\n**进度流式化（Graph 编排体验）：**\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `streamReactive_shouldMapProgressRowToProgressEvent` | 进度行 → `event: progress` + `data: {"stage":..,"detail":..}`，且**不计入**会话摘要 |\r\n\r\n**路由策略与降级：**\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `streamReactive_routingGraphOverride_shouldOrchestrateWithoutJudge` | 显式 `routing=graph` → 不询问判定器直接编排，meta 透出 `path=graph` / `source=OVERRIDE` |\r\n| `streamReactive_routingAgenticOverride_shouldDirectAnswer` | 显式 `routing=agentic` → general 直答，meta 透出 `source=OVERRIDE` |\r\n| `streamReactive_boundAgentWithGraphOverride_shouldStaySingleAgent` | 绑定专属 Agent 忽略 graph，保持单 Agent（meta `source=BOUND_AGENT`） |\r\n| `streamReactive_agenticExhausted_shouldNotEscalateToGraph` | Agentic 链路不升级 Graph：正常收尾，meta 保持 `path=agentic` |\r\n| `streamReactive_agenticFails_shouldNotEscalateAndEmitError` | Agentic 失败不升级 Graph，错误原样透出（error 事件 + meta 仍为 agentic） |\r\n\r\n### 5. `AgentServiceImplTest` —— Agent 路由与 Goal 生命周期\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `executeStreamReactive_emitsTokensAndSucceeds` | goal 创建 → RUNNING → SUCCEEDED 全链路 |\r\n| `executeStreamReactive_onError_marksFailed` | Agent 抛错 → goal 标记 FAILED |\r\n\r\n### 6. `ContextAssemblingAdvisorTest` —— 上下文组装\r\n\r\n纯函数式单测，覆盖组装三步（filterNoise / normalizeRoles / trimToBudget）：\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `assemble_filtersEmptyMessages` | 空消息与系统占位噪声被丢弃 |\r\n| `assemble_systemPlacedFirst` | system 消息置顶 |\r\n| `assemble_mergesConsecutiveSameRole` | 连续同角色消息交替化（保留最后一条） |\r\n| `assemble_trimsOldMessagesByTokenBudget_keepsRecent` | 超 token 预算从最旧逐条丢弃，保 system + 最近消息 |\r\n| `assemble_underBudget_preservesAll` | 未超预算不动原文 |\r\n| `assemble_preservesModelOptions` | 重建 Prompt 时 model 参数等 options **不被丢失** |\r\n\r\n### 7. 配置与注册表\r\n\r\n`AgentConfigProviderTest`（agent 表查询）：\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `findAgentNameById_hit / miss / null` | 按 agentId 查名称：命中 / 未命中 / 空参 → Optional.empty |\r\n| `getAgentConfig_hit_shouldReturnModelAndPrompt` | 命中行返回 model + prompt |\r\n| `getAgentConfig_miss_shouldReturnEmpty` / `nullModelShouldBeBlankToNull` | 未查到返回 empty；DB 空串/null 归一化 |\r\n\r\n`LlmClientRegistryTest`（按部署模型 id → ChatClient 注册表）：\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `get_registeredModel_shouldHit` | 已注册模型按 id 命中对应客户端 |\r\n| `get_unknownModel_shouldThrow` | 未知 id 直接报错，不回退默认客户端 |\r\n| `invalidate_rebuildsOnNextGet` | 失效后下一次 get 重建（连接池可替换） |\r\n\r\n### 8. `ChatControllerTest` —— HTTP 输出层契约\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `stream_eachElementEndsWithNewline` | Flux 每个元素末尾带 `\\n`，序列化后每元素独立成行，CLI 可按行解析 |\r\n| `stream_preservesElementOrder` | 元素发射顺序不变 |\r\n\r\n### 9. `JavaHarnessApplicationTests` —— 冒烟\r\n\r\n`contextLoads()`：Spring 容器全量装配通过（含 MySQL 连接、MyBatis Mapper、各配置 Bean），是最基本的一票否决项。\r\n\r\n### 10. `LlmCallRecorderTest` —— LLM 调用观测落库\r\n\r\n验证观测旁路的三条契约：估算口径、异步落库、失败隔离。\r\n\r\n| 用例 | 验证点 |\r\n| --- | --- |\r\n| `estimateTokens_mixedText_followsAdvisorConvention` | 中英文混合 token 估算与 `ContextAssemblingAdvisor` 同口径（中文 1 token、其它 (长度+3)/4） |\r\n| `record_insertsEntityAsynchronously` | record 后异步落库，字段映射正确（SYNC/OK/total/estimated/duration） |\r\n| `record_mapperFailure_neverThrowsToCaller` | Mapper 抛异常不影响调用方（观测失败永不阻塞主链路） |\r\n\r\n***\r\n\r\n## 四、测试基建约定\r\n\r\n- **mock 边界**：数据库经 H2/spring-test 或 mock Mapper 规避真实依赖；LLM 一律 mock `ChatClient` 返回固定 content——单测不真实出网、结果确定可重复。\r\n- **分层结构**：单元测试为主（Agent/Advisor/Service 层行为），`JavaHarnessApplicationTests` 提供容器级冒烟。\r\n- **防回归重点**：钩子方案的两个踩坑（merge 后关闸死锁、并行线程并发发射丢事件）由 `executeStreamReactive_streamsStageEventsInOrder` 的时序断言兜住；「宁可轻量」的路径判定兜底语义由 `LlmRouteJudgeTest` 六个失败分支覆盖。\r\n- **命名规约**：`方法_条件_期望结果`（如 `judge_whenCallThrows_shouldFallbackSimple`），失败信息一眼定位功能点。\r\n\r\n***\r\n\r\n## 五、功能 ↔ 测试覆盖矩阵\r\n\r\n| 业务功能 | 对应测试 |\r\n| --- | --- |\r\n| 主 Agent 前置路径判定（Agentic / Graph，内部枚举 SIMPLE/COMPLEX + 兜底） | `LlmRouteJudgeTest`、`ChatServiceImplTest.chat/streamReactive_shouldInvokeMainAgentRouteJudge` |\r\n| Agentic 路径对话（上下文注入 + 记忆多轮；含 ChatClient 回退） | `ChatServiceImplTest`（会话/写回）、`ContextAssemblingAdvisorTest`、`AgentScopeGeneralAgentTest` |\r\n| Graph 编排路径（拆解→并行→聚合） | `MultiAgentGraphAgentTest.execute_*` |\r\n| Graph 编排路径实时进度推送 | `MultiAgentGraphAgentTest.executeStreamReactive_*`、`ProgressLineTest`、`ChatServiceImplTest.streamReactive_shouldMapProgressRowToProgressEvent` |\r\n| SSE 对外契约（token/DONE/meta/error 按序） | `ChatServiceImplTest.streamReactive_*`、`ChatControllerTest` |\r\n| 多 Agent 显式指定与回退 | `AgentServiceImplTest` |\r\n| agent/model 双表配置驱动 | `AgentConfigProviderTest`、`LlmClientRegistryTest` |\r\n| LLM 调用观测（耗时/token 账本） | `LlmCallRecorderTest` |\r\n| 观测表保留期清理 | `ObservabilityLogCleanerTest` |\r\n| 观测查询接口（三张观测表读取口径收口） | `ObserveQueryServiceImplTest` |\r\n| 渠道会话绑定清理（core ↔ 渠道端口解耦） | `SessionServiceImplTest.deleteSession_*`、`OneBotEventServiceImplTest` |\r\n| 登录鉴权（口令/token 双通道） | `AuthControllerTest`、`ApiTokenFilterTest`、`LoginStateStoreTest` |\r\n',pr=`# Knowledge Base QA (RAG)\r
\r
> Deep dive behind the README "REST API" section: gating conditions, multi-KB & agent binding, directory watching / BM25 hybrid retrieval.\r
\r
---\r
\r
Drop documents into the \`knowledge/\` directory (\`.md\` / \`.txt\`, optional front-matter \`title:\`); after ingestion both paths automatically retrieve & inject before answering:\r
\r
\`\`\`bash\r
mkdir -p knowledge && cp your-doc.md knowledge/\r
curl -X POST http://localhost:8080/api/knowledge/sync          # incremental ingestion (only mtime-changed docs re-embedded)\r
curl 'http://localhost:8080/api/knowledge/search?q=deploy'     # debug retrieval hit view\r
\`\`\`\r
\r
Answers carry \`【Source N】\` inline citations, the CLI prints a "Sources:" footer, and \`meta.sources\` / \`ChatResponse.sources\` expose structured provenance (doc name / title / score). Configuration (top-k / min score / injection budget) lives under \`app.knowledge.*\` in \`application-knowledge.yaml\`.\r
\r
#### 🗂️ Multiple Knowledge Bases & Agent Binding\r
\r
Each first-level subdirectory of \`knowledge/\` is a standalone knowledge base (kb id); loose files at the root belong to the shared \`default\` base:\r
\r
\`\`\`bash\r
mkdir -p knowledge/java knowledge/frontend        # subdirectory = knowledge base\r
cp spring.md knowledge/java/ && cp vue.md knowledge/frontend/\r
curl -X POST http://localhost:8080/api/knowledge/sync\r
\`\`\`\r
\r
Set the \`agent\` table's \`knowledge\` column to a comma-separated list of kb ids (e.g. \`java,frontend\`) to bind an agent to specific bases — retrieval filters on the vector metadata \`kb\` field (\`kb in [...]\`), so an agent only reads its bound bases; leave it empty/NULL to search all knowledge. Moving a document across subdirectories (kb change) triggers automatic re-ingestion on the next sync.\r
\r
#### 🔍 RAG Trigger Logic\r
\r
RAG is never triggered by explicit commands — it is a **bypass check before every prompt assembly**; unmet conditions degrade silently with zero side effects on the main flow.\r
\r
Trigger decision flow:\r
\r
\`\`\`mermaid\r
flowchart TD\r
    A[User request<br/>Path A direct answer / Path B orchestration node] --> B["AgentRequestSpecFactory<br/>before assembling system prompt"]\r
    B --> C{"app.knowledge.enabled?"}\r
    C -->|false| X[Silent skip<br/>zero side effects]\r
    C -->|true| D{"Agent role in skip list?<br/>aggregator: its material is subtask results"}\r
    D -->|yes| X\r
    D -->|no| E{"User text length ≥<br/>min-query-chars (8)?"}\r
    E -->|no| X\r
    E -->|yes| F["Vector search: user text → DashScope embedding<br/>→ pgvector cosine top-k (4)"]\r
    F --> G{"Hits with score ≥<br/>min-score (0.5)?"}\r
    G -->|no| X\r
    G -->|yes| H["Accumulate tokens per hit:<br/>hits beyond context-budget (3000) are truncated"]\r
    H --> I["Render knowledge block with 【Source N】<br/>append to system prompt"]\r
    I --> J["Answer: inline 【Source N】 citations<br/>+ meta.sources + CLI source footer"]\r
\`\`\`\r
\r
Retrieval injection sequence:\r
\r
\`\`\`mermaid\r
sequenceDiagram\r
    participant U as User\r
    participant F as AgentRequestSpecFactory\r
    participant R as KnowledgeRetriever\r
    participant S as KnowledgeService\r
    participant E as DashScope Embedding\r
    participant V as pgvector\r
    participant L as LLM\r
    U->>F: Request (Path A / Path B node)\r
    F->>R: buildKnowledgeBlock(agent, sessionId, user)\r
    R->>R: enabled / role / query-length short-circuits\r
    R->>S: search(query)\r
    S->>E: Embed query\r
    S->>V: cosine similarity top-k\r
    V-->>S: Hit chunks\r
    S-->>R: List<KnowledgeHit>\r
    R-->>F: Knowledge block (【Source N】 within budget) or null\r
    F->>L: system prompt (+ knowledge block)\r
    L-->>U: Answer with inline citations + meta.sources\r
\`\`\`\r
\r
Trigger conditions (fully config-driven, tune via \`application-knowledge.yaml\`):\r
\r
| Trigger condition | Config key | Current value | When unmet |\r
|---|---|---|---|\r
| Knowledge base enabled | \`enabled\` | true | silent skip |\r
| Role not in skip list | — (aggregator skipped by policy) | aggregator | silent skip |\r
| Query length threshold | \`min-query-chars\` | 8 | silent skip |\r
| Hit score threshold | \`min-score\` | 0.5 | drop the hit |\r
| Within injection budget | \`context-budget\` | 3000 | truncate budget-exceeding hits |\r
\r
\r
---\r
\r
[⬅ Back to README](../../README.md)\r
`,gr="# 知识库问答（RAG）\r\n\r\n> README「REST 接口」的 RAG 深入篇：触发条件、多知识库与 agent 绑定、目录监听 / 混合检索等增强配置。\r\n\r\n---\r\n\r\n\r\n把文档放进 `knowledge/` 目录（`.md` / `.txt`，支持 front-matter `title:`），摄取后路径 A/B 回答自动检索注入。触发是**每次组装 prompt 前的旁路检查**：总开关、角色名单、查询长度、相关度、注入预算五层条件全部满足才注入，任一不满足静默降级、主链路零感知——全部配置驱动（`application-knowledge.yaml` 的 `app.knowledge.*`），决策流程与时序图见 [`data-flow.md` 5i 节](../data-flow.md#5i-rag-知识检索注入数据流prompt-组装前旁路)。\r\n\r\n```bash\r\nmkdir -p knowledge && cp 你的文档.md knowledge/\r\ncurl -X POST http://localhost:8080/api/knowledge/sync          # 增量摄取（只处理 mtime 变更的文档）\r\ncurl 'http://localhost:8080/api/knowledge/search?q=部署步骤'     # 调试检索看命中\r\n```\r\n\r\n回答中出现 `【出处N】` 内联引用时，CLI 回合末尾会打印「来源:」尾注；`meta.sources` / `ChatResponse.sources` 携带结构化出处（文档名/标题/相关度）。\r\n\r\n#### 🗂️ 多知识库与 agent 绑定\r\n\r\n`knowledge/` 的一级子目录即独立知识库（kb 标识），根目录散文档归公共库 `default`：\r\n\r\n```bash\r\nmkdir -p knowledge/java knowledge/frontend        # 一级子目录 = 知识库\r\ncp spring.md knowledge/java/ && cp vue.md knowledge/frontend/\r\ncurl -X POST http://localhost:8080/api/knowledge/sync\r\n```\r\n\r\n在 `agent` 表 `knowledge` 列填写逗号分隔的 kb 标识（如 `java,frontend`）即可把 agent 绑定到指定知识库——检索时按向量 metadata 的 `kb` 字段过滤，agent 只读绑定的库，防止读串；列留空/NULL = 未绑定，不触发知识库检索。文档在子目录间移动（kb 变更）会在下次 sync 自动重摄取补齐。\r\n\r\n#### ⚙️ 知识库增强配置（app.knowledge.*）\r\n\r\n```yaml\r\napp.knowledge:\r\n  watch-enabled: false            # 目录监听自动摄取总开关：开启后 knowledge/ 增删改文件，\r\n                                  # 静默期过后自动触发一次增量摄取（免手动 sync；并发安全，\r\n                                  # 与手动 sync 同时到达时后到者跳过、由下一轮事件补齐）\r\n  watch-debounce-seconds: 3       # 文件事件静默期（秒）：批量拷贝/编辑器原子写合并为一次\r\n  hybrid-enabled: false           # BM25 混合检索总开关：开启后向量 + BM25 双路 RRF 融合重排，\r\n                                  # 关键词/编号/专有名词类查询字面精确召回更好（默认关 = 纯向量）\r\n  bm25-max-chunks: 20000          # BM25 内存索引规模护栏：chunk 总数超过则不建索引、退化为纯向量；0 = 不限\r\n  search-timeout-seconds: 10      # 检索限时（秒）：嵌入+向量查询在守护线程池限时执行，超时中断降级\r\n                                  # （不注入知识块、不阻断聊天主链路）；0 = 关闭包裹直通现状\r\n```\r\n\r\n- **目录监听**（`watch-enabled`）：默认关。开启要求启动时 `knowledge/` 目录已存在（Docker 挂载天然保证）；监听范围 = 根目录 + 一级子目录，监听期间新建的一级子目录自动补注册；监听线程异常仅告警并自动恢复，绝不影响应用。\r\n- **混合检索**（`hybrid-enabled`）：默认关，关闭时行为与纯向量完全一致。开启后 BM25 索引从 PG 向量表按需懒构建（`sync`/`delete` 后自动失效重建），检索输出仍受 `top-k` 约束，融合分数归一化到 (0,1] 保持「相关度 %.2f」渲染口径。\r\n- **检索限时与预取**（`search-timeout-seconds`）：嵌入端点/PG 挂起时请求线程不再分钟级阻塞——超时中断按既有语义静默降级。同时入口在 RouteJudge 判定期间并行预取检索（无数据依赖，judge 与 RAG 耗时重叠），结果按「同会话 + 同 query + 同 kb 绑定」缓存短路命中，编排专家节点（query 为子任务描述）不受影响仍精准现查。\r\n- **上传**：`POST /api/knowledge/upload`（multipart，`.md`/`.txt` ≤1MB，`kb` 可选且仅允许小写字母/数字/-/_）只落盘不自动摄取——随后调 sync 或依赖目录监听生效；管理页上传成功会自动链同步。\r\n\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n",ur=`# MCP 工具接入\r
\r
> README「MCP 工具接入」一节的完整版：配置模板、按 agent 分配工具与 general 最小权限对齐。\r
\r
---\r
\r
\r
工具生态经 MCP 扩展：client 支持 stdio（本地进程）与 Streamable HTTP（远程 server），懒连接 + 按 server 失败隔离（外部 server 挂了不影响主链路），连接事件落库 \`mcp_server_log\`。\r
\r
连接配置在项目根 \`mcp-config.json\`（Claude/Cursor 同款 \`mcpServers\` 结构）。**该文件含 API Key，属本地配置不入库**（已在 \`.gitignore\`，clone 后需自建），模板：\r
\r
\`\`\`json\r
{\r
  "mcpServers": {\r
    "tavily": { "url": "https://mcp.tavily.com/mcp/?tavilyApiKey=<你的Tavily key>" }\r
  }\r
}\r
\`\`\`\r
\r
- 远程 server 填 \`url\`，本地进程填 \`command\`(±\`args\`)；\`enabled: false\` 跳过条目；**改配置需重启**\r
- **按 agent 分配**：\`agent\` 表 \`tools\` 列按精确工具名授予（改库免重启，已有行的改动最多滞后 30s）。如把 Tavily 搜索分给 general/researcher：\r
\r
\`\`\`sql\r
UPDATE agent SET tools = CONCAT(tools, ', tavily_search')\r
WHERE agent_name IN ('general', 'researcher') AND tools NOT LIKE '%tavily_search%';\r
\`\`\`\r
\r
- 未声明的工具模型不可见；声明时 server 未连接则该 token 跳过（warn），不影响其余工具\r
\r
> [!IMPORTANT]\r
> **最小权限**：\`general\` 是所有回退路径的落点（路由兜底/未识别专家/lead 漏指派），已收敛为只读探索者\r
> （网页抓取 + 沙箱只读文件 + 浏览器 + MCP 白名单）——执行类 \`sandbox.base\` 与写入类 \`sandbox.write\`\r
> 只留给 lead 明确指派的 \`coder\`/\`analyst\`。general 的 \`tools\` 列对齐已落在 \`V1__init_schema.sql\` 基线种子数据里（压平前的 V17 迁移已并入；新库/存量库跑 Flyway 即生效），仅 Flyway 管不到的外部库需手工对齐\r
> （数据驱动优先于代码内置，改库即生效）：\r
\r
\`\`\`sql\r
UPDATE agent SET tools = 'web, sandbox.read, sandbox.browser, tavily_search'\r
WHERE agent_name = 'general' AND (tools LIKE '%sandbox.base%' OR tools LIKE '%sandbox.write%');\r
\`\`\`\r
\r
> [!NOTE]\r
> MCP 工具返回内容不经过项目的内容裁剪链（缓存/相关段落过滤），超长由工具结果硬预算统一截断。\r
\r
\r
---\r
\r
[⬅ 返回 README](../../README.md)\r
`,vr='# 多模型与多服务商\r\n\r\n> 数据库驱动的三层 LLM 配置：供应商 → 模型部署 → 路由，Agent 只绑定路由；新增模型零代码、热更新免重启（管理接口写入即时生效；直接改库不会触发刷新，见文末说明）。\r\n\r\n---\r\n\r\n**数据库驱动的多 Agent + 多模型服务商**，接入方式零代码：\r\n\r\n```mermaid\r\nflowchart LR\r\n    A[agent 表<br/>agent_name / route_id / params] -->|按 id 绑定| R[llm_route<br/>稳定别名 main/fast/judge/multi]\r\n    R --> M[llm_route_member<br/>priority 失败链]\r\n    M --> D[llm_model<br/>upstream_name / capabilities / default_params]\r\n    D --> P[llm_provider<br/>base_url / credential_ref]\r\n```\r\n\r\n- 🧩 **Agent**（`agent` 表）：每行一个 Agent（`agent_name`/`route_id`/`prompt`）。种子行：`general`/`deepseek`（聊天）、`multi-agent`（编排器）、`lead`（拆解器）、`aggregator`（聚合器）、`researcher`/`coder`/`analyst`/`writer`（专家）、`wms`（领域专员，工具绑定见 [wms-mcp.md](wms-mcp.md)）\r\n- 🗺️ **路由**（`llm_route` + `llm_route_member`）：Agent 只认路由 id；成员按 `priority` 升序构成失败链，仅首个 token 前允许切换；`capabilities` 声明工具/视觉/思考/上下文窗口，启动与热更新时校验\r\n- 🧭 **调用链**：Agent → `route_id` → 首选成员 `model_id` → `llm_provider` 端点与凭证；代码只按 id 查找，不按模型名解析\r\n- 📈 **观测**：`llm_call_log` 记录 `route_id`/`model_id`/`provider_code`/`upstream_name`/`fallback_from_model_id`/`error_class`，可还原失败切换路径。其中 `model` 列写**实际应答成员**的上游模型名（fallback 后不再是首选成员），按 `model` 聚合与 `upstream_name` 同口径；请求侧的原始模型名不再单独落库\r\n\r\n> [!TIP]\r\n> **新增第三方供应商（如 Moonshot、OpenRouter）零代码**：\r\n> 1. 设置环境变量 `MOONSHOT_API_KEY`（约定规则：`<PROVIDER大写>_API_KEY`，或 `app.providers.moonshot.api-key`）\r\n> 2. 通过管理接口登记（先校验再落库并热更新）：\r\n>    ```text\r\n>    POST /api/admin/providers   {"code":"moonshot","baseUrl":"https://api.moonshot.cn/v1","credentialRef":"MOONSHOT_API_KEY"}\r\n>    POST /api/admin/models      {"providerId":<上一步返回 id>,"upstreamName":"kimi-k2","displayName":"Kimi K2","capabilities":"{\\"tool_calling\\":true,\\"context_window\\":131072}"}\r\n>    POST /api/admin/routes      {"code":"backup","members":[{"modelId":<模型 id>,"priority":10}]}\r\n>    PUT  /api/admin/agents/{agentId}/route   {"routeId":<路由 id>}\r\n>    ```\r\n> 3. 立即生效（服务端重读目录 → 全量校验 → 原子替换；校验失败保留旧配置并返回原因）\r\n>\r\n> **直接改库不会触发刷新**：`llm_provider` / `llm_model` / `llm_route` / `llm_route_member` 只在管理接口写入后重建内存映射，手工 SQL 修改需再调一次管理接口写入或重启服务。Agent 行（prompt / tools / route_id）走 30s 进程内缓存，最多滞后 30s；新增 Agent 行因未命中缓存而立即注册。\r\n>\r\n> CLI：`/provider` 查看供应商，`/provider add <provider> <apiUrl> <模型1,模型2,...>` 走同一管理接口。\r\n\r\n> [!WARNING]\r\n> API Key 出于安全不落库，`llm_provider.credential_ref` 只存引用名，解析规则（约定优于配置）：\r\n> 1. `credential_ref` 本身（如 `DASHSCOPE_API_KEY`）\r\n> 2. `app.providers.<provider>.api-key`（yaml 显式映射）\r\n> 3. `<PROVIDER大写>_API_KEY` 环境变量（约定式回退）\r\n>\r\n> Key 的**装载通道**：系统级环境变量（Windows 侧 + `WSLENV` 透传进 WSL）> 仓库根 `.env.local`（启动脚本自动 source，gitignored）> 当前会话 `export`；解析优先级不受通道影响。\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n',hr='# QQ 机器人\r\n\r\n> README「QQ 机器人」一节的完整版：NapCat 双通道接入步骤、触发方式、指令交互、单条回复与表情包参数。\r\n\r\n---\r\n\r\n\r\n除 CLI / REST 之外的第三个入口：基于 **OneBot 11 协议（HTTP POST 模式）** 经 [NapCat](https://napneko.github.io/) 对接 QQ 群聊与私聊。消息进入后与 REST 全链路一致（会话记忆 → Agentic / Graph 路径分流 → 编排），回复整段单条发出（超长按上限截断）。\r\n\r\n| 能力 | 说明 |\r\n|---|---|\r\n| 💬 群聊触发 | `group-trigger.mode`: `at`（@机器人；`reply-to-bot=true` 时回复机器人消息也触发）/ `prefix`（默认 `/ai`）/ `all`；私聊直答，可选 `private-allow-users` 白名单 |\r\n| 📝 单条回复 | 整段单条发送；超过 `reply.max-length`（默认 3000 字）截断并补 `…`；群聊带引用原消息，`reply.at-asker=true` 时首条 @ 提问者 |\r\n| 🧵 指令 | `/new` `/reset`（清空绑定、下条重开新会话）、`/help`、`/history [n]`（默认 5 条，不耗 LLM）、`/agent [名]`（查看/切换 Agent，切换需已有会话）、`/recall`（撤回机器人上一条消息） |\r\n| 😀 表情包 | LLM 在回复末尾输出 `[emoji:分类]`（happy / angry / shock / chill / social / abstract / festive / none）；正文剥离标记后发送，再按分类从 `emojis/emoji-index.json` 随机取一张（图片 `base64://` 内联、跨机部署可用）；`probability` / `send-delay-ms` 控频与节奏 |\r\n| 🔊 语音 | 用户明确要求「用语音说 / 念一下」时，Agent 调用 `text_to_speech` 工具（Edge TTS）合成 mp3；core 在响应 `flags` 中透出 `voice:<id>`，QQ 渠道取工件后以 `record` 段发送。语音成功时本轮不再发文字与表情，工件缺失/发送失败回退文字 |\r\n| 🧠 会话记忆 | 同一 QQ 用户/群自动延续多轮上下文；`/new` / `/reset` 删除绑定行后下条消息建档新会话；harness 删除会话时同步清理绑定 |\r\n| 📬 主动推送 | 定时任务跑完由 `MessageSender` 端口经 `sendPrivateMsg` 私聊送达（`notifyChannel` 形如 `qq:<目标>`）；判定走轮询 + `notified_at` 幂等，发送失败也标记避免刷渠道；未注册渠道 / 地址非法只 WARN 不抛异常。能力全景见 [features.md](../features.md) |\r\n| 🚦 防刷 | 同用户限频（`rate-limit.per-user-seconds`）+ 私聊白名单（`private-allow-users`，空 = 不限制）；群聊暂无群号白名单 |\r\n| ⏱️ 聊天超时 | `chat-timeout-seconds` 超时中断并放弃回复（后台任务取消），防 LLM 卡死占满线程池 |\r\n| 🎯 指定 Agent | `agent-id` 仅在会话首次建档时绑定，`/agent <名>` 可随时切换。绑定非 general 的专属 Agent 走 Agentic 直答（跳过 Graph 判定）；general 仍走统一路由（SIMPLE/COMPLEX） |\r\n\r\n**接入步骤**（NapCat 侧建议 Docker 部署，只允许 HTTP POST、不用 WebSocket）：\r\n\r\n1. NapCat 配置 `onebot11` 网络双通道——正向 API + 反向上报：\r\n\r\n   ```jsonc\r\n   {\r\n     "httpServers": [{ "name": "java-harness", "host": "0.0.0.0", "port": 3000,\r\n                        "enableCors": false, "enableWebsocket": false,\r\n                        "messagePostFormat": "array", "token": "<与 napcat.api-token 一致>" }],\r\n     "httpClients": [{ "name": "report-to-harness", "url": "http://<harness主机>:8080/onebot/event",\r\n                        "messagePostFormat": "array", "reportSelfMessage": false,\r\n                        "token": "<与 napcat.event-secret 一致>" }]\r\n   }\r\n   ```\r\n\r\n2. harness 侧 `application-qq.yaml` 的 `napcat:` 块按注释配置（`enabled` / `api-base-url` / `api-token` / `self-id` 等，全部有默认值）；不接入设 `napcat.enabled: false` 即可\r\n3. 表情包（可选）：图片放 `emojis/`（不进 git，自行放置）。`emojis/emoji-index.json` 首次启动会自动创建为空映射，往里登记「表情名 → 图片文件」即可（表情名前缀即分类，如 `happy_01` → `happy`），改完重启生效\r\n4. 语音（可选）：目标 Python 安装 `edge-tts`，并让 `TTS_PYTHON` 指向该解释器（`application-tts.yaml` 默认 `python3.11`）；语音参数在 `app.tts.*`（voice / rate / volume / max-chars / 超时与并发）\r\n\r\n> [!NOTE]\r\n> 跨机部署（NapCat 在远端 Docker、harness 在本机）：`api-base-url` 用 `ssh -L 3000:127.0.0.1:3000` 拉回；上报方向 `httpClients` 填 `http://172.17.0.1:8080/onebot/event`，配合 `ssh -R 8080` 反向隧道 + 远端 socat 转发到本机 harness，完整命令见 [dev.md](../dev.md) 的「QQ 远程 NapCat 联调」。\r\n\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n',Sr=`# 断点续跑\r
\r
> 复杂编排基于自研引擎阶段检查点的断点续跑：断开时机与续跑行为、API 与 CLI 用法。\r
\r
---\r
\r
Graph 编排路径（内部枚举 \`COMPLEX\`）按阶段落检查点到 \`orchestration_checkpoint\` 表（goal 维度；阶段 \`LEAD_DONE\` / \`SUBTASKS_DONE\` / \`DONE\`）：\r
\r
| 断开时机 | 续跑行为 |\r
|---|---|\r
| ✅ 编排已完整跑完 | 零 LLM 调用，直接回放最终回答 |\r
| ⏸️ 子任务批已完成、聚合中断 | 只补跑聚合（打字机输出），子任务结果复用 |\r
| ⏹️ 更早断开（如子任务批执行中） | 已完成阶段不重跑，只补执行缺口 |\r
| 🚫 无任何检查点 | 快速失败：提示该 goal 未走过复杂路径 |\r
\r
\`\`\`bash\r
# 断点续跑\r
curl -N -X POST "http://localhost:8080/api/chat/resume?goalId=<goalId>"\r
curl "http://localhost:8080/api/chat/goal-status?goalId=<goalId>"   # 只查状态，不重跑\r
\r
# 断点续跑\r
/resume\r
/resume <goalId>\r
\`\`\`\r
\r
> [!NOTE]\r
> \`goal\` 不存在返回 400；仍在执行中返回 409；无检查点时流内发 \`error\` 事件。\r
\r
\r
---\r
\r
[⬅ 返回 README](../../README.md)\r
`,Ar=`# 本地部署详解\r
\r
> README「快速开始」的完整版：数据库准备、一键启动脚本、API Key 三种配置方式、agent 服务商核对。\r
\r
---\r
\r
MySQL 只需建库一次（表结构由 Flyway 启动时自动创建，无需手动执行脚本）。连接信息在 \`src/main/resources/application-datasource.yaml\` 的 \`spring.datasource\`（默认 \`localhost:3306/harness\`、账号 \`root\`、空密码，按需修改）：\r
\r
\`\`\`sql\r
CREATE DATABASE IF NOT EXISTS harness DEFAULT CHARACTER SET utf8mb4;\r
\`\`\`\r
\r
### ⚡ 一键启动\r
\r
> [!TIP]\r
> 两套启动脚本任选其一（**勿同时运行**，8080 端口会冲突）：\r
> - **\`scripts/run-wsl.sh\`**（WSL 终端）：\`./scripts/run-wsl.sh\` 全流程——编译 → 杀掉 8080 旧服务 → 新窗口起服务 → 本终端轮询就绪 → 进入 CLI；子命令 \`server / stop / cli / build / test\`\r
> - **\`scripts/run-win.bat\`**（Windows 本机）：Windows 侧检出 + Windows JDK/Maven 环境时使用\r
\r
> [!NOTE]\r
> **Windows 本机启动前核对三件事**\r
> - \`JAVA_HOME\` 必须指向 **JDK 25**（项目 \`pom.xml\` 为 \`release 25\`；PATH 上的旧 JDK 会直接编译失败）。验证：\`java -version\` 输出 25.x；临时生效用 \`$env:JAVA_HOME='<JDK25目录>'\`，长期生效在"系统环境变量"里配置并重开终端\r
> - \`scripts/run-win.bat\` **不读取** \`.env.local\`（该文件只被 \`run-wsl.sh\` 加载），API Key 需用 \`setx QWEN_API_KEY "sk-..."\` / \`setx DEEPSEEK_API_KEY "sk-..."\` 设为用户环境变量后重开窗口，或在启动服务的窗口里临时 \`$env:QWEN_API_KEY='sk-...'\`\r
> - Web 控制台需要 **Node 20.19+**（Vite 7 要求）；低于该版本 \`npm run dev\` 只告警但建议升级（\`nvm use 20.19.0\` 或安装新版 Node）\r
\r
### 🔧 手动启动\r
\r
**1️⃣ 启动主服务**\r
\r
\`\`\`bash\r
# 本地部署详解\r
mvn -DskipTests install\r
java -jar deerfect-server/target/deerfect-server-0.0.1-SNAPSHOT.jar\r
\r
# 本地部署详解\r
mvn -pl deerfect-api,channel-qq -DskipTests install\r
mvn -pl deerfect-server spring-boot:run\r
\`\`\`\r
\r
**2️⃣ 另开终端，启动 CLI**\r
\r
\`\`\`bash\r
mvn -pl deerfect-cli -Pcli compile exec:exec\r
\`\`\`\r
\r
> [!TIP]\r
> 以上命令直接使用你自己的 Maven 环境（全局 settings + 默认仓库）。国内网络可追加 \`-s .mvn/settings.xml\` 走阿里云镜像加速——注意它会同时把本地仓库切到项目内 \`.mvn-repo/\`（已 gitignore，首次会全量重新下载）。\r
\r
**3️⃣ 或直接用 REST 聊天（无需 CLI）**\r
\r
\`\`\`bash\r
curl -s -X POST http://localhost:8080/api/chat \\\r
  -H "Content-Type: application/json" \\\r
  -d '{"message":"你好"}'\r
\`\`\`\r
\r
**4️⃣ （可选）配置真实 API Key**\r
\r
按优先级任选其一，重启服务后即可真实对话（不配置也能启动，调用模型返回 401 占位 key 错误）：\r
\r
\`\`\`powershell\r
# 本地部署详解\r
setx QWEN_API_KEY "sk-你的key"      # DashScope（通义千问）\r
setx DEEPSEEK_API_KEY "sk-你的key"  # DeepSeek\r
setx WSLENV "QWEN_API_KEY/u:DEEPSEEK_API_KEY/u"   # 透传进 WSL（Linux 侧运行时需要）\r
\r
# 本地部署详解\r
# 本地部署详解\r
# 本地部署详解\r
\r
# 本地部署详解\r
$env:QWEN_API_KEY = "sk-你的key"    # Windows PowerShell；WSL 用 export QWEN_API_KEY=...\r
\`\`\`\r
\r
**数据库连接（可选覆盖）**：默认连 \`localhost:3306/harness\`、账号 \`root\`、空密码、关闭 SSL，只为本机开发服务。非本机部署用环境变量覆盖，不要改 yaml：\r
\r
\`\`\`powershell\r
setx MYSQL_USERNAME "harness_app"   # 专用账号，避免用 root 跑业务\r
setx MYSQL_PASSWORD "强密码"\r
setx MYSQL_USE_SSL "true"           # 公网/跨机必开，否则口令与数据明文过网\r
\`\`\`\r
\r
> [!IMPORTANT]\r
> **首次对话前核对 Agent 绑定的路由与供应商**：只配 \`QWEN_API_KEY\`、而路由首选成员指向未配 key 的\r
> 供应商时，首次对话会 401。用下面 SQL 核对，必要时通过管理接口调整路由成员或用 \`QWEN_API_KEY\`\r
> 所属供应商的模型替换：\r
>\r
> \`\`\`sql\r
> SELECT a.agent_name AS agent, r.code AS route, m.upstream_name, p.code AS provider\r
> FROM agent a\r
> LEFT JOIN llm_route r ON a.route_id = r.id\r
> LEFT JOIN llm_route_member rm ON rm.route_id = r.id AND rm.enabled = 1\r
> LEFT JOIN llm_model m ON rm.model_id = m.id\r
> LEFT JOIN llm_provider p ON m.provider_id = p.id\r
> WHERE a.agent_name IN ('general', 'researcher')\r
> ORDER BY a.agent_name, rm.priority;\r
>\r
> -- 调整路由成员请走管理接口（自动校验并热更新）：\r
> -- PUT /api/admin/routes/{routeId}  {"code":"main","members":[{"modelId":<已配 key 的模型 id>,"priority":10}]}\r
> \`\`\`\r
\r
> [!NOTE]\r
> 不接入 QQ 渠道？在 \`application-qq.yaml\` 设 \`napcat.enabled: false\`（QQ 渠道为可选组件，但当前默认开启——关闭后不再有 NapCat 连接告警，其余功能不受影响）。接入与能力清单见 [\`docs/guides/qq-channel.md\`](./qq-channel.md)。\r
\r
\r
---\r
\r
[⬅ 返回 README](../../README.md)\r
`,kr='# SSE Streaming Protocol\r\n\r\n> Full version of the README "SSE Streaming Protocol" section: event stream example, event types and consumption notes.\r\n\r\n---\r\n\r\nResponse `Content-Type: text/plain` (an SSE-style line protocol; each Flux element is its own line, with `event:` + `data:` pairs). Example event stream:\r\n\r\n```text\r\nevent: progress\r\ndata: {"stage":"编排","detail":"开始拆解复杂目标…"}\r\nevent: progress\r\ndata: {"stage":"拆解","detail":"4 个子任务已就绪"}\r\nevent: token\r\ndata: chunk-1\r\nevent: token\r\ndata: chunk-2\r\n...\r\ndata: [DONE]\r\nevent: meta\r\ndata: {"sessionId":"9","newSession":true,"goalId":null,"status":"SUCCEEDED","error":null}\r\n```\r\n\r\n| Event | Meaning |\r\n|---|---|\r\n| 📺 `event: token` | Text chunks generated by the model (pushed token by token; in-line newlines are escaped so each event stays a single line) |\r\n| 📣 `event: progress` | Real-time orchestration progress (orchestration / decomposition / subtask done / aggregation); **not** written into session memory |\r\n| 🏷️ `event: meta` | Session info at the end of a turn: `sessionId` / `newSession` / `goalId` / `status`; on failure `status=FAILED` plus an `error` field; when knowledge hits exist, `sources` carries them (doc name / title / relevance) |\r\n| ⚠️ `event: error` | In-stream error message |\r\n\r\n> [!IMPORTANT]\r\n> - Each SSE event is emitted as a single atomic pair (`event:` + `data:` are never interleaved by other events)\r\n> - `[DONE]` is sent after everything has been pushed\r\n> - Use `curl -N` to watch chunks arrive one by one\r\n\r\n\r\n---\r\n\r\n[⬅ Back to README](../../README.md)\r\n',Cr='# SSE 流式协议\r\n\r\n> README「SSE 流式协议」一节的完整版：事件流示例、事件类型与消费注意事项。\r\n\r\n---\r\n\r\n\r\n响应 `Content-Type: text/plain`（SSE 风格行协议，每个 Flux 元素独占一行，`event:` + `data:` 成对），事件流示例：\r\n\r\n```text\r\nevent: progress\r\ndata: {"stage":"编排","detail":"开始拆解复杂目标…"}\r\nevent: progress\r\ndata: {"stage":"拆解","detail":"4 个子任务已就绪"}\r\nevent: token\r\ndata: 片段1\r\nevent: token\r\ndata: 片段2\r\n...\r\ndata: [DONE]\r\nevent: meta\r\ndata: {"sessionId":"9","newSession":true,"goalId":null,"status":"SUCCEEDED","error":null}\r\n```\r\n\r\n| 事件 | 含义 |\r\n|---|---|\r\n| 📺 `event: token` | 模型生成的文本片段（逐 token 推送；行内换行已转义保证单行完整） |\r\n| 📣 `event: progress` | 编排各阶段实时进度（编排/拆解/子任务完成/聚合），**不计入会话记忆** |\r\n| 🏷️ `event: meta` | 回合末尾的会话信息：`sessionId` / `newSession` / `goalId` / `status`；失败时 `status=FAILED` 且追加 `error`；知识命中时携带 `sources`（文档名/标题/相关度） |\r\n| ⚠️ `event: error` | 流内错误信息 |\r\n\r\n> [!IMPORTANT]\r\n> - 每个 SSE 事件以单个元素成对输出（`event:`+`data:` 不会被其它事件交叉打断）\r\n> - 全部推完发送 `[DONE]`\r\n> - 可用 `curl -N` 观察逐段到达\r\n\r\n\r\n---\r\n\r\n[⬅ 返回 README](../../README.md)\r\n',_r=`# WMS 仓储查询适配\r
\r
> 独立部署的 MCP server（\`wms-mcp-adapter\` 模块）：把 WMS 仓储系统的只读 OpenAPI 包装为 10 个中文语义查询工具，harness 以自然语言查库存/查单据。harness 侧零代码改动——\`mcp-config.json\` 加一个 server 条目 + agent 表 \`wms\` 行（V25 迁移自动落库）。\r
\r
---\r
\r
## 它是什么\r
\r
- **独立 Spring Boot 应用**（不进 server fat jar，\`java -jar\` 独立部署），暴露 MCP Streamable HTTP 端点（\`/mcp\`），与 harness、WMS 均为进程外解耦：WMS 宕机只影响 wms 工具调用（返回结构化错误文本，MCP 连接保持存活），不影响主链路\r
- **只读**：仅包装 WMS 查询类 GET 端点；全部写操作、\`exportXls\` 文件流导出与业务动作（发货/面单打印等）一律不暴露\r
- **双侧鉴权、零硬编码**：adapter→WMS 用只读账号凭证（环境变量注入，空默认）；harness→adapter 校验 \`X-API-Token\` 请求头（常量时间比较）\r
- **组合查询对模型透明**：按商品名称查库存时，adapter 内部先查商品表换 productId 再查库存，模型一次调用即得结果\r
\r
## 构建与运行\r
\r
\`\`\`bash\r
mvn -pl wms-mcp-adapter -am package\r
java -jar wms-mcp-adapter/target/wms-mcp-adapter-<version>.jar\r
\`\`\`\r
\r
环境变量（启动前注入，无任何默认凭据）：\r
\r
| 环境变量 | 作用 | 说明 |\r
|---|---|---|\r
| \`WMS_API_BASE\` | WMS OpenAPI 基础地址 | 如 \`http://wms.example.com\` |\r
| \`WMS_API_TOKEN\` | adapter→WMS 的鉴权凭证 | 只读账号 token |\r
| \`WMS_TOKEN_HEADER\` | adapter→WMS 的鉴权头名称 | 默认 \`X-Access-Token\`（jeecg 惯例；OpenAPI 未声明鉴权方案，接入前与 WMS 方确认） |\r
| \`WMS_MCP_TOKEN\` | harness→adapter 的调用方 token | mcp-config.json 条目 headers 中 \`X-API-Token\` 须与其一致 |\r
| 端口 | adapter 监听端口 | 由 adapter 的 \`application.yaml\` 配置（可用 \`SERVER_PORT\` 覆盖），下例以 \`18080\` 为例 |\r
\r
## harness 接入\r
\r
**1. mcp-config.json 新增 wms 条目**（项目根，gitignored，改配置需重启）：\r
\r
\`\`\`json\r
{\r
  "mcpServers": {\r
    "tavily": { "url": "https://mcp.tavily.com/mcp/?tavilyApiKey=<你的Tavily key>" },\r
    "wms": {\r
      "url": "http://127.0.0.1:18080/mcp",\r
      "headers": { "X-API-Token": "<与 adapter 的 WMS_MCP_TOKEN 一致>" }\r
    }\r
  }\r
}\r
\`\`\`\r
\r
> [!NOTE]\r
> 当前 harness 版本的 mcp-config.json 解析仅识别 \`url\`/\`command\`/\`args\`/\`enabled\` 字段（见 \`deerfect-server/src/main/java/com/dark/javaHarness/tool/McpConfigParser.java\`），\`headers\` 字段的支持以 harness 版本为准——未支持时 token 不会随请求携带，adapter 将拒绝调用（见常见问题）。\r
\r
**2. agent 表 wms 行**：由 Flyway 基线 \`V1__init_schema.sql\` 的种子数据自带（原 V25 迁移并入；is_internal=0，web AgentSelect 可见可绑定；tools 列绑定下表 10 个工具名，改库免重启，最多滞后 30s）。前提是 \`llm_model\` 中已有 \`wms\` 绑定的路由首选成员模型（V20 已从旧数据回填）。\r
\r
**3. 使用**：QQ / web / CLI 任一渠道选择「仓储专员（wms）」或由编排指派后，即可自然语言查询；每次工具调用落库 \`tool_call_log\`（含来源 server=wms）。\r
\r
## 工具清单（只读，10 个）\r
\r
| # | MCP 工具 | 语义 | WMS 端点 | 关键参数（透传过滤） |\r
|---|---|---|---|---|\r
| 1 | wms_search_stock | 查库存（数量/批次/储位） | GET /inventory/wmsInventory/list | productId、locationCode、batchNumber、containerCode、pageNo/pageSize；支持 productName 先查商品换 id（组合） |\r
| 2 | wms_search_products | 商品检索 | GET /goods/wmsProducts/list | productName、productCode、productBarcode、productBrand、ownerId |\r
| 3 | wms_get_product | 商品详情 | GET /goods/wmsProducts/queryById | id |\r
| 4 | wms_search_stock_in_orders | 入库单检索 | GET /inorder/wmsStockInOrders/list | orderNumber、orderType、status、expectedArrivalTime |\r
| 5 | wms_get_stock_in_order | 入库单主+明细聚合 | GET /inorder/wmsStockInOrders/queryById + /queryWmsStockInOrderItemsByMainId | id |\r
| 6 | wms_search_out_orders | 出库单检索 | GET /outorder/wmsOutOrders/list | orderNo、orderType、status、warehouseId、ownerId |\r
| 7 | wms_get_out_order | 出库单主+明细+分配聚合 | GET /outorder/wmsOutOrders/queryById + /queryWmsOutOrdersItemsByMainId + /queryWmsOutOrdersAllocationByMainId | id |\r
| 8 | wms_search_shipments | 包裹/发运检索（含明细） | GET /shipment/wmsShipment/list (+ /queryWmsShipmentDetailByMainId) | 单号、状态、日期 |\r
| 9 | wms_search_warehouse_network | 仓库/库区/库位查询（level 分派） | GET /warehouse/wmsWarehouses\\|wmsStorageZones\\|wmsStorageLocations/list | level、编码/名称关键词、上级 id、状态 |\r
| 10 | wms_search_tasks | 收货/上架/拣货/波次/缺货任务查询（type 分派） | GET /inorder/receiveTasks\\|inorder/putawayTasks\\|wave/pickingTasks\\|wave/wmsWaveMaster\\|wave/wmsShortageRegistration/list | taskType、任务号、状态、productId |\r
\r
## 常见问题\r
\r
**Q：工具调用返回「WMS 暂不可达」？**\r
\r
- 在 adapter 部署机验证 WMS 可达：\`curl $WMS_API_BASE/<任一查询端点>\`（带鉴权头）\r
- WMS 宕机期间 adapter 返回结构化错误文本、MCP 连接保持存活，WMS 恢复后无需重启 harness 或 adapter\r
\r
**Q：adapter→WMS 鉴权失败？**\r
\r
- 确认 \`WMS_API_TOKEN\` 为有效只读账号凭证，且 \`WMS_TOKEN_HEADER\` 与 WMS 方约定的请求头名称一致（默认 \`X-Access-Token\`）\r
\r
**Q：harness→adapter 鉴权失败（调用被拒）？**\r
\r
- 确认 mcp-config.json 条目 headers 中 \`X-API-Token\` 的值与 adapter 环境变量 \`WMS_MCP_TOKEN\` 完全一致\r
- 确认所用 harness 版本的 mcp-config.json 解析支持 \`headers\` 字段（当前版本仅识别 \`url\`/\`command\`/\`args\`/\`enabled\`，token 未携带会被 adapter 拒绝）\r
\r
**Q：agent 的工具列表里没有 wms_* 工具？**\r
\r
- mcp-config.json 无 wms 条目或 url 写错：harness 启动/预热日志会 warn「连接/发现失败」（改配置需重启）\r
- agent 表 tools 列未绑定：确认 wms 行 \`tools\` 列含对应工具名（精确名授予，改库免重启，最多滞后 30s）\r
- 按名授予时 server 未连接则该 token 跳过（warn），不影响其余工具；\`enabled: false\` 会整体跳过条目\r
\r
---\r
\r
[⬅ 返回 README](../../README.md)\r
`,Tr=`---\r
title: "文档导航"\r
order: 1\r
updated: 2026-11-14\r
---\r
\r
# 文档导航\r
\r
> 项目主入口在仓库根 [README](../README.md)；本页是 \`docs/\` 全量索引。\r
> 目录约定：核心架构文档在根层；使用/部署/协议类在 \`guides/\`；设计文档在 \`design/\`（按日期存档）；报告在 \`reports/\`；历史实现计划与设计 spec 在 \`archive/specs/\`，一次性开发/运维历史在 \`archive/\`。\r
> 历史设计文档正文里出现的 \`docs/superpowers/...\` 是 2026-10-30 文档压平前的旧目录，现已合并到 \`archive/specs/\`；这些存档按「时点记录」保留，不再逐个回填路径。\r
> **非文档数据**：\`knowledge/**\` 是 RAG 知识库语料（含演示错误码，被测试套件引用）、\`user-profile/user-profile.md\` 是运行时画像数据，两者不是项目文档，不随代码基线更新。\r
\r
## 项目约束\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [principal.md](./principal.md) | 架构与修复原则（代码精简 / 兜底边界 / 分层依赖 / 重构纪律 / 测试口径） |\r
| [dev.md](./dev.md) | 本地开发环境，只收三类：环境事实 / 本机操作 / FAQ（设计决策见 \`design/\`） |\r
| [ops.md](./ops.md) | 运行观测与运维：指标、上下文快照、保留期、探活告警 |\r
\r
## 快速开始\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [guides/setup.md](./guides/setup.md) | 本地部署详解（数据库 / API Key / 启动脚本 / agent 服务商核对） |\r
| [guides/docker-deploy.md](./guides/docker-deploy.md) | Docker 三件套部署（构建 / ACR 拉取 / 离线部署） |\r
| [guides/models.md](./guides/models.md) | 多模型与多服务商接入（含 thinking 开关） |\r
| [guides/first-run-audit.md](./guides/first-run-audit.md) | 首次使用流程规则与走查记录 |\r
\r
## 架构与设计\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [features.md](./features.md) | 项目功能介绍（已落地能力全景 / 未实现清单） |\r
| [design/agentic-simple-path-feasibility.md](./design/agentic-simple-path-feasibility.md) | Agentic 路径可行性（AgentScope 评估、落地结果与依赖冲突） |\r
| [architecture.md](./architecture.md) | 架构总览（两路径数据流 + 架构图） |\r
| [data-flow.md](./data-flow.md) | 数据流详解（路由 / 组装 / 调用 / 记账 / 观测） |\r
| [project-structure.md](./project-structure.md) | 服务端目录树（[EN](./project-structure.en.md)） |\r
| [tech-stack.md](./tech-stack.md) | 技术栈明细与扩展方向 |\r
| [design/super-agent-ideas-adopted.md](./design/super-agent-ideas-adopted.md) | Super-agent 文章中已在本项目落地的思想对照（Fast/Slow Path / 投机执行 / Inline & Spill / 链式召回 / 状态分离 / 多信道 / 事件入口 / Watchdog） |\r
| [roadmap.md](./roadmap.md) | 落地 TODO / 路线图（未完成 + 已完成存档） |\r
| [design/2026-11-08-delivery-reliability-feishu-email-plan.md](./design/2026-11-08-delivery-reliability-feishu-email-plan.md) | 主动投递可靠性规划书（outbox 重投 + 通道降级链 + 探活告警 + 飞书/邮件渠道；待实施） |\r
| [design/2026-11-11-feishu-channel-design.md](./design/2026-11-11-feishu-channel-design.md) | 飞书渠道设计决策（长连接 / 入站 / 指令 / 表情 / 回复形态 / 语音 / 文件下发） |\r
| [design/2026-11-11-session-serialization.md](./design/2026-11-11-session-serialization.md) | 渠道侧按会话串行：锁为什么必须加在读上下文之前 |\r
| [design/2026-11-11-external-tools.md](./design/2026-11-11-external-tools.md) | 外部工具三层模型（core / MCP + 声明式 HTTP / skill）与 WMS 收敛 |\r
| [design/2026-11-11-cli-terminal-probe.md](./design/2026-11-11-cli-terminal-probe.md) | CLI 终端探测 40 秒阻塞的根因与最终取舍 |\r
| [design/2026-11-12-llm-provider-model-routing.md](./design/2026-11-12-llm-provider-model-routing.md) | LLM 供应商 / 模型 / 路由三层模型与 Agent 绑定设计（待实施） |\r
| [design/](./design/) | 设计文档存档（流式改造 / 路由判定 / 多 Agent 图等，按日期） |\r
\r
## 功能指南\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [guides/api.md](./guides/api.md) | REST 接口全表与知识库管理页 |\r
| [guides/cli.md](./guides/cli.md) | CLI 命令（/agent、/goal、/resume 等） |\r
| [guides/knowledge-rag.md](./guides/knowledge-rag.md) | RAG 知识库配置（[EN](./guides/knowledge-rag.en.md)） |\r
| [guides/mcp-tools.md](./guides/mcp-tools.md) | MCP 工具接入（多 server、mcp-config.json） |\r
| [guides/wms-mcp.md](./guides/wms-mcp.md) | WMS 仓储查询 MCP 适配（独立 adapter、只读工具集、双侧鉴权） |\r
| [guides/qq-channel.md](./guides/qq-channel.md) | QQ 机器人接入（NapCat HTTP 模式） |\r
| [guides/resume.md](./guides/resume.md) | 复杂编排断点续跑 |\r
\r
## 协议与规范\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [guides/sse-protocol.md](./guides/sse-protocol.md) | SSE 流式协议（事件名 / 转义 / [DONE] / meta）（[EN](./guides/sse-protocol.en.md)） |\r
| [guides/commit-convention.md](./guides/commit-convention.md) | 提交规范（Conventional Commits 中文适配） |\r
| [guides/context-optimization.md](./guides/context-optimization.md) | 上下文优化与 Token 预算策略 |\r
\r
## 测试与报告\r
\r
| 文档 | 内容 |\r
|---|---|\r
| [guides/functional-testing.md](./guides/functional-testing.md) | 测试全景（类清单 / 场景 / 运行方式） |\r
| [reports/](./reports/) | 阶段报告存档（CLI 输出优化 / 沙箱接入 / token 预算评审） |\r
| [archive/specs/](./archive/specs/) | 历史实现计划与设计 spec 存档（时点记录，不随代码回溯更新） |\r
| [archive/2026-11-11-dev-history.md](./archive/2026-11-11-dev-history.md) | dev 历史记录（Flyway 压平、飞书八项加固；时点记录） |\r
| [task-checklist/](task-checklist/) | 可执行清单：[能力补齐](task-checklist/personal-agent-gap.md)（11/14）、[工程护栏](task-checklist/project-hardening.md)（13/14）、**投递可靠性四份**（[outbox](task-checklist/delivery-outbox.md) / [邮件渠道](task-checklist/email-channel.md) / [探活告警](task-checklist/channel-health.md) / [飞书渠道](task-checklist/feishu-channel.md)，2026-11-08 新建）；完整索引见 [readme](task-checklist/readme.md) |\r
`,Er='---\r\ntitle: "运行观测与运维手册"\r\norder: 9\r\n---\r\n\r\n# 运行观测与运维手册\r\n\r\n> 指标、日志、保留期与告警的口径。本地启动、端口与工具链见 [dev.md](./dev.md)。\r\n\r\n## 观测端点与指标\r\n\r\n端点（只暴露 health / info / prometheus 三类，`env` / `heapdump` 等不开放）：\r\n\r\n- `GET /actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`\r\n- `GET /actuator/info`\r\n- `GET /actuator/prometheus`（Prometheus 抓取格式）\r\n\r\n指标（`HarnessMetrics` 埋点，路径判定/LLM 台账/流控/编排降级四个位置）：\r\n\r\n| 指标                                | 类型      | 说明 / PromQL 示例                                                                                            |\r\n| --------------------------------- | ------- | --------------------------------------------------------------------------------------------------------- |\r\n| `harness_sse_active`              | gauge   | 活跃 SSE 连接数                                                                                                |\r\n| `harness_llm_first_token_seconds` | timer   | 首 token 延迟。P95：`histogram_quantile(0.95, sum by (le) (rate(harness_llm_first_token_seconds_bucket[5m])))` |\r\n| `harness_llm_duration_seconds`    | timer   | 单次调用耗时；route-judge P95 取 `{agent="route-judge"}`                                                          |\r\n| `harness_llm_calls_total`         | counter | 按 `status=ok/error`；错误率 = `sum(rate(...{status="error"}[5m])) / sum(rate(...[5m]))`                       |\r\n| `harness_graph_fallbacks_total`   | counter | `stage=to_agentic`（编排失败降级单 Agent）/ `digest`（聚合失败降级汇总）                                                     |\r\n| `harness_route_decisions_total`   | counter | 按 `path=graph/agentic`；Graph 降级率 = `fallbacks{stage="to_agentic"}` 速率 / `path="graph"` 速率                 |\r\n| `harness_llm_tokens_total`        | counter | 按 `kind=prompt/completion/total`、`model`；每小时用量 `increase(harness_llm_tokens_total{kind="total"}[1h])`     |\r\n\r\n成本由 token 用量 × 供应商单价换算（单价随供应商调整，不在代码里固化，避免过期价格变成伪指标）。\r\n\r\n### 会话上下文构成快照\r\n\r\n- `GET /api/monitor/context?sessionId=...`：返回该会话最近一次真实 LLM 请求发往模型前的输入构成（system 分段 / RAG / 工具 Schema / 历史 user / 历史 assistant / 当前 user）；无快照返回 404。\r\n- 快照为进程内内存数据：同会话覆盖最近一份，TTL 30 分钟、最多 200 个会话，重启清空；接口只读快照，不重新组装 prompt、不触发 RAG、不访问数据库。\r\n- 估算口径统一为 `TokenEstimator`，占比之和为 100%；ReAct 轮内动态追加的工具结果不计入（属于 `tool_call_log`），多 Agent 编排只保留最近一次调用的快照并标注实际 agent 名。\r\n- Web 监控页「活跃会话」的「上下文」按钮调用该接口，展示堆叠占比条与明细表。\r\n\r\n### 日志与 TraceId\r\n\r\n控制台输出 logstash JSON（`logging.structured.format.console: logstash`），`TraceIdFilter`\r\n把请求头 `X-Trace-Id`（缺省生成 32 位 hex）写入 MDC 的 `traceId` 并回写响应头；Reactor\r\n异步链路（route-judge / Agent 流式生成所在的 boundedElastic）经 `ContextPropagationConfig`\r\n开启自动传播后继承 MDC（模型 SDK 自建线程不在传播范围）。本地想恢复可读文本日志，启动\r\n参数加 `--logging.structured.format.console=`。\r\n\r\n### 数据保留期（两个清理任务，均可配、0 = 禁用该表）\r\n\r\n| 任务 | 覆盖表 | 保留期配置（默认） | 时间基准 |\r\n| --- | --- | --- | --- |\r\n| `ObservabilityLogCleaner` | `llm_call_log` / `tool_call_log` / `kb_retrieval_log` / `mcp_server_log` | `app.observability.retention-days`（90） | `created_at` |\r\n| `DataRetentionCleaner` | `session_message_archive` / `goal`（级联 `orchestration_checkpoint`）/ `delivery_outbox`（仅终态） | `app.retention.archive-days` / `goal-days`（180）、`outbox-sent-days`（7）/ `outbox-dead-days`（30） | `archived_at` / `finished_at` / `created_at` |\r\n\r\n- 两者都分批删除（观测 1000 行/批、业务 500 行/批），失败仅 warn 不抛，下一轮调度重试；\r\n  cron 错开（03:00 / 03:30）。\r\n- 业务数据与日志分开配的理由：归档是 `recall_history` 的数据源，`goal` 含 objective/summary\r\n  正文，和「丢了不心疼」的日志共用保留期会把两件事混在一起。\r\n- `goal` 只清终态（`SUCCEEDED` / `FAILED`）且必须先级联删检查点——只删 goal 会留下既不能被\r\n  resume、也查不到归属的 `state_json`。\r\n- 删除会话时级联清：`session_messages`（快照）、`session_summary`（滚动摘要）、渠道绑定；\r\n  `session_message_archive` 按上面的保留期走，**不随会话删除**（跨会话召回要用）。\r\n\r\n### 渠道探活告警（能报什么 / 不能报什么）\r\n\r\n`ChannelWatchdog` 每 `app.channel-watchdog.interval-ms`（默认 5 分钟）读一次各渠道的\r\n`ChannelHealth`，连续 `app.alert.min-failures`（默认 3）次 `DOWN` 才发告警；恢复后补一条\r\n「已恢复」；同一渠道 `app.alert.throttle-hours`（默认 24 小时）内不重复提醒。\r\n告警本身写 `delivery_outbox`（`source=alert`），所以发失败还会按退避重投，可在表里查。\r\n\r\n两条判定口径值得记住：\r\n\r\n- **只对「确认不可用」告警**：`ChannelHealth.Status` 四态里，`UNKNOWN` 不是故障。邮件渠道\r\n  用「最近一次投递」当探针，7 天没投递只能报 `UNKNOWN`——把它当故障会每周发一条假警报。\r\n- **告警不走故障渠道**：入队前会把 `alert-chain` 里该渠道的段删掉（`qq` 挂了走\r\n  `email:me@example.com`）。删完为空时只记 ERROR 日志——此时确实没有可用通道。\r\n\r\n**边界（刻意不做进程外心跳）**：本层只覆盖「进程活着、渠道发不出去」。进程/主机死亡不在\r\n范围内，理由：单实例个人部署下进程死了你通常会自己察觉；`restart: unless-stopped` 已覆盖\r\n崩溃拉起；外部观察者本身是需要长期维护、失效时无人监控的依赖。**残余风险**：容器一直起不来\r\n（镜像损坏、磁盘满、端口占用）时所有通知一起停，发现方式退化为「你注意到今天没收到消息」。\r\n将来若确实需要，接一个外部探活调 `/actuator/health` 即可，不需要改本层结构。\r\n',br='---\r\ntitle: "项目约束"\r\norder: 7\r\n---\r\n\r\n# 项目约束\r\n\r\n> 本文件只写**约束**，不解释原理。原理见 [architecture.md](./architecture.md)、[data-flow.md](./data-flow.md)；目录树见 [project-structure.md](./project-structure.md)；环境与命令见 [dev.md](./dev.md)。\r\n>\r\n> 判定标准：每条都应能一眼判断是否被违反；不可判定的描述不进本文件。\r\n>\r\n> 代码现状与本文件冲突时，视为**代码待修**，而不是本文件作废。\r\n\r\n## 1. 代码精简\r\n\r\n- 行数只作排查信号，不作验收指标：单文件超过约 300 行、单方法超过约 50 行时，必须在提交说明或类注释中说明职责边界与不拆理由。\r\n- 新增抽象（接口、基类、工具类、装饰器）前，必须已有 ≥3 处真实复用点；只有一处调用时直接内联。\r\n- 新增第三方依赖前，先说明为什么不能手写（给出替代实现的大致规模）。\r\n- 不为「将来可能用到」预留参数、泛型、开关。\r\n- 注释只写「为什么」，不复述「做了什么」。\r\n\r\n## 2. 防御性兜底只在边界\r\n\r\n- `try/catch` 与空值校验只允许出现在系统边界：\r\n  - HTTP 入参与请求体（`validation` 包）\r\n  - 外部系统返回：模型调用、MCP 工具、网页抓取、DB 中的外部数据\r\n  - 反序列化与异步任务入口\r\n- 内部调用之间不写兜底：信任类型系统、框架保证与上游校验结果。逻辑层出现 `if (x == null) return null;` 这类静默兜底，视为 bug。\r\n- 校验规则必须是纯函数：无 Spring 上下文、无 Mapper、无 IO；失败即抛出带明确信息的异常。\r\n\r\n## 3. 分层与依赖方向\r\n\r\n- `controller`：只做参数接收、调用 service、组装响应；不写业务分支、不直连 Mapper。\r\n- `service` 编排逻辑；`mapper` / `domain.entity` 只做数据读写与映射。\r\n- 运行时包依赖单向：\r\n  - `graph` → `agent` / `state`\r\n  - `agent` 不得 import `graph`（共用常量下沉到 `agent` 包，如 `SubtaskResults`）\r\n  - `validation` 不依赖任何运行时包；观测表只允许经 `observability` 查询服务访问\r\n- core（`deerfect-server`）编译期不得依赖 `channel-qq`（由 [deerfect-server/pom.xml](../deerfect-server/pom.xml) 的 `maven-enforcer-plugin` 守护）；渠道新增输出控制走 `deerfect-api` 的 `ReplyPostProcessor`，不改 core。\r\n\r\n## 4. 重构期不留兼容层\r\n\r\n- 旧构造器、旧重载、旧接口、旧字段、旧开关直接删除；不做旧版本兼容，不写 deprecated 桥接。\r\n- 结构调整后与新结构不符的旧测试，直接删除或重写，不为「保持测试通过」保留兼容代码。\r\n- 移动/重命名包时，同一次提交内更新文档中的路径引用（`project-structure.md` 等）。\r\n\r\n## 5. 测试\r\n\r\n- 以行为、契约与集成测试为主；不为私有方法或实现细节写单测。\r\n- 修 bug 必须补一条能复现该 bug 的行为级测试，并在提交说明中指向它。\r\n- 重构允许删除过时测试，但覆盖面不得下降：删除前确认同等行为已被其它测试覆盖。\r\n- 验证命令统一 `mvn -s .mvn/settings.xml -pl <module> -am test`；跨模块或 SQL 变更需全量 `install` 通过后才能声明完成。\r\n\r\n## 6. bug 修复\r\n\r\n- 默认路径是根治：先定位根因（调用链、数据来源、契约），再改结构或契约，不叠加临时判断。\r\n- 逃生阀：仅当根因在上游（第三方库缺陷、外部协议怪癖、平台限制）且根治成本明显高于收益时，允许最小补丁，且必须同时满足：注释写明根因与补丁理由、留 issue 或文档链接、提交说明标注「补丁」。\r\n- 禁止「加个 if 先让测试过」式修复；禁止为绕过单个异常而静默吞掉异常。\r\n\r\n## 7. 单一事实来源\r\n\r\n- 上下文预算数值只在 yaml（`app.context.*`）维护，代码里不写第二套数字。\r\n- token 估算统一用 `TokenEstimator`；新增估算逻辑须说明与现有口径的差异。\r\n- 工具装饰器只在 `DefaultToolDecorators` 登记（新装饰器 = 新类 + 此处一行）。\r\n- 数据库变更只新增 Flyway 迁移（2026-10-30 压平后从 V2 重新编号，**当前基线 V13，新增从 V14 起**），已发布迁移不改，不手工在生产库执行 DDL；迁移若被 `@SpringBootTest` 提前应用到本地库，改脚本后须按 `flyway repair` 校正 checksum 再启动。\r\n\r\n## 8. 变更纪律\r\n\r\n- 未经用户明确要求，不执行 `git commit` / `git push`（规范见 [commit-convention.md](./guides/commit-convention.md)；本机细则另见 `.trae/rules/git-workflow.md`）。\r\n- 不提交 `.mvn-repo/`、`target/`、`.idea/`、`.vscode/`、`.trae/` 与本地密钥文件。\r\n- 结构与接口变化必须同一次提交更新对应文档；文档只做必要的表述更新，不重写历史记录。\r\n',yr=`# Project Structure\r
\r
> Full version of the README "Project Structure" section: multi-module layout plus the complete \`server\` module directory tree with per-file notes.\r
\r
---\r
\r
## Maven Modules\r
\r
| Module | Responsibility | Depends on |\r
|---|---|---|\r
| \`deerfect-api\` | Shared contracts for channels and core: \`contract.api\` (callable ports), \`contract.spi\` (implementation SPIs), \`contract.model\` (port payloads), \`view\` (outward DTOs) | no reactor-internal deps |\r
| \`deerfect-server\` | Spring Boot main service, core implementation, fat-jar host | compile → \`deerfect-api\`; runtime → \`channel-qq\` |\r
| \`channel-qq\` | QQ channel (NapCat/OneBot 11): client / events / replies / persistence | compile → \`deerfect-api\` |\r
| \`deerfect-cli\` | Terminal client, pure HTTP + SSE | compile → \`deerfect-api\` |\r
| \`wms-mcp-adapter\` | Domain MCP adapter example | independent |\r
\r
> Rule: core (\`deerfect-server\`) must not reference \`channel.qq\` at compile time (enforced by \`com.dark:channel-qq:*:*:compile\`); new channel output controls go through \`ReplyPostProcessor\` without touching core.\r
\r
---\r
\r
Classic layered architecture (Controller → Service → Mapper/Entity), with the domain model grouped under the \`domain\` parent package. The tree below is the **\`deerfect-server\` module** (\`deerfect-server/src/main/java/com/dark/javaHarness/\`):\r
\r
<details>\r
<summary><b>📂 Click to expand the full directory tree</b></summary>\r
\r
\`\`\`text\r
deerfect-server/src/main/java/com/dark/javaHarness/\r
├── JavaHarnessApplication.java   # Spring Boot entry (@MapperScan("com.dark.javaHarness.mapper") scans core only; channel mappers are scanned by channel-qq itself)\r
├── controller/                   # Presentation layer: REST endpoints + SSE streaming\r
│   ├── ChatController.java       # Chat endpoints (/api/chat, /stream, /resume, /goal-status)\r
│   ├── HarnessController.java    # Management endpoints (agents / submit / goals / sessions / session-bound agent)\r
│   ├── KnowledgeAdminController.java # Knowledge-base management (document list / upload / delete / incremental sync)\r
│   ├── LlmCallController.java    # LLM call observability queries (/api/llm-calls)\r
│   ├── ToolCallController.java   # Tool-call observability queries (/api/tool-calls)\r
│   └── KbRetrievalController.java # RAG retrieval observability queries (/api/kb-retrievals)\r
├── service/                      # Business layer (interfaces + impl/)\r
│   ├── GoalService.java          # Goal ledger (AgentService/SessionService/ChatService/SessionBindingCleaner ports live in deerfect-api)\r
│   ├── RouteJudge.java           # Main-agent routing decision (SIMPLE / COMPLEX)\r
│   ├── AgentConfigProvider.java  # Reads agent runtime config (model name from route primary member)\r
│   └── impl/                     # Implementations (AgentServiceImpl / ChatServiceImpl / SessionServiceImpl / SseEncoder etc.)\r
│       ├── ChatServiceImpl.java  # Chat facade (only chat/streamReactive/resume dispatch)\r
│       ├── ChatTurnExecutor.java / ChatStreamPipeline.java  # Sync execution / streaming & SSE\r
│       ├── RouteDecider.java / TurnPersistence.java / ReplyDecorator.java  # Routing & prefetch join / session persistence / reply decoration\r
│       ├── SessionSnapshotStore.java  # Session snapshot store: single-row JSON read / append / trim / archive / serialize-retry / FOR UPDATE lock\r
│       ├── SessionHistoryArchiveService.java  # Archive search backend for recall_history (current = session-scoped / all = cross-session, time-desc)\r
│       ├── ScheduleServiceImpl.java / GoalRetryScheduler.java  # Cron dispatch to goals / backoff retry of failed goals\r
│       └── route/                # LlmRouteJudge (main-agent routing decision) / LlmSemanticTriggerJudge (channel semantic trigger)\r
├── advisor/                      # Spring AI Advisor interceptors (cross-cutting agent-flow management)\r
│   ├── ContextAssemblingAdvisor.java  # Context assembly: filter / token-budget truncation / role normalization\r
│   └── PromptBudgetAdvisor.java  # Prompt section budgets (history / user / tool-result truncation)\r
├── config/                       # Application configuration\r
│   ├── GoalExecutorConfig.java   # Execution pools: goal-exec- background goal pool + mvc-async- MVC async slot\r
│   ├── ContextBudgetProperties.java  # Unified context budget config (app.context.*; yaml is the single source of numbers)\r
│   ├── knowledge/                # RAG knowledge-base wiring\r
│   │   ├── KnowledgeProperties.java  # RAG knowledge-base config carrier (app.knowledge.*)\r
│   │   └── KnowledgeConfig.java      # Knowledge-base wiring: vector datasource / embedding model / PgVectorStore (conditional + lazy connections)\r
│   ├── datasource/               # Datasource & MyBatis-Plus wiring\r
│   │   ├── PrimaryDataSourceConfig.java  # Explicit primary (MySQL) datasource declaration (@Primary; Flyway/MyBatis ownership with multiple datasources)\r
│   │   └── MybatisPlusConfig.java    # MyBatis-Plus configuration (pagination etc.)\r
│   └── agent/                    # Agent configuration & assembly\r
│       ├── ChatAgentConfig.java      # Registers agent beans + orchestration checkpoint store\r
│       ├── ChatClientFactory.java    # Builds OpenAI-compatible ChatClient per provider + endpoint\r
│       └── ThinkingSwitchChatModel.java  # Injects thinking switch when default_params.thinking_mode=off\r
├── prompt/                       # Prompt assembly pipeline (shared by both paths)\r
│   ├── PromptAssembler.java      # Five-section system prompt (role / tool index / discipline / output / skill)\r
│   ├── MemoryPolicy.java         # Per-role session-memory injection matrix\r
│   ├── SkillManager.java / SkillRepository.java  # Markdown skill library assembly (injected on demand)\r
│   ├── ToolLazyManager.java      # Two-phase lazy tool-schema loading (lightweight index → expand_tool)\r
│   └── PromptSection.java / SkillSectionProvider.java  # Section model and skill extension point\r
├── mapper/                       # Data access: MyBatis-Plus mappers\r
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper\r
│       / LlmCallLogMapper / ToolCallLogMapper / KbRetrievalLogMapper / McpServerLogMapper / KbDocumentMapper\r
│       (the OneBot session-binding mapper lives in the channel-qq module)\r
├── domain/                       # Domain model (parent package)\r
│   ├── RouteDecision.java        # Routing decision enum (SIMPLE / COMPLEX)\r
│   ├── LlmCallLog.java           # Observability record of one LLM call (latency/tokens/outcome)\r
│   ├── KbRetrievalLog / McpServerLog / ToolCallLog  # Remaining observability records\r
│   └── entity/                   # DB entities (agent / goal / session_message / llm_provider / llm_model / llm_route / observability log tables)\r
├── enums/                        # Shared constants: AgentConstants (GoalStatus → contract.model, SseProtocol → view)\r
├── exception/                    # Global exception handling (@RestControllerAdvice, uniform {code, message})\r
├── agent/                        # Agent abstractions, orchestration & LLM calls\r
│   ├── Agent.java / AgentRegistry.java    # Agent interface and registry\r
│   ├── AgentScopeGeneralAgent.java # Agentic path: AgentScope ReAct reasoning-action loop\r
│   ├── agentic/                    # Agentic path adapters\r
│   │   ├── AgentScopeModelFactory.java  # Agent route primary member → AgentScope OpenAI-compatible Model\r
│   │   └── SpringAiToolBridge.java      # Spring AI ToolCallback → AgentScope ToolBase (MCP/sandbox reuse)\r
│   ├── GeneralAssistantAgent.java  # Agentic path fallback: ChatClient single-model chat (true token-by-token stream)\r
│   ├── AgentChatCaller.java        # LLM call lifecycle facade (call/stream retry loops, cancellation interception, observation recording)\r
│   ├── AgentChatPipeline.java      # Streaming pipeline core (streamAttempt/streamCore/tokenStream + watchdog idle timeout / frame accounting / empty-response guards)\r
│   ├── CallContext.java            # Observation value object (llm_call_log parameter bundling, shared by call/stream)\r
│   ├── CallSpecAssembler.java      # Role-based assembly policy (MemoryPolicy injection / maxTokens tiers / attachment list)\r
│   ├── AgentRequestSpecFactory.java  # Shared request-assembly factory (system / memory injection / tool decoration / output tier)\r
│   ├── LlmRetry.java               # LLM call retry policy\r
│   ├── RagPrefetcher.java          # RAG entry prefetch pool (shared by entry & orchestration; KB binding resolution)\r
│   ├── ProgressLine.java           # Progress line wire protocol (MARK+stage+SEP+detail) codec\r
│   └── BranchProgressListener.java # Subtask outcome broadcaster (shared with Graph)\r
├── graph/                        # Graph orchestration path (Lead → parallel experts → aggregation)\r
│   ├── MultiAgentGraphAgent.java   # Graph facade (execute/stream/resume entries; engine in engine/ParallelOrchestrationEngine)\r
│   ├── OrchestrationNodes.java     # Orchestration node impls (lead/subtask/aggregate + predict* bridges + streaming aggregation guard)\r
│   ├── LeadOutputParser.java       # Lead decomposition JSON parsing (subtask count + expert dispatch whitelist)\r
│   ├── OrchestrationBudget.java    # Orchestration budget ledger (AtomicLong shared accounting + degradation note)\r
│   ├── MultiAgentStreamPipeline.java  # Orchestration streaming pipeline (progress lines → SSE events)\r
│   ├── ThinkingThrottle.java       # Thinking-delta frame throttle\r
│   └── engine/                     # OrchestrationEngine / ParallelOrchestrationEngine / OrchestrationState / EngineEvent\r
├── state/                        # Execution state and checkpoint/resume\r
│   ├── OrchestrationStage.java / OrchestrationCheckpoint.java / CheckpointSelector.java\r
│   └── persistence/              # OrchestrationCheckpointStore / OrchestrationCheckpointEntity\r
├── observability/                # Observability (recorders + queries + request logging)\r
│   ├── LlmCallRecorder / KbRetrievalRecorder / McpServerRecorder  # Async persistence of the three observation tables\r
│   ├── LlmCallObserver / LlmRequestLogAdvisor  # Call-side accounting and request-start logging\r
│   ├── ObserveQueryService / ObserveQueryServiceImpl  # Unified three-table queries (controllers never touch mappers)\r
│   ├── ObservabilityLogCleaner  # Observation-table retention (llm/tool/kb/mcp, 90d default)\r
│   └── DataRetentionCleaner  # Business-data retention (archive / terminal goals + cascaded checkpoints, 180d default)\r
├── validation/                   # Pure rules (stateless, no DB access)\r
│   ├── FileNameRules.java        # Upload filename / size / kb naming\r
│   └── ProviderRules.java        # provider / apiUrl / model-name format\r
├── knowledge/                    # RAG knowledge base\r
│   ├── KnowledgeDocumentScanner.java  # Knowledge directory scan (.md/.txt, front-matter titles, bad files skipped)\r
│   ├── MarkdownChunker.java      # Paragraph-aware splitting (~700 chars + overlap, pure function)\r
│   ├── KnowledgeService(Impl).java  # Incremental ingestion (mtime compare, delete-then-write) / delete / paging / search\r
│   └── KnowledgeRetriever.java   # Retrieval injector: user text → top-k hits → 【出处N】knowledge section (budget-truncated)\r
├── channel/qq/                   # Moved out to the standalone \`channel-qq\` module (NapCat/OneBot 11, conditional on napcat.enabled)\r
├── memory/                       # User profile (cross-session long-term memory)\r
│   ├── UserProfileService.java   # Incremental extraction of new messages from idle sessions (message-id cursor) → key-merged patch into global profile Markdown (atomic write + .bak)\r
│   ├── UserProfileSectionProviderImpl.java  # Profile section injected into general/lead system prompts (order 150)\r
│   ├── SessionSummaryService.java  # Rolling summary of trimmed turns, cursor-based incremental merge (P2-3)\r
│   └── ExplicitMemorySectionProviderImpl.java  # Explicit-memory section (order 140, before profile; user-stated facts win)\r
├── deerfect-cli/                  # Moved out to the standalone \`deerfect-cli\` module (\`deerfect-cli/src/main/java/com/dark/javaHarness/cli\`, pure HTTP to 8080)\r
└── tool/                         # Tools\r
    ├── WebTools.java             # Web fetch facade (fetchUrl: fetch + 30-min content cache + query-relevant clipping)\r
    ├── HtmlToMarkdown.java / ContentRelevance.java  # Noise removal / main-content Markdown extraction / query-intent clipping\r
    ├── SandboxToolProvider.java  # Container-level sandbox tools (Python/Shell/file + browser; bounded lazy init, graceful degradation)\r
    ├── McpToolProvider.java / McpConfigParser.java  # MCP tool access (multi-server lazy connect, failure isolation) / mcp-config.json parsing\r
    ├── McpServerTools.java       # Demo tools exposed by the in-process MCP server (Streamable-HTTP /mcp)\r
    ├── ToolAssignments.java      # Tool assignment table: per-expert toolsets (dual-channel injection, least privilege)\r
    ├── ToolCallBudget.java / ToolCallTracer.java    # Tool count/result hard budget / start-stop progress lines\r
    ├── ToolCallbackDecorator.java / ToolDecorationContext.java  # Pluggable decorator interface & context (Ordered chain)\r
    ├── ToolObservationDecorator.java / ToolBudgetDecorator.java / ToolLazyLoadDecorator.java / SkillMetaToolDecorator.java  # Default decorator chain (observation 100 → budget 200 → lazy-load 300 → meta-tool 400)\r
    ├── DefaultToolDecorators.java  # Default decorator-chain factory (single source of truth; new decorator = new class + one registration line)\r
    ├── TokenEstimator.java       # Project-wide token estimation standard\r
    ├── builtin/history/RecallHistoryTools.java  # recall_history: archive retrieval by keyword / message id (scope=current|all)\r
    ├── builtin/memory/MemoryTools.java  # remember / forget / list_memories: explicit memory read-write\r
    └── DemoTools.java            # Demo toolset (time / calculator / weather)\r
\`\`\`\r
\r
</details>\r
\r
> [!NOTE]\r
> **Layer responsibilities**: \`controller\` handles REST/SSE and carries no business logic; \`service\` orchestrates the core logic (interfaces separated from implementations); \`mapper\` / \`domain.entity\` handle database reads, writes and mapping. Runtime concerns live in dedicated packages: \`agent\` (Agentic executor), \`graph\` (Graph orchestration), \`state\` (checkpoint/resume), \`observability\` (observation persistence and queries), \`validation\` (pure rules).\r
\r
\r
## Database Migrations (Flyway, V1–V11)\r
\r
\`deerfect-server/src/main/resources/db/migration/\` — applied automatically at startup. **Never edit an applied script**: a changed checksum blocks startup until \`flyway repair\` (or a manual checksum fix in \`flyway_schema_history\`).\r
\r
| Version | Purpose |\r
| --- | --- |\r
| V1 | Initial schema (sessions / goals / agents / observability tables) |\r
| V2 | Nailong TTS tool config |\r
| V3 | \`session_message_archive\` (sink for budget-trimmed messages) |\r
| V4 | \`goal.last_progress_at\` |\r
| V5 | \`recall_history\` tool assignment |\r
| V6 | \`goal.channel\` |\r
| V7 | \`agent_schedule\` cron table |\r
| V8 | \`schedule.notified_at\` push idempotency marker |\r
| V9 | Goal retry columns (\`agent_name\` / \`retry_count\` / \`next_retry_at\`) |\r
| V10 | Incremental profile distillation (\`profile_extracted_at\` / \`last_extracted_message_id\` replaces the one-shot lock) |\r
| V11 | \`idx_archive_time(archived_at, id)\` for \`scope=all\` cross-session time-desc recall |\r
| V12 | \`session_summary\` rolling conversation summary (cursor \`covered_message_id\`) |\r
| V13 | \`memory\` explicit-memory table (key / content / source / expires_at / soft delete) + \`memory\` tool group for general/lead |\r
| V14 | \`delivery_outbox\` delivery outbox (PENDING/SENT/DEAD, per-hop attempts + claim lease; failures retried and auditable) |\r
\r
---\r
\r
[⬅ Back to README](../README.md)\r
`,fr=`---\r
title: "项目结构"\r
order: 6\r
---\r
\r
# 项目结构\r
\r
> README「项目结构」一节的完整版：多模块布局与 server 完整目录树、逐文件说明。\r
\r
---\r
\r
## Maven 模块\r
\r
| 模块 | 职责 | 依赖 |\r
|---|---|---|\r
| \`deerfect-api\` | 渠道与 core 共用的契约层：\`contract.api\`（调用型端口）、\`contract.spi\`（实现型 SPI）、\`contract.model\`（端口载荷）、\`view\`（对外 DTO） | 无同反应堆依赖 |\r
| \`deerfect-server\` | Spring Boot 主服务、core 实现与 fat jar 宿主 | compile → \`deerfect-api\`；runtime → \`channel-qq\` |\r
| \`channel-qq\` | QQ 渠道（NapCat/OneBot 11）：客户端/事件/回复/持久化；事件准入 \`ChannelEventPolicy\` + 语义层端口 | compile → \`deerfect-api\` |\r
| \`deerfect-cli\` | 终端客户端，纯 HTTP + SSE | compile → \`deerfect-api\` |\r
| \`wms-mcp-adapter\` | 领域 MCP 适配范例 | 独立 |\r
\r
> 规则：core（deerfect-server）编译期不得引用 \`channel.qq\`（enforcer 守护 \`com.dark:channel-qq:*:*:compile\`）；渠道新增输出控制走 \`ReplyPostProcessor\`，主动外发走 \`MessageSender\`（deerfect-api 端口，实现由渠道装配），两者都不改 core。详见 [\`docs/design/channel-core-module-split.md\`](./design/channel-core-module-split.md)。\r
\r
---\r
\r
采用经典分层架构（Controller → Service → Mapper/Entity），领域模型统一收纳在 \`domain\` 父包下。以下目录树为 **\`deerfect-server\` 模块**（\`deerfect-server/src/main/java/com/dark/javaHarness/\`）：\r
\r
<details>\r
<summary><b>📂 点击展开完整目录树</b></summary>\r
\r
\`\`\`text\r
deerfect-server/src/main/java/com/dark/javaHarness/\r
├── JavaHarnessApplication.java   # Spring Boot 启动类（@MapperScan("com.dark.javaHarness.mapper") 仅扫 core；渠道 mapper 由 channel-qq 自身扫描）\r
├── controller/                   # 表现层：REST 接口 + SSE 流式\r
│   ├── ChatController.java       # 聊天接口（/api/chat、/stream、/resume、/goal-status）\r
│   ├── HarnessController.java    # 管理接口（agents / submit / goals / sessions / 会话绑定 Agent）\r
│   ├── ScheduleController.java   # 定时任务管理（/api/schedules 列表 / 创建 / 删除）\r
│   ├── MemoryController.java    # 显式记忆管理（/api/memories 列表 / 新增 / 软删）\r
│   ├── KnowledgeAdminController.java # 知识库管理（文档列表/上传/删除/增量同步）\r
│   ├── MonitorController.java    # 运行监控（/api/monitor 汇总快照）\r
│   ├── SkillController.java      # 技能只读校验（扫描结果/索引）\r
│   ├── ContextCompositionController.java # 上下文构成快照查询\r
│   ├── LlmCallController.java    # LLM 调用观测查询（/api/llm-calls）\r
│   ├── ToolCallController.java   # 工具调用观测查询（/api/tool-calls）\r
│   └── KbRetrievalController.java # RAG 检索观测查询（/api/kb-retrievals）\r
├── service/                      # 业务层（接口 + impl/ 实现）\r
│   ├── GoalService.java          # 目标台账（AgentService/SessionService/ChatService/SessionBindingCleaner 端口已迁 deerfect-api）\r
│   ├── ScheduleService.java      # 定时任务：定义 + 到点派发（复用 AgentService.submit，不另建执行链）\r
│   ├── RouteJudge.java           # 主 Agent 路由判断（Agentic / Graph 路径决策）\r
│   ├── AgentConfigProvider.java  # 从 agent 表读取运行配置（模型名取路由首选成员）\r
│   └── impl/                     # AgentServiceImpl / ChatServiceImpl / SessionServiceImpl / GoalServiceImpl / SseEncoder 等\r
│       ├── ChatServiceImpl.java  # 聊天门面（仅 chat/streamReactive/resume 分发）\r
│       ├── ChatTurnExecutor.java / ChatStreamPipeline.java  # 同步执行段 / 流式与 SSE 段\r
│       ├── RouteDecider.java / TurnPersistence.java / ReplyDecorator.java  # 路由判定与预取汇合 / 会话建档与写回 / 回复装饰\r
│       ├── ScheduleServiceImpl.java  # 定时任务：到期扫描 → 提交 goal → 推进 next_run_at（失败也推进，防卡调度器）\r
│       ├── ActiveGoalSectionProviderImpl.java  # 活跃目标段渲染（objective 截断 200 字 + 天数 + /goal done 提示）\r
│       ├── GoalRetryScheduler.java  # 失败重投：扫 next_retry_at 到期的 FAILED goal 重新提交（退避 1→2 分钟，上限 2 次）\r
│       ├── SessionSnapshotStore.java  # 会话上下文快照存储：单行 JSON 的读 / 追加写 / 裁剪 / 归档 / 序列化重试 / 写回行锁（自 SessionServiceImpl 抽出）\r
│       ├── MemoryServiceImpl.java  # 显式记忆：同 key 覆盖（软删旧的）、过期/软删过滤、关键词定位\r
│       ├── SessionHistoryArchiveService.java  # 会话归档检索（recall_history 数据源；current 按会话过滤 / all 跨会话按时间倒序）\r
│       └── route/                # LlmRouteJudge（主 Agent 路由判定）/ LlmSemanticTriggerJudge（渠道路由语义判定）\r
├── advisor/                      # Spring AI Advisor 拦截器（Agent 流程横切管理）\r
│   ├── ContextAssemblingAdvisor.java  # 上下文组装：过滤/token 预算截断/role 归一化\r
│   ├── PromptBudgetAdvisor.java  # Prompt 分段预算（历史/user/工具结果三段裁剪）\r
│   └── ContextCompositionAdvisor.java # 上下文构成快照采集（请求结束后记账）\r
├── config/                       # 应用配置\r
│   ├── GoalExecutorConfig.java   # 执行线程池：goal-exec- 后台 Goal 池 + mvc-async- MVC 异步槽位\r
│   ├── StreamConnectionLimiter.java  # 流式连接数限流（超限 429；终止信号传播前归还名额）\r
│   ├── ChatTimeoutProperties.java    # 模型调用超时（首帧/空闲/读超时）\r
│   ├── AgenticProperties.java    # AgentScope 路径开关（回退开关/最大迭代/重试）\r
│   ├── OpenAiClients.java        # OpenAI 兼容客户端构建（连接池与超时）\r
│   ├── ContextBudgetProperties.java  # 上下文预算统一配置（app.context.*，yaml 为唯一数值源）\r
│   ├── logging/                  # 日志与 MDC 传播\r
│   │   ├── TraceIdFilter.java    # 请求级 traceId 写入 MDC\r
│   │   ├── ContextPropagationConfig.java # 响应式上下文（MDC）跨线程传播\r
│   │   ├── ClientAbortLogFilter.java # 客户端断连噪声降级\r
│   │   └── LogNoiseConfig.java   # 第三方库日志降噪（TurboFilter）\r
│   ├── security/                 # API 鉴权（双通道）\r
│   │   ├── SecurityConfig.java   # 过滤器链装配\r
│   │   ├── ApiTokenFilter.java   # X-API-Token 常量时间比较 + 登录 Cookie 校验\r
│   │   ├── LoginStateStore.java  # 内存登录态（滑动续期/失败锁定）\r
│   │   └── AuthController.java   # /api/auth/login|logout\r
│   ├── knowledge/                # RAG 知识库装配\r
│   │   ├── KnowledgeProperties.java  # RAG 知识库配置载体（app.knowledge.*）\r
│   │   └── KnowledgeConfig.java      # 知识库装配：向量库数据源/嵌入模型/PgVectorStore（条件装配+懒连接）\r
│   ├── datasource/               # 数据源与 MyBatis-Plus 装配\r
│   │   ├── PrimaryDataSourceConfig.java  # 主库（MySQL）显式声明（@Primary，多数据源下 Flyway/MyBatis 归属）\r
│   │   └── MybatisPlusConfig.java    # MyBatis-Plus 分页等配置\r
│   └── agent/                    # Agent 配置与装配\r
│       ├── ChatAgentConfig.java  # 注册各 Agent bean + 编排检查点存储（OrchestrationCheckpointStore）\r
│       ├── ChatClientFactory.java    # 按服务商 + 端点构建 OpenAI 兼容 ChatClient（llm.client 复用）\r
│       └── ThinkingSwitchChatModel.java  # 按 default_params.thinking_mode=off 注入思考开关\r
├── prompt/                       # Prompt 组装管线（两路径统一）\r
│   ├── PromptAssembler.java      # 分段式 system prompt 组装（角色/画像/工具索引/纪律/输出约定/信道规范/skill）\r
│   ├── SystemAssembly.java       # system 段组装结果（文本 + 各段 token，快照与请求共用）\r
│   ├── MemoryPolicy.java         # 会话记忆按角色注入矩阵\r
│   ├── ActiveGoalSectionProvider.java  # 活跃目标段扩展点（按会话取跨轮目标，无则空段）\r
│   ├── SkillManager.java / SkillRepository.java  # Markdown 技能库动态装配（按需注入 system）\r
│   ├── ToolLazyManager.java      # 工具 Schema 两段式延迟加载（轻量索引 → expand_tool 展开）\r
│   ├── ContextCompositionCalculator.java / ContextCompositionRecorder.java / ContextCompositionStore.java  # 上下文构成快照：占比计算 / 写入 / 短 TTL 存储\r
│   └── PromptSection.java / SkillSectionProvider.java  # 段模型与 skill 扩展点\r
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper\r
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / SessionMessageArchiveMapper / ModelProviderMapper\r
│       / LlmCallLogMapper / ToolCallLogMapper / KbRetrievalLogMapper / McpServerLogMapper / KbDocumentMapper\r
│       / OrchestrationCheckpointMapper / ScheduleMapper\r
│       （OneBot 会话绑定 Mapper 在 channel-qq 模块）\r
├── domain/                       # 领域模型（父包）\r
│   ├── RouteDecision.java        # 路由决策枚举（SIMPLE / COMPLEX）\r
│   ├── RoutePlan.java            # 路径判定结果（agentic/graph + 置信度 + 来源）\r
│   ├── LlmCallLog.java           # 一次 LLM 调用的观测记录（耗时/token/成败）\r
│   ├── KbRetrievalLog / McpServerLog / ToolCallLog  # 其余观测记录\r
│   └── entity/                   # 数据库实体：AgentEntity / GoalEntity / SessionEntity / SessionMessageEntity\r
│                                 #   / SessionMessageArchiveEntity / ModelProviderEntity / KbDocumentEntity\r
│                                 #   / LlmCallLogEntity / ToolCallLogEntity / KbRetrievalLogEntity / McpServerLogEntity\r
│                                 #   / ScheduleEntity\r
│   └── dto/                      # 服务端内部视图 DTO：MonitorSnapshot / RuntimeMetrics / ComponentStatus / ContextComposition\r
│                                 #   （对外契约 DTO 在 deerfect-api 的 view/ 包）\r
├── enums/                        # 共享常量：AgentConstants（GoalStatus → contract.model，SseProtocol → view）\r
├── exception/                    # 全局异常处理与领域异常\r
│   ├── GlobalExceptionHandler.java   # @RestControllerAdvice，统一 {code, message}\r
│   └── ConcurrentRequestException / ResumeConflictException / ModelAuthException / ModelQuotaException\r
├── llm/                          # LLM 三层配置（provider → model → route）与解析链\r
│   ├── catalog/                  # AgentCatalog / ProviderCatalog / LlmConfigRefresher（重读 + 校验 + 回滚）\r
│   ├── routing/                  # ModelResolver / RoutePlan / RouteAttempt / FailurePolicy / RouteExecutor\r
│   ├── client/                   # LlmClientFactory / LlmClientRegistry（按 model_id 缓存与失效）\r
│   ├── call/                     # LlmCallOptions（模型默认参数 < Agent 覆盖）\r
│   ├── model/                    # ModelCapabilities（工具/视觉/思考/JSON/上下文窗口）\r
│   ├── validation/               # BindingValidator（启动与热更新校验）\r
│   └── admin/                    # LlmAdminService / LlmAdminController（/api/admin/*）\r
├── agent/                        # Agent 抽象、编排与 LLM 调用\r
│   ├── Agent.java / AgentRegistry.java    # Agent 接口与注册表\r
│   ├── AgentScopeGeneralAgent.java # Agentic 路径：AgentScope ReAct 显式推理-行动循环\r
│   ├── agentic/                    # Agentic 路径适配层\r
│   │   ├── AgentScopeModelFactory.java  # Agent 路由首选成员 → AgentScope OpenAI 兼容 Model\r
│   │   └── SpringAiToolBridge.java      # Spring AI ToolCallback → AgentScope ToolBase（MCP/沙箱工具复用）\r
│   ├── GeneralAssistantAgent.java  # Agentic 路径回退：ChatClient 单模型对话（真·逐 token stream）\r
│   ├── AgentChatCaller.java        # LLM 调用生命周期编排门面（call/stream 重试循环、取消拦截、观测记录）\r
│   ├── AgentChatPipeline.java      # 流管道核（streamAttempt/streamCore/tokenStream + watchdog 空闲超时/流帧记账/空响应防御）\r
│   ├── AggregateStreamGuard.java   # 聚合流式护栏（失败自愈与必发策略）\r
│   ├── BudgetLedger.java           # 编排预算账本契约（roundtrip 增量记账 + 熔断异常）\r
│   ├── CallCancellation.java       # 取消词汇表（取消异常的单一构造口径）\r
│   ├── LlmErrorClassifier.java     # 模型错误分类纯函数（账户级硬错误/未知名工具调用）\r
│   ├── SubtaskResults.java         # 子任务结局常量（graph 与 agent 共用，避免 agent 反向依赖 graph）\r
│   ├── CallContext.java            # 观测值对象（llm_call_log 落库参数收敛，call/stream 两通道共用）\r
│   ├── CallSpecAssembler.java      # 角色装配策略（MemoryPolicy 记忆判定/maxTokens 档位/观测名单计算）\r
│   ├── AgentRequestSpecFactory.java  # 双路径共用请求组装工厂（system/记忆注入/工具装饰/输出档位）\r
│   ├── LlmRetry.java               # LLM 调用重试策略\r
│   ├── RagPrefetcher.java          # RAG 入口预取共享池（入口与编排侧复用；kb 绑定解析内聚）\r
│   ├── ProgressLine.java           # 进度行线协议（MARK+stage+SEP+detail）编解码\r
│   └── BranchProgressListener.java # 子任务结局播报（并行完成事件串行发射；与 Graph 共用）\r
├── graph/                        # Graph 编排路径（Lead → 专家并行 → 聚合）\r
│   ├── MultiAgentGraphAgent.java   # Graph 编排门面（执行/流式/续跑入口；引擎见 engine/ParallelOrchestrationEngine）\r
│   ├── OrchestrationNodes.java     # 编排三节点实现（lead/subtask/aggregate + predict* 桥接 + 流式聚合护栏）\r
│   ├── OrchestrationPrompts.java   # 编排各环节兜底提示词（lead/aggregator/子任务 persona）\r
│   ├── LeadOutputParser.java       # lead 拆解 JSON 解析（子任务数 + 专家指派白名单）\r
│   ├── OrchestrationBudget.java    # 编排预算账本（AtomicLong 共享记账 + 降级说明）\r
│   ├── MultiAgentStreamPipeline.java  # 编排流式管道（进度行 → SSE 事件装配）\r
│   ├── ThinkingThrottle.java       # 思考增量合帧节流器\r
│   └── engine/                     # OrchestrationEngine / ParallelOrchestrationEngine / OrchestrationState / EngineEvent\r
├── state/                        # 执行态与断点续跑\r
│   ├── OrchestrationStage.java / OrchestrationCheckpoint.java / CheckpointSelector.java\r
│   ├── OrchestrationStateKeys.java  # 编排状态 Map 的键常量（引擎与节点共用的字符串键单一来源）\r
│   └── persistence/              # OrchestrationCheckpointStore / OrchestrationCheckpointEntity\r
├── observability/                # 观测（记录器 + 查询 + 请求日志）\r
│   ├── LlmCallRecorder / KbRetrievalRecorder / McpServerRecorder  # 三表异步落库\r
│   ├── LlmCallObserver / LlmRequestLogAdvisor  # 调用侧口径与请求发起观测\r
│   ├── ObserveQueryService / ObserveQueryServiceImpl  # 三表查询收口（controller 不直连 mapper）\r
│   ├── HarnessMetrics            # 指标埋点（路径判定 / LLM 台账 / 流控 / 编排降级）\r
│   ├── MonitorService / ComponentStatusService / RuntimeMetricsService  # 运行监控聚合（模型、工具、组件状态、JVM 指标）\r
│   ├── ObservabilityLogCleaner  # 观测表保留期清理（llm/tool/kb/mcp 四表，默认 90 天）\r
│   ├── DataRetentionCleaner  # 业务数据保留期清理（归档 / 终态 goal + 级联检查点，默认 180 天）\r
│   └── StaleGoalWatchdog       # Goal 停滞看门狗（RUNNING 无进展 → FAILED，默认关闭）\r
├── validation/                   # 纯规则（无状态、不查库）\r
│   ├── FileNameRules.java        # 上传文件名/大小/kb 命名\r
│   └── ProviderRules.java        # provider/apiUrl/模型名格式\r
├── knowledge/                    # RAG 知识库\r
│   ├── KnowledgeDocumentScanner.java  # 知识目录扫描（.md/.txt、front-matter 标题、坏文件跳过）\r
│   ├── MarkdownChunker.java      # 段落感知切分（~700 字符 + 重叠，纯函数）\r
│   ├── KnowledgeService.java / KnowledgeServiceImpl.java  # 对外门面（委托读写协作者 + 启动自动摄取）\r
│   ├── KnowledgeIngestor.java    # 写路径：增量摄取（mtime+kb 比对删旧写新）/ 孤儿清理 / 删除 / 分页\r
│   ├── HybridKnowledgeRetriever.java  # 读路径：向量 + BM25 + RRF 融合（不依赖写路径）\r
│   ├── KnowledgeBm25Index.java   # BM25 内存索引（懒重建，混合检索第二路）\r
│   ├── KnowledgeDirectoryWatcher.java # 知识目录监听（去抖后触发增量 sync）\r
│   ├── KnowledgeSyncView.java    # 增量同步结果（扫描/更新/跳过/chunk 数）\r
│   └── KnowledgeRetriever.java   # 检索注入器：user 文本→top-k 命中→【出处N】知识段（预算截断）\r
├── channel/qq/                   # 已整体迁出为独立模块 \`channel-qq\`（NapCat/OneBot 11，napcat.enabled 条件装配，单向依赖 core 端口）\r
├── message/                      # 主动推送（任务结果 / 失败告警外发）\r
│   ├── MessageSenderRegistry.java  # channel:target 路由；降级链 chainOf（严格）/ sendWithFallback（宽松）/ sendOnce（单跳）\r
│   ├── DeliveryOutboxService.java  # 投递 outbox：入队 / 领取（发送前计数 + 租约）/ 结案 / 换跳 / 长文转邮件\r
│   ├── DeliveryRetryScheduler.java  # 30s 扫到期消息：送达 / 退避 / 换下一跳 / 终态\r
│   └── AlertGate.java              # 告警抑制：连续 N 次才发、24h 窗口、恢复补发\r
├── memory/                       # 长期记忆（用户画像 + 显式记忆）\r
│   ├── UserProfileService.java   # 增量提炼空闲会话的新消息 → 按 key 合并进全局画像（补丁式，非全文重写；原子写 + .bak）\r
│   ├── UserProfileSectionProviderImpl.java  # 画像段注入 general/lead 的 system prompt（order 150）\r
│   ├── SessionSummaryService.java  # 会话滚动摘要：裁剪丢弃的轮次压缩落库，游标增量合并（P2-3）\r
│   └── ExplicitMemorySectionProviderImpl.java  # 显式记忆段注入（order 140，排在画像之前，冲突以用户明说为准）\r
├── deerfect-cli/                  # 已独立为 \`deerfect-cli\` 模块（\`deerfect-cli/src/main/java/com/dark/javaHarness/cli\`，纯 HTTP 连 8080）\r
├── tool/                         # 工具层\r
    ├── WebTools.java             # 网页抓取门面（fetchUrl：抓取 + 30min 内容缓存 + 相关段落裁剪）\r
    ├── HtmlToMarkdown.java / ContentRelevance.java  # 噪声剔除/主内容 Markdown 化 / 按查询意图裁剪\r
    ├── SandboxToolProvider.java  # 容器级沙箱工具（Python/Shell/文件 + 浏览器，懒初始化、失败降级、健康探测自愈）\r
    ├── SandboxShellTools.java / SandboxFileTools.java / SandboxBrowserTools.java / SandboxToolSupport.java\r
    │                             # 沙箱工具封装（执行 / 文件读写 / 浏览器 / 统一结果截断）\r
    ├── SandboxGuards.java        # 沙箱边界守卫：写入路径限 /workspace、Shell 破坏性命令拦截\r
    ├── McpToolProvider.java / McpConfigParser.java  # MCP 工具接入（多 server 懒连接、失败隔离）/ mcp-config.json 解析\r
    ├── McpServerTools.java       # 内置 MCP Server 暴露的演示工具（Streamable-HTTP /mcp）\r
    ├── ToolAssignments.java      # 工具分配表：按专家分配工具集（双通道注入，最小权限）\r
    ├── ToolInputs.java / TextSanitizer.java / ToolContextKeys.java  # 工具入参解析 / 工具结果文本清洗 / ToolContext 键常量\r
    ├── mcp/ServerTaggedCallback.java  # MCP 来源标注（观测按 server 维度拆分）\r
    ├── ToolCallBudget.java / ToolCallTracer.java    # 工具次数/结果硬预算 / 调用起止进度行（装饰内核）\r
    ├── ToolCallbackDecorator.java / ToolDecorationContext.java  # 可插拔装饰器接口与上下文（Ordered 责任链）\r
    ├── ToolObservationDecorator.java / ToolBudgetDecorator.java / ToolLazyLoadDecorator.java / SkillMetaToolDecorator.java  # 默认装饰链四组件（观测100→预算200→懒加载300→元工具400）\r
    ├── DefaultToolDecorators.java  # 默认装饰链工厂（单一事实来源；新增装饰器=新类+此处登记一行）\r
    ├── builtin/SandboxToolkit.java  # 沙箱工具包（Python/Shell/文件/浏览器回调集合）\r
    ├── builtin/DemoTools.java     # 内置演示工具（@Tool 注解通道样例）\r
    ├── builtin/history/RecallHistoryTools.java  # recall_history：按关键词/消息 ID 检索会话归档（scope=current|all）\r
    ├── builtin/memory/MemoryTools.java  # remember / forget / list_memories：显式记忆读写（多命中不删，返回候选）\r
    ├── builtin/tts/TextToSpeechTools.java  # text_to_speech 工具入口：校验 + 合成 + 写工件\r
    ├── builtin/tts/EdgeTtsClient.java      # Edge TTS 子进程调用（超时杀进程 / 并发信号量）\r
    └── TokenEstimator.java       # 全项目统一 token 估算口径\r
└── voice/                        # 语音共享能力（工具入口在 tool/builtin/tts）\r
    ├── TtsProperties.java        # app.tts 配置（工具与工件库共用）\r
    └── InMemoryVoiceArtifactStore.java  # 短 TTL 工件库（core/渠道按接口消费）\r
\`\`\`\r
\r
</details>\r
\r
> [!NOTE]\r
> **分层职责**：\`controller\` 收发 REST/SSE，不承载业务逻辑；\`service\` 编排核心逻辑（接口与实现分离）；\`mapper\` / \`domain.entity\` 负责数据库读写与映射。运行时关注点独立成包：\`agent\`（Agentic 执行体）、\`graph\`（Graph 编排）、\`state\`（检查点/续跑）、\`observability\`（观测落库与查询）、\`validation\`（纯规则）。\r
\r
\r
## 数据库迁移（Flyway）\r
\r
\`deerfect-server/src/main/resources/db/migration/\`，启动时自动执行。编号取**目录里当前最大版本号 +1**，\r
不在这里维护「当前版本」——那个数字每次加迁移都会变，以目录为准。\r
**已应用的脚本不可再改**——改动会导致校验和不匹配，需 \`flyway repair\`（或按 Flyway 的\r
逐行 CRC32 算法校正 \`flyway_schema_history.checksum\`）后才能启动。\r
\r
下表是已落地迁移的说明（记录各版本做了什么、为什么），不是必须与目录逐条同步的清单；\r
新增迁移不必回来补表。\r
\r
| 版本 | 内容 |\r
| --- | --- |\r
| V1 | 初始化 schema（会话 / goal / agent / 观测表等） |\r
| V2 | nailong TTS 工具配置 |\r
| V3 | \`session_message_archive\` 归档表（裁剪转归档的数据源） |\r
| V4 | \`goal.last_progress_at\` |\r
| V5 | \`recall_history\` 工具分配 |\r
| V6 | \`goal.channel\` |\r
| V7 | \`agent_schedule\` 定时任务表 |\r
| V8 | \`schedule.notified_at\` 主动推送幂等标记 |\r
| V9 | Goal 失败重投三列（\`agent_name\` / \`retry_count\` / \`next_retry_at\`） |\r
| V10 | 画像增量提炼（\`profile_extracted\` 一次性锁定 → \`profile_extracted_at\` + \`last_extracted_message_id\` 游标；存量会话标记已处理、不回溯） |\r
| V11 | \`idx_archive_time(archived_at, id)\`，支撑 \`scope=all\` 跨会话时间倒序召回 |\r
| V12 | \`session_summary\` 会话滚动摘要（游标 \`covered_message_id\`，滚动合并） |\r
| V13 | \`memory\` 显式记忆表（key / content / source / expires_at / deleted_at 软删）+ general/lead 工具列追加 \`memory\` 组 |\r
| V14 | \`delivery_outbox\` 主动投递 outbox（PENDING/SENT/DEAD，\`hop_attempts\` 本跳次数 + 领取租约，失败可重投可审计） |\r
| V15 | \`feishu_session_binding\` 飞书会话绑定（open_id + chat_id → session_id；渠道私有表不进 core） |\r
| V16 | \`session.creator\` 放宽到 64（飞书 \`feishu:\` + open_id 共 42 字符，原 30 装不下） |\r
| V17 | general / nailong 追加 \`play_music\`（点歌工具） |\r
| V18 | WMS 十个工具 → HTTP pack \`wms.query\`（已被 V19 取代，保留为历史） |\r
| V19 | WMS 改为 MCP 分发器 \`wms_query\`（十六个资源，替代十个专用工具） |\r
\r
---\r
\r
[⬅ 返回 README](../README.md)\r
`,Mr=`# 知识库演示与测试套件（按项目优点组织）

> **用途**：每个项目优点配一组「可直接照读的问题 + 预期表现 + 对应视频镜号」——既是知识库功能测试集，也是演示台词库。
> **配套测试文档**（4 份，均已在 \`knowledge/\` 下，不覆盖既有文件）：
> - \`knowledge/platform/平台功能与参数手册.md\` · \`knowledge/platform/常见问题FAQ.md\` —— **platform** 库，内容为项目真实配置值
> - \`knowledge/ops/推理服务运维手册.md\` · \`knowledge/ops/模型选型矩阵.md\` —— **ops** 库，故障码（ERR-4102/5201/6307/7104/8101）为**专造稳定测试桩**，仅存在于这批文档中
> - 既有 \`java/\`、\`frontend/\`、根目录 default 三处未改动
> **Agent 绑定**：V36 迁移已按角色重排——general/lead/analyst 绑 platform+ops，researcher 全库，deepseek 绑 ops，wms/nailong 不绑（防串台与保人设）

---

## 一、启用步骤

\`\`\`bash
# 1. 同步（增量，按 mtime 比对；重复执行安全）
curl -X POST http://localhost:8080/api/knowledge/sync

# 2. Agent 绑定已由 V36 迁移落库（按角色分配）；如需临时改绑定：
#    agent 表 knowledge 列填知识库名，逗号分隔，改库即时生效
\`\`\`

多知识库对应关系：\`knowledge/platform/\` → platform 库，\`knowledge/ops/\` → ops 库，根目录散文档 → default 库（所有 Agent 可检索）。

---

## 二、优点 → 问题总览（速查表）

| 优点 | 一句话陈述 | 推荐问题 | 预期表现 | 镜号 |
|---|---|---|---|---|
| ① 算力调度 | 简单请求不烧编排算力 | 「用一句话解释什么是 RAG」 | 判定 SIMPLE → 直答，无编排开销 | 镜 4 |
| ② 编排全可解释 | 谁在想什么、工具谁在调 | 复杂任务（见镜 5 台词） | COMPLEX → 任务书 → 专家并行 → 聚合 | 镜 5 |
| ③ 回答可溯源 | 每条结论带【出处N】 | 「流式连接最多允许多少个？」 | 答 **50 上限（429）** + 出处 | 镜 6 |
| ④ 编号精准召回 | 错误码类查询 BM25 强项 | 「ERR-4102 是什么问题，怎么处理？」 | 空闲后首调黑洞 + HTTP/1.1 处置 | 镜 6 备选 |
| ⑤ 参数真实 | 数值零漂移 | 「如果专家一直不返回结果，最多等多久？」 | **600 秒墙钟**（问题原文无"超时"字样） | 镜 6 备选 |
| ⑥ 静默降级 | 依赖挂了不阻断主链路 | 「ERR-6307 报了但聊天还能用，正常吗？」 | 向量库不可用时返回空、聊天照常 | 镜 8 备选 |
| ⑦ 拒答诚实 | 不编造出处与处置 | 「ERR-9999 怎么处理？」 | 明确无此码，不编造 | 附录 |
| ⑧ 多库隔离 | metadata 过滤生效 | 绑 platform 后问 ERR-4102 | 检索不到 ops 库内容 | 附录 |
| ⑨ 真实业务数据 | 存量系统 AI 服务化 | 「查一下最近 5 条入库任务」 | wms 专员返回真实单据 | 镜 7 |

---

## 三、分优点问题详表

### 优点 ①｜算力调度：简单请求不烧编排算力（镜 4）

| # | 问题（照读） | 预期表现 |
|---|---|---|
| S1-1 | 「用一句话解释什么是 RAG」 | 进度行「路由判定中…」→「判定完成：SIMPLE，走 general 直答」→ 逐 token 流式，全程无编排开销 |
| S1-2 | 「把 REQUIRES_NEW 传播行为的坑讲清楚，给出事务回滚对照表」 | 判定 **COMPLEX** 进编排——同一会话难度陡增时分流不同，对照 S1-1 展示调度价值 |

### 优点 ②｜编排全可解释（镜 5）

| # | 问题（照读） | 预期表现 |
|---|---|---|
| S2-1 | 「调研 Spring AI 的 tool calling 用法，写一份带代码示例的入门指南，并给出学习路线」 | ①判定 COMPLEX；②Lead 任务书带目标/背景/约束/交付物四要素、至多 4 条；③专家并行 + 信号量错峰；④「思考N · 专家名」折叠可见；⑤工具行挂在发起专家名下；⑥聚合成稿 |

### 优点 ③｜回答可溯源（镜 6 主问题）

| # | 问题（照读） | 预期表现 | 原分组 |
|---|---|---|---|
| S3-1 | 「流式连接最多允许多少个？」 | 答 **50 上限（超限 429）**，内联**【出处1】**指向《平台功能与参数手册》；\`meta.sources\` 可对应 | D2 |
| S3-2 | 「对话怎么证明自己是合法用户？」 | 覆盖 **HttpOnly Cookie + X-API-Token 双通道**与常量时间比较，带出处 | A3 |
| S3-3 | 「怎么让某个 Agent 只查某个知识库？」 | 答 agent 表 **knowledge 列绑定** + metadata 过滤，带出处 | FAQ |

### 优点 ④｜编号精准召回：BM25 强项（镜 6 备选 / 镜 8 素材）

| # | 问题（照读） | 预期表现 | 原分组 |
|---|---|---|---|
| S4-1 | 「ERR-4102 是什么问题，怎么处理？」 | **空闲后首调黑洞**：HTTP/1.1 + 30s 空闲驱逐 + 首帧 20s 判死；错误码精确命中是向量检索的弱项、BM25 的强项 | B1 |
| S4-2 | 「出现 ERR-5201 需要紧急处理吗？」 | 识别为**预算熔断的预期行为**，不是故障 | B2 |
| S4-3 | 「ERR-7104 和 ERR-4102 的区别？」 | **跨段对比**：MCP 工具面为空 vs 连接黑洞 | B4 |

### 优点 ⑤｜参数真实：数值零漂移（镜 6 备选）

| # | 问题（照读） | 预期表现 | 原分组 |
|---|---|---|---|
| S5-1 | 「如果专家一直不返回结果，系统最多等多久才会放弃？」 | **600 秒墙钟**——问题原文无「超时/墙钟」字样，纯语义匹配 | A1 |
| S5-2 | 「不想让一次任务把 token 额度烧穿，有什么保护？」 | **60000 token 编排熔断** + 超限占位跳过 | A2 |

### 优点 ⑥｜静默降级：依赖挂了不阻断（镜 8 备选）

| # | 问题（照读） | 预期表现 | 原分组 |
|---|---|---|---|
| S6-1 | 「ERR-6307 报了但聊天还能用，正常吗？」 | **正常**：检索限时 10 秒、向量库不可用返回空、聊天不受影响 | B3 |
| S6-2 | （运维向）停掉 PostgreSQL 容器后问 S5-1 | 回答照常生成（**无出处**），应用不报错；测完 \`docker start\` | E4 |

### 优点 ⑦｜拒答诚实：不编造（附录演示）

| # | 问题（照读） | 预期表现 | 原分组 |
|---|---|---|---|
| S7-1 | 「平台支持量子计算模型吗？」 | 知识库未覆盖时**不编造出处**，或明确说明未覆盖 | E2 |
| S7-2 | 「ERR-9999 怎么处理？」 | 手册中无此码，**不得编造处置步骤** | E3 |
| S7-3 | 输入「RAG」（3 字符） | 短于 \`min-query-chars: 8\` **跳过检索**直接回答，无出处 | E1 |

### 优点 ⑧｜多库隔离：metadata 过滤（附录验证）

| # | 前置 | 问题 | 预期表现 | 原分组 |
|---|---|---|---|---|
| S8-1 | general 的 knowledge 临时改为 \`platform\` | S4-1（ERR-4102） | **检索不到** ops 库，回答无该出处 | F1 |
| S8-2 | 改回 \`platform,ops\` | S4-1 | 出处恢复 | F2 |

### 优点 ⑨｜真实业务数据：存量系统 AI 服务化（镜 7）

| # | 前置 | 问题（照读） | 预期表现 |
|---|---|---|---|
| S9-1 | wms-mcp-adapter（:8090）已启动 | 「查一下最近 5 条入库任务」 | wms 专员经 \`wms_*\` 工具返回**真实单据**（单号/状态/数量），不编造 |

---

## 四、判定口径（什么算「通过」）

1. **出处真实**：【出处N】能在 \`meta.sources\` 找到对应文档，且文档确实包含该结论（抽 2 条人工核对）。
2. **数值零漂移**：所有参数值（600 秒 / 60000 / 50 / 1024 维…）与文档一致，出现近似值即算失败。
3. **拒答诚实**：S7 组不允许出现「编造的出处号」或「编造的故障码处置」。
4. **隔离干净**：S8-1 的回答中不得混入 ops 库内容。

---

## 五、镜号衔接速查

- **镜 4**：S1-1（简单直答）；可选加 S1-2 作对比。
- **镜 5**：S2-1（复杂编排，唯一问题）。
- **镜 6**：主问题 **S3-1**（数值 + 出处双重可验证）；备选 S4-1（BM25 话术，可复用到镜 8 画外音）。
- **镜 7**：S9-1（wms 专员；注意 wms 不绑 platform/ops 库，防串台）。
- **镜 8**：画外音可引 S4-1 的「连接层自愈」话术；S6-1 作降级演示备选。
- **附录/花絮**：S7、S8 组（诚实性与隔离性），时间充裕再加。
`,Pr=`# 《智算云上的多智能体编排平台》视频讲解文稿\r
\r
> **作品**：Deerfect Harness · 智算云主题\r
>\r
> **历史存档**：本文为 2026-10-05 的视频脚本，其中 Java 17 / StateGraph 等表述已过时；\r
> 当前技术口径以 [features.md](../features.md) 与 [tech-stack.md](../tech-stack.md) 为准；\r
> 文末「700+ 自动化测试用例」等数字同为时点值，当前为 1086，见 [README](../../README.md)。\r
> **视频定位**：作品展示 / 评审场景 · 核心功能 + 使用流程\r
> **目标时长**：约 3 分 40 秒（可按 3–4 分钟剪辑）\r
> **基调**：工程师演示风——真实录屏为主，不摆拍；每个功能点都对应系统真实行为\r
> **配套文档**：[project-intro.md](./project-intro.md)（同主题文稿，已随代码同步更新）\r
\r
---\r
\r
## 一、拍摄前准备（素材清单）\r
\r
| 素材 | 准备内容 | 状态确认 |\r
|---|---|---|\r
| 环境 | Docker 三件套已起（主服务 8080 / MySQL / PostgreSQL），\`APP_PASSWORD\` 已设置 | \`docker ps\` 三容器 Up |\r
| Web 控制台 | \`web/\` 前端 dev 或构建版，localhost:5173 可登录 | 口令登录成功 |\r
| 知识库 | \`knowledge/platform\`（参数手册/FAQ）与 \`knowledge/ops\`（运维手册/模型矩阵）测试文档已就绪，已执行 \`POST /api/knowledge/sync\`（问题库见 [knowledge-test-kit](./2026-10-05-knowledge-test-kit.md)） | 管理页可见文档列表 |\r
| wms 专员 | wms-mcp-adapter（:8090）已启动，agent 表 wms 行就绪，可查到真实库存/任务数据 | 对话能返回真实单据 |\r
| 演示任务 | 预先写好两个提问（见镜 4 / 镜 5 台词），避免现场打字卡顿 | — |\r
| 轨迹页 | 先跑一遍演示，保证 \`llm_call_log\` 有真实数据可看 | 轨迹页有记录 |\r
| 录屏 | 1080p、60fps；终端字体调大；浏览器开无痕（干净界面） | — |\r
\r
---\r
\r
## 二、分镜脚本\r
\r
### 镜 1 ｜开场 Hook（00:00 – 00:15）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 快剪三连：①复杂任务输入回车 → 进度行刷出「Lead 拆解中…」；②多路专家流式输出交错滚动；③最终成稿在聚合节点打字机式收尾 |\r
| 字幕 | 「任务发进来，自动拆解 · 并行执行 · 汇总交付」 |\r
| 口播 | “这是一个跑在智算云模型服务上的多智能体编排平台——Deerfect Harness。你只需要把任务发进来，剩下的：拆解、并行执行、汇总，它全自动完成。” |\r
\r
### 镜 2 ｜项目定位与架构（00:15 – 00:35）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 切架构图（可用配套文档第二章的双泳道图，或画外音覆盖 Web 首页空镜）；高亮 RouteJudge 菱形与上下两条泳道 |\r
| 字幕 | 「简单问题直答 · 复杂任务编排 · 全程流式可视化」 |\r
| 口播 | “它的核心是一条路由判定链：每个请求先经过 LLM 路由判定——简单问题走单 Agent 直答，不浪费算力；复杂任务才进入 StateGraph 多智能体编排。技术底座是 Java 17 加 Spring AI，模型服务来自通义千问与 DeepSeek 等智算云供应商。” |\r
\r
### 镜 3 ｜使用流程：启动与登录（00:35 – 00:50）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 终端：\`docker compose up\` 三容器就绪 → 浏览器打开 localhost:5173 → 输入口令登录 |\r
| 字幕 | 「Docker 三件套一键起 · 口令换 HttpOnly Cookie 登录态」 |\r
| 口播 | “使用流程非常简单：Docker 三件套一键启动主服务、MySQL 和 PostgreSQL；浏览器打开控制台，输入口令换取登录态——凭据不进前端，机器客户端走独立的 API Token 通道。” |\r
\r
### 镜 4 ｜演示一：简单问题直答（00:50 – 01:15）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 新建会话，输入：「用一句话解释什么是 RAG」→ 回车。**重点特写**：进度行先出「路由判定中…」，再出「判定完成：SIMPLE，走 general 直答」→ 逐 token 流式上屏 |\r
| 字幕 | 「路由判定中… → 判定完成：SIMPLE」·「真·逐 token 流式」 |\r
| 口播 | “第一个演示：简单问题。注意看顶部——先显示『路由判定中』，判定完成，结果是 SIMPLE，于是直接由 general 单 Agent 回答。回答是真·逐 token 流式，没有假打字机效果。简单请求不进编排，这就是第一层算力调度。” |\r
\r
### 镜 5 ｜演示二：复杂任务编排全流程（01:15 – 02:05）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 同会话输入复杂任务：「调研 Spring AI 的 tool calling 用法，写一份带代码示例的入门指南，并给出学习路线」→ 回车后**依次特写**：①「判定完成：COMPLEX，走 multi-agent 编排」；②Lead 拆解出的任务书卡片（目标/背景/约束/交付物 四要素，至多 4 条）；③多条专家进度行并行滚动，展开「思考 · researcher」折叠块看思考内容；④工具行挂在对应专家名下；⑤聚合节点流式成稿 |\r
| 字幕 | 「COMPLEX → Lead 拆解 → 专家并行 → 聚合成稿」·「谁在想什么、工具谁在调，全程可见」 |\r
| 口播 | “第二个演示：复杂任务。判定为 COMPLEX，进入编排。Lead 把目标拆成结构化任务书——每条都带目标、背景、约束、交付物四要素，最多四条防拆解滚雪球。多个专家并行执行，信号量错峰控制并发。注意每个专家的思考内容实时折叠展示，工具调用挂在发起它的专家名下——整个过程完全可解释。最后聚合节点把并行结果汇成一份成稿。” |\r
\r
### 镜 6 ｜演示三：知识库 RAG（02:05 – 02:30）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | ①知识库管理页：展示已同步的《平台功能与参数手册》《推理服务运维手册》；②回到对话提问：「流式连接最多允许多少个？」→ 回答给出 **50 上限（超限 429）** 且内联**【出处1】**指向《平台功能与参数手册》；③点开 \`meta.sources\` 展示来源对应关系 |\r
| 字幕 | 「pgvector + BM25 双路召回 · RRF 融合 · 回答自带出处」 |\r
| 口播 | “接着是知识能力。平台手册已经同步进知识库——现在问它：流式连接最多允许多少个？回答给出准确数字：五十个上限，超限返回 429，并自动标注出处，指向参数手册的对应章节——每一条结论都能溯源，数字可现场核对。” |\r
\r
> 备选问题（同一镜换词即可）：「ERR-4102 是什么问题，怎么处理？」——错误码精确命中，展示 BM25 编号召回强项（见测试套件 S4-1）。\r
\r
### 镜 7 ｜演示四：领域专员与工具生态（02:30 – 02:50）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | ①会话切到 wms 专员（agent 选择器），提问：「查一下最近 5 条入库任务」→ 返回真实 WMS 单据数据；②画外音覆盖时插一帧 \`mcp-config.json\` 配置文件 |\r
| 字幕 | 「MCP 工具生态 · 只读适配器 · 容器沙箱」 |\r
| 口播 | “工具生态基于 MCP 协议：兼容 Claude 同款配置文件，按服务懒连接、故障隔离。这里演示的是把存量 WMS 仓储系统包装成的只读 MCP 服务——专员查到的是真实业务数据，不是编的。代码与浏览器操作则全部在 Docker 沙箱里执行，宿主机零暴露。” |\r
\r
### 镜 8 ｜观测与成本护栏（02:50 – 03:15）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | ①切轨迹观测页：\`llm_call_log\` 列表——每轮 LLM 调用的模型、token、耗时逐行可见；②画外音覆盖：预算示意（可用文档中预算条图）；③（可选）演示子任务超时后写占位、聚合照常输出 |\r
| 字幕 | 「四张观测表 · token 核算到轮次」·「四级预算护栏 · 失败不连坐」 |\r
| 口播 | “每一次模型调用都落观测表——prompt 和 completion 的 token、耗时，精确到轮次，成本可核算。消费侧有四级预算护栏：单次编排六万 token 熔断，超限断流；单个子任务超时写占位跳过，聚合照常进行——一个专家翻车，不会拖垮整张图。” |\r
\r
### 镜 9 ｜多端入口快剪（03:15 – 03:28）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 三连快切：①终端 CLI（\`mvn -pl cli compile exec:exec\`）对话；②QQ 群里 @ 机器人，渐进式分段回复；③REST \`curl\` 一发 |\r
| 字幕 | 「Web · CLI · QQ 机器人 · REST API」 |\r
| 口播 | “入口不止网页：终端 CLI、QQ 机器人、REST API，同一套编排能力，多端可达。” |\r
\r
### 镜 10 ｜收尾（03:28 – 03:40）\r
\r
| 项目 | 内容 |\r
|---|---|\r
| 画面 | 回到深色封面/架构图定帧；字幕滚出关键数字：700+ 自动化测试用例 · 4 级预算护栏 · 四张观测表 · 多端接入 |\r
| 口播 | “七百多个自动化测试用例护航，可观测、可预算、可自愈——这就是 Deerfect Harness，谢谢观看。” |\r
| 字幕 | 「可观测 · 可预算 · 可自愈」 |\r
\r
---\r
\r
## 三、口播全文（连读版，约 640 字 / 3 分 40 秒）\r
\r
这是一个跑在智算云模型服务上的多智能体编排平台——Deerfect Harness。你只需要把任务发进来，剩下的：拆解、并行执行、汇总，它全自动完成。\r
\r
它的核心是一条路由判定链：每个请求先经过 LLM 路由判定——简单问题走单 Agent 直答，不浪费算力；复杂任务才进入 StateGraph 多智能体编排。技术底座是 Java 17 加 Spring AI，模型服务来自通义千问与 DeepSeek 等智算云供应商。\r
\r
使用流程非常简单：Docker 三件套一键启动主服务、MySQL 和 PostgreSQL；浏览器打开控制台，输入口令换取登录态——凭据不进前端，机器客户端走独立的 API Token 通道。\r
\r
第一个演示：简单问题。注意看顶部——先显示「路由判定中」，判定完成，结果是 SIMPLE，于是直接由 general 单 Agent 回答。回答是真·逐 token 流式。简单请求不进编排，这就是第一层算力调度。\r
\r
第二个演示：复杂任务。判定为 COMPLEX，进入编排。Lead 把目标拆成结构化任务书——每条都带目标、背景、约束、交付物四要素，最多四条。多个专家并行执行，信号量错峰控制并发。注意每个专家的思考内容实时折叠展示，工具调用挂在发起它的专家名下——整个过程完全可解释。最后聚合节点把并行结果汇成成稿。\r
\r
接着是知识能力。平台手册已经同步进知识库——现在问它：流式连接最多允许多少个？回答给出准确数字：五十个上限，并自动标注出处，指向参数手册的对应章节——每一条结论都能溯源，数字可现场核对。检索底层是段落切分、pgvector 向量加 BM25 双路召回、RRF 融合排序。\r
\r
工具生态基于 MCP 协议：兼容 Claude 同款配置文件，按服务懒连接、故障隔离。这里演示的是把存量 WMS 仓储系统包装成的只读 MCP 服务——专员查到的是真实业务数据。代码与浏览器操作全部在 Docker 沙箱里执行，宿主机零暴露。\r
\r
每一次模型调用都落观测表——token、耗时精确到轮次，成本可核算。消费侧有四级预算护栏：单次编排六万 token 熔断，超限断流；子任务超时写占位跳过，聚合照常进行——一个专家翻车，不拖垮整张图。\r
\r
入口不止网页：终端 CLI、QQ 机器人、REST API，同一套编排能力，多端可达。\r
\r
七百多个自动化测试用例护航，可观测、可预算、可自愈——这就是 Deerfect Harness，谢谢观看。\r
\r
---\r
\r
## 四、执行备注\r
\r
1. **真实性红线**：所有画面必须来自真实运行系统；wms 数据、观测记录、知识库回答不得摆拍或后期改字。若某演示当次效果不佳（如模型偶发跳过思考），重录该镜而不是造假。\r
2. **节奏**：镜 5 信息密度最高，字幕与口播需分主次——口播讲流程，字幕只点关键状态词（判定完成 / 任务书 / 并行 / 聚合）。\r
3. **备选镜头**：若总时长需压缩到 3 分钟内，优先砍镜 9（多端快切），镜 6/7 可合并为「知识 + 工具」一镜。\r
4. **断点续跑镜头（可选加插）**：编排中途 \`Ctrl+C\` 杀进程 → 重启 → 从检查点继续、已完成子任务不重跑。演示成本高，时间充裕再加。\r
`,Lr=`# 知识图谱分析报告\r
\r
> 分析对象：Deerfect Harness（java-harness）\r
> 图谱基线：提交 \`322c6a2\`，1696 节点 / 2901 边 / 10 层 / 12 步导览\r
> 统计口径：基于 \`file:\` 级节点的 \`imports\` 边（跨模块、跨层均按文件归属统计）；测试覆盖为文件级 \`tested_by\` 关系，非行覆盖率\r
> 时点说明：图谱统计（节点/边数、类名、符号数）为该提交快照，\`SandboxToolkit\` 等文件在后续重构中已拆分；文中测试数已按当前主干更新为 1162，其余数字不随代码回溯。\r
\r
## 摘要\r
\r
架构方向正确、模块边界清晰、失败处理成熟，但 \`server\` 内部的包级分层和几个上帝类会成为长期维护的主要成本来源。\r
\r
---\r
\r
## 一、高风险模块（按爆炸半径排序）\r
\r
| 风险级 | 模块 / 文件 | 依据 | 建议关注点 |\r
|---|---|---|---|\r
| P0 | [ChatServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 全项目扇出最高（24 条出边、0 条入边），一个类同时承担建档 / 路由判定 / 直答 / 编排 / SSE / 续跑 / 写回 / RAG / 限流 / 降级；\`complex\` | 任一环节异常直接影响主链路，优先拆分职责 |\r
| P0 | [ParallelOrchestrationEngine.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/engine/ParallelOrchestrationEngine.java) + [MultiAgentGraphAgent.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java) + [OrchestrationNodes.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java) | Graph 路径核心，三者互相依赖形成导入环；涉及并发子任务、检查点续跑、聚合降级，状态机复杂 | 子任务失败隔离、续跑一致性、聚合降级边界 |\r
| P1 | [SandboxToolProvider.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java) + \`SandboxToolkit.java\`（已拆分为 File/Shell/Browser 工具与 \`SandboxGuards\`） | 容器沙箱 = 任意命令 / 文件 / 浏览器权限；20 个 ToolCallback 集中在一个类；无 Docker 时静默降级为空工具面 | 权限最小化、降级可观测性、工具面按类别拆分 |\r
| P1 | [McpToolProvider.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/mcp/McpToolProvider.java) + [ToolAssignments.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java) | 外部 MCP 服务懒连接、失败隔离；工具权限边界（agent 表 \`tools\` 列）在此收口，配置错即越权或空工具；与两个 Provider 构成导入环 | 工具名与注册名一致性校验、启动期一次性告警 |\r
| P2 | [LlmCallRecorder.java](../../deerfect-server/src/main/java/com/dark/javaHarness/observability/LlmCallRecorder.java) | 异步落库且「异常只告警」，观测数据可能静默丢失；被 20 个文件引用 | 有界队列 + 丢弃 / 失败指标，避免排障数据缺失 |\r
| P2 | [KnowledgeServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java) | 增量摄取 + 孤儿清理 + 向量 / BM25 混合检索，写路径有数据一致性风险（16 个符号） | 摄取幂等、孤儿清理事务边界、检索失败隔离 |\r
| P2 | [OneBotEventServiceImpl.java](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java) | 渠道入口扇出 15，外部 OneBot 协议 + 长连接状态 | 事件重放 / 断连恢复 / 消息去重 |\r
\r
---\r
\r
## 二、耦合最严重的文件\r
\r
### 双向枢纽（同时高扇入 + 高扇出）\r
\r
| 文件 | 扇入 | 扇出 | 备注 |\r
|---|---|---|---|\r
| [ToolAssignments.java](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java) | 18 | 7 | 处于导入环，权限装配与工具实现纠缠 |\r
| [MultiAgentGraphAgent.java](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java) | 4 | 22 | 编排器同时依赖引擎、节点与工厂 |\r
| [PromptAssembler.java](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java) | 17 | 5 | 提示装配成为多路径公共依赖 |\r
| [KnowledgeRetriever.java](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeRetriever.java) | 15 | 5 | RAG 入口被多路径复用 |\r
\r
### 单向下游巨口（改一处牵连多）\r
\r
| 文件 | 扇出 | 说明 |\r
|---|---|---|\r
| [ChatServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 24 | 依赖面最广，任何下游签名变化都会波及 |\r
| [AgentScopeGeneralAgent.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java) | 22 | ReAct 执行器依赖消息、工具、观测多个子系统 |\r
| [AgentRequestSpecFactory.java](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java) | 21 | 请求规格组装横跨 prompt / 工具 / 检索 |\r
\r
### 高扇入基座（动它就全站回归）\r
\r
| 文件 | 扇入 |\r
|---|---|\r
| [AgentService.java](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/api/AgentService.java) | 25 |\r
| [ChatClientRegistry.java](../../deerfect-server/src/main/java/com/dark/javaHarness/config/agent/ChatClientRegistry.java) | 24 |\r
| [SessionService.java](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/api/SessionService.java) | 23 |\r
| [Goal.java](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/model/Goal.java) | 20 |\r
\r
### 「上帝文件」（文件内符号数最多）\r
\r
| 文件 | 符号数 |\r
|---|---|\r
| \`SandboxToolkit.java\`（当时，已拆分） | 20 |\r
| [client.ts](../../deerfect-web/src/api/client.ts) | 18（整个前端的 HTTP + SSE 都在一个文件里） |\r
| [KnowledgeServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java) | 16 |\r
| [ChatServiceImpl.java](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) | 15 |\r
| [ChatCli.java](../../deerfect-cli/src/main/java/com/dark/javaHarness/cli/ChatCli.java) | 13 |\r
\r
### 导入环（2 处）\r
\r
1. \`McpToolProvider\` ↔ \`SandboxToolProvider\` ↔ \`ToolAssignments\`\r
2. \`ParallelOrchestrationEngine\` ↔ \`MultiAgentGraphAgent\`\r
\r
> 环为文件级 \`imports\` 关系推导，属于结构信号，不等同于运行时循环依赖。\r
\r
---\r
\r
## 三、分层评估\r
\r
### 模块级：干净\r
\r
跨模块导入只有 3 个方向，没有反向依赖：\r
\r
| 方向 | 导入边数 |\r
|---|---|\r
| \`server → harness-api\` | 155 |\r
| \`channel-qq → harness-api\` | 21 |\r
| \`cli → harness-api\` | 11 |\r
\r
- \`wms-mcp-adapter\` 完全独立（零跨模块依赖，75% 文件级测试覆盖）\r
- \`web\` 与后端零编译期耦合\r
- \`channel-qq\` / \`cli\` 严格只依赖契约层\r
\r
### server 内部包级：分层失效\r
\r
- \`server-runtime ↔ agent-orchestration\` 双向依赖（111 / 50 条跨层导入），\`service\` / \`controller\` 与 \`agent\` / \`graph\` 边界被打穿\r
- \`tool\` 包自成一个环，权限装配（\`ToolAssignments\`）与工具实现纠缠\r
- 观测（\`LlmCallRecorder\` 20 条入边）、提示装配、配置 Bean 散布在各层，没有稳定的横切层\r
- \`harness-api\` 同时装领域模型 + 服务接口 + DTO（35 个文件），契约与领域未拆分\r
- \`web\` 仅 10.6% 生产文件有测试关系，且 API 客户端单体化\r
\r
### 文件级测试覆盖（按模块）\r
\r
| 模块 | 已测试 / 生产文件 | 占比 |\r
|---|---|---|\r
| wms-mcp-adapter | 6 / 8 | 75.0% |\r
| server | 85 / 195 | 43.6% |\r
| cli | 3 / 8 | 37.5% |\r
| channel-qq | 7 / 22 | 31.8% |\r
| harness-api | 5 / 35 | 14.3% |\r
| web | 5 / 47 | 10.6% |\r
\r
> 口径为图中 \`tested_by\` 边覆盖，非行覆盖率。\r
\r
---\r
\r
## 四、综合评价\r
\r
### 优点\r
\r
1. 多模块边界纪律好：渠道 / CLI 只依赖 \`harness-api\`，没有反向耦合，WMS 适配器独立部署\r
2. 失败处理意识强：Graph 聚合降级、子任务失败占位、沙箱不可用降级、向量库故障不阻断主链路\r
3. 测试体量与覆盖面在同类 Demo 中偏上：1162 个测试（\`server\` 968 例），\`server\` 文件级覆盖 43.6%，核心编排 / 工具类大多带 \`tested\` 标签\r
4. 可观测性成体系：四张观测表 + Prometheus + traceId + JSON 日志\r
\r
### 主要问题\r
\r
1. 核心类职责过载：\`ChatServiceImpl\` 一个类串起 9 项职责，是最大的单点风险\r
2. \`server\` 内部包级分层失效：双向依赖 + 2 个导入环，重构时容易连环回归\r
3. 契约层欠测试：\`harness-api\` 仅 14.3%，而它是 25 / 23 扇入的基座\r
4. 前端是短板：\`client.ts\` 单体 + 10.6% 覆盖 + 12 个 \`complex\` 文件\r
5. 异步观测静默失败：\`LlmCallRecorder\` 只告警不重试，排障数据可能缺失\r
\r
### 优先级建议\r
\r
1. 把 \`ChatServiceImpl\` 拆成 \`route → execute → stream → persist\` 四段流水线，保留薄门面\r
2. 用 ArchUnit 规则禁止 \`tool\` / \`graph\` 包环，并把 \`ToolAssignments\` 抽出接口\r
3. 拆分 \`harness-api\`：\`domain\` 与 \`contract\` 分开，补契约层测试\r
4. \`LlmCallRecorder\` 改有界队列 + 丢弃指标；\`client.ts\` 按领域拆分\r
5. 对 \`server\` 中 61 个 \`complex\` 文件做一次复杂度热点收敛（优先 \`graph\` / \`tool\` / \`service\`）\r
\r
---\r
\r
## 附录：图谱统计\r
\r
- 节点 1696：file 437、function 795、class 352、document 59、config 24、table 16、service 7、pipeline 6\r
- 边 2901：contains 1156、imports 828、exports 358、tested_by 213、documents 107、calls 96、depends_on 53、configures 33、related 25、implements 13、defines_schema 13、migrates 4、deploys 2\r
- 复杂文件分布：\`server\` 61、\`web\` 12、\`docs\` 7，其余模块合计 14\r
`,xr=`# 测试对数据库真实数据的依赖：全量审查报告\r
\r
> 日期：2026-11-14 ｜ 范围：\`deerfect-server\` 全部 128 个测试类、1136 个用例\r
> 方式：静态审查 + 全新空库实跑对照（MySQL 8.4，Flyway V1–V24 全量迁移）\r
> 结论先行：**在全新初始化的空库上跑全量测试，0 个失败**；不存在「必须靠本地库额外数据才能过」的用例。但存在 2 个 P1 风险与 1 处配置漂移，测试目前**发现不了**。\r
\r
---\r
\r
## 一、测试与数据库的接触面\r
\r
| 类别 | 数量 | 是否连库 |\r
| --- | --- | --- |\r
| 纯单元测试（Mockito） | 126 类 | 否 |\r
| \`@SpringBootTest\` 全上下文 | 1 类 | 是（\`JavaHarnessApplicationTests\`） |\r
| \`@SpringBootTest + @Transactional\` 集成测试 | 1 类 | 是（\`MemoryRepositoryIntegrationTest\`） |\r
\r
**1. \`JavaHarnessApplicationTests\`**（\`deerfect-server/src/test/java/com/dark/javaHarness/JavaHarnessApplicationTests.java\`）\r
\r
| 行 | 用例 | 依赖 |\r
| --- | --- | --- |\r
| 11 | \`@SpringBootTest\` | 启动全上下文 → 真实库 + Flyway + 校验器 |\r
| 18 | \`contextLoads\` | 库可连、V1–V24 可迁移、路由成员非空、被引用供应商凭证非空 |\r
| 30 | \`gracefulShutdown_isConfigured\` | 仅读 Environment（仓库 yaml） |\r
| 46 | \`implicitConfigKeys_areDeclaredInYaml\` | 仅读 Environment（仓库 yaml） |\r
| 60 | \`runtimeLimits_areConfigured\` | 仅读 Environment（仓库 yaml） |\r
\r
**2. \`MemoryRepositoryIntegrationTest\`**（\`.../memory/MemoryRepositoryIntegrationTest.java:29-30\`）\r
\r
8 个用例，类级 \`@Transactional\` 回滚，key 拼随机后缀（\`:37\`），不依赖库中已有行，也不会污染他人数据。\r
\r
**其余 126 个测试类**：全部 Mockito，无 DataSource；单元测试里出现的 \`4L/5L/11L/12L\` 等数字均为 \`when(...).thenReturn(...)\` 的桩值（如 \`llm/admin/LlmAdminServiceTest.java:210,312\`），不查库。\r
\r
---\r
\r
## 二、逐项核对结论（对应任务列的 6 个排查点）\r
\r
| # | 排查点 | 结论 | 证据 |\r
| --- | --- | --- | --- |\r
| ① | 除初始 agent 外的自建 agent | **不依赖** | 本地库多出 \`java-tutor\`（手工建），全新库无此行走查全绿；代码只在请求命中时按名查表，无列表断言 |\r
| ② | 默认工具以外的工具配置 | **不依赖** | 无测试读取 \`agent.tools\`；\`ToolAssignmentsTest\` 用内存字符串驱动，不查库 |\r
| ③ | MCP 配置与连接 | **不依赖本地文件** | \`McpToolProviderTest:31\` 注释明确避开项目根 \`mcp-config.json\`，用 \`@TempDir\`/临时文件；**实测把 \`mcp-config.json\` 移走后，\`JavaHarnessApplicationTests\` 仍 4/4 通过** |\r
| ④ | 硬编码 id / 名称 / 顺序 | **无风险** | 16 处硬编码 id 全是 Mockito 桩；无 \`@TestMethodOrder\`；surefire 未开并行 |\r
| ⑤ | 依赖供应商/模型/路由种子 | **仅启动校验依赖"有成员"** | V24 保证空库路由有成员；凭证由 yaml 默认 \`\${QWEN_API_KEY:dummy-key-for-startup}\` 满足，**不带任何 key 也能过**（实测） |\r
| ⑥ | 测试间共享状态/顺序 | **无** | 无 \`@Sql\`、无数据初始化脚本、无可变静态字段；集成测试靠事务回滚与随机 key 隔离 |\r
\r
---\r
\r
## 三、实跑对照：全新库 vs 本地开发库\r
\r
\`\`\`text\r
全新空库（harness_fresh_test）：24 个迁移 → 1136 用例，Failures 0 / Errors 0 / Skipped 3\r
本地开发库（harness）        ：同代码同参数 → 同样全绿\r
\`\`\`\r
\r
两者都是绿的，但**数据并不等价**：\r
\r
| 维度 | 本地开发库 | 全新库（迁移产物） | 说明 |\r
| --- | --- | --- | --- |\r
| agent 数 | 16 | 15 | 本地多 \`java-tutor\`（手工） |\r
| llm_provider | 3 | 2 | 本地多 1 个供应商（手工） |\r
| llm_model | 12 | 9 | 本地多 3 个模型部署（手工） |\r
| \`lead.route_id\` | 1（main） | 3（judge） | 迁移回填与本地手工改库不一致 |\r
| \`aggregator/analyst/coder.route_id\` | 1（main） | 4（multi） | 同上 |\r
| \`general.tools\` | 缺 \`browser_scroll\` | 含 \`browser_scroll\` | 本地是手工对齐过的旧值 |\r
\r
**这组差异本身就是问题**：同一份代码，CI/新部署与开发者机器上的路由与工具授权不同；而**没有任何测试断言种子状态**，所以漂移不会变红。根因与任务 12 同源：V20 按硬编码 id 回填 + 本地手工改库，且无"空库契约测试"。\r
\r
---\r
\r
## 四、风险清单\r
\r
### P1（建议尽快处理）\r
\r
1. **真模型用例用「环境变量存在」当开关**\r
   \`AgentScopeGeneralAgentTest.java:71,92\` 的 \`@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".+")\` 会在任何注入了该变量（哪怕是占位值）的环境里发起**真实外网调用**。本次审查中，注入 \`DEEPSEEK_API_KEY=dummy\` 后立即出现 2 个 401 失败——与数据库无关，却会让人误判为"空库跑不过"。\r
\r
2. **集成测试连的是开发者当前库**\r
   没有 \`src/test/resources\`、没有 test profile、没有 Testcontainers；\`application.yaml\` 的数据源默认指向本机 \`harness\`。测试理论上可能读到个人会话/记忆数据，也无法保证 CI 与本地同构。\r
\r
### P2\r
\r
3. **种子漂移无守护**：agent 路由、工具授权、供应商/模型数量均无断言，改迁移或手工改库都不会触发失败。\r
4. **\`contextLoads\` 隐式依赖 yaml 默认占位 key**：目前 \`dummy-key-for-startup\` 恰好满足启动校验；若默认值改为空，测试会以"绑定校验失败"的形式红，原因不直观。\r
5. **3 个跳过与 1 个跳过的口径差异**：不注入 \`DEEPSEEK_API_KEY\` 时 \`AgentScopeGeneralAgentTest\` 跳过 2 个 + 另 1 个其他跳过；注入后会真跑。文档若写"1136 全绿"需注明跳过数。\r
\r
---\r
\r
## 五、建议（本项不改代码，仅建议）\r
\r
1. **测试库隔离**：加 \`src/test/resources/application-test.yaml\`（或 Testcontainers MySQL），CI 与本地都用一次性库；至少把库名固定为 \`harness_test\`，避免误连开发库。\r
2. **真模型用例改用标签**：\`@Tag("live")\` + surefire 默认排除，显式 \`-Plive\` 才跑；不要用"环境变量存在"做隐式开关。\r
3. **加一个「空库契约测试」**：全新库启动后断言关键快照（agent 名单、每个 agent 的 route code、general 的工具集合、路由成员数），把第三节的漂移变成红灯。这条与任务 12 的"全新库初始化测试"是同一件事，建议合并做。\r
4. **种子去硬编码 id**（任务 12）：按 \`code\`/语义键定位，避免再次出现 V20 那种 \`m.id=12\` 的空库事故。\r
5. **文档标注**：开发库允许手工改；CI 与验收以全新库为准。\r
\r
---\r
\r
## 六、复现方式\r
\r
\`\`\`bash\r
# 1) 建空库\r
mysql -uroot -e "DROP DATABASE IF EXISTS harness_fresh_test; CREATE DATABASE harness_fresh_test CHARACTER SET utf8mb4;"\r
\r
# 2) 指向空库跑全量（不要注入 DEEPSEEK_API_KEY，否则会触发真模型用例）\r
env -u DEEPSEEK_API_KEY \\\r
  SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/harness_fresh_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \\\r
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= \\\r
  DASHSCOPE_API_KEY=ci-placeholder \\\r
  mvn -B -s .mvn/settings.xml -pl deerfect-server -am test\r
\r
# 3) 验证不依赖本地 mcp 配置\r
mv mcp-config.json mcp-config.json.bak\r
mvn ... -Dtest=JavaHarnessApplicationTests   # 4/4 通过\r
mv mcp-config.json.bak mcp-config.json\r
\r
# 4) 对比两库种子\r
mysql -uroot -e "SELECT agent_name, route_id, tools FROM harness.agent ORDER BY agent_name;"\r
mysql -uroot -e "SELECT agent_name, route_id, tools FROM harness_fresh_test.agent ORDER BY agent_name;"\r
\`\`\`\r
`,Rr=`# 智算云上的多智能体编排平台\r
## ——Deerfect Harness 面向 LLM 工作负载的调度、部署与成本管理实践\r
\r
---\r
\r
## 一、项目概述\r
\r
**Deerfect Harness** 是一个基于 Java 25 + Spring Boot 4.1 + Spring AI 2.0 构建的企业级 AI 智能体平台，提供两条执行路径：**Agentic 路径**由单 Agent 显式 ReAct 循环自主推理-行动；**Graph 编排路径**自动拆解、专家并行、汇总交付。两条路径全程流式可视化。\r
\r
项目消费智算云上的模型推理服务（通义千问 DashScope、DeepSeek 等），在其之上构建了覆盖**算力调度、模型部署、智能体框架、AI 工作负载监控与成本管理**的完整应用层体系：\r
\r
| 征集方向 | 项目对应能力 |\r
|---|---|\r
| 算力调度 | 请求级智能分流、模型分级路由、并发错峰、双闸资源保护、连接层自愈 |\r
| 模型部署 | 多服务商统一接入、模型配置库表化热生效、独立 MCP 服务、容器化交付 |\r
| 智能体框架 | Agentic + Graph 双路径执行、Lead 拆解-专家并行-聚合、RAG 混合检索、MCP 工具生态、Docker 沙箱 |\r
| 监控与成本管理 | 四张观测表、按轮次的 token 成本账本、四级预算护栏、观测数据保留期治理 |\r
\r
**技术基线**：Java 25 / Spring Boot 4.1.2 / Spring AI 2.0.2 / AgentScope ReAct（Agentic 路径）+ 自研 \`ParallelOrchestrationEngine\`（Graph 编排路径，替代 StateGraph）与 agentscope 容器沙箱 / MySQL 8.4+（Flyway 管理 13 个迁移版本 V1–V13）+ PostgreSQL pgvector（向量库）/ MyBatis-Plus / Vue 3 Web 控制台。服务端 968 个自动化测试用例（另有 harness-api 40 / QQ 83 / CLI 28 / WMS 43，合计 1162），除 \`contextLoads\` 集成用例需本机 MySQL 外不依赖真实 DB 与外网。\r
\r
---\r
\r
## 二、总体架构\r
\r
\`\`\`mermaid\r
flowchart TD\r
    A["🖥 Web 控制台 / REST API / 终端 CLI / QQ 机器人"] --> B["ChatController · 鉴权过滤器"]\r
    B --> C{"🧭 RouteJudge · LLM 路径判定<br/>(Agentic / Graph)"}\r
    C -->|Agentic| D["⚡ Agentic 路径<br/>ReAct 单 Agent · 逐 token 流式"]\r
    C -->|Graph| E["🕸 Graph 编排路径<br/>Lead → 专家并行 → 聚合"]\r
    E --> F["🧩 Lead 拆解<br/>结构化任务书 · 至多 4 条"]\r
    F --> G["🔍 researcher · 💻 coder<br/>📊 analyst · ✍️ writer · 📦 wms"]\r
    G --> H["📌 Aggregator 聚合 · 流式成稿"]\r
    D & H --> I[("🗄 Goal 状态 + 会话记忆<br/>+ LLM/工具/检索观测落库")]\r
    I --> J["📤 SSE 流式出口 · 断点续跑"]\r
\`\`\`\r
\r
- **Agentic 路径**：单 Agent 以显式 ReAct 循环自主推理-行动，逐 token 真·流式输出；\r
- **Graph 编排路径**：Lead 将目标拆解为带「目标 / 背景 / 约束 / 交付物」四要素的结构化任务书，子任务并发执行，聚合节点汇成最终回答；\r
- **专属 Agent 直答**：会话绑定领域专员（如 WMS 仓储专员）时跳过判定与 RAG 预取，该 Agent 直接应答，避免编排链路额外开销。\r
\r
---\r
\r
## 三、算力调度：面向 LLM 推理负载的细粒度调度\r
\r
项目的「算力」即云端模型推理资源（token 与并发），调度体系分五层：\r
\r
### 3.1 请求级智能分流\r
\r
\`LlmRouteJudge\` 以独立轻量 LLM 调用（独立连接池、10 秒读超时、无工具）选择执行路径：**Agentic 路径**由单 Agent 自主执行，避免为轻量请求支付 Lead 拆解 + 多专家 + 聚合的成倍推理成本；**Graph 编排路径**才进入多 Agent 拆解与并行。判定按**任务形态**而非难度：先规则短路（多工作流措辞 → Graph，≤12 字短消息 → Agentic），灰区再由 LLM 返回 \`route/confidence/workstreams\`，Graph 需 ≥2 个可独立工作流且置信度 ≥0.8，否则收敛 Agentic；判定异常兜底 Agentic（内部枚举 \`SIMPLE\`，宁可轻量，不烧 token）。运行中还有逃生舱（流式路径）：Agentic 迭代耗尽或失败且无输出时升级 Graph，Graph 编排失败则降级单 Agent 重答。判定过程本身可视化（「路由判定中…」→「判定完成」），结果经 \`meta.route\` 透出路径、置信度与决策来源；请求可通过 \`routing\`（Web 路径下拉 / CLI \`/routing\`）显式指定路径。\r
\r
### 3.2 模型分级路由\r
\r
\`model_provider\` 表登记各服务商模型端点，\`agent\` 表为每个智能体独立指定模型——**重活用强模型、轻活用快模型**：\r
\r
- 路由判定 / 画像提炼等内部调用：\`qwen3.8-27b\`（轻量、短读超时）；\r
- 常规直答：\`qwen3.7-flash\`（默认）；\r
- 编排子任务与聚合：按专家分档配置 \`qwen3.8-max\` / \`deepseek-v4-pro\` 等；\r
- 嵌入服务：\`text-embedding-v4\`（1024 维）。\r
\r
改库即生效，无需重启，相当于应用层的「按负载选卡」。\r
\r
### 3.3 并发调度与错峰\r
\r
子任务并行扇出经**信号量排队**（\`subtask-concurrency: 2\`）错峰执行：收敛下游智算服务的并发压力，并把预算熔断后的在途超额窗口从「整批」收窄到「至多 N 个在途调用」。流式 SSE 连接数设全局上限（默认 50，超限 429），防过载。\r
\r
### 3.4 资源保护双闸\r
\r
- **数量闸**：Lead 拆解子任务上限（默认 4，clamp [1,4]），防拆解滚雪球；\r
- **时间闸**：子任务墙钟超时（默认 600 秒），deadline 包装取消令牌，超时在 token 边界中止在途调用、写占位结果，聚合照常执行；\r
- **失败不连坐**：单个子任务失败写占位跳过，聚合 prompt 前置「失败说明」，一个专家翻车不拖垮整张图。\r
\r
### 3.5 连接层算力利用率优化\r
\r
针对云端推理服务长连接的「空闲后首调黑洞」问题（NAT / 网关静默杀闲置连接）：\r
\r
- \`ChatClientFactory\` 强制 **HTTP/1.1**，利用 JDK 内建 30 秒空闲连接驱逐；\r
- **流空闲看门狗**（120 秒 \`Flux.timeout\`）+ **首帧超时**（20 秒，零帧请求快速判死）组合，闭合「完全静默挂死」与「慢速滴流」两种模式；\r
- 死连接丢池重建 + 逐通道重试（可重试口径统一：超时 / 网络类 IO / 5xx / 429 / 408；已有部分 token 下发、取消、4xx 业务错误不重试），重试以 \`Mono.delay\` 非阻塞退避，且按 attempt 粒度落库观测。\r
\r
---\r
\r
## 四、模型部署：多模型多服务商的统一接入\r
\r
### 4.1 OpenAI 兼容协议统一网关\r
\r
以 Spring AI OpenAI 兼容模式统一对接多家智算服务商（DashScope 兼容端点、DeepSeek 等），API Key 全部经环境变量注入不落库，端点 URL 以 \`model_provider\` 表为准。新增供应商通常零配置——设置对应环境变量即可。\r
\r
### 4.2 模型配置库表化、热生效\r
\r
\`agent\` 表每行定义一个智能体的模型、提示词、工具包、知识库绑定、思考内容透传开关；\`AgentConfigProvider\` 每次调用读取最新配置。**在数据库里配两行就能跑起新专家**，改库即生效，无需重新部署——模型资产的运维成本降到 SQL 级。\r
\r
### 4.3 独立领域服务部署范例：wms-mcp-adapter\r
\r
独立 Maven 模块，把已存量运行的 WMS 仓储系统（jeecg-boot REST）包装成**只读 MCP Server**（Streamable HTTP \`/mcp\`，端口 8090）：\r
\r
- 10 个中文语义查询工具（任务查询 / 库存 / 单据等）；\r
- 适配侧加固：分页钳制 ≤20、审计字段黑名单（createBy / createTime 等出参剔除）、鉴权头可配置；\r
- 与主服务同机的进程级隔离部署，是「存量业务系统 AI 服务化改造」的最小侵入范例。\r
\r
### 4.4 容器化交付\r
\r
Docker 三件套一键部署（主服务 / MySQL / PostgreSQL），支持镜像构建、仓库拉取与离线部署；模型生成的代码与浏览器操作全部在**沙箱容器**内执行（Python/Shell base 容器 + browser 容器，按组独立初始化、失败只降级本组），宿主机零暴露。\r
\r
---\r
\r
## 五、智能体框架：从编排到 RAG 应用的全链路\r
\r
### 5.1 多 Agent 编排\r
\r
- **Lead 拆解**：复杂目标拆成结构化任务书（四要素：目标 / 背景 / 约束 / 交付物），单条上限 2000 字符；\r
- **专家团开箱即用**：researcher / coder / analyst / writer 四类通用专家 + 领域专员（wms）；专家只能用固定模板 + Lead 指定的工具包，Lead 不能凭空创造新身份（白名单硬约束）；\r
- **聚合节点**：并行子任务结果汇成成稿，失败 / 超时 / 预算占位自动识别并注入降级说明；\r
- **断点续跑**：长编排中断后从检查点继续，已完成子任务不重跑。\r
\r
### 5.2 RAG 知识库（混合检索）\r
\r
- pgvector 向量召回 + BM25 关键词召回，**双路 RRF 融合重排**（关键词 / 编号类查询召回显著更好）；\r
- 文档增量摄取：mtime 比对 → 段落感知切分（~700 字符 + 重叠）→ 嵌入分批（≤8/请求）→ 确定性 chunk id 精确覆盖；\r
- 检索注入：top-k 4、相似度阈值 0.5、注入预算 3000 token，回答自动附【出处N】引用与 \`meta.sources\`；\r
- 多知识库隔离：一级子目录多 KB + agent 表 \`knowledge\` 列绑定，向量 metadata 过滤；\r
- 检索限时 10 秒，超时静默降级不阻断聊天；PostgreSQL 不可用时应用照常启动。\r
\r
### 5.3 工具生态\r
\r
- **MCP 多 Server**：兼容 Claude / Cursor 的 \`mcp-config.json\`，stdio / HTTP 双传输，按 server 懒连接与故障隔离，连接失败不拖垮启动；\r
- **Docker 沙箱工具**：Python / Shell 执行、文件读写检索、浏览器操作（导航 / 快照 / 点击 / 输入）容器内执行；\r
- **工具分配硬边界**：未分配给专家的工具对模型不可见（服务端控制，非提示词约束）；\r
- **两段式工具暴露**：请求级只注入工具名称 + 用途索引，模型经 \`expand_tool\` 元工具按需展开完整 Schema，压缩上下文占用；Markdown 技能库同理（\`load_skill\` 按需加载 + mtime 热重载）。\r
\r
### 5.4 记忆体系\r
\r
- **会话记忆**：多轮上下文自动组装、按预算裁剪（MySQL 双表）；\r
- **用户画像**：后台定时扫描空闲会话（≥30 分钟静默），按快照消息游标**增量**提炼——只喂上次处理之后的新消息，LLM 只输出新增/变更补丁，代码按 \`[key]\` 合并（同 key 原位覆盖、新 key 追加），既有条目不会被模型漏写抹掉；写盘前备份 \`.bak\`、主文件被清空可自动恢复，注入 Agentic 与 Graph 编排，越聊越懂用户。\r
- **历史归档召回**：被上下文预算裁掉的消息带稳定 ID 转存 \`session_message_archive\`，\`recall_history\` 默认只搜本会话、传 \`scope=all\` 可跨全部历史会话按归档时间倒序检索并标注来源会话；向量化召回仍待做。\r
- **显式记忆**：用户明说「记住…」时落 \`memory\` 表（跨会话），\`forget\` 软删、\`list_memories\` 查看；同 key 以最新为准，注入段排在画像之前并声明冲突时以用户明说为准；CLI 与 REST 同源。\r
\r
### 5.5 多端接入\r
\r
Web 控制台（会话管理 / 思考折叠 / 轨迹观测）、REST API（同步 / SSE 流式）、Claude Code 风格终端 CLI、QQ 机器人（NapCat / OneBot 11，群聊 @ 触发、私聊白名单 + 限频、整段单条回复（超长截断）与表情包）。\r
\r
---\r
\r
## 六、AI 工作负载的监控、调度与成本管理\r
\r
### 6.1 全链路观测\r
\r
四张观测表全部落库，Web 轨迹页可查，观测查询统一经 \`ObserveQueryService\` 收口：\r
\r
| 观测表 | 记录内容 |\r
|---|---|\r
| \`llm_call_log\` | 每次 LLM 调用的会话、角色、模型、同步/流式、成败、真实 token、耗时、错误 |\r
| \`tool_call_log\` | 工具调用参数摘要与耗时，归属发起它的子 Agent |\r
| kb_retrievals | RAG 检索命中与得分 |\r
| MCP 连接事件 | 各 server 连接成败与降级记录 |\r
\r
**按模型轮次精确拆账**：Spring AI 工具循环默认把「决策轮 + 汇总轮」合并成一行日志，本项目按 usage 帧边界拆分——每轮独立记录真实 prompt / completion token、轮次序号、起止时间，成本核算到轮次粒度。\r
\r
### 6.2 四级预算护栏\r
\r
全部数值仅存于 application.yaml，代码零硬编码：\r
\r
| 层级 | 机制 | 默认值 |\r
|---|---|---|\r
| 输入侧 | 会话历史 / lead 拆解 / 聚合 / 工具结果注入 分段裁剪 | 4000 / 4000 / 12000 / 5000 token |\r
| 输出侧 | 按角色分档 maxTokens：lead / 最终回答 / 专家 | 4000 / 8000 / 4000 token |\r
| 编排全程 | 单次编排 token 消费熔断（内存账本，真实 usage 优先） | 60000 token |\r
| 工具侧 | 调用次数硬上限 + 结果注入总量双钳制 | 4 次 / 5000 token |\r
\r
超限断流、写占位跳过、聚合注入降级说明——**长任务不炸上下文、不烧爆 token**。\r
\r
### 6.3 观测数据治理\r
\r
\`llm_call_log\` / \`tool_call_log\` 按保留期（默认 90 天）分批删除（单批 1000 行防长事务），每日凌晨 3 点低峰期自动清理。\r
\r
### 6.4 可视化与可解释性\r
\r
- 真·逐 token SSE 上屏，编排各阶段实时进度行；\r
- 思考内容透传：模型思考 delta 实时折叠展示，按「思考 · general」「思考N · 专家名」「思考 · 聚合」身份归组；\r
- 工具行归属：每次工具调用挂在发起它的子 Agent 名下，多子任务交错流式不乱。\r
\r
---\r
\r
## 七、工程质量\r
\r
- 129 个测试类 / **1162 个用例**（harness-api 5 类 40 例、服务端 109 类 968 例、QQ 8 类 83 例、CLI 3 类 28 例、WMS 4 类 43 例）；除 \`contextLoads\` 集成用例需本机 MySQL 外不依赖真实 DB 与外网；\r
- Flyway 版本化数据库迁移（13 个版本 V1–V13），存量库 baseline 无缝接入；\r
- 全局统一异常响应、双通道 API 鉴权（HttpOnly Cookie 登录态 + \`X-API-Token\` 机器通道、常量时间比较防时序探测、连续失败限流锁定）。\r
\r
---\r
\r
## 八、总结与展望\r
\r
Deerfect Harness 在智算云模型服务之上，交付了一套**可观测、可预算、可自愈**的智能体平台：\r
\r
1. **算力调度**——把「用多少推理算力」变成显式决策：简单 / 复杂分流、模型分级、并发错峰、双闸保护，层层收敛无效 token 消耗；\r
2. **模型部署**——多服务商统一接入 + 配置库表化热生效，模型资产运维成本降到 SQL 级，并给出存量系统 AI 服务化（wms-mcp-adapter）的范式；\r
3. **智能体框架**——Lead 拆解 / 专家并行 / 聚合的编排闭环，叠加混合检索 RAG、MCP 工具生态与沙箱隔离，从对话到 RAG 应用一站式覆盖；\r
4. **成本管理**——轮次级 token 账本 + 四级预算护栏 + 保留期治理，让 AI 工作负载的花费可核算、可控制、可审计。\r
\r
**后续方向**：MCP 失败缓存 TTL 重试、Micrometer 全链路 Tracing、Redis 热点缓存、更多模态接入。\r
`,Ir='---\r\ntitle: "路线图与待办"\r\norder: 10\r\n---\r\n\r\n# 路线图与待办\r\n\r\n> 由原 `ROADMAP.md` 与 `roadmap.md` 合并而成；2026-09-11 结构重构：未完成区只保留 `- [ ]` 条目（P0→P3），已完成全部归档（按主题分节），拆出遗留尾巴立条。\r\n> 约定：`- [ ]` 未开始，`- [x]` 已完成。**未完成在前（按优先级），已完成在后（存档备查）**。\r\n> 术语说明：历史条目中的「路径 A / SIMPLE」对应 **Agentic 路径**，「路径 B / COMPLEX」对应 **Graph 编排路径**。\r\n> 版本基线：Spring Boot 4.1.2 / Spring AI 2.0.2 / Java 25（以根 `pom.xml` 与 [tech-stack.md](./tech-stack.md) 为准）；升级任何 AI 相关依赖前先验证兼容性。\r\n> 首次可用性约定：新增功能/入口合入前按 `docs/guides/first-run-audit.md`「五、首次使用流程规则」复查，走查结论追加至该报告「六、走查记录」。\r\n> 产品视角的能力补齐进度（主动性 / 记忆 / 触达）见 [task-checklist/personal-agent-gap.md](task-checklist/personal-agent-gap.md)：2026-11-07 为 10/14，P0 / P1 全完成，P2 完成 4/4。\r\n\r\n***\r\n\r\n## 目标架构（两路径数据流）\r\n\r\n```\r\n1. 请求进入 Harness\r\n     请求（message / sessionId / agentId）→ ChatController（统一入口）\r\n        │\r\n2. 加载会话原始数据 + 上下文组装\r\n     sessionId → session_messages 还原 Message 列表\r\n     → ContextAssemblingAdvisor 过滤 / 截断 / 角色格式化\r\n        │\r\n3. 主 Agent 前置判断（RouteJudge，LLM 决策 SIMPLE / COMPLEX，失败兜底 SIMPLE）\r\n     ├─ 场景 A 简单 → GeneralAssistantAgent 单次调用（真·逐 token 流式）→ 结束\r\n     └─ 场景 B 复杂 → MultiAgentGraphAgent 编排（自研 ParallelOrchestrationEngine：lead 拆解 → 并行子任务 → 聚合，阶段检查点续跑）\r\n                       生命周期钩子旁路推送执行进度（event: progress SSE）→ 结束\r\n```\r\n\r\n| 目标架构角色                 | 现有实现                                              |\r\n| ---------------------- | ------------------------------------------------- |\r\n| Harness 外壳入口           | `ChatController` / `ChatService` / `AgentService` |\r\n| 会话原始数据加载               | `SessionService.loadContext(sessionId)`           |\r\n| 上下文组装(过滤/截断)           | `ContextAssemblingAdvisor`                        |\r\n| 主 Agent 前置判断           | `RouteJudge` / `LlmRouteJudge`                    |\r\n| 路径 A(简单) 单次调用          | `GeneralAssistantAgent`                           |\r\n| 路径 B(复杂) 多 Agent Graph | `MultiAgentGraphAgent`（lead→并行子任务→聚合→final）       |\r\n| 统一响应出口                 | `ChatController` 同步 + 响应式 SSE                     |\r\n| 兼容兜底                   | `AgentService` 回退 general                         |\r\n\r\n***\r\n\r\n# 一、未完成 TODO\r\n\r\n## 方向定调（2026-08-28 / 2026-09-06 评估收拢，2026-10-28 更新现状）\r\n\r\n- **现状**：两路径架构闭环（RouteJudge 分流 + 自研并行引擎编排）、沙箱容器级隔离 + 按专家分配工具权限（服务端硬边界）、进度全链路可视 + 断连优雅处理、CLI/Web/QQ 三端可用、RAG 混合检索、MCP 工具生态、Docker 部署与双通道鉴权均已落地。\r\n- **短板**（按风险排序）：工程化（接口级限流、CD 自动部署、分支保护仍缺）、编排智能化（无依赖声明 / 失败重规划）、长会话记忆（滚动摘要已落地，跨会话召回仍为关键词检索、未向量化）、多实例水平扩展。\r\n- **定调**：主线 = 能力扩展（web search → RAG ✓ → MCP ✓）；支线 = 可靠性收口（P0）；中期 = 编排智能化（P2）；远期 = 工程化可观测 + 产品化（P3）。\r\n- **增补定档**（2026-09-06）：Goal 失败重投 → P2；长期记忆滚动摘要 → P2；多实例水平扩展、Agent 行为回归评测集 → P3；多模态输入 → 远期。\r\n\r\n## P0 · 可靠性与安全收口（眼前优先，先做稳）\r\n\r\n- [ ] **接口级限流防刷**：模型调用重试与指数退避已由 `LlmRetry` 落地（3 次），流式并发上限已由 `StreamConnectionLimiter` 落地；剩余：按 IP / 端点的请求级限流（超限 429）\r\n  - 验收：突发请求超过阈值时返回 429，且不影响正常会话\r\n- [x] **并发 / 资源控制**：流式连接数限制、模型调用超时兜底与熔断降级（COMPLEX 编排失败可降级为 SIMPLE 单模型重答）。已完成（2026-09-13）\r\n  - 2026-09 部分完成：异步线程池隔离与参数化已落地（`goalExecutor` 有界池 + 拒绝兜底 + MVC 异步槽位，见存档「异步治理与发布工程」）\r\n  - 2026-09-13 完成：流式连接数限制（`StreamConnectionLimiter`，stream/resume 两入口 tryAcquire + doFinally 释放，超限 429，`app.chat.max-stream-connections` 配置 0=不限制）；LLM 超时参数化（`ChatTimeoutProperties` + `app.chat.timeouts.*`，connect/read/stream-idle 秒值，未配置回退现值）；COMPLEX 编排失败降级重答（流式与同步路径，降级为会话绑定 Agent 单模型重答一次，一层兜底不递归，`app.chat.complex-fallback.enabled` 开关）；spec 见 `docs/archive/specs/add-stream-limit-and-complex-fallback/`\r\n  - 验收：并发提交多个流式请求稳定，无连接/线程池耗尽（全量回归 482 例绿；限流计数/超限/降级重答由 StreamConnectionLimiterTest + ChatServiceImplTest 降级用例覆盖）\r\n- [x] **工具分配最小权限化**：已完成（2026-09-14）。`general`（所有回退路径的落点：路由兜底/未识别专家/lead 漏指派）收敛为只读探索者——legacy 分配 = webTools（fetchUrl/getCurrentTime）+ `sandbox.read` + `sandbox.browser` + MCP 白名单（页面交互类，只读探索的推进动作），去掉 `sandbox.base`（执行）与 `sandbox.write`（写入）；执行/写入只留 lead 明确指派的 `coder`（base+write，读文件走 shell cat 闭环不变）/`analyst`（base+read）；researcher/未登记专家面零变化。数据驱动优先于 legacy，生产库 general 行 `tools` 列需手工对齐（README「MCP 工具接入」节已附收敛 UPDATE，改库即生效免重启）。验证：ToolAssignmentsTest 13 例全绿（general 只读面 5 callbacks + verify never base/write、重名去重重算、blank 回退断言同步）\r\n- [ ] **限制思考预算（Thinking Budget）**：qwen3 类混合推理模型的思考段不受控——长思考拖慢首 token、烧 token。经模型参数透传思考开关与预算上限（如 `enable_thinking` / `thinking_budget`），按 agent/场景粒度生效\r\n  - 设计要点待细化：参数透传方式（Spring AI 2.x `ChatOptions` 扩展）、配置落点（agent 表列 vs `app.chat.*`）、路由场景（SIMPLE 直答可关思考）；思考开关本身已落地（agent 表 `thinking` 列 + `ThinkingSwitchChatModel`），预算上限未做\r\n  - 验收：指定 agent 的思考 token 有上限且可观测；关闭思考的会话直答不含思考段\r\n- [x] **沙箱读写工具的重置与安全校验**：已完成（2026-11-12 核对）。`SandboxGuards` 强制读写路径落在 `/workspace`（拒绝 `~` 展开与 `..` 越界），并拦截 `rm -rf /`、`mkfs`、`dd of=/dev/`、fork 炸弹等破坏性命令；`SandboxToolProvider` 已有健康探测（`PROBE_TOKEN`）与失败自动重建（`rebuildBase`，单飞互斥 + 限时探测），坏容器无需停服即可自愈\r\n  - 已知边界：符号链接逃逸依赖容器隔离兜底（容器不挂载宿主目录）；若将来给沙箱挂载宿主卷，需补容器内 `realpath` 校验\r\n\r\n## P1 · 能力扩展主线（产品核心价值）\r\n\r\n- [x] **Web Search 接入**：注册搜索服务商 API（博查/Tavily/SerpAPI 等任一），新增 `search` 工具归入 `WebTools`，分配给 researcher/general——补「调研」第一步空缺，浏览器组退居 JS 渲染兜底\r\n  - 验收：researcher 对「近期事件」类问题能返回带来源的检索结果\r\n  - 进度（2026-09-12）：改走 MCP hosted 路线——Tavily 远程 MCP（Streamable HTTP）经 `mcp-config.json` 接入（该文件含 key 转本地不入库，模板见 README「MCP 工具接入」），general/researcher 的 agent 表 `tools` 列追加 `tavily_search`（数据路径分配；注意真实注册名是下划线 `tavily_search` 而非文档宣传的 `tavily-search`，另有 SDK baseUri.resolve 丢 query 的坑已修，见 spec）；待端到端验收后勾选\r\n- [x] **知识库增强余项**：已完成（2026-09-16）。① sync 并发串行化（ReentrantLock tryLock 快速失败，手动/监听并发不重复摄取）；② 目录文件监听 `KnowledgeDirectoryWatcher`（WatchService 根目录+一级子目录、新建子目录补注册、事件风暴 debounce 合并、异常 warn 自恢复、`watch-enabled` 默认关）；③ BM25 内存索引 + RRF 融合 `KnowledgeBm25Index`（PG 向量表 chunk 懒构建、中文 bigram/ASCII 词元、k1=1.2/b=0.75、RRF k=60 归一化 (0,1]、护栏 `bm25-max-chunks`、`hybrid-enabled` 默认关零回归，sync/delete 后失效懒重建）；④ 管理端点扩展（`/search?full=true` 带片段原文向后兼容、`POST /api/knowledge/upload` multipart ≤1MB 只落盘）+ 单文件管理页 `static/knowledge.html`（台账/删除/同步/上传/调试检索，503 降级横幅）\r\n  - 验证：知识库面单测 85 例全绿（含 watcher 真实 WatchService 时序用例、RRF 融合排序、失效钩子、上传校验/路径穿越拒绝）；监听/混合检索/上传的端到端手工验收待运行环境（watch-enabled 与 hybrid-enabled 均默认关，开启方式见 README「知识库增强配置」）\r\n- [x] **RAG 旁路超时治理**（2026-09-12 tavily e2e 时发现）：已完成（2026-09-18，spec 见 docs/archive/specs/add-rag-bypass-timeout-prefetch/）。① 检索限时：`searchWithTimeout` 包裹（`app.knowledge.search-timeout-seconds` 默认 10，0=关闭直通；守护线程池 + Future 限时，超时/异常 warn 降级 null 走既有静默语义）；② 入口预取：`KnowledgeRetriever.prefetch` 请求级缓存（sessionId 键 + query/kb 双重校验命中短路，专家节点 query=子任务描述天然不命中仍精准现查）；③ judge/预取并行汇合：ChatServiceImpl 同步与流式两入口预取先行提交与 judge 并行，judge 语义零改动，汇合窗口 2s 超时放弃\r\n  - 验收：模拟嵌入接口挂起时，请求在秒级超时后正常降级回答（而非分钟级阻塞）——单测以 sleep 挂起 mock 验证 1s 限时降级；KnowledgeRetrieverTest 24 例 + ChatServiceImplTest 33 例全绿\r\n  - 顺带优化：RouteJudge 判定与 RAG 检索**无数据依赖**（都只吃用户原始消息），可并行执行再汇合——省掉「judge LLM（秒级）→ RAG（嵌入+PG）」的串行等待；实现为独立线程 + future 带超时汇合，judge 异常兜底 SIMPLE 的既有语义不变；注意 embed→search 本身是串行数据依赖且 RAG 结果是 LLM 请求前置材料，**不可**在依赖链内部并行化\r\n- [ ] **聚合前子任务结果预算分摊裁剪**（拆自 Token 预算条目遗留）：子任务结果喂聚合前按预算分摊裁剪，前移聚合侧 sections 兜底——避免输出都长时靠后子任务整段被剪\r\n  - 验收：多子任务结果超预算时各结果按分摊裁剪而非靠后者整段丢失\r\n- [ ] **对话内 Goal 命令（跨轮长期目标）**：在现有 goal 异步台账之上补「目标记忆」形态——用户在对话中声明长期目标（CLI `/goal <objective>` 起步，自然语言识别后续增强），落 goal 表并绑定 `session_id`；每轮对话 `ContextAssemblingAdvisor` 把活跃目标及最近进展注入上下文，agent 主动推进并汇报；完成由 LLM 自评 + 用户 `/goal done <id>` 双通道确认\r\n  - 设计要点：V18 迁移加 `mode` 列区分 async（现有批任务，全自主跑完）/ conversational（人机协作跨轮推进）；**conversational 目标必须排除在** **`failAllRunning`** **启动清理之外**——它没有后台执行体、可存活数天，重启即被误标 FAILED 等于丢数据；注入活跃目标数设上限防 prompt 膨胀；复杂推进复用路径 B 编排，结果摘要回写 `goal.summary`\r\n  - 验收：会话内声明目标，隔多轮 agent 仍能引用并主动汇报进展；`/goal done` 后不再注入；服务重启后目标状态完好且未被标 FAILED\r\n\r\n## P2 · 编排智能化（中期，agent 从「能跑」到「聪明」）\r\n\r\n- [ ] **子任务失败重规划**：子任务失败/结果为空时，把失败上下文交 lead 反思一轮（重拆 or 换专家 or 降级放行），而非直接聚合残缺结果\r\n  - 验收：mock 某专家首次失败，端到端能看到重试/换人后的成功聚合\r\n- [ ] **子任务依赖编排**：lead 拆解支持声明依赖（如 `depends_on`），有依赖的子任务串行、无依赖的并行，替代当前纯并行\r\n  - 验收：「先调研 X 再基于结论写代码」类任务按依赖顺序执行\r\n- [x] **复杂路径 Checkpointer（落库断点）**：已落地（`orchestration_checkpoint`，LEAD\\_DONE / SUBTASKS\\_DONE / DONE 三阶段，见 [guides/resume.md](./guides/resume.md)）。\r\n- [x] **Goal 失败重投（2026-11-06 完成）**：失败 Goal 按 1→2 分钟退避自动重投，最多 2 次\r\n  - 落地：V9 给 `goal` 补 `agent_name` / `retry_count` / `next_retry_at`（原设计笔记写的 V10 编号已重整为 V9；此前 goal 不记录执行 Agent，既没法重投也看不出谁跑的）；`GoalRetryScheduler` 每 30 秒扫到期项调 `AgentService.retry`，重投跑**同一个 goal**以保住 traceId 与会话归属\r\n  - 取舍：不做「仅瞬态失败」精细分类——提交异常分支统一排期，取消类异常白名单排除；watchdog 只把停滞目标标 FAILED，两者不并发执行同一 Goal\r\n  - 验收：端到端造 FAILED 到期 goal → 30 秒后受理「重投第 1/2 次」→ 执行成功，`retry_count=1`、标记清空；成功路径 `retry_count` 恒为 0\r\n- [x] **长期记忆滚动摘要**：会话历史超预算被裁剪的最旧轮次，滚动压缩为一段摘要注入 system（`MemoryPolicy` 扩展），长会话不丢单\r\n  - 进度（2026-11-03）：已落地最小版「消息 ID + 裁剪转归档 + 历史引用行 + `recall_history` 单跳检索」\r\n  - 进度（2026-11-07）：滚动摘要压缩落地——`ContextAssemblingAdvisor` 裁剪时把丢弃消息投递给 `SessionSummaryPort`，`SessionSummaryService` 异步用轻量模型滚动合并（已有摘要 + 新增对话，≤`app.context.summary-max-chars`），落 `session_summary` 表（V12，`covered_message_id` 增量游标）；下一轮组装把摘要并入 system 段；LLM 失败不写库不阻塞主链\r\n  - 验收：超长会话中早期关键信息（如用户自述偏好）仍能被引用\r\n- [ ] **对话历史长期归档（RAG 记忆库）**：旧会话原文跨会话归档 + 向量化，按 query 检索召回（解决「上个月的 bug 最后怎么解决的」类提问）——大语料、按需召回，是长期记忆 RAG 的正确挂载点（用户偏好画像为常驻小数据，全量注入即可，不走 RAG）\r\n  - 进度（2026-11-06）：归档表 `session_message_archive` + `recall_history` 已落地；**跨会话召回已支持**（`scope=all`，按归档时间倒序 + 关键词，结果带来源 sessionId；新增 `idx_archive_time` 支撑跨会话排序）；向量化召回仍待做（当前关键词 LIKE 在语料变大后是全表扫描）\r\n  - 设计要点：旧会话（空闲超阈值/显式归档）原文从 session\\_messages 快照导出入归档表 + pgvector 向量化（复用知识库 embedding 批量链路与 PgVectorStore 设施）；与「长期记忆滚动摘要」互补——摘要压缩掉的原文先进归档库再删快照，信息零丢失\r\n  - 验收：数月前旧会话的细节可被自然语言问题召回，并引用出处会话\r\n- [x] **画像提取活跃重置（长青会话缺口，2026-11-06 完成）**：旧设计「一次会话只提炼一次」（`profile_extracted` 锁定）导致长青会话（QQ 渠道按用户固定 sessionId）在首次提炼后新增的对话不再被捕捉\r\n  - 落地：改为增量提炼——`session.profile_extracted_at` 记录「上次提炼对应的活跃时刻」，`last_active_at` 超过它即重新入队（写回路径零维护）；`session.last_extracted_message_id` 做快照游标，每轮只喂游标之后的新消息（≤40 条 / 6000 字符）；LLM 只输出「新增/变更」补丁，代码按 `[key]` 合并（同 key 覆盖、新 key 追加），既有条目不再依赖提示词自觉保留；写盘前备份 `.bak`，主文件被清空时自动从备份读\r\n  - 取舍：存量会话在 V10 迁移里统一标记为「已处理到当时活跃时刻」，升级后不回溯重提炼（避免 88 个历史会话一次性灌进画像）；「活跃重置」由 `last_active_at` 比较天然实现，不再需要 `touchSession` 重置标记\r\n  - 验收：真机两轮对话验证——首轮喂 2 条消息写入 `[技术栈]`/`[沟通风格]`；次轮只喂新增 2 条且同 key 原位覆盖、旧条目保留；空闲后不再重扫\r\n\r\n## P3 · 工程化与产品化（按需启动）\r\n\r\n- [x] **Actuator + 监控**：已落地（2026-11-12 核对）。`spring-boot-starter-actuator` + `micrometer-registry-prometheus`，`application.yaml` 暴露 `health,info,prometheus`；Grafana 看板由部署侧自行配置（仓库不含）\r\n  - 验收：`/actuator/health` 可用，`/actuator/prometheus` 可被 Prometheus 抓取\r\n- [ ] **完整链路追踪（Micrometer Tracing）**：接 Spring AI 原生 observation（micrometer-tracing + Zipkin exporter + Zipkin 容器），补齐 `llm_call_log`（成本账本）不具备的**单次请求耗时瀑布**——HTTP → 路由 → lead 拆解 → 各专家 → 聚合每段耗时与父子 span 关系；建议与「Actuator + 监控」同批实施（共享 Micrometer 基建）\r\n  - 验收：Zipkin UI 能看到一次聊天请求的完整瀑布图，观测埋点代码不重写（llm\\_call\\_log 与 Tracing 互补共存）\r\n- [x] **工具调用查询端点** **`/api/tool-calls`**（拆自工具日志条目验收尾巴）：`/api/tool-calls?sessionId=` 可查询一次编排内全部工具调用（含 MCP 工具及来源 server）；MCP 某 server 故障时能从日志定位到该 server\r\n  - 验收：与 `GET /api/llm-calls` 同风格的分页查询可用\r\n- [ ] **会话缓存**：`spring-boot-starter-data-redis` 缓存会话快照，降低 MySQL 压力\r\n  - 验收：会话上下文命中 Redis，DB 读次数下降\r\n- [ ] **Testcontainers 集成测试**：起真实 MySQL 环境跑集成测试\r\n  - 验收：`mvn test` 在容器化 DB 上全绿\r\n- [ ] **API 文档**：springdoc-openapi 自动生成 Swagger UI\r\n  - 验收：`/swagger-ui.html` 可浏览所有接口\r\n- [x] **Maven 多模块拆分**（2026-09-18 立项，2026-10-24 完成最终形态；2026-11-11 核心模块统一加 `deerfect-` 前缀）：根 pom `packaging=pom` + `deerfect-api` / `channel-qq` / `deerfect-server` / `deerfect-cli` / `wms-mcp-adapter`，后续新增 `channel-email` / `channel-feishu`。\r\n  - **`deerfect-api`**：渠道与 core 共用的契约层（`ChatService`/`SessionService`/`AgentService`/`SessionBindingCleaner` 端口、DTO、`SessionEntity`、`GoalStatus`、`SseProtocol`、`ReplyPostProcessor`），包名不变、全项目 import 零改动；框架类型依赖声明为 optional，不向下游传递。\r\n  - **`deerfect-server`**：Spring Boot 主服务与 fat jar 宿主（产物 `deerfect-server/target/deerfect-server-0.0.1-SNAPSHOT.jar`），编译期依赖 `deerfect-api`，以 **runtime** scope 装配 `channel-qq`（core 编译期看不到渠道，`maven-enforcer-plugin` 守护不允许改成 compile）。\r\n  - **`channel-qq`**：QQ 渠道（NapCat/OneBot 11）整体独立成模块，仅依赖 `deerfect-api`；mapper 扫描由渠道自身 `NapCatChannelConfig` 承担；渠道输出控制走 core 的通用 `ReplyPostProcessor` SPI（新增处理器 + flag，不改 core）。\r\n  - **`deerfect-cli`**：纯 HTTP 客户端，依赖 `deerfect-api` 获取 DTO/SseProtocol（不再自持副本），运行期 classpath 不引入 spring-ai/mybatis。\r\n  - **`wms-mcp-adapter`**：领域 MCP 适配范例，独立于本次拆分。\r\n  - 验收：`mvn test` 全绿；`mvn -DskipTests package` 后 `deerfect-server/target/*.jar` 可直接启动；`deerfect-server` 主源码树零 `channel.qq` 引用；`docker compose build` 待在具备 Docker 的环境验证。\r\n- [x] **容器化交付**：Dockerfile + docker-compose（app + MySQL + pgvector）一键启动\r\n  - 验收：`docker compose up` 后完整可访问\r\n- [x] **CI/CD**：GitHub Actions / Gitee Go 自动流水线（2026-09-18 充实，检查项取自多模块拆分验收清单）\r\n  - CI（push/PR 触发）：`mvn -s .mvn/settings.xml clean package` 全模块编译 + 测试（当前 harness-api 39 / server 893 / channel-qq 83 / cli 28 / wms 43 例）→ 断言 server fat jar 无 `com/dark/javaHarness/cli/` 目录与 jline → 断言 cli 运行时 classpath 无 spring-ai/mybatis/reactor 传递依赖（harness-api 框架依赖为 optional）→ `docker build -f docker/Dockerfile .` 验证镜像可构建（基础镜像走 BUILD\\_BASE/RUN\\_BASE ARG，CI 侧按网络环境注入加速通道）\r\n  - 缓存：`actions/setup-java` 内置 maven 缓存对齐 `.mvn-repo`（localRepository 由 `maven.multiModuleProjectDirectory` 定位项目根，CI 工作区即仓库）\r\n  - CD（可选后置）：分支镜像打 tag 推送镜像仓库，服务器 `docker compose pull && up -d` 拉起；上线前是否人工确认决定 Delivery/Deployment 形态\r\n  - 验收：PR 红灯可见（测试失败 / fat jar 混入 cli class / 镜像构建失败均可拦截）；全绿后产物可直接部署\r\n  - 验收：push 后流水线全绿并产出镜像\r\n- [x] **web 端 MVP（完全自包含）**：已完成（2026-10-27 核对）。顶层 `web/` 目录，Vue3 + Vite + TypeScript；`npm install && npm run dev` 即跑，不进 Maven 反应堆、不依赖项目任何文件，整目录可拷走独立部署（2026-09-19 定稿，升级原「前端 + EventSource」条目）；实际范围已超出 MVP——含 Trace 观测页与知识库管理页\r\n  - 定位：server 的第二个客户端（与 cli 平级），只消费 REST + SSE，把 server 当外部服务对待\r\n  - 独立性约束：TS 类型完全独立定义，不参照 shared 源码，以 server HTTP JSON 实际响应为准；代价是 server 改字段 web 端编译期零感知，靠 mock 回归 + server 侧 DTO 变更视为 breaking change 的纪律兜底\r\n  - mock 模式：内置 mock（含 SSE 流式 mock），server 不启动也能开发调试 UI\r\n  - MVP 范围：会话列表 + 聊天窗（SSE 流式打字机渲染）+ agent 切换 + 新建会话；provider/知识库管理后台后置\r\n  - 认证：一期沿用 API-Key（`@microsoft/fetch-event-source` 带 header，原生 EventSource 不支持自定义 header）；多用户登录后置单独立条\r\n  - 验收：`npm run dev`（mock 开/关两种模式）均可完成一轮流式对话；`npm run build` 产物为纯静态文件可 nginx 直托管\r\n- [ ] **PDF 输出（报告导出）**：把最终回答/会话记录（Markdown）渲染为 PDF 供下载——flexmark（MD→HTML）+ openhtmltopdf（HTML→PDF，纯 Java 免外部依赖、无 AGPL 风险），中文字体内嵌资源目录（不依赖系统字体，避免容器/跨机乱码）；新增 `GET /api/export/pdf?sessionId=` 导出会话，CLI 加 `/export` 命令拉取保存\r\n  - 验收：一次多 Agent 任务完成后可导出排版正常的中文 PDF（标题/列表/代码块/表格不乱码、不缺字）\r\n- [ ] **消息队列**：RabbitMQ / Kafka 异步派发 Goal，长任务解耦\r\n  - 验收：提交 Goal 后立即返回，执行与消费异步解耦\r\n- [ ] **安全**：Spring Security + JWT 接口鉴权；API Key 走 KMS/Vault 管理（现有双通道鉴权已落地：`X-API-Token` 机器通道 + HttpOnly Cookie 登录通道，见 [tech-stack.md](./tech-stack.md)；本条指更高阶的 JWT/KMS 方案）\r\n  - 验收：未带 token 的请求被拒绝\r\n- [ ] **WebSocket**：如需全双工交互（如任务进度推送）可扩展\r\n- [ ] **多实例水平扩展**：解除单实例假设——`failAllRunning` 启动清理、本地 `goalExecutor`、GRAPH\\_CHECKPOINT 单写者；分布式锁（如 ShedLock）或消息队列派发二选一，与「消息队列」条目同批评估\r\n  - 验收：双实例同时运行，同一 Goal 不被双重执行，重启清理只影响本实例\r\n- [ ] **Agent 行为回归评测集**：固定输入集 + 断言（路由 SIMPLE/COMPLEX 判定、专家派遣、输出约定），prompt / 模型参数改动后一条命令跑完防回归\r\n  - 验收：评测脚本输出每项通过与耗时，可选纳入 CI\r\n- [ ] **多模态输入（远期）**：视觉模型（qwen-vl 类）经 agent 表接入，支持图片理解类请求\r\n  - 验收：提交图片 URL 得到基于图片内容的回答\r\n\r\n## 明确暂缓项（2026-09-25 优化评审收拢，需立项或产品决策后再排期）\r\n\r\n> 来源 `docs/optimization-review-2026-09-25.md`；单用户自用场景暂不排期，启动前先立项或由产品决策。\r\n> 其中「超长类拆分」已于 2026-09-25 立项执行并完成（见存档「超长类拆分」节）。\r\n\r\n- [ ] **web 多标签页缓存竞态**：`chatCache.ts` saveCache 为 read-modify-write 非原子（chatCache.ts:80-88），无 storage 事件监听/Web Locks，两标签页并发写后写整份赢\r\n  - 决策点：Web Locks 单写者 vs storage 事件同步 vs 以服务端会话列表为准\r\n  - 验收：双标签页并发对话，切回后消息缓存与会话列表不串桶不丢更\r\n- [ ] **TraceView 分页**：前端一次性全量拉 200 LLM + 200 工具调用记录（client.ts:87-94; TraceView\\.vue:50），ChatWindow\\.loadTokens 每轮流结束 1s 后全量重拉（ChatWindow\\.vue:83-90）；后端 `/api/llm-calls`、`/api/tool-calls` 目前仅 limit 截断（默认 50 封顶 200），无 offset/cursor 分页\r\n  - 改造要点：后端两端点加分页参数（offset 或 id 游标），前端按页加载 + 流结束增量补拉\r\n  - 验收：长会话 TraceView 首屏只拉当前页，轮结束增量补拉不重拉全量\r\n- [ ] **web 端 VITE\\_API\\_BASE**：API 路径硬编码相对 `/api/...`（client.ts:39,161），生产必须同域反代\r\n  - 决策点：是否支持独立域名/跨域部署（涉及 CORS 与 SSE 跨域带凭证）\r\n  - 验收：配置 `VITE_API_BASE` 后构建产物可独立域名部署\r\n\r\n***\r\n\r\n# 二、已完成（存档）\r\n\r\n> 本节按完成时点记录，保留当时的实现描述；部分技术选型已被后续重构替换\r\n> （StateGraph → 自研 `ParallelOrchestrationEngine`、Spring AI 1.1 → 2.0、Boot 3.5 → 4.1），\r\n> 现状以 [tech-stack.md](./tech-stack.md) 与 [features.md](./features.md) 为准。\r\n\r\n## 两路径架构（2026-08 完成）\r\n\r\n- [x] **请求接入 Harness 入口**：`ChatController`（`/api/chat` 同步、`/api/chat/stream` 响应式）统一入口。\r\n- [x] **加载会话原始数据**：`SessionService.loadContext(sessionId)` 还原 `List<Message>`。\r\n- [x] **执行上下文组装**：`ContextAssemblingAdvisor` 按 token 预算裁剪（保留 system + 最近 N 轮）、过滤噪声、规范 role 顺序；测试见 `ContextAssemblingAdvisorTest`。\r\n- [x] **主 Agent 前置判断**：`RouteJudge`/`LlmRouteJudge` 输出 SIMPLE/COMPLEX 结构化决策；异常兜底 SIMPLE（宁可简单）；测试见 `LlmRouteJudgeTest`。\r\n- [x] **路径 A 真流式**：`GeneralAssistantAgent.executeStreamReactive` 走 `.stream().content()` 逐 token 发射（实测 token 间隔 48\\~70ms 到达）。\r\n- [x] **路径 B 多 Agent 编排**：`MultiAgentGraphAgent` 基于 `StateGraph`（lead 拆解上限 `MAX_SUBTASKS=4` → subtask-0..3 并行 → 聚合），已在 `ChatAgentConfig` 注册 bean，测试见 `MultiAgentGraphAgentTest` / `ChatServiceImplTest`。\r\n- [x] **执行进度实时推送**：graph-core 生命周期钩子旁路捕获并行分支完成事件（before/after 配对过滤短路槽位、串行发射防丢事件、关闸在 merge 前防死锁）；`ProgressLine` 统一线协议 `\\0stage\\1detail`；SSE 事件 event/data 单元素成对输出、内容行换行转义保完整性；CLI 实时渲染 `[stage] detail`。\r\n- [x] **两条路径统一出口与记忆写回**：SSE 出口一致；会话记忆经 `writeBackContext` 回写（进度行不计入摘要）。\r\n- [x] **CLI 传输稳定性**：OkHttp readTimeout=15min / callTimeout=30min，长任务不再中途断连；接收端还原换行转义。\r\n- [x] **常量抽离 enums**：跨类共享常量归集至 `enums` 包（如 `AgentConstants`）。\r\n- [x] **补齐 agent 表配置**：`schema.sql` 种子数据新增 `agentName=\'multi-agent\'` 行（编排提示词），重启即生效（`INSERT IGNORE` 幂等），消除「使用默认配置」退回。\r\n- [x] **专家 agent 派遣**：lead 拆解 JSON 升级为对象数组 `{"subtasks":[{"desc":"..","agent":"专家名"}]}`，subtask 节点按专家名查 agent 表配置取对应客户端执行；白名单校验（researcher/coder/analyst/writer/general），非法回退默认；兼容旧纯字符串格式。测试见 `MultiAgentGraphAgentTest` 派遣与回退两用例。\r\n  - 专家清单：`multi-agent`（编排器，lead 拆解+聚合）、`researcher`（资料调研）、`coder`（代码）、`analyst`（数据分析）、`writer`（汇总撰写）、`general`（通用兜底），均登记 agent 表。\r\n- [x] **Graph 内逐 token 流式**：聚合节点流式调用——首个 token 前推「聚合」进度行，逐 token 经旁路 sink 实时发射，主干 contentSent 短路防重复；流式失败回退阻塞调用。子任务节点保持阻塞（并行 token 会交错乱序）。`AgentChatCaller` 拆出 buildSpec 供 call/stream 共用。验证：真实 LLM 端到端 139 个 token 片段逐段到达。\r\n- [x] **并发断连的可观测处理**：闭环「降噪 + 及时终止编排」：\r\n  - 降噪：`ClientAbortLogFilter`（logback TurboFilter）DENY 框架 logger 的断连 ERROR；`GlobalExceptionHandler` 专 handler（AsyncRequestNotUsableException）+ 兜底 `isClientAbort` 均降级 warn 单行，业务异常不误杀\r\n  - 取消传播：`ChatServiceImpl.doOnCancel` warn 单行（sid）→ `AgentServiceImpl.doOnCancel` goal 落库 FAILED，不残留 RUNNING\r\n  - 编排终止：`MultiAgentGraphAgent` 主干 `doFinally(CANCEL)` 置位 cancelled → lead/子任务/聚合节点执行前短路，cancel 后零次新 LLM 调用；路径 A 响应式 cancel 天然终止\r\n  - 在途中止（2026-09 补齐）：`AgentChatCaller.call()` 改为流式通道背书（RestClient 阻塞调用不可中断，JDK HttpClient 不响应线程中断，流式是 Spring AI 1.1.4 + JDK 连接器下唯一可中止通道）——`BooleanSupplier` 取消令牌贯穿 call/stream：置位后调用直接抛取消异常（零 HTTP 请求），执行中置位经 `takeUntil` 在下一个 token 边界中止订阅（取消向上传播关闭 HTTP 连接，厂商端停止生成）。取消不重试、部分输出不按成功返回、`llm_call_log` 记 `ok=false` 且 `error_msg` 含 `client-cancelled`；编排三节点传入共享断连标志（同步路径维持 null），聚合流式中止不回退阻塞调用。副作用：该路径 token 用量从响应 usage 真实值变为按输出文本估算（`tokensEstimated=true`）。测试：`AgentChatCallerTest`/`AgentChatCallerRetryTest` 取消用例 + `MultiAgentGraphAgentTest` 断连中止/零新增调用用例（全量 202 用例通过）\r\n  - 测试：`ClientAbortLogFilterTest` 7 用例 + `GlobalExceptionHandlerTest` 3 用例 + cancel 落库/编排短路守护用例\r\n- [x] **执行层收敛（2026-09-11）**：路径 A/B 流式脚手架（重试/取消/预算记账/空帧跳过）双实现漂移消除——`AgentChatCaller` 抽出 `tokenStream` 共用管道核与 `callWithAssembly`/`streamWithAssembly` 直传入口，`GeneralAssistantAgent` 薄适配化（仅装配差异声明 + 响应式流终结钩子）；`GeneralAssistantAgentScaffoldTest` 基线测试锁定执行层行为。全量 446 用例通过（392e8dd）\r\n\r\n## Prompt 动态装配（2026-09 完成）\r\n\r\n- [x] **prompt 的动态加载（六子项总览）**：\r\n  - [x] 1\\. skill 的动态装配：`SkillRepository` 扫描 `skills/<skill>/` 技能子目录（入口 `SKILL.md` 或 `<skill>.md`，front-matter 声明 name/description/agents，mtime 热重载免重启）+ `SkillManager` 两段式暴露——system prompt 只注入「名称：描述」索引段，模型按需调 `load_skill` 元工具取完整正文；越权防护按 agent 可见技能集服务端硬校验，返回全文按 tool-result-budget 截断\r\n  - [x] 2\\. tool 的动态装配：agent 表新增 `tools` 列（V10 迁移），`ToolAssignments` 优先按列声明分配（组名 `web`/`sandbox.base`… 或精确工具名，跨目录查找含 MCP 动态工具），改库即生效免重启；列 NULL/空白回退代码内置分配（legacy 语义不变）\r\n  - [x] 3\\. mcp 的动态装配：`McpToolProvider` 重构为多 server——解析 `mcp-config.json`（Claude/Cursor 同款 mcpServers 结构）全量条目（stdio/http 混合、`enabled:false` 跳过），每 server 独立懒连接 + 失败隔离，工具并集按名去重；文件缺失回退 legacy yaml 单 server。附带：流式真实 usage 统计——`OpenAiChatOptions.streamUsage(true)` 让末帧回传真实 usage（prompt/completion token），llm\\_call\\_log 优先记真实值、无 usage 回退估算；`load_skill` 与 `expand_tool` 同为元工具不占工具次数额度\r\n  - [x] 4\\. agent 角色 prompt 的组装（详见下条 Prompt 组装管线）\r\n  - [x] 5\\. 记忆上下文的动态注入（详见下条）\r\n  - [x] 6\\. 工具 Schema 的延迟加载（详见下条）\r\n- [x] **Prompt 组装管线（子项 4）**：新增 `prompt` 包 `PromptAssembler`——每请求按 agent 名段落化组装 system prompt（角色段→工具索引段→工具纪律段→输出约定段→skill 段），两路径（`GeneralAssistantAgent`/`AgentChatCaller.buildSpec`）统一接入；`predictSubtask` 的硬编码 persona/工具纪律拼接删除、收敛为组装段。角色段优先级保持：agent 表 prompt > 角色兜底 > 默认。skill 段为扩展点接口 `SkillSectionProvider`（当前空实现）——后续子项 1「skill Markdown 目录装配」只需实现该接口注入即可，不改组装管线；子项 2/3（tool/mcp 装配数据化）可复用 `ToolAssignments.purposeOf` 元数据与工具索引段机制。测试 `PromptAssemblerTest`\r\n- [x] **记忆上下文动态注入（子项 5）**：`MemoryPolicy` 按角色策略注入——编排 `lead` 注入会话记忆（与路径 A general 完全同口径：`SessionService` 同源 memoryStore + `MessageChatMemoryAdvisor` + `ContextAssemblingAdvisor` 预算裁剪，无会话 ID 跳过）；聚合节点与子任务专家不注入（聚合忠实于各子任务结果，子任务上下文由 lead 在子任务描述中传递）；编排内 general 兜底专家（未指派落点）不注入。测试 `MemoryPolicyTest` + `MultiAgentGraphAgentTest` 编排注入断言\r\n- [x] **工具 Schema 延迟加载（子项 6）**：`ToolLazyManager` 会话级两段式暴露——首轮未展开工具仅注入轻量态（名称+用途，inputSchema 置空，直接调用返回中文引导文本不执行真实逻辑），system 经工具索引段给全量工具清单；模型调 `expand_tool(toolName)` 后工具加入会话级展开集合并返回完整参数说明，其后请求按完整 schema 注入并可执行（同请求内 expand 后自动放行，避免自愈循环）；expand\\_tool 元工具不经 tracer/预算（零工具行噪声、不占执行额度）；越权展开未分配工具被拒绝。开关 `app.prompt.lazy-tools.enabled`（默认 true）关闭即回退全量注入现状。测试 `ToolLazyManagerTest`（13 用例）\r\n\r\n## 能力扩展（2026-09 完成）\r\n\r\n- [x] **RAG 知识库**（2026-09 完成）：pgvector 向量库 + DashScope text-embedding-v4 嵌入（1024 维）；knowledge/ 目录 .md/.txt 增量摄取（mtime 比对→MarkdownChunker 切分→嵌入入向量库，`POST /api/knowledge/sync`）；路径 A/B 检索增强（AgentRequestSpecFactory 唯一汇合点注入【出处N】知识段，aggregator 角色策略跳过）；出处透出（meta.sources + CLI 来源尾注）；管理端点 /api/knowledge（sync/documents/search/delete）；PG 不可用静默降级不影响启动；多知识库隔离（knowledge/ 一级子目录=kb，agent 表 knowledge 列 CSV 绑定，检索按向量 metadata.kb filterExpression 过滤防读串，kb 变更重摄取自愈）\r\n  - 验收：knowledge/ 放本地文档 → sync 摄取 → 知识库问答，回答带【出处N】内联引用 + meta.sources + CLI 尾注 ✓\r\n  - 余项已拆出为未完成条目（P1「知识库增强余项」）\r\n- [x] **用户偏好画像（跨对话长期记忆，2026-09 完成）**：全局单份 Markdown 画像 `user-profile/user-profile.md`（工作区顶层目录，容器整目录挂载防单文件 inode 陷阱），`UserProfileService` 定时扫描（5 分钟 fixedDelay 不堆叠）静默超 30 分钟且未提炼的会话，全量快照经 LLM 提炼偏好并与既有画像合并（保留无关条目/冲突以新为准/无新偏好原样输出；空白输出不落盘防抹画像，写盘成功才标记 `profile_extracted`）；画像全文经 `UserProfileSectionProvider` 注入 general/lead 的 system 画像段（order 150，角色过滤 `MemoryPolicy` 口径防污染子任务）；`app.memory.profile-enabled` 总开关同闸服务与注入点。不建 DB 表不做 RAG——常驻小数据全量注入优于检索（RAG 属对话历史归档，见未完成条目）；单会话一次提炼锁定（该缺口已于 2026-11-06 修复，见下）\r\n  - 迁移：V19（session 表加 `last_active_at`/`profile_extracted` 两列，存量会话不回溯提炼）\r\n  - 测试：`UserProfileServiceTest`（扫描/提炼/合并/落盘 7 用例）+ `UserProfileSectionProviderTest`（5 用例）+ `PromptAssemblerTest` 画像段用例\r\n  - 后续（2026-11-06，P2-1）：上述「一次提炼锁定」已废除——V10 把 `profile_extracted` 换成 `profile_extracted_at` + `last_extracted_message_id` 增量游标，扫描条件改为 `last_active_at > profile_extracted_at`（写回零维护），LLM 只输出补丁、代码按 `[key]` 合并，写盘前备份 `.bak`；`UserProfileServiceTest` 扩到 15 例。本条中的 `profile_extracted` / V19 仅为当时的实现记录，详见上方「画像提取活跃重置」条目。\r\n- [x] **显式记忆（记住 / 忘掉 / 查看，2026-11-07 完成）**：用户明说「记住…」时落 `memory` 表（跨会话），与画像分离——画像是模型推断的软偏好，显式记忆是用户声明的事实，只有用户能删\r\n  - 落地：V13 建表（`memory_key` / `content` / `source` / `session_id` / `expires_at` / `deleted_at` 软删）；`remember` / `forget` / `list_memories` 三工具（V13 给 general/lead 工具列追加 `memory` 组）；`ExplicitMemorySectionProvider` 注入段 order 140（排在画像 150 之前，段内声明「冲突以用户明说为准」）；CLI `/remember` `/memories` `/forget` + `GET/POST/DELETE /api/memories`\r\n  - 取舍：同 key 以最新为准（写新记忆时软删旧条目，避免两条矛盾事实同时注入）；忘掉多命中时不删、返回候选让用户确认（误删一条珍视的记忆，代价远高于多问一句）；状态在读取时按 now 计算，不维护状态列\r\n  - 验收：真机——对话里「记住车牌」→ 新会话答出「沪A12345」；「忘掉」→ 新会话答「不知道」；`list_memories` 列出 id / 来源 / 时间 / 状态；画像里的 `[技术栈] Java` 与显式记忆「技术栈=Go」冲突时答 Go\r\n- [x] **MCP 工具接入**：让 Agent 通过 MCP 连接外部工具/服务，扩展工具生态（不再逐个自研）\r\n  - 与 Sandbox 衔接：agentscope-runtime 内置 MCP 桥接，接入时优先评估复用（见存档「Spring AI Alibaba Sandbox 接入」条目）\r\n  - 验收：模型可调用一个外部 MCP 工具完成真实任务 ✓\r\n\r\n## 观测与预算（2026-09 完成）\r\n\r\n- [x] **Token 预算统一控制**（2026-09-08 落地，详见 `docs/guides/context-optimization.md` 第五节）：预算集中配置（`app.context.*` / `ContextBudgetProperties`），消费侧新增两项——\r\n  - 输出封顶（生成侧防失控，非事后裁剪）：`AgentChatCaller.buildSpec` 按角色分档设 `maxTokens`——lead 2000（JSON 中间产物）/ 聚合与路径 A 直出 8000（同直出最终回答共档）/ 子任务专家 4000；0 = 不限保持模型默认\r\n  - 编排全程消费上限熔断（`orchestration-budget` 60000）：input 注入内存账本（AtomicLong + 估算标记），`AgentChatCaller` 经 `BudgetLedger` 按 roundtrip 增量同步累计，发起调用前与每轮 usage 帧两时点熔断（含单次 call 内部工具循环每轮，超限断流；真实 usage 优先，无则估算并与 `tokens_estimated` 同口径，不依赖异步落库时序）；未发起子任务超限即短路跳过（零 LLM 调用，result 写「预算超限跳过」占位），已在途的在下一轮熔断停止；聚合必发不受熔断（record-only 仅记账）、prompt 前置【预算降级说明】（跳过数 + 已消耗/上限 + 口径），编排正常终态；子任务并行限并发 `subtask-concurrency` 错峰，收窄在途超额窗口（2026-09-08 评审 C1 按方向 b 修正：熔断下沉 caller roundtrip 粒度，消除并行扇出检查盲区与工具循环烧穿）\r\n  - 会话累计不设硬配额（评估结论：输入/输出双侧已封顶单次消耗，会话累计只反映正常用量，硬拦截伤聊天体验且无安全收益），消费量观测由 `llm_call_log` 与 `GET /api/llm-calls` 承担\r\n  - 验收 ✅：熔断用例（同步 + 流式 + 真实部分跳过）子任务 mock 零交互、聚合带降级说明正常返回；三档 maxTokens 捕获 ChatOptions 断言、0 时不写入；caller 门控/记账 + 配置绑定用例（共新增 17 用例）\r\n  - 遗留已拆出为未完成条目（P1「聚合前子任务结果预算分摊裁剪」）\r\n- [x] **工具调用与 MCP 日志记录**：`ToolCallTracer` 此前仅装饰 ToolCallback 发 `tool`/`tool-done` SSE 进度行（无 emitter 的调用链路不可见），工具调用不落库——`llm_call_log` 只记 LLM 调用，工具侧无成本/审计账本；MCP server 连接/发现失败仅 warn 单行，无 server 维度可查询记录\r\n  - 改造要点：仿照 `llm_call_log` 落库工具调用（工具名/参数摘要/耗时/成败/所属 agent 与 sessionId），MCP 工具标注来源 server；MCP 连接失败、懒连接失败结构化记录（`McpToolProvider` 每 server 独立记录，失败隔离后仍可追溯）\r\n  - 落地：V15 `tool_call_log` + V16 `mcp_server_log` 两张观测表；`LlmCallRecorder` 扩展 `recordToolCall`（两类台账同置一处，复用既有注入面零级联接线）；`ToolCallTracer` 支持「无 emitter 仅落库」装饰（emitter/recorder 任一非空即装饰），QQ 渠道、API 直调等无 SSE 链路观测盲区补齐；MCP 工具经 `ServerTaggedCallback` 标注来源 server，`McpToolProvider` 连接/发现成功与失败均结构化落库（失败隔离语义不变）；`expand_tool`/`load_skill` 元工具与 `ToolCallBudget` 预算拦截不落库；观测落库失败仅 warn 不影响主链路（与 `llm_call_log` 同口径）\r\n  - 注：验收中的 `/api/tool-calls` 查询端点不在本次范围，已拆出为未完成条目（P3）；本次验收以单测覆盖（411 通过）\r\n- [x] **链路追踪（自建轻量方案）**：记录 LLM 调用耗时与 token 消耗——`llm_call_log` 表 + `LlmCallRecorder` 异步落库，`GET /api/llm-calls` 查询；Micrometer Tracing / OpenTelemetry 完整链路留待按需升级（见 P3「完整链路追踪」）\r\n  - 验收 ✅：可经 `/api/llm-calls?sessionId=` 按会话查询每次调用的耗时/token/成败\r\n\r\n## 异步治理与发布工程（2026-09-06 完成）\r\n\r\n- [x] **后台 Goal 执行线程池治理**：`submit` 的 `CompletableFuture.runAsync`（commonPool）改为受管 `goalExecutor` 池（core=max=8、队列 50、优雅停机 30s），替代在 commonPool 上跑分钟级阻塞 LLM 调用（CPU-1 线程且 JVM 全局共用）；队列满 `RejectedExecutionException` 兜底把 Goal 落 FAILED（轮询可见，不再无声排队）；`run()` catch 从 Exception 放宽到 Throwable，Error 逃逸不再卡 RUNNING。测试：`AgentServiceImplTest` 新增 10 并发全 SUCCEEDED / 队列满兜底 / LinkageError 逃逸三用例（bdd2ac4）；失败重投经评估另立任务（见 P2「Goal 失败重投」）\r\n- [x] **MVC 异步执行器槽位补位**：定义 goalExecutor 后 Boot 自动配置的 applicationTaskExecutor 让位，MVC 流式 SSE 派发回退每请求一线程的 `MvcSimpleAsyncTaskExecutor` 并打生产告警（反汇编定位：Boot 按 bean 名 `containsBean("applicationTaskExecutor")` 接线）；显式补 `applicationTaskExecutor` 槽位 bean（`mvc-async-` 前缀），与 goal 长任务池分离（d4a4d96）\r\n- [x] **数据库迁移工具**：Flyway 替代 `spring.sql.init` 管理 schema 演进（V1 建表 / V2 goal 索引 / V3 llm\\_call\\_log；存量库经 baseline 无缝接入，`spring.sql.init` 已移除）\r\n  - 验收 ✅：新增表结构变更通过迁移脚本自动应用（新增 `db/migration/V*__*.sql` 即可，启动自动执行）\r\n- [x] **API Key 装载链路修复与系统级分发**：非交互 bash 不执行 .bashrc export（开头守卫 early-return），run.sh 开窗（wt.exe 新 wsl 会话）与 run-wsl.bat 显式注入路径都拿不到 key → 服务 401。key 迁仓库根 `.env.local`（gitignored，run.sh / run-wsl.bat / .bashrc 三处 source 兜底）+ 主机制升级 Windows 用户环境变量 + `WSLENV` 透传（所有 wsl 会话自动带 key）；顺带修 cmd interop 传参引号污染（1e63a8a + 系统级 setx）\r\n- [x] **CLI 流式渲染增量直出**：渲染器每 token 整行擦除重绘（`\\r\\033[2K`+全缓冲）依赖终端转义支持，失效终端表现为同段文字带渐长尾巴重复（DB 取证：goal.summary 与 llm\\_call\\_log 干净、单次调用，排除模型/服务端）；改为只补打未上屏部分，纯文本行零重绘，着色行完成时才整行重绘升级。测试：`TerminalRendererTest` 重影回归用例（1ba27d6）\r\n\r\n## 杂项修复与增强（2026-08/09 完成）\r\n\r\n- [x] 修复目前会话无法创建 goal 的问题\r\n- [x] 检查 agent 角色提示词是否被写入了会话消息中（结论：未写入，角色段每请求组装不落库）\r\n- [x] 增加会话 agent 切换功能：当在会话内切换 agent，会话表的 agentId 同步更改\r\n- [x] 修复进入多 agent 时请求卡住的问题\r\n\r\n## 工具与沙箱（2026-08 完成）\r\n\r\n- [x] **Agent 工具库扩充**：`tool` 包落地真实工具体系（对标 DeepSeek Harness 内置工具面），按专家分配、统一治理（宿主机自研工具后被 Sandbox 容器级隔离替代并退役）：\r\n  - 现存自研工具：`WebTools`（fetchUrl 真实抓取，HTML→纯文本，仅 http/https，2MB/12k 字符上限）+ `DemoTools`（本地函数示例）；网页搜索待搜索服务商 API key 后补充（已列入 P1「Web Search 接入」）\r\n  - 已退役（能力由 Sandbox 等价承接）：`ToolSandbox`/`FileTools`/`SearchTools`/`ShellTools` 连同其治理与测试一并移除\r\n  - `ToolAssignments`：按专家分配工具集，双通道注入（@Tool 对象走 `.tools()`、ToolCallback 走 `.toolCallbacks()`）；`MultiAgentGraphAgent.call` 按子任务专家、`GeneralAssistantAgent` 按自身 agent 名；未指派子任务回退 general（全量权限的收敛见 P0「工具分配最小权限化」）\r\n- [x] **WebTools 从能用到好用（2026-09-06）**：fetchUrl 从「正则去噪 + 纯文本」升级为「jsoup HTML5 解析 + 主内容启发式提取 + Markdown 输出」：\r\n  - 抓取：jsoup 连接（15s 超时 / 2MB 下载上限 / 重定向 / Accept-Language），编码自动嗅探（header + meta charset，GBK 中文站不再乱码），删除「先截字节再解码」的多字节切断缺陷\r\n  - 主内容：DOM 级去噪（噪声标签 + class/id 特征正则）+ 文本密度/链接密度评分选正文块（简化 Readability，自研纯函数；借鉴 jsoup → Readability → Markdown 业界管线，经评估未引入 Readability4J/flexmark 依赖）\r\n  - 输出：Markdown（标题/列表/链接绝对化/代码块/管道表）+ 元数据头（标题/来源 URL）；query 意图过滤保留（按 Markdown 段落切块，未命中回退开头）\r\n  - 缓存：拒绝式改内容式（URL→内容，50 条 / TTL 30 分钟）——重复抓取回放缓存而非「请勿重抓」，解决旧内容被上下文裁剪后无法重看的死路\r\n  - 错误结构化：全链扫描分类（DNS/超时/连接拒绝/TLS/403/404/429/5xx/非网页 Content-Type），中文可自愈提示；单测实证 WSL DNS 隧道下域名失败表现为 ConnectException 链（判据在链底 UnresolvedAddressException，需全链扫描而非只看最深一层）\r\n  - 测试：`WebToolsTest` 重写 16 用例（JDK 内置 HttpServer 承载本地 fixture，走真实 Jsoup.connect 链路：GBK 解码/噪声剔除/Markdown 元素/缓存回放/错误分类/截断），全量 268 用例通过；jsoup 为唯一新增依赖（MIT、零传递依赖）\r\n- [x] **Spring AI Alibaba Sandbox 接入**：引入 `spring-ai-alibaba-sandbox`（BOM 管理版本），工具执行从进程内软隔离升级为容器级隔离。\r\n  **架构决策**：产品定位为通用助手（调研/报告/数据分析/跑代码），agent 不操作宿主机项目文件——模型生成的所有代码/命令只在容器内执行，宿主机零暴露；不做降级路径（沙箱是硬依赖，无 Docker 环境该功能整体不可用）。\r\n  - `SandboxToolProvider`：懒初始化（双检锁只尝试一次），失败降级空工具面（warn、不重试、不回退宿主机）；`@PreDestroy` 释放容器\r\n  - 工具面三类：执行类（RunPythonCode/RunShellCommand）、只读文件类、写入类；浏览器组独立镜像独立初始化，失败只降级本组\r\n  - 重合即退役：`ShellTools`/`FileTools`/`SearchTools`/`ToolSandbox` → 沙箱等价工具；`WebTools`/`DemoTools` 保留（Sandbox 未覆盖）\r\n  - 验证：JShell 直连验证容器拉起 + Shell 执行 + 退出自动删除；浏览器组 `quotes.toscrape.com/js/`（JS 渲染页）snapshot 取得 5381 字符内容；过程记录见 `docs/reports/2026-08-28-sandbox-integration.md`\r\n- [x] **Sandbox 真实 LLM 端到端** ✅：「Python 计算前 20 个素数和并运行验证」——lead 拆 1 个子任务指派 coder，`RunPythonCodeTool` 经容器 fastapi 执行，SSE 进度完整、`meta SUCCEEDED`、结果正确（素数和 639）；容器随首次工具调用懒创建、优雅停服随 `@PreDestroy` 销毁（注意：强杀 `mvn spring-boot:run` 不触发优雅关闭会残留容器）\r\n\r\n## CLI 体验（2026-08 完成）\r\n\r\n- [x] **CLI 输出优化（对标 Claude Code 的终端体验）**：\r\n  - 流式 Markdown 渲染：逐 token 渲染标题/列表/**粗体**/代码块语法高亮\r\n  - 过程状态原位刷新：进度 spinner + 已耗时固定状态行刷新，完成后折叠归档灰色 ✓ 摘要行，不刷屏\r\n  - 工具调用行：服务端 `ToolCallTracer` 装饰 ToolCallback（schema 原样透传），起止经旁路 sink 发 `tool`/`tool-done` 进度行（路径 A/B 通用）；CLI 展示 `⏺ 工具名(参数摘要)` → `✓ 耗时 · +N/-M 行`（diff 近似着色）\r\n  - 回合小结：耗时 / 子任务数 / token 近似量\r\n  - 输入体验：JLine 3——历史持久化、`/` 命令 Tab 补全、多行粘贴；无 TTY 自动降级。**JLine 3 不可替代性**：Windows 控制台无纯 Java 逐键 raw 输入；启动方式 `mvn -s .mvn/settings.xml -Pcli compile exec:exec`（fork 独立进程接管真实终端）\r\n  - 乱码修复：CLI 输出统一收敛到 JLine `terminal.writer()` 宽字符通道（WriterBridge），GBK/65001 代码页均正常\r\n  - 验收 ✅：全量 101 用例通过；文档 `docs/reports/2026-08-28-cli-output-optimization.md`\r\n- [x] **回答归属前缀（2026-09-06）**：SSE 流首新增 `stage=agent` 进度行——服务端下发实际路由到的 agent 名（智能分流与指定 agentId 两条路径都覆盖，续跑固定 multi-agent），CLI 在首个回答 token 前渲染着色「agentName> 」前缀，与用户侧「你> 」提示符对称，用户/智能体一眼可分；前缀计入渲染器行状态，首行着色重绘时原样补回（不被 CLEAR\\_LINE 擦掉）\r\n\r\n## 项目基础（Roadmap P0 及能力项）\r\n\r\n- [x] **Goal 落库**：Goal 从内存 `ConcurrentMap` 迁移到 MySQL，重启不丢；提供目标历史查询接口\r\n- [x] **清理遗留依赖**：移除未使用的 JLine、Hutool 及无用 dependencyManagement 条目\r\n- [x] **清理遗留 SQL**：`schema.sql` 重写废弃 `goal` 表\r\n- [x] **统一参数校验**：`spring-boot-starter-validation` 注解校验，非法请求返回统一 400 错误体\r\n- [x] **全局异常处理**：`@RestControllerAdvice` 统一 `{code, message}` 响应\r\n- [x] **单 agent 系统**：创建 agent 表并引入项目\r\n- [x] **多模型接入**：`ChatAgentConfig` 按 agent 表装配多个 Agent（qwen-plus/max/turbo），新增 Agent = agent 表一行（2026-09 起表驱动自动注册，见下条）\r\n- [x] **多 Agent 编排（初版）**：主 Agent 判定 COMPLEX 路由到 `multi-agent` 完成 lead→并行→聚合闭环\r\n- [x] **响应式流式改造**：流式端点已直接返回 `Flux<String>`（text/event-stream），DB 阻塞操作经 `Schedulers.boundedElastic()` 边界隔离；同步 `/api/chat` 保留\r\n- [x] **会话上下文管理与 Token 裁剪**：`ContextAssemblingAdvisor` 按 token 预算裁剪，长对话体积受控\r\n- [x] **Mockito 单测（核心场景）**：10 个测试类 / 50 用例覆盖路由判定、双路径执行、进度协议、SSE 契约（详见 docs/guides/functional-testing.md）\r\n- [x] **Agent 表驱动自动注册（2026-09）**：新增 `AgentRegistry`——启动时读 agent 表把全部 `is_internal=0` 行自动注册为对话 Agent（`GeneralAssistantAgent` 按行配置生效），路由未命中惰性查表热注册（`ConcurrentHashMap.computeIfAbsent` 原子构造，运行中插行免重启）；V9 迁移加 `is_internal` 列（multi-agent/lead/aggregator 置 1，排除逻辑纯数据驱动，新增内部角色免改代码）；启动逐行 fail-safe（脏行 warn 跳过）；`general` 行缺失时代码兜底注册并 warn（DB 行存在则完全按 DB，两来源不合并）。`ChatAgentConfig` 删除 generalAgent/deepseekAgent 手工 bean，`AgentServiceImpl` 路由委托 Registry（构造 `@Lazy` 解创建期循环）。注册口径收敛为：**新增 Agent = agent 表一行（is\\_internal=0）**。测试：`AgentRegistryTest` 9 用例 + `AgentServiceImplTest` 适配扩展（全量 249 用例通过）\r\n\r\n## 超长类拆分（2026-09-25 完成）\r\n\r\n- [x] **超长类拆分**（立项自「明确暂缓项」，源 docs/optimization-review-2026-09-25.md）：四类按职责簇拆分，新类全部包私有零 Spring 装配（先例 AggregateStreamGuard/CallTrace），行为契约逐字面等价搬迁\r\n  - 落点：AgentChatCaller 749→478（拆出 `AgentChatPipeline` 管道核：streamAttempt/streamCore/tokenStream/watchdog/流帧记账/空响应防御；`CallContext` 观测值对象提升为顶层 record；`CallSpecAssembler` 角色装配策略；`recordEstimatedIfNoUsage` 并入 BudgetLedger 静态；noToolsVariant 下沉为 `Assembly.withoutTools()`；宿主留守 call/stream 门面、两重试循环、configOf、测试契约薄委托 tokenStreamWithWatchdog/attachmentsFor/buildSpec）；MultiAgentGraphAgent 645→413（拆出 `OrchestrationNodes` 三节点 + predict\\* 桥接 + 流式聚合护栏所有权；状态键常量放宽包级留守宿主不动 MultiAgentStreamPipeline 契约；死代码 safe() 删除；宿主留守执行/续跑入口与图装配）；ChatServiceImpl 524→430（拆出 `SseEncoder` SSE 编码纯函数/metaEvent 参数化 sources、`RagPrefetcher` 共享预取池 + 会话知识绑定解析；agentId 切换重复收编 `bindAgentQuietly`；toSseBody 流生命周期编排、resolveAgentWithPrefetch 汇合胶水、route 簇留守）；OneBotEventServiceImpl 520→331（拆出 `ReplySplitter` 分段纯函数、`EventTextParser` 事件解析与触发判定；handle/chatWithTimeout/拟人延迟留守）\r\n  - 测试适配仅 3 处静态调用改类名（OneBotEventServiceImplTest 16 处 splitXxx→ReplySplitter、AgentChatCallerTest/AgentChatCallerRetryTest 各 1 处 contentOf→AgentChatPipeline）；ChatServiceImplTest（33 例）/MultiAgentGraphAgentTest（23 例）/GeneralAssistantAgentScaffoldTest（等价性裁判）零改动全绿\r\n  - 验收 ✅：核心类职责单一、四类行数收敛 18%\\~36%（拆出 8 个新类均 <275 行）；全量 610 用例 0 失败 0 错误\r\n\r\n***\r\n\r\n## 备注\r\n\r\n- 测试全景见 [docs/guides/functional-testing.md](./guides/functional-testing.md)，数据流详解见 [docs/data-flow.md](./data-flow.md)，技术栈对照见 [tech-stack.md](./tech-stack.md)。\r\n- 每项完成后按对应"验收"标准验证后再勾选；完成的条目连同落地与验收记录移入本文件「二、已完成（存档）」对应主题节，若留有后续尾巴（余项/端点/优化）须拆出为未完成条目，避免尾巴被埋进存档。\r\n\r\n',jr='# 渠道探活告警（M2）\r\n\r\n- 范围：`server` 的 `message` 包与 `app.channel-watchdog.*` 配置；复用已有的 `ChannelHealth` 端口\r\n- 创建：2026-11-08\r\n- 状态：共 4 项，已完成 4 项\r\n- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 7 节\r\n- 关系：依赖 `delivery-outbox.md` 的 D1-1（`sendWithFallback` 的 `excludeChannel`）与 `email-channel.md`（告警的落点）\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| 探活与告警 | 3 | 0 | 3 |\r\n| 边界与文档 | 1 | 0 | 1 |\r\n| 合计 | 4 | 0 | 4 |\r\n\r\n## 背景\r\n\r\n探活其实已经有了：`harness-api` 的 `ChannelHealth` 端口 + `channel-qq` 的 `NapCatHealth`（调 `get_login_info`，结果缓存 30 秒）。缺的只是**把状态变化变成通知**——现在它只被 `ComponentStatusService` 读给监控页，渠道断了要你自己打开 Web 页面才发现。\r\n\r\n一条硬约束：**不能用飞书去告警「飞书挂了」**。所以告警必须走「非本渠道」，实现上在入队前把告警链里该渠道的段删掉。\r\n\r\n## 条目\r\n\r\n- [x] W1-1 ChannelWatchdog 扫描与状态判定\r\n  - 作用：把「渠道当前可不可用」从一次性查询变成连续状态\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`\r\n  - 做法：`@Scheduled(fixedDelayString = "${app.channel-watchdog.interval-ms:300000}")` 遍历所有 `ChannelHealth`（`ObjectProvider<ChannelHealth>`，渠道未装配时为空）；维护内存态「连续失败次数」，达到阈值（默认 3）才判定为故障，避免抖动时来回报警；`enabled=false` 的渠道不参与判定\r\n  - 验收：连续 2 次 `available=false` 不告警、第 3 次告警；中间恢复一次则计数清零；渠道未装配时不报错\r\n  - 结果（2026-11-08）：`ChannelWatchdog` 新增，`scan()` 可单测、`tick()` 只做异常兜底。计数与抑制复用 `AlertGate`（key 前缀 `channel:`），**没有**新增 `fail-threshold` 配置——它与 `app.alert.min-failures` 是同一语义，两个键会漂移。`ChannelWatchdogTest` 14 条覆盖阈值、恢复、抑制、关闭、空渠道、入队异常隔离。\r\n\r\n- [x] W1-2 告警走非本渠道\r\n  - 作用：渠道挂了仍然能通知到你——这是整个存活层唯一的意义\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`\r\n  - 做法：告警链配置 `app.channel-watchdog.alert-chain`（如 `email:me@example.com`）；发送前剔除故障渠道，正文含渠道名、失败次数、最后错误、判定时间；发送本身也进 outbox（`source=alert`），失败可重投\r\n  - 验收：停 NapCat 后最迟 `app.alert.min-failures × interval-ms`（默认 15 分钟）+ 一个投递节拍（30 秒）内收到邮件告警；`alert-chain` 只含同一渠道时不会「用坏渠道报坏渠道」，而是记 ERROR 日志\r\n  - 结果（2026-11-08）：实现为「入队前从链上剔除故障渠道」，没有走 `sendWithFallback(excludeChannel)`——outbox 的一行就是一条链，入队前定下来才能让**重投**也遵守排除；用 `excludeChannel` 只能管第一次发送。`withoutChannel()` 对 `qq:123|feishu:main|email:me@example.com` 剔 `feishu` 后保持原序。\r\n  - 延迟口径：默认 5 分钟 × 3 次 = 15 分钟，比初稿写的「5 分钟」长。要更快就把 `interval-ms` 调到 60000（3 分钟判定）；NapCat 探活本身有 30 秒缓存，代价可忽略。\r\n\r\n- [x] W1-3 恢复通知与抑制\r\n  - 作用：知道「坏了」也要知道「好了」，同时不让反复抖动刷屏\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/ChannelWatchdog.java`（复用 D1-7 的 `AlertGate`）\r\n  - 做法：故障态持续期间不重复告警（`throttle-hours` 内一条）；`available=true` 且此前处于故障态时补一条「已恢复」，并清零计数\r\n  - 验收：故障持续 1 小时只收到 1 条；恢复后收到 1 条「已恢复」；再次故障会重新计次数\r\n  - 结果（2026-11-08）：`AlertGate.onSuccess` 只在「此前已告警」时返回 true，所以「抖一下又好了」不会发恢复；恢复文案含渠道名、最近状态、恢复时间。抑制窗口推移用 `MutableClock` 测，不 sleep。\r\n\r\n- [x] W1-4 边界声明（不做进程外心跳）\r\n  - 作用：避免后来者把「进程死亡无人知」当成漏做，反复提议加外部探活\r\n  - 位置：`docs/dev.md` 的运行观测章节、本清单结果行\r\n  - 做法：写明本层只覆盖「进程活着、渠道发不出去」；进程/主机死亡不在范围，理由见规划书 7.2 的三条（单实例下自己会察觉 / 外部观察者是无人监控的依赖 / `restart: unless-stopped` 已覆盖崩溃拉起）；注明将来接外部探活**不需要改本方案结构**\r\n  - 验收：`docs/dev.md` 能查到该边界；`docs/task-checklist/project-hardening.md` 的 H2-3 仍标待办，不与本清单混淆\r\n  - 结果（2026-11-08）：`docs/dev.md` 新增「渠道探活告警（能报什么 / 不能报什么）」，含四态判定口径、非本渠道规则、进程死亡边界与残余风险。H2-3 未动。\r\n\r\n## 与初稿的两处偏差\r\n\r\n| 初稿 | 实际 | 原因 |\r\n| --- | --- | --- |\r\n| `sendWithFallback(chain, text, excludeChannel)` 直接发 | 剔除后入队 outbox | 重投也要遵守「不走坏渠道」；直接发只能管第一次 |\r\n| 独立配置 `fail-threshold` | 复用 `app.alert.min-failures` | 同一语义不设两个键；`AlertGate` 已经是这套状态机 |\r\n\r\n另外补了一处初稿没考虑到的判定漏洞：`ChannelHealth.Snapshot` 由二元组扩成四态（`UP` / `DOWN` / `UNKNOWN` / `DISABLED`）。邮件渠道 7 天没投递报的是「未知」，若按 `available=false` 判故障，会周期性地发假警报。旧的三参构造保留，监控页与既有测试无需改动。\r\n\r\n## 已知残余风险\r\n\r\n容器一直起不来（镜像损坏、磁盘满、端口占用）时，所有通知一起停，且没有机制告诉你。发现方式退化为「你注意到今天没收到消息」。这一条按需接受，不列为验收项。\r\n',Dr='# 投递层：outbox 与降级链（M1 前半）\r\n\r\n- 范围：`server` 的 `message` 包与新增 V14 迁移；不改 `channel-*` 模块\r\n- 创建：2026-11-08\r\n- 状态：共 10 项，已完成 10 项，待办 0 项\r\n- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 4 / 5 / 6 节\r\n- 关系：本清单只做「投递层」。邮件渠道见 `email-channel.md`，探活告警见 `channel-health.md`，飞书渠道见 `feishu-channel.md`\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| 端口契约 | 1 | 0 | 1 |\r\n| 链路解析 | 1 | 0 | 1 |\r\n| 存储与状态 | 3 | 0 | 3 |\r\n| 调度与重投 | 3 | 0 | 3 |\r\n| 抑制与保留 | 2 | 0 | 2 |\r\n| 合计 | 10 | 0 | 10 |\r\n\r\n## 背景\r\n\r\n现状是**失败即永久丢弃**：`ScheduleServiceImpl.deliver()` 无论 `send()` 返回 true 还是 false 都调 `markNotified()`，注释写的理由是「避免每 10 秒重试刷爆渠道」。顾虑成立，但结论错——正确做法是交给 outbox 控制重投节奏，而不是直接放弃。\r\n\r\n做完本清单后，「上周那条日报到底发没发」有据可查；主通道失败会自动走备用通道。**全部条目不依赖飞书**，用邮件或测试替身就能验收。\r\n\r\n## 条目\r\n\r\n- [x] D1-0 端口回执扩展（`MessageSender.sendForReceipt`）\r\n  - 作用：outbox 要按失败原因决定「重试还是终态」，而 `boolean` 只能表达成/败\r\n  - 位置：`harness-api/src/main/java/com/dark/javaHarness/service/MessageSender.java`\r\n  - 做法：新增 `default sendForReceipt` 与 `DeliveryReceipt(sent / messageId / reason / retryable)`，工厂 `sent` / `failed`（默认可重试）/ `fatal`（配置错）；现有实现零改动——default 折 `send`\r\n  - 验收：`harness-api` 的 `MessageSenderTest` 覆盖 default 折 send、三种工厂语义、空原因兜底\r\n  - 结果（2026-11-08）：新增 6 条用例。`retryable` 是这条链的关键：未知失败默认 `true`（宁可多试一次也别丢消息），配置错用 `fatal` 明确终止，避免白白重试三轮。该端口原本没有契约守卫快照，无需改守卫。\r\n\r\n- [x] D1-1 通道链解析与入队校验\r\n  - 作用：`feishu:main|email:me@x.com` 按序降级；单值即长度 1 的链，存量 `qq:123` 零迁移\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/MessageSenderRegistry.java`\r\n  - 做法：新增 `chainOf(channelRef)` 负责解析与校验（空段、未知渠道前缀、缺 target 一律拒绝）与 `sendWithFallback(channelRef, text, excludeChannel)`；`send()` 保留原语义（只试第一个可用通道）。校验提前到 `ScheduleService.create()` 与 outbox 入队前，配置错误当场报，不等触发任务才在日志里发现\r\n  - 验收：五类用例——单值兼容 / 两跳成功 / `"qq:"` 空 target 拒绝 / `"q:1"` 未知前缀拒绝 / `excludeChannel` 跳过本渠道；存量 `qq:123` 配置零改动可推送\r\n  - 结果（2026-11-08）：`chainOf` 严格校验、抛 `IllegalArgumentException`（入队前用）；`sendWithFallback` 宽松解析、绝不抛（发送时用）——两种职责刻意分开，合并会让「配置错误当场报」与「投递失败不炸链路」互相打架。新增 `sendOnce` 供调度器只发当前一跳。测试 7 → 18 条。\r\n\r\n- [x] D1-2 V14 建 outbox 表\r\n  - 作用：每条主动消息有记录、有终态（`PENDING` / `SENT` / `DEAD`），不存在「发没发不知道」\r\n  - 位置：`server/src/main/resources/db/migration/V14__delivery_outbox.sql`\r\n  - 做法：按规划书 4 节 DDL 建表，含 `chain_index`（当前尝试到链上第几个）、`attempts`（跨通道累加）、`next_retry_at`（兼作领取租约）、`last_error`；索引 `idx_outbox_due(status, next_retry_at)` 与 `idx_outbox_created(created_at)`\r\n  - 验收：空库从 V1 顺序跑到 V14 成功；存量库 V13→V14 增量执行；`SHOW CREATE TABLE delivery_outbox` 两个索引齐备\r\n  - 结果（2026-11-08）：V14 建表。比原设计多一列 `hop_attempts`——原方案只有跨通道累加的 `attempts`，无法判断「本跳已试几次」，会导致第二跳一上来就被判超限。\r\n\r\n- [x] D1-3 outbox 实体与 Mapper\r\n  - 作用：给服务层提供持久化入口\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/domain/entity/DeliveryOutboxEntity.java`、`server/src/main/java/com/dark/javaHarness/mapper/DeliveryOutboxMapper.java`\r\n  - 做法：MyBatis-Plus 实体 + `BaseMapper`；查询一律用**字符串列名** `QueryWrapper`，不用 lambda（纯 Mockito 单测下没有 `TableInfo` 缓存，会报 `can not find lambda cache`——P2-4 踩过）\r\n  - 验收：`mvn -pl server -am compile` 通过；单测里不出现 lambda 条件构造\r\n  - 结果（2026-11-08）：实体用 Lombok `@Data`，Mapper 继承 `BaseMapper`；条件构造一律用字符串列名（纯 Mockito 下没有 `TableInfo` 缓存，lambda 会报 `can not find lambda cache`）。\r\n\r\n- [x] D1-4 DeliveryOutboxService（入队 / 领取 / 标记 / 链推进）\r\n  - 作用：把「领取」与「发送」分开，让「发送中崩溃」可恢复、重试次数可审计\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryOutboxService.java`\r\n  - 做法：`enqueue(source, refId, chain, content)` 写 `PENDING`；`claimDue(limit)` 用条件更新抢占（`WHERE id=? AND status=\'PENDING\' AND next_retry_at<=NOW()`，`SET attempts=attempts+1, next_retry_at=NOW()+lease`，改到 1 行才算领到）；`markSent` / `markFailed(reason, retryable)` / `advanceChain`。`attempts` 必须在发送**前**递增，否则发送中崩溃会永远不计次、无限重试\r\n  - 验收：领取幂等（同一行第二次 `claim` 不命中）；可重试失败回到 `PENDING` 且 `next_retry_at` 按 60s/180s/600s 退避；三次耗尽后 `chain_index+1`；链走完置 `DEAD` 并保留最后一次错误\r\n  - 结果（2026-11-08）：`claimDue` 先查再逐条条件更新（MySQL 没有 UPDATE...RETURNING，而调度器要知道领到了哪些行）；所有状态更新都带 `status=PENDING` 条件，避免覆盖已结案行。`DeliveryOutboxServiceTest` 14 条。\r\n\r\n- [x] D1-5 调度侧改为入队（`notified_at` 语义变更）\r\n  - 作用：调度只管「任务跑完了」，投递成败交给 outbox，两个关注点分开\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/service/impl/ScheduleServiceImpl.java`\r\n  - 做法：`deliver()` 改为 `enqueue()`：写 outbox 成功后再 `markNotified()`；`notified_at` 语义从「已送达」改为「已入队」，同步改写方法注释（现有注释「失败也标记避免刷屏」需要删掉，理由已由 outbox 承接）；`markNotified` 的条件更新（`last_goal_id` 仍匹配才置位）**保持不变**\r\n  - 验收：`ScheduleServiceImplTest` 相关断言改为「入队即标记」；新增「入队失败不标记」用例（下一轮会重新收集）；`markNotified` 原有的「不串轮」测试继续通过\r\n  - 结果（2026-11-08）：`notifyFinished` 的返回值语义改为「入队数」；`create()` 调 `chainOf` 校验链。`ScheduleServiceImplTest` 19 条按新语义改造（含新增「入队失败不标记」），全绿。\r\n\r\n- [x] D1-6 DeliveryRetryScheduler（扫描 / 重投 / 降级）\r\n  - 作用：到期消息自动重投，链上还有下一个通道就换路\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryRetryScheduler.java`\r\n  - 做法：`@Scheduled(fixedDelayString = "${app.delivery.scan-interval-ms:30000}")`，每轮 `claimDue(batch)`（默认 50）；逐条按 `chain_index` 取通道，调 `sendForReceipt`；成功 `markSent`，可重试失败按退避回 `PENDING`，不可重试或耗尽则 `advanceChain`；异常吞在单条内，不影响同批其它行\r\n  - 验收：到期行被处理、未到期行不动；单条抛异常不影响同批其余行；链上第二个通道被调用时 `chain_index=1`\r\n  - 结果（2026-11-08）：只发当前一跳，不用 `sendWithFallback`——那会一次跑完整条链，使「每跳重试 3 次」失去意义。`DeliveryRetrySchedulerTest` 9 条。\r\n\r\n- [x] D1-7 告警抑制 AlertGate\r\n  - 作用：避免「每次失败发一条」把自己练成不看告警的人\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/AlertGate.java`\r\n  - 做法：内存计数（重启重置可接受）——同一 `source + ref_id` 连续失败达 `min-failures`（默认 3）才放行首条；`throttle-hours`（默认 24）内同类只发一条；成功恢复后补一条「已恢复」\r\n  - 验收：连续 2 次不发、第 3 次发；24h 内第二条被抑制；恢复后补发且计数清零\r\n  - 结果（2026-11-08）：内存态 + `Clock` 注入，测试用 `MutableClock` 推进 24 小时窗口，不 sleep。`AlertGateTest` 7 条。\r\n\r\n- [x] D1-8 长文分流（超阈值改走邮件）\r\n  - 作用：webhook 请求体上限 20 KB，QQ 单条也有长度限制——截断会丢信息，转邮件不丢\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/message/DeliveryOutboxService.java`（入队时决定链顺序）\r\n  - 做法：入队时若 `content.length() > app.delivery.long-text-threshold`（默认 6000）且链上含 `email`，把 `email` 提到链首；不分段、不截断\r\n  - 验收：8000 字符 → 链首是 `email`，`feishu` 不被调用；500 字符 → 顺序不变；链上没有 `email` 时保持原序（此时才允许下游按各自上限截断）\r\n  - 结果（2026-11-08）：超 6000 字符且链上有 `email` 时把 email 提到链首，不分段不截断；链上没有 email 则保持原序。测试 3 条，配置 `app.delivery.long-text-threshold` 已进 yaml。\r\n\r\n- [x] D1-9 outbox 保留期接入清理器\r\n  - 作用：表不会无界增长，同时保留「上周那条发了没」的审计窗口\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/observability/DataRetentionCleaner.java`\r\n  - 做法：新增 `outbox-sent-days`（默认 7）与 `outbox-dead-days`（默认 30），按 `created_at` 分批删；**`PENDING` 永不删**；`0` = 禁用该表清理\r\n  - 验收：`SENT` 超 7 天被删、`DEAD` 超 30 天被删、`PENDING` 无论多久都保留；配置为 0 时一条不删\r\n  - 结果（2026-11-08）：两档保留期（SENT 7 天 / DEAD 30 天），`PENDING` 永不删；`DataRetentionCleanerTest` 5 → 8 条。\r\n\r\n## 验收前置\r\n\r\nD1-6 的端到端降级验收（第二个通道真的收到消息）需要 `email-channel.md` 的 E1-3 完成。在此之前可用测试替身（注册一个假的 `MessageSender`）验证链路切换。\r\n',wr='# 邮件渠道模块（M1 后半）\r\n\r\n- 范围：新增 `channel-email` 模块 + 根 `pom.xml` / `server/pom.xml` 登记 + `application-channel.yaml`\r\n- 创建：2026-11-08\r\n- 状态：共 6 项，已完成 5 项，待办 1 项（E1-5 只差真实 SMTP 验证）\r\n- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 6.3 节\r\n- 关系：依赖 `delivery-outbox.md` 的 D1-1（通道链）才能被降级链调用；本清单只做渠道实现，不含投递层\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| 模块骨架 | 1 | 0 | 1 |\r\n| 实现 | 3 | 0 | 3 |\r\n| 部署与测试 | 1 | 1 | 2 |\r\n| 合计 | 5 | 1 | 6 |\r\n\r\n## 背景\r\n\r\n邮件在运行时就是一个渠道：实现 `MessageSender`、`channel()` 返回 `email`、被 `notify_channel` 按 `email:` 前缀指名。所以它是**独立模块**，不是 `server` 里的一个实现类——判据不是「有没有入站」，而是「外部依赖会不会被 core 继承」与「实现会不会被替换」。\r\n\r\n放 `server` 里，`spring-boot-starter-mail` 就进了 core 的依赖树，此后所有部署都带着它，哪怕只跑 QQ。模块化换来的是「不启用时 core 依赖里没有任何 mail 相关库」。\r\n\r\n邮件有双用途：**兜底告警**与**长文投递**（IM 里长文会被截断或折叠）。后者让它不只是「失败时才用」的通道。\r\n\r\n## 条目\r\n\r\n- [x] E1-1 模块骨架与三处登记\r\n  - 作用：让邮件作为可选渠道独立打包、独立启停，不污染 core 依赖树\r\n  - 位置：新增 `channel-email/pom.xml`；改根 `pom.xml` 的 `<modules>`、`server/pom.xml`\r\n  - 做法：三处同步——① 根 `pom.xml` 加 `<module>channel-email</module>`；② `server/pom.xml` 加 `channel-email` 的 `runtime` 依赖，并在 enforcer `bannedDependencies` 里补 `com.dark:channel-email:*:*:compile` 排除（现有规则禁止 core 编译期依赖渠道模块）；③ 模块自身 pom 声明 `harness-api` + `spring-boot-starter-mail`，并设 `jacoco.line.minimum`（父 pom 默认 0.00 只出报告，初值可设 0.5，实测后按「实测下方约 2 点」调整）\r\n  - 验收：`mvn -o -B verify` 通过；`mvn dependency:tree -pl server` 里 `channel-email` 是 runtime、`spring-boot-starter-mail` 不出现；`ArchitectureTest` 全绿\r\n  - 结果（2026-11-08）：新建 `channel-email` 模块（pom 声明 `harness-api` + `spring-boot-starter-mail`，不依赖 core）。三处登记全部完成：根 pom `<modules>`、server pom 的 `runtime` 依赖 + enforcer `bannedDependencies` 排除、模块自身 `jacoco.line.minimum`（实测 63.0% → 门槛 0.61）。`mvn dependency:tree -pl server` 里 mail starter 不出现。\r\n\r\n- [x] E1-2 EmailProperties 与渠道配置\r\n  - 作用：邮件配置归到 `channel.*` 命名空间，与 `channel.feishu.*` 对称；避免 `MailSenderAutoConfiguration` 的隐式装配\r\n  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailProperties.java`、`server/src/main/resources/application-channel.yaml`\r\n  - 做法：`@ConfigurationProperties(prefix = "channel.email")`，字段 `enabled / host / port / from / username / password / ssl / connectTimeoutMs`；`@ConditionalOnProperty(prefix="channel.email", name="enabled", havingValue="true")` 控制装配，默认关闭\r\n  - 验收：`enabled=false` 时容器里没有 `EmailMessageSender`，`MessageSenderRegistry` 启动日志的渠道列表不含 `email`；`enabled=true` 且配置齐全时注册成功\r\n  - 结果（2026-11-08）：`channel.email.*` 共 8 个键，`@ConditionalOnProperty` 默认关闭；配置放新文件 `application-channel.yaml`（与 `application-qq.yaml` 并列，经 `application.yaml` 的 `spring.config.import` 引入）。未用 `spring.mail.*`，因此不会触发 `MailSenderAutoConfiguration` 的隐式装配。\r\n\r\n- [x] E1-3 EmailMessageSender（发送 + 错误分流）\r\n  - 作用：把「发出去」和「为什么没发出去」都变成可判定的事实\r\n  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailMessageSender.java`\r\n  - 做法：自建 `JavaMailSenderImpl`（不依赖自动配置），`channel()` 返回 `"email"`；覆盖 `sendForReceipt`；正文纯文本 + 固定头部（来源 / 目标 / 时间 / 错误原因），不做 HTML。失败分流：认证失败（`AuthenticationFailedException`）→ 终态，重试只会一直失败且可能触发服务商风控；连接超时 → 可重试；收件人拒绝（`SendFailedException` 的 invalid addresses）→ 终态\r\n  - 验收：三类失败分别产生「终态 / 可重试 / 终态」三种回执，`reason` 含原始异常摘要；成功时 `sent=true`\r\n  - 结果（2026-11-08）：显式 new `JavaMailSenderImpl`（连接/读/写超时都可配）；主题取正文首行并截断到 40 字符，正文带「来源/目标/时间」头部。四类失败分流落地：认证失败与收件人无效 → `fatal`，超时与未知异常 → `failed`。\r\n\r\n- [x] E1-4 EmailChannelHealth（真实流量当探针）\r\n  - 作用：让监控页能看到邮件渠道状态，同时不付出 SMTP 建连 + 认证的成本\r\n  - 位置：`channel-email/src/main/java/com/dark/javaHarness/channel/email/EmailChannelHealth.java`\r\n  - 做法：实现 `ChannelHealth`，`snapshot()` **只读最近一次投递结果与时间**，不发探活请求。超过 7 天无投递报「未知（无近期投递）」而不是假装健康\r\n  - 验收：从未投递过 → `available=false` + `detail` 为「未知」；刚成功过 → `available=true`；`detail` 不含用户名密码\r\n  - 结果（2026-11-08）：只读 `EmailMessageSender.lastAttempt()`，不发探活请求；超 7 天无投递报「未知」而不是「可用」。`Clock` 可注入，测试用 `Clock.fixed` 造 8 天前的时间，不 sleep。\r\n\r\n- [ ] E1-5 环境变量与首次部署检查\r\n  - 作用：凭据不进仓库；「邮件进垃圾箱」这件事在首次部署时就被确认，而不是等告警丢了才发现\r\n  - 位置：`application-channel.yaml` 的占位符、`docs/dev.md` 的部署说明、`docs/guides/docker-deploy.md` 的环境变量清单\r\n  - 做法：`username` / `password` 从 `MAIL_USERNAME` / `MAIL_PASSWORD` 注入；文档写清 SMTP 服务商选择（QQ 邮箱授权码 / 阿里云邮件推送等）与「发件人白名单」；首次部署检查清单加一条：**发一封测试邮件到你手机上的邮箱客户端，确认不在垃圾箱**\r\n  - 验收：`grep -r "MAIL_PASSWORD" docs/` 能查到说明；仓库里无明文密码；首次部署后测试邮件在收件箱\r\n  - 进度（2026-11-08）：**代码与文档侧已就绪**——`MAIL_USERNAME` / `MAIL_PASSWORD` 占位符、`docs/dev.md` 的四步首次启用检查、`docker-deploy.md` 的渠道开关说明都已补。**验收未闭环**：没有 SMTP 凭据，无法验证「测试邮件落在收件箱而非垃圾箱」。需要你自己配一次并确认，这条才能勾选。\r\n\r\n- [x] E1-6 单元测试\r\n  - 作用：避免邮件模块成为「写完就没人动、坏了不知道」的一角\r\n  - 位置：`channel-email/src/test/java/com/dark/javaHarness/channel/email/`\r\n  - 做法：`EmailMessageSenderTest`（成功 / 认证失败 / 超时 / 无效收件人 四类），`EmailChannelHealthTest`（无投递 / 近期成功 / 超 7 天）\r\n  - 验收：两个测试类全绿；`channel-email` 行覆盖率不低于 `jacoco.line.minimum`\r\n  - 结果（2026-11-08）：`EmailMessageSenderTest` 9 条（成功 / 认证失败 / 收件人无效 / 超时 / 空目标 / 空正文 / 缺 from / 长主题截断 / lastAttempt 记录）、`EmailChannelHealthTest` 6 条，共 15 条全绿。\r\n\r\n## 与投递层的接口约定\r\n\r\n- 目标格式：`email:me@example.com`（`target` 就是收件地址，不做别名映射）\r\n- 回执：`DeliveryReceipt.reason` 必须能区分「配置错」与「瞬时故障」，否则 D1-4 的 `markFailed(retryable)` 无法判断\r\n- 邮件正文不超过 20 KB 不做限制（这里不是 webhook），但长文由 D1-8 决定走哪个通道\r\n',Nr='# 飞书渠道（M3a webhook / M3b 事件订阅）\r\n\r\n- 范围：新增 `channel-feishu` 模块；M3a 只做出站推送，M3b 才有入站对话\r\n- 创建：2026-11-08\r\n- 状态：共 9 项，已完成 8 项；其中 **M3a 4/5**（F2-5 只差真实群 webhook 验证）、**M3b 4/4 完成**（含群聊 @ 扩展）\r\n- 规划书：[docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md) 第 5.3 / 8 节\r\n- 关系：依赖 `delivery-outbox.md`（错误码要能落到 `markFailed(retryable)`）；与 `email-channel.md` 结构对称，登记方式相同\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| M3a 出站推送 | 4 | 1 | 5 |\r\n| M3b 事件订阅（长连接） | 4 | 0 | 4 |\r\n| 合计 | 8 | 1 | 9 |\r\n\r\n## 背景\r\n\r\n判据只有一句：**投递可靠性只需要「发得出去」，不需要「收得进来」。** 这两件事在飞书上是两套接入方式，成本差 6 倍。\r\n\r\n- **M3a 群机器人 webhook**：0.5 人日。不建应用、不申请权限、不需要审核、不需要公网回调、零 SDK。拿到 `code 0/9499/11232` 三种错误码就够 outbox 分流\r\n- **M3b 事件订阅**：3~4 人日。才有对话入口；回调路线需域名 + HTTPS，长连接路线需 SDK 在 Java 25 上能跑\r\n\r\nM3a 做完，「主通道可换、失败原因可分类」就成立了。M3b 是产品升级，不是可靠性修复。\r\n\r\n## M3a：群机器人 webhook\r\n\r\n- [x] F2-1 模块骨架与三处登记\r\n  - 作用：飞书作为可选渠道独立打包，不启用时 core 依赖里没有任何 lark / http 相关库\r\n  - 位置：新增 `channel-feishu/pom.xml`；改根 `pom.xml`、`server/pom.xml`\r\n  - 做法：与 `channel-email` 同一套登记动作（根 pom `<modules>` / server pom `runtime` 依赖 + enforcer `bannedDependencies` 排除 / 自身 pom 的 `jacoco.line.minimum`）。M3a **只依赖 `harness-api` + JDK `HttpClient`**，不要引入 `spring-boot-starter-web`（那是 M3b 回调才需要）\r\n  - 验收：`mvn -o -B verify` 通过；`dependency:tree -pl channel-feishu` 里没有 web starter、没有 `lark-oapi`\r\n  - 结果（2026-11-08）：新建 `channel-feishu`，依赖只有 `harness-api` + `spring-boot-starter`（容器能力）+ `jackson-databind`（请求体与错误码），**没有** web starter、没有 `lark-oapi`。三处登记完成：根 pom `<modules>`、server pom 的 `runtime` 依赖 + enforcer 排除、自身 `jacoco.line.minimum`（实测 84.5% → 门槛 0.82）。\r\n\r\n- [x] F2-2 FeishuProperties 与 webhook 配置\r\n  - 作用：让 `notify_channel` 只写别名，凭证不进业务表\r\n  - 位置：`channel-feishu/.../FeishuProperties.java`、`server/src/main/resources/application-channel.yaml`\r\n  - 做法：`@ConfigurationProperties(prefix = "channel.feishu")`，M3a 字段 `enabled / webhookAlias / webhookUrl / webhookSecret`；`webhook-url` 与 `webhook-secret` 从环境变量注入（`FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET`）；`@ConditionalOnProperty` 默认关闭\r\n  - 验收：`enabled=false` 时无 Bean；`enabled=true` 且缺 secret 时启动即报配置缺失（不要静默按无签名发送）；`notify_channel` 里出现的是 `feishu:main` 而不是 URL\r\n  - 结果（2026-11-08）：字段为 `enabled / alias / webhookUrl / webhookSecret / timeoutMs`，默认 `alias=main`、`enabled=false`。`FeishuChannelConfig` 在 `enabled=true` 时校验 URL 与 secret，缺任一直接抛 `IllegalStateException` 拒绝启动（`FeishuChannelConfigTest` 三例覆盖）。顺手补了 `docker-compose.yml` 的渠道环境变量透传——此前只有文档写了 `CHANNEL_EMAIL_ENABLED`，compose 并未注入，等于文档说不通。\r\n\r\n- [x] F2-3 FeishuWebhookSender（签名 + 错误映射）\r\n  - 作用：把飞书的结构化错误码翻译成「可重试 / 终态」，让 outbox 能自动补救\r\n  - 位置：`channel-feishu/.../FeishuWebhookSender.java`\r\n  - 做法：`channel()` 返回 `"feishu"`；`target` 是配置里的别名（如 `main`），不是 URL；签名 `sign = Base64(HMAC-SHA256(key = timestamp + "\\n" + secret, data = ""))`，**timestamp 用秒级**；请求体 `{"msg_type":"text","content":{"text":"..."}}`。映射：`code=0` → 成功；`code=9499` → 终态（请求体非法，含超 20 KB）；`code=11232` → 可重试；返回体提示 sign / timestamp → 终态并提示查时钟\r\n  - 验收：单测覆盖四类分支；签名用固定 `timestamp + secret` 能算出与飞书文档一致的 base64；本机时钟偏 10 分钟时给出可读错误而不是笼统失败\r\n  - 结果（2026-11-08）：`FeishuWebhookSenderTest` 13 条，用 JDK `HttpServer` 起本地桩验证「发出去的 JSON」与「响应怎么翻译」，不 mock HttpClient。签名向量由 Python `hmac` 独立算出并硬编码（`1700000000` + `test-secret` → `mbm4Y4o…PL84=`），锁住「key 是 timestamp+secret、data 为空、秒级时间戳」三件事。分流补齐 HTTP 维度：429 / 5xx 可重试，其余 4xx 终态；超 20 KB 在发送前就判终态，不浪费一次必然失败的请求。\r\n\r\n- [x] F2-4 FeishuHealth\r\n  - 作用：让 `ChannelWatchdog` 能发现飞书不可达\r\n  - 位置：`channel-feishu/.../FeishuHealth.java`\r\n  - 做法：实现 `ChannelHealth`，读最近一次投递结果与时间（与邮件同一策略：不发主动探活，避免为了探活往群里发测试消息）；超 7 天无投递报「未知」\r\n  - 验收：`channelName()` 为「飞书 / Webhook」；从未投递 → `available=false` + 「未知」；`detail` 不含 webhook URL 与 secret\r\n  - 结果（2026-11-08）：`channelId()=feishu`（探活告警靠它排除本渠道），四态与邮件一致：从未投递 / 超 7 天 → `UNKNOWN`，最近一次失败 → `DOWN`。`FeishuHealthTest` 7 条，其中一条专门断言 `detail` 不含 URL 与 secret——监控页是浏览器能打开的页面。\r\n\r\n- [ ] F2-5 真机验证（含真实群与限流实验）\r\n  - 作用：证明「换个通道就能拿到可分类的失败原因」不是纸面结论\r\n  - 位置：飞书客户端 + 本机运行中的服务\r\n  - 做法：建一个只有自己的群 → 群设置 → 群机器人 → 添加自定义机器人 → 开启签名校验 → 拿到 URL 与 secret；配进 `application-channel.yaml`；把某条定时任务的 `notify_channel` 改成 `feishu:main|email:...`\r\n  - 验收：① 正常推送在飞书群可见；② 故意写错 secret → outbox 该行 `DEAD` 且 `last_error` 提示签名；③ 停 NepCat 后任务结果从飞书收到（`chain_index=0`）；④ 20 KB 以上正文走邮件（D1-8）\r\n  - 进度（2026-11-08）：**代码侧就绪，验收未闭环**。签名、错误分流、20 KB 预检都有单测覆盖，但没有真实 webhook URL 与 secret，无法验证「消息真的出现在飞书群」。需要你建一个只有自己的群、加自定义机器人、开启签名校验，把 URL 与 secret 配进环境变量后按上面四步验一遍。\r\n\r\n## M3b：事件订阅（未启动，等 M3a 跑一两周再决定）\r\n\r\n- [x] F2-6 路线选型与前置验证\r\n  - 作用：先确认「有没有域名」，再决定走回调还是长连接——两条路的前置完全不同\r\n  - 位置：`docs/dev.md` 的渠道说明；`channel-feishu/pom.xml`\r\n  - 做法：有域名 + 有效证书（Let\'s Encrypt）→ 走 Webhook 回调，零 SDK；没有域名 → 只能长连接，**先花 15 分钟验证 `lark-oapi` 在 Java 25 上能建 WS**，跑不通就停在这里（不值得为它降级 JDK）\r\n  - 验收：选型结论与依据写进 `docs/dev.md`；若选长连接，SDK 连通性验证有明确结论\r\n  - 结果（2026-11-09）：选长连接（无可用 HTTPS 域名）。`oapi-sdk 2.4.19` 在 JDK 25.0.5 上握手成功（日志 `connected to wss://msg-frontier.feishu.cn/...`）。**发现阻断性缺陷**：SDK 没有公开 stop，`pingLoop` 是死循环且吞中断、OkHttp 线程非守护——JVM 永不自然退出。方案：`FeishuWsClient.stop()` 断开后延迟 3 秒 `Runtime.halt(0)`，并写进 `docs/dev.md`，升级 SDK 必须重跑「启动 → Ctrl+C」。\r\n\r\n- [x] F2-7 入站结构（3 秒硬约束）\r\n  - 作用：一次 agent 对话几十秒，而飞书要求 3 秒内应答，超时会重推\r\n  - 位置：`channel-feishu/.../event/FeishuEventController.java`（回调）或 `ws/FeishuWsClient.java`（长连接）\r\n  - 做法：收到事件 → 验签 → 去重 → **入有界队列 → 立即返回 200**；后台线程池消费。**复用 `OneBotEventServiceImpl` 的「有界队列 + 背压丢弃 + 超时 `cancel(true)` + 失败静默」结构**，不重新发明\r\n  - 验收：下发一条需要 30 秒生成的消息，飞书侧无「超时重推」；队列满时回「忙，稍等」而不是静默丢\r\n  - 结果（2026-11-09）：`FeishuInboundService` 只做判定 + 入队，2~4 线程、有界队列（默认 32），满则丢并记 warn；SDK 回调毫秒级返回。实测群里 @ 后 1.6 秒内完成回复（含模型生成），无重推。队列满时目前是丢弃 + 日志（未回「忙」），个人用量下可接受，作为已知取舍记录。\r\n\r\n- [x] F2-8 验签三关与去重\r\n  - 作用：回调端点暴露在公网，任何人知道 URL 就能伪造事件\r\n  - 位置：`channel-feishu/.../event/`（验签与去重）\r\n  - 做法：三关缺一不可——校验 `verification_token`、用 `encrypt_key` 解密、`allowed_users` 白名单（`open_id` 不在名单直接丢弃）；`event_id` 用 `LinkedHashMap` + 5 分钟 TTL 去重（复用 `MessageDedupCache` 写法）。**不为「先跑通」临时关掉验签**\r\n  - 验收：伪造请求被拒（无验签 / 错 token 两例）；同一 `event_id` 第二次到达不处理；不在白名单的用户消息被丢弃且只记 debug\r\n  - 结果（2026-11-09）：长连接没有回调端点，三关相应变形——① 连接层由 SDK 用 App 凭据换取 ws 地址，`encrypt-key` 可选解密；② `allowed-users` 白名单（空 = 配对模式，只打印 open_id）；③ `FeishuEventDedup` 按 event_id 去重，5 分钟 TTL、容量 2048。差异写进 `docs/dev.md`。`FeishuEventDedupTest` 5 条、`FeishuInboundServiceTest` 8 条。\r\n\r\n- [x] F2-9 对话入口接 ChatService\r\n  - 作用：给「随手可用」这个入口补上 1:1 对话\r\n  - 位置：`channel-feishu/.../reply/FeishuReplyService.java`\r\n  - 做法：从事件取 `open_id` 与文本，复用 core 的 `ChatService`，会话按 `open_id` 绑定（与 QQ 按用户绑定同构）；回复走 `im/v1/messages`；首次对接需要配对——用户先发一句话，日志打印 `open_id`，复制进 `notify_channel`，**不做自动发现**\r\n  - 验收：私聊一句话能收到 agent 回复；同一 `open_id` 多轮上下文连续；未配对用户的消息被白名单丢弃\r\n  - 结果（2026-11-09）：`FeishuChatHandler` 按 `(open_id, chat_id)` 绑定 `feishu_session_binding`（V15），复用 `ChatService`（channel=feishu），回复走 `im/v1/messages`（单聊 `open_id` / 群聊 `chat_id`）。实测单聊与群聊 @ 均收到回复。\r\n  - **超出原条目的扩展**：群聊 @ 机器人（原计划只做 1:1）。入站接受 `chatType=group` 且 `mentions` 非空，剥掉 `@_user_N` 占位符；会话按人 + 群隔离。需要额外权限 `im:message.group_at_msg`。\r\n  - **实测发现的两个 bug**：① `session.creator` 只有 varchar(30)，`feishu:` + 35 字符 open_id 超长，建会话报 `Data too long`——V16 放宽到 64；② `mvn -pl server -am package` 在只改渠道模块时不重打 fat jar，必须 `clean`，否则跑的是旧代码。\r\n\r\n## 安全前提\r\n\r\nwebhook URL 本身就是凭证（泄露即被冒名发消息），所以 F2-2 强制要 secret、F2-3 强制签名。M3b 的回调端点还要额外做 F2-8 的三关。这条不是可选项——「先跑通再补安全」在这类端点上通常意味着永远不补。\r\n',Or='# 飞书入站扩展：群聊全量 / 富文本 / 图片\r\n\r\n- 范围：`channel-feishu` 的 `ws` 包；契约侧 `harness-api`（`ChatRequest` / `ChatAttachment`）；\r\n  执行侧 `server` 的 `AgentService` 与 `AgentScopeGeneralAgent`\r\n- 创建：2026-11-10\r\n- 状态：共 11 项，已完成 10 项（T2 需真机验证）\r\n- 前置：飞书后台权限\r\n  - `im:message.group_msg`（获取群组中所有消息，**敏感权限需审核**）—— 用户已确认开通\r\n  - `im:resource`（下载消息中的图片/文件资源）—— **待确认**，图片链路依赖它\r\n  - `im:message:send_as_bot`（已有，回复用）\r\n- 关系：`feishu-channel.md` 的 M3b 只做了「单聊 + 群聊 @」，本清单是对入站能力的扩展\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| 群聊接入 | 3 | 0 | 3 |\r\n| 消息解析 | 2 | 0 | 2 |\r\n| 图片多模态 | 4 | 0 | 4 |\r\n| 测试与文档 | 1 | 1 | 2 |\r\n| 合计 | 10 | 1 | 11 |\r\n\r\n## 背景\r\n\r\n当前入站有两道门禁，都在 `FeishuInboundService.onEvent`：\r\n\r\n```java\r\n// 第 90 行：群里没人 @ 就丢弃（依赖「只推送 @ 消息」的旧权限语义）\r\nif (group && (message.getMentions() == null || message.getMentions().length == 0)) { ... return; }\r\n// 第 108 行：非 text 一律丢弃\r\nif (!"text".equals(message.getMessageType())) { ... return; }\r\n```\r\n\r\n目标是：**群里所有消息都回**，并且**图片直接给模型看**、**富文本能读**。\r\n\r\n两处都牵动边界值得先说清：\r\n\r\n1. **放开群聊会引入自回环风险**。飞书文档写明 `im.message.receive_v1` 的群消息不包含机器人自己发的，\r\n   但这是「权限配置正确时」的前提；一旦权限或事件配置变化，机器人可能对自己的回复再回一轮。\r\n   闸门放在 `sender_type` 上，成本极低。\r\n2. **图片要穿过三层**才能到模型：飞书下载 → 契约携带 → AgentScope 消息块。任何一层断了，\r\n   模型都只会看到一段文字，而且**不会报错**（图片被静默丢弃），所以验收必须看模型是否真的描述了图。\r\n\r\n## 条目\r\n\r\n### 群聊接入\r\n\r\n- [x] G1 去掉 `mentions` 门禁\r\n  - 作用：群内普通消息（不 @ 机器人）也能触发回复\r\n  - 位置：`channel-feishu/.../ws/FeishuInboundService.java` 第 90~95 行\r\n  - 做法：删除 group + mentions 空即 return 的分支；`stripMentions` 保留（@ 过的消息仍要剥占位符）\r\n  - 验收：群里发一句不 @ 的话，机器人回复；日志出现 `收到群聊消息` 而不是 `群里没人 @ 机器人，忽略`\r\n\r\n- [x] G2 过滤机器人自身与其他 bot 的消息\r\n  - 作用：防止自回环——开 `group_msg` 后若事件包含 bot 消息，会形成无限对话\r\n  - 位置：同上，取 `data.getSender().getSenderType()`\r\n  - 做法：`senderType` 不是 `user` 直接丢（`app`/`bot` 都算）；日志按 debug，避免正常运行时刷屏\r\n  - 验收：机器人回复后不再触发第二轮；两个机器人在同群不会互刷\r\n  - 备注：飞书文档称群消息事件不含机器人消息，这里是有意保留的一道闸，不是兜底逻辑\r\n\r\n- [x] G3 权限与文案同步\r\n  - 作用：代码放开了但后台没开权限，表现是「没人 @ 就永远没反应」，极易误判为 bug\r\n  - 位置：`channel-feishu/README.md`、`docs/guides/qq-channel.md` 无需改、`docs/dev.md`\r\n  - 做法：README 把「群聊必须 @ 机器人」改成「群内所有消息都会回复（需 `im:message.group_msg`，敏感权限）」；\r\n    启动日志或 README 提示权限缺失时的表现\r\n  - 验收：按 README 配好权限能收到普通群消息；未开权限时文档能解释「为什么没反应」\r\n\r\n### 消息解析\r\n\r\n- [x] P1 富文本 `post` 解析为纯文本\r\n  - 作用：飞书长消息/粘贴内容常以 post 发送，当前整条被丢弃\r\n  - 位置：`FeishuInboundService`，新增 `postText(JsonNode)`；`onEvent` 按 `messageType` 分派\r\n  - 做法：`content` 结构为 `{"title":"...","content":[[{"tag":"text","text":"..."},...]]}`；\r\n    遍历段落与元素，`text` 取 `text`、`a` 取 `text`、`at` 取显示名或剥离、`img` 收集 `image_key`；\r\n    `title` 非空时置于首行。解析失败按原文兜底\r\n  - 验收：一条「标题 + 两段文字 + 一张图」的富文本，模型能读到完整文字并看到图\r\n\r\n- [x] P2 图片消息识别与附件提取\r\n  - 作用：`image` 类型当前直接丢弃，用户发图问「这是什么」没有任何反馈\r\n  - 位置：同上；`FeishuInboundMessage` 增加附件字段\r\n  - 做法：`image` 类型取 `content.image_key`；post 里的 `img` 元素同样收集；\r\n    `FeishuInboundMessage` 增加 `List<FeishuAttachment>`（`fileKey` + 推断的 mimeType）\r\n  - 验收：单发一张图（无文字）也能触发，文本部分为占位（如「[图片]」），不因 `@NotBlank` 被拦\r\n\r\n### 图片多模态\r\n\r\n- [x] V1 契约：`ChatAttachment` 与 `ChatRequest.attachments`\r\n  - 作用：让"一条消息带图"成为端口能力，而不是渠道内部的私货\r\n  - 位置：`harness-api` 新增 `contract/model/ChatAttachment.java`；`view/ChatRequest.java` 加组件\r\n  - 做法：`record ChatAttachment(String mimeType, byte[] data)`，构造时校验非空；\r\n    `ChatRequest` 增加 `List<ChatAttachment> attachments`，**保留 5 参重载**（现有 55 处调用不动），\r\n    紧凑构造器把 null 归一为空列表\r\n  - 验收：`PortSignatureGuardTest` 更新快照后通过；不带附件的调用零改动\r\n\r\n- [x] V2 执行链：把附件送到 Agent\r\n  - 作用：`ChatService` 到 Agent 之间只有 `String objective`，附件没有通道\r\n  - 位置：`contract/api/AgentService.java`、`server/.../AgentServiceImpl.java`、`agent/Agent.java`、\r\n    `service/impl/ChatTurnExecutor.java`\r\n  - 做法：`AgentService` 增 `executeSync(..., List<ChatAttachment>)` 重载；\r\n    `Agent` 增 `default execute(Goal, List<ChatAttachment>)`（默认委托无附件版本，保持既有实现不改）；\r\n    `ChatTurnExecutor` 传 `request.attachments()`\r\n  - 验收：单测用假 Agent 断言附件原样到达；无附件时走原路径，行为不变\r\n\r\n- [x] V3 AgentScope 侧构造多模态消息\r\n  - 作用：让模型真的"看见"图，而不是收到一句「用户发了图片」\r\n  - 位置：`server/.../agent/AgentScopeGeneralAgent.java` 约 202 行\r\n  - 做法：有附件时用 `new UserMessage(ContentBlock...)`：`TextBlock` 放正文，\r\n    每个附件转 `ImageBlock(new Base64Source(mimeType, base64(data)))`；无附件保持原写法\r\n  - 验收：发一张能明确辨认内容的图（如写着数字的截图），模型回答里出现该内容\r\n  - 已知限制：**Graph 编排路径不支持**（`MultiAgentGraphAgent` 走 default 实现，附件被忽略）。\r\n    多 Agent 场景下图片会丢，需在文档标注\r\n\r\n- [x] V4 飞书下载图片资源\r\n  - 作用：事件里只有 `image_key`，字节要单独取\r\n  - 位置：`channel-feishu/.../ws/FeishuApiClient.java`\r\n  - 做法：`GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image`，\r\n    带 `tenant_access_token`；按响应头 `Content-Type` 定 mimeType；\r\n    **单图上限 8 MB**（飞书限制 10 MB，留余量），超限丢弃并 WARN\r\n  - 验收：图片下载成功且 mimeType 正确；缺 `im:resource` 时日志能看出是权限问题（401/403），\r\n    且文本部分仍照常回复\r\n\r\n### 测试与文档\r\n\r\n- [x] T1 入站解析用例\r\n  - 作用：三类消息的分派逻辑最容易回归\r\n  - 位置：`channel-feishu/src/test/.../FeishuInboundServiceTest.java`\r\n  - 做法：补「群聊不 @ 也入队」「sender_type=app 丢弃」「post 解析出文字 + 图片」「image 提取 image_key + 占位文本」\r\n  - 验收：新增用例全绿；原有 8 条不受影响\r\n\r\n- [ ] T2 端到端与文档\r\n  - 做法：真机发普通群消息、发图、发富文本各一条；`docs/dev.md` 记录「群聊全量接收」的权限前提与自回环闸门\r\n  - 验收：三条消息都有回复，图片被正确描述；README 与实现一致\r\n\r\n## 风险与取舍\r\n\r\n| 项 | 说明 |\r\n| --- | --- |\r\n| 群里每条都回 | 用户明确要求。群多或活跃时 LLM 成本与骚扰都会上来；后续若要收敛，可复用 `SemanticTriggerJudge` 加「何时开口」策略，本次不做 |\r\n| 敏感权限审核 | `im:message.group_msg` 需企业管理员审核；个人租户通常即时通过 |\r\n| 图片不进记忆 | 会话快照只存文本，下一轮看不到上一轮的图。模型当轮可见，多轮追问需要重发 |\r\n| Graph 路径不支持图 | 多 Agent 编排会把附件丢掉，需要在文档明确；若要支持，得改编排节点的消息构造 |\r\n| base64 体积 | 图片以 base64 进请求体，8 MB 图约 10.7 MB 请求。飞书发送上限不受影响（那是出站），但模型侧有 token 成本 |\r\n\r\n## 暂不做\r\n\r\n- **文件 / 语音 / 表情包消息**：`file`、`audio`、`sticker` 仍记 debug 丢弃。\r\n  文件要先下载再按类型解析（PDF/Excel 等），语音要 ASR，属于独立议题。\r\n- **图片回传**：让模型/Agent 生成图片再发回飞书，与本清单方向相反，另开。\r\n\r\n## 落地记录（2026-11-10）\r\n\r\n- **G1/G2**：`FeishuInboundService.onEvent` 去掉 mentions 门禁，改按 `sender_type` 过滤非用户消息。\r\n- **P1/P2**：新增 `parse()` 按 `messageType` 分派——`text` 原样、`post` 拼标题+段落并把 `img`\r\n  收集成资源键、`image` 取 `image_key` 并以「[图片]」占位。`FeishuAttachment(fileKey)` 随\r\n  `FeishuInboundMessage.attachments` 传递。\r\n- **V1**：`contract.model.ChatAttachment(mimeType, byte[])`；`ChatRequest` 增加 `attachments`，\r\n  保留 5 参重载，55 处既有调用未改。\r\n- **V2**：`AgentService.executeSync(..., List<ChatAttachment>)`；`Agent.execute(Goal, List)` 默认\r\n  实现忽略附件；`AgentServiceImpl` 仅在附件非空时走新方法（纯文本轮次行为不变）。\r\n- **V3**：`AgentScopeGeneralAgent.userMessage()` 把图片转 `ImageBlock(Base64Source)`，\r\n  非图片附件跳过；`AgentScopeUserMessageTest` 4 条覆盖纯文本 / 单图 / 多图 / 非图片。\r\n- **V4**：`FeishuApiClient.downloadImage()` 调 `resources` 接口，mimeType 取自响应头，\r\n  超 8 MB 丢弃；下载失败只跳过该图。\r\n- **T1**：`FeishuInboundServiceTest` 扩到 11 条，覆盖群聊不 @、bot 消息丢弃、post 解析、图片附件、文件类型丢弃。\r\n\r\n**待 T2**：真机验证——群里发普通消息（不 @）、发图、发富文本各一条，确认都能回复且图片内容被正确描述。\r\n需要后台确认 `im:resource` 权限已开通，否则图片下载会 403。\r\n',Gr='# LLM 供应商 / 模型 / 路由与 Agent 绑定\r\n\r\n- 范围：`deerfect-server` 的 LLM 配置模型、解析链路、Agent 绑定、失败切换、观测与管理面\r\n- 创建：2026-11-12\r\n- 状态：共 29 项，已完成 29 项，待办 0 项\r\n- 规划书：[docs/design/2026-11-12-llm-provider-model-routing.md](../design/2026-11-12-llm-provider-model-routing.md)\r\n- 前提：按全新设计落地，不保留旧 `model_provider` 表与按名查找接口的兼容层\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n| --- | --- | --- | --- |\r\n| 数据层 | 3 | 0 | 3 |\r\n| 解析与客户端 | 6 | 0 | 6 |\r\n| 参数与能力 | 4 | 0 | 4 |\r\n| 内部调用收口 | 3 | 0 | 3 |\r\n| 接入改造 | 4 | 0 | 4 |\r\n| 管理面 | 3 | 0 | 3 |\r\n| 观测 | 2 | 0 | 2 |\r\n| 清理与验收 | 4 | 0 | 4 |\r\n| 合计 | 29 | 0 | 29 |\r\n\r\n## 背景\r\n\r\n现状把供应商凭证、模型名、端点地址混在 `model_provider` 一张表里，内部调用仍按模型名解析，且缺少引用完整性与失败链。本清单按规划书实现三层模型（provider → model → route）与唯一调用入口，目标是：代码只按 id 查找、配置错误启动即暴露、主模型失败可按数据切换、所有调用可按 route/model 观测。\r\n\r\n## 里程碑\r\n\r\n| 里程碑 | 包含条目 | 可独立提交 |\r\n| --- | --- | --- |\r\n| M1 数据层与解析器 | A1-A3、B1-B6 | 是（暂不接入业务） |\r\n| M2 主链路接入 | C1-C4、E1-E4 | 是 |\r\n| M3 内部调用收口 | D1-D3 | 是 |\r\n| M4 管理面、观测与清理 | F1-F3、G1-G2、H1-H4 | 是 |\r\n\r\n## 条目\r\n\r\n### A. 数据层\r\n\r\n- [x] A1 V20 迁移：四张新表与 Agent 改造\r\n  - 作用：建立 provider / model / route / route_member 的实体关系，Agent 改为绑定路由\r\n  - 位置：`deerfect-server/src/main/resources/db/migration/V20__llm_provider_model_route.sql`\r\n  - 做法：按规划书第 4 节 DDL 建表；`agent` 增加 `route_id`、`params`、索引与外键；`llm_model` 唯一键 `(provider_id, upstream_name)`，`llm_route_member` 唯一键 `(route_id, priority)`\r\n  - 验收：空库从 V1 顺序跑到 V20 成功；`SHOW CREATE TABLE` 四张表的外键与唯一键齐备；删除仍被引用的 provider/model 被数据库拒绝\r\n  - 依赖：无\r\n  - 结果（2026-11-12）：V20 已应用（`flyway_schema_history` 记录 version=20 success=1），本地库产生 3 供应商 / 12 模型 / 4 路由 / 4 成员\r\n  - 修复（2026-11-12）：V20 的成员回填硬编码 `main -> model_provider.id=12`，而 V1 种子只到 id 11——全新库上 main 路由 0 成员，`BindingValidator` 启动校验失败（本地因人工补过 id=12 未暴露，CI 首跑即红）。新增 `V24__llm_route_member_repair.sql` 幂等修复：任何无启用成员的路由补入 id 最小的启用模型；CI backend 注入占位 `DASHSCOPE_API_KEY`/`DEEPSEEK_API_KEY` 满足凭证校验（不调真实 API）\r\n\r\n- [x] A2 新表实体与 Mapper\r\n  - 作用：给解析层与校验层提供持久化入口\r\n  - 位置：`deerfect-server/src/main/java/com/dark/javaHarness/domain/entity/`、`mapper/`\r\n  - 做法：新增 `LlmProviderEntity` / `LlmModelEntity` / `LlmRouteEntity` / `LlmRouteMemberEntity` 与对应 `BaseMapper`；`AgentEntity` 增加 `routeId`、`params`；纯 Mockito 单测下条件构造用字符串列名，不用 lambda\r\n  - 验收：`mvn -pl deerfect-server -am compile` 通过；实体字段与 DDL 一一对应\r\n  - 依赖：A1\r\n  - 结果（2026-11-12）：4 个实体 + 4 个 Mapper；`AgentEntity` 增加 `routeId`/`params`\r\n\r\n- [x] A3 现有数据回填与种子脚本\r\n  - 作用：把当前 12 条模型部署、13 个 Agent 迁移到新模型，保证切换后行为一致\r\n  - 位置：V20 迁移内数据段或单独 `V20_1__llm_routing_seed.sql`\r\n  - 做法：按供应商拆出行；为现有 Agent 建立 `main` / `fast` / `judge` 路由映射；内部功能建成 `is_system=1` 的系统 Agent；同名模型按 `(provider_id, upstream_name)` 各自成行\r\n  - 验收：回填后每个 Agent 的 `route_id` 非空；`llm_model` 中不存在因重名覆盖导致的行丢失；回填脚本可重复执行或明确只执行一次\r\n  - 依赖：A1、A2\r\n  - 结果（2026-11-12）：13 个 Agent 全部绑定路由（main 9 / fast 2 / judge 2 / multi 1）；旧模型 id 保留，8/9 重名两行各自成行\r\n\r\n### B. 解析与客户端\r\n\r\n- [x] B1 AgentCatalog：按 Agent 名读取配置\r\n  - 作用：所有调用从 Agent 出发，成为唯一身份入口\r\n  - 位置：`llm/catalog/AgentCatalog.java`\r\n  - 做法：缓存 `agent` 表（含系统 Agent），提供 `get(agentName)`；启动与热加载时刷新；找不到 Agent 直接报错，不回退默认\r\n  - 验收：按名命中、未命中抛错、禁用 Agent 不可用；单测覆盖\r\n  - 依赖：A2\r\n  - 结果（2026-11-12）：懒加载 + `reload()`；2 条单测\r\n\r\n- [x] B2 ProviderCatalog：provider / model 读取与凭证解析\r\n  - 作用：把端点与凭证解析从调用链中剥离，集中处理\r\n  - 位置：`llm/catalog/ProviderCatalog.java`\r\n  - 做法：读取 `llm_provider` / `llm_model`；按 `credential_ref` 从环境或密钥源解析；凭证缺失记录明确错误\r\n  - 验收：凭证存在时返回、缺失时抛配置错误；密钥不出现在日志与异常文本中\r\n  - 依赖：A2\r\n  - 结果（2026-11-12）：`Environment.getProperty(credentialRef)` 解析；2 条单测\r\n\r\n- [x] B3 ModelResolver：route → 有序可用成员\r\n  - 作用：唯一的路由解析实现，替代所有按名查找\r\n  - 位置：`llm/routing/ModelResolver.java`\r\n  - 做法：按 `route_id` 取启用成员，按 `priority` 升序；过滤 `enabled=0` 的成员、模型与供应商；结果为空抛错；解析结果按 route 缓存，热加载时失效\r\n  - 验收：顺序、禁用过滤、空路由、路由不存在四类用例；不出现任何按模型名查询\r\n  - 依赖：A2、B2\r\n  - 结果（2026-11-12）：代码内二次排序保证顺序；5 条单测\r\n\r\n- [x] B4 ClientFactory：provider + model → 客户端\r\n  - 作用：按部署行构建 OpenAI 兼容客户端，参数不再散落在调用方\r\n  - 位置：`llm/client/ClientFactory.java`\r\n  - 做法：按 `base_url` + 凭证构建；应用 `default_params`；处理超时、连接池、思考能力开关；构建失败返回明确错误\r\n  - 验收：同一 model 重复构建结果稳定；缺凭证/非法 URL 构建失败；单测覆盖参数映射\r\n  - 依赖：B2\r\n  - 结果（2026-11-12）：`LlmClientFactory` 复用既有 `ChatClientFactory` 的端点/凭证解析；`default_params` 合并留给 C2\r\n\r\n- [x] B5 ClientRegistry：按 model_id 缓存与失效\r\n  - 作用：连接池复用与失败重建，彻底移除按名索引\r\n  - 位置：`llm/client/ClientRegistry.java`\r\n  - 做法：`Map<Long, ChatClient>`；`get(modelId)` 未命中时构建；`invalidate(modelId)` 按 id 重建；热加载整体替换\r\n  - 验收：按 id 命中/失效；传入不存在的 id 报错而非回退默认；并发失效仅重建一次\r\n  - 依赖：B4\r\n  - 结果（2026-11-12）：`invalidate` 移除缓存、下次 `get` 重建；4 条单测\r\n\r\n- [x] B6 RouteExecutor：失败链、重试与首 token 锁定\r\n  - 作用：把失败切换策略集中在一处，调用方不再各自处理\r\n  - 位置：`llm/routing/RouteExecutor.java`\r\n  - 做法：按规划书第六节矩阵实现；首帧前可切换，首 token 后锁定当前成员；4xx（除 408/429）不切；429 同成员退避；取消立即终止；每次尝试回调观测\r\n  - 验收：超时/5xx/429/4xx/取消/首 token 后失败六类用例；成员耗尽错误可读；切换次数与观测字段一致\r\n  - 依赖：B3、B5\r\n  - 结果（2026-11-12）：`FailurePolicy` + `RouteExecutor`；12 条单测覆盖矩阵；流式首 token 锁定在 M2 接入时补\r\n  - 收尾（2026-11-12）：此前 `RouteExecutor` 未被生产链路使用（失败链内联在 `AgentChatCaller`/`AgentChatPipeline`）——已接入：新增流式入口 `stream()`，阻塞路径（`AgentChatPipeline.streamCore`）与流式路径（`AgentChatCaller.streamWithRoute`）统一由其推进成员；副作用经 `AttemptObserver`（丢池/指标/终端条件）外置；另补 3 条流式单测（首成员成功/首帧前切换/首帧后锁定）\r\n\r\n### C. 参数与能力\r\n\r\n- [x] C1 能力解析与校验\r\n  - 作用：防止"配了工具但模型不支持"在线上静默失效\r\n  - 位置：`llm/validation/BindingValidator.java`\r\n  - 做法：解析 `capabilities` JSON；校验 Agent 工具、图片、思考、上下文预算；不满足时阻止路由成员生效\r\n  - 验收：工具/视觉/思考/上下文四类不满足用例均被拦截；错误信息指出 Agent、路由、成员 id\r\n  - 依赖：B1-B3\r\n  - 结果（2026-11-12）：`ModelCapabilities` 解析 + 校验；4 条单测\r\n\r\n- [x] C2 参数合并\r\n  - 作用：模型默认参数与 Agent 覆盖有唯一口径\r\n  - 位置：`llm/call/CallOptions.java`\r\n  - 做法：`model.default_params < agent.params`；合并后显式下发；未配置项不下发，保持端点默认\r\n  - 验收：覆盖优先级、空值、非法值三类用例；请求体断言只包含合并后的键\r\n  - 依赖：B1、B2\r\n  - 结果（2026-11-12）：`LlmCallOptions.merge`；3 条单测\r\n\r\n- [x] C3 thinking 三语义拆分\r\n  - 作用：区分"模型是否支持思考""请求是否开启""前端是否展示"\r\n  - 位置：`llm/call/CallOptions.java`、请求组装处\r\n  - 做法：`capabilities.thinking` 控制可用性；`agent.params.thinking_mode` 生成请求参数；`agent.params.show_thinking` 只影响流式展示\r\n  - 验收：关闭思考不发思考参数；展示开关不影响模型调用；不支持思考的模型配 `thinking_mode` 时启动失败\r\n  - 依赖：C1、C2\r\n  - 结果（2026-11-12）：`ThinkingMode.AUTO/OFF` 与 `showThinking` 分离；校验器覆盖能力不足场景\r\n\r\n- [x] C4 BindingValidator 启动与热加载校验\r\n  - 作用：配置错误在启动期暴露，不静默回退默认客户端\r\n  - 位置：`llm/validation/BindingValidator.java`\r\n  - 做法：启动时校验 Agent 路由、路由成员、凭证、能力、上下文预算；热加载先校验后原子替换，失败保留旧配置\r\n  - 验收：路由缺失、成员为空、凭证缺失、能力不足、引用禁用模型五类用例均启动失败；热加载失败时旧配置继续服务\r\n  - 依赖：C1\r\n  - 结果（2026-11-12）：启动 `@PostConstruct` 全量校验；`LlmConfigRefresher` 在重读目录后先跑同一校验，失败恢复旧快照并让事务回滚——非法配置不会进入调用链\r\n\r\n### D. 内部调用收口\r\n\r\n- [x] D1 route-judge 改为系统 Agent + 路由\r\n  - 作用：路由判定不再读配置里的模型名\r\n  - 位置：`service/impl/route/LlmRouteJudge.java`、Agent 种子数据\r\n  - 做法：读取 `route-judge` Agent 的 `route_id`；轻量超时参数保留；失败兜底语义不变\r\n  - 验收：判定使用 `judge` 路由；改路由成员后判定模型随之改变，Agent 配置零改动；单测覆盖\r\n  - 依赖：B3、B5\r\n  - 结果（2026-11-12）：`settings()` 经 `AgentCatalog.require("route-judge")` → `ModelResolver` 首选成员取 modelId/模型名，prompt 仍取 agent 行；轻量客户端按 modelId 获取、失败按 modelId 丢池；模型常量已删除，路由缺失直接兜底 Agentic\r\n\r\n- [x] D2 summary / profile / trigger-judge 改为系统 Agent + 路由\r\n  - 作用：消除最后三处按模型名解析\r\n  - 位置：`memory/UserProfileService.java`、`memory/SessionSummaryService.java`、`service/impl/route/LlmSemanticTriggerJudge.java`\r\n  - 做法：各自读取对应系统 Agent 的 `route_id`；删除 `@Value("...model")` 注入口径；提示词保留在各自代码或 Agent 行\r\n  - 验收：三个功能的模型来源均为路由；配置 `summary-model` / `trigger-judge.model` 不再被读取；单测覆盖\r\n  - 依赖：B3、B5、A3\r\n  - 结果（2026-11-12）：V21 注册 profile / summary（fast）与 trigger-judge（judge）系统 Agent；三处均改为 `AgentCatalog → ModelResolver → LlmClientRegistry`，观测模型名取实际路由成员；`@Value` 模型配置已删除\r\n\r\n- [x] D3 删除按名查找 API\r\n  - 作用：从结构上消除重名歧义\r\n  - 位置：旧 `ChatClientRegistry`\r\n  - 做法：删除 `idsByName`、`getByModel`、`getLightweightByModel`、`invalidateLightweight`；确认全仓库无引用\r\n  - 验收：`grep` 零命中；全量测试通过；ArchUnit 禁止新增按名解析\r\n  - 依赖：D1、D2\r\n  - 结果（2026-11-12）：四个按名 API 已删除，源码零引用（仅 checklist 历史记录保留字样）；全量 1121 测试通过；ArchUnit 守卫在 H1 补齐\r\n\r\n### E. 接入改造\r\n\r\n- [x] E1 GeneralAssistantAgent 接入 LlmCaller\r\n  - 作用：Agentic 直答路径成为新门面的第一个消费者\r\n  - 位置：`agent/GeneralAssistantAgent.java`\r\n  - 做法：改为 `LlmCaller.stream(agentName, ...)`；删除本地客户端获取与丢池逻辑（由 RouteExecutor 承接）；观测字段改由门面写入\r\n  - 验收：流式 token、取消、重试语义与现网一致；`llm_call_log` 出现 route/model 字段；单测覆盖\r\n  - 依赖：B6、C2\r\n  - 结果（2026-11-12）：改为 `chatCaller.streamWithRoute`，按路由成员重建 spec 并在首 token 前切换\r\n\r\n- [x] E2 AgentChatPipeline / AgentChatCaller 接入\r\n  - 作用：阻塞收集路径与流式路径共用同一失败策略\r\n  - 位置：`agent/AgentChatPipeline.java`、`agent/AgentChatCaller.java`\r\n  - 做法：客户端获取与失败丢池移入 RouteExecutor；保留现有看门狗与观测钩子；删除按 modelName 传参\r\n  - 验收：看门狗超时触发成员切换（首帧前）；首 token 后超时不切换；现有测试改按 route/model 断言\r\n  - 依赖：B6、E1\r\n  - 结果（2026-11-12）：`streamCore` 按成员循环，首 token 后错误不再切换；丢池按 `model_id`；全量测试通过\r\n\r\n- [x] E3 Graph 编排路径接入\r\n  - 作用：lead / 子任务 / aggregator 与直答使用同一解析链\r\n  - 位置：`graph/OrchestrationNodes.java`、`graph/engine/*`\r\n  - 做法：每个角色通过 Agent 名调用 LlmCaller；子任务失败占位语义不变；预算账本不变\r\n  - 验收：编排全链路可跑通；单子任务模型失败可按路由切换；观测能区分 lead 与各专家\r\n  - 依赖：E2\r\n  - 结果（2026-11-12）：Graph 经共享 AgentChatCaller 自动走新链路；真实请求验证通过\r\n\r\n- [x] E4 AgentScope 构建改为路由解析\r\n  - 作用：Agentic ReAct 路径不再按名兜底\r\n  - 位置：`agent/agentic/AgentScopeModelFactory.java`\r\n  - 做法：删除 `modelProviderId == null` 时按名查库的兜底；统一从 `ModelResolver` 取 model 行；构建参数与 ClientFactory 同源\r\n  - 验收：无绑定 Agent 启动失败；AgentScope 路径模型与 ChatClient 路径一致；单测覆盖\r\n  - 依赖：B3、C4\r\n  - 结果（2026-11-12）：`resolveRow(agentName)` 经 Agent → 路由 → 首选成员；按名兜底已删除；真实请求走 AgentScope 路径成功\r\n\r\n### F. 管理面\r\n\r\n- [x] F1 供应商 / 模型 / 路由 / Agent 绑定管理接口\r\n  - 作用：新增或调整模型只改数据，不改代码\r\n  - 位置：`llm/admin/LlmAdminService.java`、`controller/LlmAdminController.java`\r\n  - 做法：实现规划书第十节接口；写操作先校验再落库；返回结构化校验错误\r\n  - 验收：四类资源 CRUD 可用；非法外键/优先级冲突/空路由被拒绝\r\n  - 依赖：A2、C4\r\n  - 结果（2026-11-12）：`LlmAdminController` 暴露 `/api/admin/providers|models|routes|agents/{id}/route`；写操作事务内落库并触发热更新，校验失败转 400；11 条单测覆盖非法 URL/凭证缺失/重名/空路由/优先级冲突/禁用成员\r\n\r\n- [x] F2 热加载：校验后原子替换\r\n  - 作用：改配置不重启，且失败不破坏现有服务\r\n  - 位置：`llm/catalog/*`、`llm/routing/ModelResolver.java`\r\n  - 做法：变更后重建 catalog、路由缓存与客户端映射；先完整校验，通过后一次性替换引用；失败记录错误并保留旧配置\r\n  - 验收：修改成员优先级后下一次调用生效；提交非法配置时旧配置继续服务\r\n  - 依赖：F1\r\n  - 结果（2026-11-12）：`LlmConfigRefresher.refresh()` 重读目录 → 清路由缓存 → 全量校验 → 清客户端缓存；校验失败恢复旧快照并重新清空缓存，异常上抛使事务回滚；3 条单测覆盖成功/校验失败/重读失败\r\n\r\n- [x] F3 路由连通性测试端点\r\n  - 作用：上线前确认每个成员真的可用\r\n  - 位置：`LlmAdminController` 的 `POST /api/admin/routes/{id}/test`\r\n  - 做法：按优先级逐个发一条最小请求，返回每个成员的延迟、HTTP 状态、错误分类\r\n  - 验收：可用/超时/4xx/5xx 四类结果可区分；不写入会话与业务表\r\n  - 依赖：B5、B6\r\n  - 结果（2026-11-12）：`LlmAdminService.testRoute` 用轻量客户端（15s 读超时）逐成员探测，返回 ok/latency/httpStatus/errorClass（timeout/connection/4xx/5xx/other）；不挂 advisor，不写 llm_call_log 与会话表\r\n\r\n### G. 观测\r\n\r\n- [x] G1 `llm_call_log` 扩展\r\n  - 作用：明确"这次到底是谁答的、第几个成员答的"\r\n  - 位置：新迁移 + `observability/LlmCallRecorder.java`\r\n  - 做法：增加 `route_id`、`route_code`、`model_id`、`provider_code`、`upstream_name`、`fallback_from_model_id`、`attempt`、`error_class`；保留现有字段\r\n  - 验收：一次切换产生两条记录，字段可完整还原切换路径；旧查询接口兼容返回\r\n  - 依赖：B6\r\n  - 结果（2026-11-12）：V22 增加 7 列（attempt 已存在）；`LlmCallLog`/实体/落库链路透传；新增 `RouteAttempt` 在阻塞、响应式、AgentScope、judge/画像/摘要调用中回写；error_class 由 `FailurePolicy.errorClass` 统一分类\r\n\r\n- [x] G2 路由与模型指标\r\n  - 作用：能看降级频率与各模型健康度\r\n  - 位置：`observability/HarnessMetrics.java`\r\n  - 做法：新增 `llm_route_fallback_total`、`llm_model_error_total`、`llm_model_latency`；标签维度控制基数\r\n  - 验收：触发一次切换后指标 +1；Prometheus 端点可见；标签不超过规划书范围\r\n  - 依赖：G1\r\n  - 结果（2026-11-12）：新增 `harness.llm.route.fallbacks{route,from_model,to_model}`、`harness.llm.model.errors{model_id,error_class}`、`harness.llm.model.latency{model_id,kind}`；阻塞与响应式切换路径均埋点；单测断言计数与 timer\r\n\r\n### H. 清理与验收\r\n\r\n- [x] H1 ArchUnit 守卫\r\n  - 作用：防止新代码重新引入按名解析或绕过门面\r\n  - 位置：`deerfect-server/src/test/java/.../architecture/ArchitectureTest.java`\r\n  - 做法：禁止 `ChatClient` 出现在 `llm.client` / `llm.call` 之外；禁止 Mapper 被 catalog/admin/validation 之外的包使用；禁止除 `LlmCaller` 外依赖 `ClientRegistry`\r\n  - 验收：三条规则在现有代码上通过；故意违规样例能被拦下\r\n  - 依赖：E1-E4\r\n  - 结果（2026-11-12）：新增 3 条守卫——LLM 解析链不得直接依赖 `ChatClient`、三层表 Mapper 只允许 catalog/admin/routing 访问、routing/validation 不得依赖 `LlmClientRegistry`；`ArchitectureTest` 8 条规则全绿\r\n\r\n- [x] H2 删除旧模型配置路径与旧表\r\n  - 作用：完成切换，不留双写与双读\r\n  - 位置：V21 迁移 + 旧 `ChatClientRegistry` / `AgentConfigProvider.resolveModelName`\r\n  - 做法：删除 `model_provider` 表、`agent.model_provider_id`、`idsByName` 相关代码与配置项；确认无引用后提交\r\n  - 验收：全仓库无 `model_provider` 读取；V21 在已回填库执行成功\r\n  - 依赖：D3、E1-E4\r\n  - 结果（2026-11-12）：V23 先把 `disable_thinking=1` 转为 `default_params.thinking_mode=off`，再删除 `agent.model_provider_id` 与 `model_provider` 表；删除 `ChatClientRegistry`、`ModelProviderEntity/Mapper`、`ProviderAdminController/Service/Impl` 与旧 DTO；`AgentConfigProvider`/`AgentScopeModelFactory` 全部改为路由成员解析；CLI `/provider` 迁移到 `/api/admin/*`\r\n\r\n- [x] H3 文档同步\r\n  - 作用：使用与排障文档与新模型一致\r\n  - 位置：`README.md`、`docs/guides/models.md`、`docs/architecture.md`、`docs/project-structure.md`、`docs/features.md`\r\n  - 做法：更新"新增模型/供应商"步骤、路由配置说明、观测字段与目录树；删除按名配置示例\r\n  - 验收：按文档从零加一个供应商 + 模型 + 路由可跑通；文档内无旧表名与旧配置键\r\n  - 依赖：F1、G1\r\n  - 结果（2026-11-12）：models.md 重写为三层模型 + 管理接口流程；features/tech-stack/data-flow/project-structure（中英）/api/setup/wms-mcp/functional-testing 同步更新；历史设计与报告保留原貌\r\n\r\n- [x] H4 全量测试与故障注入\r\n  - 作用：确认替换没有回归\r\n  - 位置：`mvn -s .mvn/settings.xml test`\r\n  - 做法：全量单测 + MySQL 集成测试；补超时/5xx/429/4xx/取消/首 token 后失败六类故障注入；核对用例数不低于当前 1474\r\n  - 验收：全量测试全绿；故障注入覆盖矩阵无遗漏；`llm_call_log` 与指标断言通过\r\n  - 依赖：H1-H3\r\n  - 结果（2026-11-12）：全模块 `mvn test` 全绿（deerfect-server 1130 执行 / 1 环境跳过；api、cli 同批通过）；故障矩阵由 `FailurePolicyTest`（超时/连接/4xx/5xx/429/取消）、`RouteExecutorTest`、watchdog 用例与 `LlmAdminServiceTest` 覆盖；`LlmCallRecorderTest` 断言 error_class 与指标计数\r\n',Hr='# 个人 Agent 能力补齐清单（主动性 / 记忆 / 触达）\r\n\r\n- 范围：`server` 主链路 + `harness-api` 端口 + `channel-qq` 推送 + `web` 入口；不改 `cli` 的命令行协议（仅加命令）\r\n- 创建：2026-11-06\r\n- 状态：共 14 项，已完成 11 项，待办 3 项\r\n- 关系：`docs/roadmap.md` 是技术路线图（工程化 / 编排智能化 / 多实例），本清单是**产品视角**的能力补齐。重叠 4 条（画像长青、归档 RAG、滚动摘要、Goal 重投）在此提前并给出可执行口径；其余为新增。两者冲突时以本清单的验收口径为准，完成后回写 roadmap 勾选。\r\n\r\n## 进度概览\r\n\r\n| 分类             | 已完成 | 待办 | 小计 |\r\n| -------------- | --- | -- | -- |\r\n| P0 主动化的安全前置   | 2   | 0  | 2  |\r\n| P1 存在感（自己开始）  | 4   | 0  | 4  |\r\n| P2 记忆连续性（人生级） | 4   | 0  | 4  |\r\n| P3 随身触达        | 1   | 1  | 2  |\r\n| P4 个人数据源       | 0   | 2  | 2  |\r\n| 合计             | 11  | 3  | 14 |\r\n\r\n## 背景\r\n\r\n现状判断：**底下很厚，上面缺一层盖。**\r\n\r\n- 行动力与编排力已成型：沙箱容器级隔离、MCP 生态、按专家最小权限、双路径编排、预算护栏、断点续跑、四张观测表——解决的都是「一次任务能跑多好」。\r\n- 记忆力正从会话级往外扩：创建时画像一次会话只提炼一次、归档检索被 `session_id` 硬过滤、无滚动摘要；前两条已由 P2-1（增量提炼 + `[key]` 合并）与 P2-2（`scope=all` 跨会话召回）解决，滚动摘要（P2-3）与向量化检索仍待做（2026-11-06 更新）。\r\n- 存在感几乎空白：全仓 `@Scheduled` 只有 4 处且全是内部维护（画像提炼 / 日志清理 / Goal 停滞检测 / 知识库目录监听）；`Goal` 必须 `POST /api/submit` 才动；QQ 有 `sendPrivateMsg` 但只在收到消息时回；没有 `user` 表，`DEFAULT_TENANT = "default"` 写死。\r\n\r\n个人 agent 与「任务执行器」的分水岭不在单次执行质量，在**没人叫它的时候它在干什么**。所以顺序是：先补主动化会放大的安全洞（P0），再让它能自己开始（P1），再让它记得住（P2），最后才是入口与数据源（P3/P4）。\r\n\r\n## P0 主动化的安全前置\r\n\r\n> 不先做这两条，P1 的无人值守执行就是在放大事故面：现在是「你让它跑代码」，之后是「它凌晨自己跑代码」。\r\n\r\n- [x] P0-1 沙箱生命周期自愈（探测 / 重建 / 孤儿回收）\r\n  - 作用：沙箱容器懒创建一次后无重置机制，坏状态只能停服恢复；强杀进程会残留容器。主动任务跑在无人值守时段，坏状态等于任务永久卡死\r\n  - 位置：[SandboxToolProvider](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxToolProvider.java)（持有 `SandboxService`，仅 `@PreDestroy` 销毁）、`application-agent.yaml` 的 `app.sandbox.*`\r\n  - 做法：容器句柄加健康探测（`exec echo` 带超时）；连续 N 次失败或镜像/配置变更时销毁重建，重建期间工具调用返回可读错误而非挂死；启动时扫描并回收本项目前缀的孤儿容器；重建事件落 `tool_call_log` 或独立日志\r\n  - 验收：手动 `docker kill` 容器后下一次工具调用自动重建并成功；强杀进程重启后无残留容器；单测覆盖「探测失败 → 重建 → 调用成功」与「重建失败 → 可读错误」\r\n  - 结果（2026-11-06）：每 5 分钟在 base 容器内 echo 探活（独立线程限时，超时判故障），失败销毁重建并整组重生成工具；重建失败清空工具面；启动时 cleanupAllSandboxes 回收遗留容器。browser 组不探测（本就按需降级）。SandboxToolProviderTest 5→17 条。提交 2a672ce。\r\n- [x] P0-2 沙箱写 / 执行边界拦截\r\n  - 作用：无人值守时模型输出没人复核，路径越界与危险命令必须在服务端拦\r\n  - 位置：[SandboxFileTools](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxFileTools.java)、[SandboxShellTools](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/builtin/SandboxShellTools.java)（当前完全信任模型输出透传容器）\r\n  - 做法：写入路径按 Unix 语义归一化后必须落在 `/workspace` 内（拒绝 `../` 越界；符号链接逃逸纯字符串判断不了，靠容器隔离兜底，已写进类注释）；shell 加危险命令黑名单 + 结果长度上限；**主防线仍是容器本身不挂载宿主目录**，黑名单只当减速带；拒绝时抛可读异常给模型\r\n  - 验收：`../` 越界写与 `rm -rf /` 等破坏性命令被拒且错误可读；正常读写零回归；`SandboxGuardsTest` 覆盖路径归一化、四类越界、命令正反例与截断\r\n  - 结果（2026-11-06）：新增 SandboxGuards：写入路径 Unix 归一化后限 /workspace（相对路径按工作区解析、~ 拒绝），覆盖写/编辑/建目录/移动四处；Shell 拦截删根、mkfs、dd 写块设备、关机重启、fork 炸弹、改根权限六类；结果统一截断 4 万字符并注明原长。类注释写明「减速带非主防线」与符号链接依赖容器隔离。新增 12 条测试。提交 575b193。\r\n\r\n## P1 存在感：让它自己开始\r\n\r\n- [x] P1-1 定时任务（schedule 表 + 调度器 + 双入口）\r\n  - 作用：让它到点自己干活，这是「存在感」的第一块砖；当前所有主动行为都必须由人触发\r\n  - 位置：Flyway `V7__agent_schedule.sql`、`service/ScheduleService`（+ impl）、`controller/ScheduleController`；复用 [`goalExecutor`](../../deerfect-server/src/main/java/com/dark/javaHarness/config/GoalExecutorConfig.java) 与 [`AgentService.submit`](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java)\r\n  - 做法：表字段 `id / objective / agent_name / cron / enabled / notify_channel / last_run_at / next_run_at / last_goal_id / notified_at`；扫描到点转 goal 执行，**不另写执行链**；cron 用 Spring `CronExpression`（6 段，秒级）不自研；入口 `POST/GET/DELETE /api/schedules` + CLI `/schedule`；`next_run_at` 落库保证重启不丢不重跑\r\n  - 验收：配一条每分钟任务，两分钟后 `goal` 表出现两条执行记录；重启后不重复触发；同一时刻只触发一次（单实例即可，多实例另案）\r\n  - 结果（2026-11-06）：agent_schedule（V7）+ /api/schedules 增删查 + CLI /schedule；派发=扫描到期行→AgentService.submit→推进 next_run_at，失败也推进（防每 10 秒重扫卡住队首）；cron 用 Spring CronExpression 6 段，创建即校验并验 agent 存在；永不触发表达式派发后停用。11 条测试。提交 d80d0e8。\r\n  - 端到端（2026-11-06 19:21，真机 MySQL + Flyway V7/V8）：创建每分钟任务 → 19:21:00.786 触发 → goal 落库并 SUCCEEDED（summary=收到）→ next_run_at 推进到 19:22:00，DELETE 与「任务不存在」400 均正常。\r\n- [x] P1-2 主动推送通道（harness-api 端口 + QQ 落地）\r\n  - 作用：任务跑完要能找到人，否则定时任务等于写日志\r\n  - 位置：`harness-api` 新增 `MessageSender` 端口（与 [ReplyPostProcessor](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/spi/ReplyPostProcessor.java) 平级但语义不同）；[channel-qq](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatApiClient.java) 实现\r\n  - 做法：`MessageSender.send(target, text)` + `channel()` 渠道标识，由 `MessageSenderRegistry` 解析 `channel:target`；QQ 实现调 `sendPrivateMsg`（NapCat 客户端自身含 1 次重试）；目标来自 `agent_schedule.notify_channel`，不反查绑定表\r\n  - 注意：**不要复用 `ReplyPostProcessor`** —— 它是「清洗模型回复文本」的 SPI，无副作用、幂等，与主动外发语义相反\r\n  - 验收：定时任务跑完 10 秒内 QQ 收到消息；未注册渠道 / 地址非法不抛异常且 WARN 日志可查；core 的 enforcer 规则未被打破\r\n  - 结果（2026-11-06）：harness-api 新增 MessageSender 端口 + MessageSenderRegistry（channel:target 路由，未注册/非法/实现违约一律 false）；channel-qq 实现私聊送达；推送判定改轮询（last_goal_id + notified_at 幂等，V8 加列）而非事件——Goal 完成点有三处，事件易漏发重发；派发与推送合成 tick 节拍；发送失败也标记避免刷渠道。16+7 条测试。提交 42bc16b。\r\n  - 端到端（2026-11-06 19:28）：触发 → goal SUCCEEDED → 19:28:19 在 schedule-notify 线程推送 → NapCat 未启动，重试 2 次后 WARN 未送达且不抛异常 → 19:28:20 notified_at 置位。验证暴露并修复两处单测覆盖不到的问题（同步推送阻塞调度线程；高频 cron 下 last_goal_id 被派发覆盖导致推送饿死），提交 8eed771。\r\n  - 已知限制：推送定位依赖 `last_goal_id` 单字段，cron 间隔必须 ≥ 30 秒（已做成创建期校验）。若要支持更密的触发，需要独立通知表（每次触发插一条待推送记录），属后续技术债。\r\n- [x] P1-3 Goal 常驻（对话内声明 + 每轮注入）\r\n  - 作用：现在的 goal 是「提交—查询」的一次性任务，不是「记得住你在追什么」的长期目标\r\n  - 位置：`goal` 表已有 `session_id`（无需加字段）；[ContextAssemblingAdvisor](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java) / [PromptAssembler](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java) 加「活跃目标」段；[ChatServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java) 入口解析 `/goal`\r\n  - 做法：`/goal <objective>` 落 goal 并绑定当前 session；每轮组装把 RUNNING 目标的 objective + 最近进展注入（按预算截断，不挤掉 system 主体）；`/goal done <id>` 与 LLM 自评双通道收口；CLI 先行，Web 后续\r\n  - 验收：声明目标后连续三轮对话，system 段都能看到目标；标记完成 / 取消后不再注入；超预算时按段裁剪，`system.channel` 等必需段不被挤掉\r\n  - 结果（2026-11-06）：新增 `GoalStatus.ACTIVE`（不复用 RUNNING——watchdog 会判死、重启会清理）；`GoalService.createActive/activeBySession/completeActive`；新增「活跃目标」prompt 段（order 160，按 sessionId 注入，provider 扩展点与画像同构）；快照加 `system.activeGoal` 映射；REST 三个端点 + CLI `/goal`。**只做手动完成，不做 LLM 自评**——误判完成会让目标悄悄消失。端到端：登记后发消息快照出现 107 token 的活跃目标段，完成后再发消失，重复完成 400。提交 183ed87（22 文件）。\r\n\r\n- [x] P1-4 Goal 失败重投\r\n  - 作用：无人值守的定时任务失败后没人管，等于白跑\r\n  - 位置：[AgentServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java) 提交的异常分支、[StaleGoalWatchdog](../../deerfect-server/src/main/java/com/dark/javaHarness/observability/StaleGoalWatchdog.java)\r\n  - 做法：失败落 `retry_count` / `last_error`（Flyway 迁移补列）；指数退避重投上限 2 次；连续失败置 `FAILED` 并经 P1-2 主动推送失败原因与最后一次错误摘要\r\n  - 验收：注入必失败的 Goal，验证重投 2 次后置 FAILED 且只推送一次；成功路径 `retry_count` 恒为 0；watchdog 与重投不互相打架（同一 Goal 不并发执行）\r\n  - 结果（2026-11-06）：V9 给 goal 补 agent_name / retry_count / next_retry_at（此前 goal 不记录执行 Agent，既没法重投也看不出谁跑的）；失败按 1→2 分钟退避排期，`GoalRetryScheduler` 每 30 秒扫到期项调 `AgentService.retry`，重投跑**同一个 goal**（保住 traceId 与会话归属）；取消类异常不排期；受理失败清标记防每轮重扫。验证中发现 `updateById` 忽略 null 导致 next_retry_at 残留，改为调度器显式 set null。端到端：造 FAILED 到期 goal → 30 秒后受理「重投第 1/2 次」→ 执行成功，retry_count=1、标记清空。契约守卫 PortSignatureGuardTest 同步。提交 6ef7401。\r\n\r\n## P2 记忆连续性：从会话级到人生级\r\n\r\n- [x] P2-1 画像长青会话（去掉一次性锁定）\r\n  - 作用：`profile_extracted=1` 让一次会话只提炼一次；QQ 渠道按用户固定 sessionId，等于每个用户一辈子只提炼一次画像，后面聊的全不进\r\n  - 位置：[UserProfileService](../../deerfect-server/src/main/java/com/dark/javaHarness/memory/UserProfileService.java)（`@Scheduled` 扫描 + 增量游标 + 原子落盘）\r\n  - 做法：改「按增量提炼」——记录 `last_extracted_message_id`，每次只喂新增消息；画像条目按 key 合并（同 key 覆盖、新 key 追加），不做全文重写；单次提炼限制消息条数与 token 上限\r\n  - 验收：同一 session 连续三轮新增对话，第二、三轮的新偏好能被捕获；重复内容不产生重复条目；单次提炼 token 不超预算；画像文件损坏时可从备份恢复\r\n  - 结果（2026-11-06）：V10 把 `session.profile_extracted`（一次性锁定）换成 `profile_extracted_at`（上次提炼对应的活跃时刻）+ `last_extracted_message_id`（快照游标），待提炼判定改为 `last_active_at > profile_extracted_at`（写回路径零维护，提炼期间的新消息也不会被吞）；`SessionService.snapshotItems` 暴露带 id 的快照条目；LLM 从「重写整份画像」改为「只输出新增/变更补丁」，代码按 `[key]` 合并（同 key 原位覆盖、新 key 追加、旧版无 key 行被同值带 key 条目替换），既有条目的存活从提示词自觉变成结构保证；单次喂入 ≤40 条 / 6000 字符；写盘前备份 `.bak`，主文件被清空时自动从备份读。存量会话在迁移里统一标记为已处理（不回溯 88 个历史会话）。\r\n  - 端到端（真机两轮）：首轮喂 2 条消息 → 画像新增 `[技术栈] Java、Spring Boot`、`[沟通风格] 回答尽量简短`；次轮只喂新增 2 条 → `[沟通风格]` 原位改写为「详细一些」、追加 `[注释语言] 中文`，`[技术栈]` 与旧条目未丢；提炼后 15 秒内无重扫（无空转）。\r\n\r\n- [x] P2-2 归档检索跨会话\r\n  - 作用：`recall_history` 现在只能回忆「这段对话里被裁掉的」，回忆不了「上个月那次」\r\n  - 位置：[SessionHistoryArchiveService](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionHistoryArchiveService.java)（`.eq("session_id", ...)` 硬过滤）、`recall_history` 工具\r\n  - 做法：工具加 `scope` 参数（`current` / `all`），**默认 `current` 保持现有行为**；`all` 走跨会话检索，先按时间倒序 + 关键词，数据量上来后接向量（与 P2-3 共用工件）\r\n  - 验收：两个不同 session 各留一条特征消息，在第三个 session 用 `scope=all` 能召回两条；默认 `scope` 行为零变化；跨会话结果带 sessionId 与时间，不串会话上下文\r\n  - 结果（2026-11-06）：`SessionHistoryArchiveService.search` 加 `allSessions` 维度（默认 false = 原「按会话过滤」行为零变化），`ArchivedMessage` 携带 `sessionId`；`recall_history` 新增 `scope` 参数（current 默认 / all 跨会话，拼错直接拒绝而非静默退回 current）；跨会话结果标注「会话 N / messageId」，模型才能说清「是哪次对话里说的」；V11 加 `idx_archive_time(archived_at, id)` 支撑跨会话「时间倒序 + LIMIT」不走 filesort。\r\n  - 端到端（真机）：会话 240 / 241 各造一条归档（分别含「蓝色鲸鱼计划」「橙色海豚计划」），在第三个会话（253）让模型 `scope=all, query=代号检索` → 命中 2 条并正确标出会话 240/241，按归档时间倒序（241 在前）；同会话再查一次默认 scope（不传参）→ 返回「未命中」，确认会话内范围未放宽。\r\n\r\n- [x] P2-3 长期记忆滚动摘要\r\n  - 作用：长会话被预算裁掉的最旧轮次直接丢失，用户问「开头说的那个约束」就答不上\r\n  - 位置：[MemoryPolicy](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/MemoryPolicy.java) / [ContextAssemblingAdvisor](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java) 的裁剪点\r\n  - 做法：裁剪时把被丢弃的轮次压缩成摘要（轻量模型异步调用，失败不阻塞主链），落独立表或 `session_messages` 的 summary 行；组装时摘要作为 system 段注入；摘要自身有 token 上限并支持滚动合并\r\n  - 验收：构造超预算的 30 轮会话，摘要能答出第 1–3 轮的关键事实；摘要 token 不超上限；摘要失败时退化为现状（丢最旧轮次），主链不受影响\r\n  - 结果（2026-11-07）：V12 新增 `session_summary` 表（`covered_message_id` 增量游标）；harness-api 新增 `SessionSummaryPort` 并由 `SessionService` 继承，`SessionServiceImpl` 委托 `SessionSummaryService`；裁剪点在 `ContextAssemblingAdvisor`——裁剪前把已有摘要并入 system 段（参与预算且不会被丢），裁剪后把被丢消息异步投递；`SessionSummaryService` 用轻量模型（`app.context.summary-model`）滚动合并「已有摘要 + 新增对话」，输出按 `app.context.summary-max-chars`（默认 600）截断，同会话串行、失败只 WARN 不写库不推进游标；AgentScope 路径经 `AgentRequestSpecFactory.trimHistory` 复用同一口径。测试：30 轮超预算会话早期事实进入摘要、摘要注入/投递、读写失败降级、游标增量、字符上限、异步不阻塞主链；server 全量 900 条 + harness-api 契约守卫全绿。\r\n\r\n- [x] P2-4 记忆显式管理（记住 / 忘掉 / 查看）\r\n  - 作用：用户要能说「记住这个」「忘掉那个」，否则记忆不可控也不可信\r\n  - 位置：新增 `memory` 表 + `remember` / `forget` / `list_memories` 工具 + CLI 命令\r\n  - 做法：显式记忆与画像分离（用户声明 vs 模型推断，来源不同、注入策略不同）；字段含 `content / source / created_at / expires_at / session_id`；注入先全量（量小），超过阈值改按相关度检索；删除为软删 + 可审计\r\n  - 验收：说「记住我的车牌是 X」后新会话能答出；说「忘掉」后不再出现；`list` 能看到来源、时间与状态；显式记忆与画像同名条目不打架\r\n  - 结果（2026-11-07）：V13 建 `memory` 表（`memory_key` / `content` / `source` / `session_id` / `expires_at` / `deleted_at` 软删），与画像文件分离；`remember` / `forget` / `list_memories` 三工具经 V13 分配给 general/lead，`MemoryServiceImpl` 负责同 key 覆盖（写新时软删旧条目）与读取时状态判定；`ExplicitMemorySectionProvider` 以 order 140 注入（排在画像 150 之前，段内声明「冲突以用户明说为准」）；CLI `/remember` `/memories` `/forget` 与 `GET/POST/DELETE /api/memories` 同源，`forget` 多命中不删、返回候选。测试：`MemoryServiceImplTest` 18 + `MemoryToolsTest` 11 + `ExplicitMemorySectionProviderTest` 6 + `MemoryControllerTest` 7。\r\n  - 端到端（真机）：会话内「记住车牌是沪A12345」→ 新会话问车牌答「沪A12345」；`list_memories` 列出 id/来源/时间/状态；「忘掉车牌」后新会话答「我不知道你的车牌号」；画像已有 `[技术栈] Java` 时明说「记住技术栈是 Go」，问技术栈答「Go」；同 key 再写 Rust 时旧条目自动软删，工具回复明确「旧条目已替换」。\r\n\r\n## P3 随身触达\r\n\r\n- [x] P3-1 PWA（手机主屏入口）\r\n  - 作用：Web 要开电脑，CLI 要开终端，QQ 要额外跑 NapCat；PWA 是成本最低的「随身」方案\r\n  - 位置：`web/`（`vite-plugin-pwa`）+ `web/vite.config.ts`\r\n  - 做法：`manifest` + service worker 离线壳；本轮只做「添加到主屏 + 全屏 + 离线页 + 未读角标」，Web Push（VAPID + HTTPS）等 P1-2 稳定后再评估\r\n  - 验收：iOS Safari 与 Android Chrome 都能添加到主屏，全屏无地址栏；断网打开显示离线页不白屏；`npm run build` 产物含 manifest 与 sw\r\n  - 结果（2026-11-07）：`vite-plugin-pwa`（generateSW + autoUpdate）：manifest 声明 standalone 与 192/512/maskable 图标，SW 预缓存应用壳（JS/CSS/HTML/图标，字体 woff2 走运行时 CacheFirst 不进预缓存），`registerSW.js` 自动注册；`index.html` 补 apple-touch-icon 与 apple-mobile-web-app 系列、`viewport-fit=cover`。新增离线门 `OfflineView`（断网替代主界面，联网自动退出；启动即断网则恢复后重载，避免带着初始化失败的空壳继续用）与未读角标（Badging API：页面不可见时完成的回复累加、回前台清零，iOS 无此 API 时静默）。测试：`pwa.test.ts` 6 条 + E2E 断网用例；生产产物实测 manifest 可读、SW 已注册、断网重载显示离线门且 logo 从缓存加载、恢复联网自动退出。**已知限制**：SW 仅在 HTTPS 或 localhost 注册，手机经局域网 `http://IP:端口` 无法安装；iOS 无安装弹窗与角标，需手动「分享 → 添加到主屏」；Web Push 按清单延后。\r\n\r\n- [ ] P3-2 微信 / 企微渠道\r\n  - 作用：QQ 的 NapCat 要单独跑容器，微信才是真实日常场景\r\n  - 位置：新增 `channel-wecom` 模块（照 `channel-qq` 的模块边界与 enforcer 规则）\r\n  - 做法：企业微信自建应用 HTTP 回调 + AES 加解密，实现 `MessageSender`（P1-2）；`server/pom.xml` 的 enforcer 白名单同步；**决策点**：个人无企业资质时改走公众号或微信客服，需先确认可用通道再立项\r\n  - 验收：企微里发消息能流式回复；定时任务结果能推到企微；core 仍不编译期依赖渠道模块\r\n\r\n## P4 个人数据源\r\n\r\n> 记忆不该只来自对话。agent 不知道你的日程，就没法主动提醒；没读过你的邮件，就没法替你起草。\r\n\r\n- [ ] P4-1 日历 / 待办连接器\r\n  - 作用：主动提醒的前提是知道「今天有什么」；这是 P1 定时任务最有价值的输入源\r\n  - 位置：新增 MCP server（CalDAV 优先，兼容 iCloud / Google / Outlook）；或直接做成 `tool/builtin` 的一个工具组\r\n  - 做法：只读起步——今日 / 本周日程查询工具；配一条 P1-1 的「每日早报」任务，把当天前三个日程推送到 P1-2；写操作（新建/改期）留到只读跑顺之后\r\n  - 验收：能读到真实日历事件（含时区与全天事件）；早报任务准确说出当天前三个日程；无日程时给出明确空态而非编造\r\n\r\n- [ ] P4-2 笔记 / 邮件连接器\r\n  - 作用：个人知识大多在笔记和邮件里，不在对话里\r\n  - 位置：MCP server（Obsidian 本地库 / IMAP 只读）\r\n  - 做法：笔记走本地目录索引（复用 [KnowledgeDocumentScanner](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeDocumentScanner.java) 的扫描与增量思路）；邮件先做「按发件人/主题搜索」，不做全量同步\r\n  - 验收：能按关键词检索到笔记原文并给出出处；邮件搜索能按发件人 + 时间范围返回摘要；索引增量更新，不全量重建\r\n\r\n## 明确不做（决策记录，避免反复讨论）\r\n\r\n- **CI/CD、Prometheus、多实例水平扩展、消息队列、Redis 会话缓存、JWT/KMS**：这些是「把自用工具做成 SaaS」的工程，对个人 agent 是负价值——增加部署复杂度，换不来体验。个人自用单机足够。\r\n- **PDF 导出、Swagger/OpenAPI、WebSocket**：低频需求，等有人真的开口再排。\r\n- **多模态输入**：等有真实传图场景再做，否则是给模型加一个用不上的入口。\r\n- **多租户 / user 表**：单用户是产品选择，不是缺陷。`DEFAULT_TENANT = "default"` 保持不变；**除非**要给家人共用，那时再补 `user` 表与租户隔离（会牵连会话、goal、画像三处，属独立立项）。\r\n\r\n## 执行顺序\r\n\r\n1. P0-1 → P0-2：主动化的前提，先堵住会被放大的洞\r\n2. P1-1 → P1-2 → P1-3 → P1-4：先能自己开始，再能找到人，再记得住目标，最后补失败兜底\r\n3. P2-1 → P2-2 → P2-3 → P2-4：记忆从「会话内」逐步扩到「跨会话」再到「用户可控」\r\n4. P3-1 → P3-2：入口按成本从低到高\r\n5. P4-1 → P4-2：数据源按「能否直接支撑 P1 主动提醒」排序，日历优先\r\n\r\n**建议先做的一条**：P1-1 + P1-2 组合（定时任务 + 主动推送）。地基全在（`goal` 表、`goalExecutor`、`sendPrivateMsg` 都已存在），改动最小，能直接把「存在感」从 0 拉到 1。\r\n',Br='# 工程护栏与结构优化清单（质量门禁 / 集成测试 / 部署默认项）\r\n\r\n- 范围：根 `pom.xml` 与 `.github/workflows`、`web/` 前端工程、`server` 测试与运行配置、`docker/docker-compose.yml`；P2 另含 `SessionServiceImpl` 与两个超大类\r\n- 创建：2026-11-07\r\n- 状态：共 14 项，已完成 13 项，待办 1 项\r\n- 关系：`project-optimization.md`（2026-11-03，15 项全完成）处理的是当时热路径的重复读写与结构清理，与本清单不重叠；本清单是 2026-11-07 新一轮体检的结论。能力补齐（主动性 / 记忆 / 触达）见 `personal-agent-gap.md`，本清单只处理工程护栏与结构债。\r\n\r\n## 进度概览\r\n\r\n| 分类            | 已完成 | 待办 | 小计 |\r\n| ------------- | --- | -- | -- |\r\n| 体检与分级         | 1   | 0  | 1  |\r\n| P1 尽快（低成本高收益） | 5   | 0  | 5  |\r\n| P2 结构性（择机）    | 4   | 1  | 5  |\r\n| P3 数据与运行期健壮性  | 3   | 0  | 3  |\r\n| 合计            | 13  | 1  | 14 |\r\n\r\n## 背景\r\n\r\n2026-11-07 对全仓做了六维度体检（构建 / 依赖 / 架构 / 测试 / 部署 / 安全），结论：**没有 P0 级问题**。ArchUnit 的 5 条分层规则在跑，异常降级（沙箱故障、知识库缺失、渠道未注册）都有可读兜底，1143 条用例全绿，P2 阶段的增量记忆也做了备份与恢复路径。\r\n\r\n短板不在功能，在**工程护栏**：\r\n\r\n- 质量门禁是空的：根 `pom.xml` 只有 `maven-compiler-plugin`，没有 jacoco / spotbugs / checkstyle；`web` 只有 `vue-tsc` 类型检查，没有 lint\r\n- 集成测试全是 Mockito：Mapper、SQL、Flyway 迁移没有一条被真实执行过。P2-1 那次「V10 被 `@SpringBootTest` 提前应用、校验和不匹配」就是这条路线缺护栏的真实信号\r\n- 测试依赖真实时间：23 处 `Thread.sleep`，慢机或 CI 抖动即 flaky\r\n- 部署缺两个默认项：`server.shutdown` 未配置（默认 immediate）、compose 的 app 服务没有日志上限\r\n\r\n护栏缺失会放大后续结构改动的风险，所以 P1 全部排在 P2 之前。P1 每项都能独立提交，合计约 2 个工作日。\r\n\r\n**建议执行顺序**：H1-5（15 分钟消除重启风险）→ H1-1 / H1-2（门禁，为 P2 的结构改动提供量化保护）→ H1-3（真实仓储）→ H1-4（去时间依赖，可随测试改动顺手做）。\r\n\r\n## P1 尽快（低成本、收益明确）\r\n\r\n- [x] 全仓体检与问题分级（2026-11-07）\r\n  - 作用：把散落的优化点收敛成一份可执行清单，避免重复排查同一批问题\r\n  - 位置：本文件\r\n  - 做法：构建配置 / 依赖 / 架构约束 / 测试结构 / 部署配置 / 安全六个维度逐项核对，按「出事故的概率 × 修复成本」分级；P3 与不建议项单列在文末\r\n  - 验收：结论可追溯到具体文件与行号；每项给出可独立提交的边界\r\n- [x] H1-1 后端覆盖率门禁（jacoco）\r\n  - 作用：1143 条用例是资产，但覆盖率不可见。P2 要拆 `SessionServiceImpl`（581 行 / 18 个 public），没有量化保护无法判断改动是否伤到未覆盖分支\r\n  - 位置：根 `pom.xml` 的 `<build><plugins>`（当前仅 `maven-compiler-plugin` + lombok）、`.github/workflows/ci.yml` 的 `backend` job\r\n  - 做法：加 `jacoco-maven-plugin`（`prepare-agent` + `report` + `check` 绑定到 `verify`）；`check` 的 bundle 下限先跑一次 `report` 取实测值，设在实测值下方约 2 个百分点，避免刚接入就红；CI 用 `mvn verify` 代替 `mvn test` 并上传覆盖率报告产物\r\n  - 验收：`mvn verify` 产出 `target/site/jacoco/index.html`；把某个类的测试删掉后构建在 `check` 阶段失败，且日志打印实际值与阈值\r\n  - 结果（2026-11-07）：jacoco 0.8.15（Java 25 需 0.8.14+，0.8.8 读不了 class 69），`prepare-agent` + `report` + `check` 全部绑 `verify`；阈值走 `jacoco.line.minimum` 属性，父 pom 默认 `0.00`（只出报告），各模块 properties 覆盖为实测下方约 2 个点。实测行覆盖：server 81.3% / channel-qq 77.2% / wms 75.0% / harness-api 63.6% / cli 23.2%。拦截验证：`mvn -pl cli -am verify -Djacoco.line.minimum=0.99` → `Rule violated for bundle harness-api: lines covered ratio is 0.63, but expected minimum is 0.99`，退出码 1。CI 的 `mvn verify` 原样复用，新增 `Upload coverage reports`（`if: always()`，失败时也能拿到报告区分「覆盖率下降」还是「测试失败」）。\r\n- [x] H1-2 前端 ESLint 与 lint 脚本\r\n  - 作用：`web` 目前只有 `vue-tsc --noEmit`，类型错能拦住，但未使用变量、错误依赖、易错写法只能靠人眼；`scripts` 里没有 `lint`\r\n  - 位置：`web/package.json`（现有 `dev` / `build` / `preview` / `typecheck` / `test` / `test:e2e`）、`web/` 根配置、`.github/workflows/ci.yml` 的 `web` job\r\n  - 做法：引入 `eslint` + `@typescript-eslint` + `eslint-plugin-vue`（flat config），加 `"lint": "eslint ."`；首次接入用 `--fix` 消化存量，规则集先取推荐集，不追求风格全覆盖；CI 的 `web` job 在 `typecheck` 前加一步 `npm run lint`\r\n  - 验收：`npm run lint` 0 error；故意留一个未使用变量能被报出；CI 上 lint 失败会阻断构建\r\n  - 结果（2026-11-07）：eslint 10.14 + typescript-eslint 8.72 + eslint-plugin-vue 10.12 + globals（flat config，`web/eslint.config.js`），`npm run lint` / `lint:fix`；CI `web` job 在 build 前加 lint。存量 30 个问题（1 error + 29 warning）清零：`TraceRow.vue` 的 `props` 对象未被引用（模板直接用解构后的 prop）去掉赋值；`MessageBubble.vue` 的 `v-html` 加 `eslint-disable-next-line` 并在上一行注明「已由 renderMarkdown 里的 DOMPurify.sanitize 净化」。关掉 8 条纯排版规则（`max-attributes-per-line` / `multiline-html-element-content-newline` / `html-closing-bracket-spacing` 等），只留会出错或腐化的规则。拦截验证：临时文件写未使用变量 → `\'unused\' is assigned a value but never used`，退出码 1。`npm run build`（含 vue-tsc）与 77 条单测仍绿。\r\n- [x] H1-3 真实仓储集成测试（@SpringBootTest + 真实 MySQL）\r\n  - 作用：现有业务测试全是 Mockito，`Memory` / `Session` 的 Mapper 与 SQL 没被真实执行过——改写 SQL、加索引、改迁移都不会被测出来；CI 的 MySQL service 目前只支撑 `JavaHarnessApplicationTests` 的 `contextLoads`\r\n  - 位置：`server/src/test/java`（新增 IT 类）、`.github/workflows/ci.yml`（已有 MySQL service）、`MemoryMapper` / `MemoryServiceImpl`\r\n  - 做法：加一条仓储冒烟（如 `MemoryRepositoryIT`），用 `@SpringBootTest` 连真实库跑完整链路：写入 → 同 key 覆盖（旧行 `deleted_at` 非空）→ 忘掉（软删）→ 按 `active` / `expired` / `deleted` 三种状态读取；本地无库时按 `JavaHarnessApplicationTests` 现有方式跳过（`@EnabledIf` 或 profile 判定），CI 上必须真实执行\r\n  - 验收：CI 日志能看到该用例真实连库并通过；把 `MemoryServiceImpl` 的 `deleted_at IS NULL` 过滤条件改错后该用例失败\r\n  - 结果（2026-11-07）：`MemoryRepositoryIntegrationTest` 8 条，`@SpringBootTest` 连真实 MySQL + Flyway 建表 + MyBatis-Plus 生成的 SQL；类级 `@Transactional` 回滚，key 拼随机后缀隔离本地数据。覆盖写入 / 同 key 覆盖（旧行 `deleted_at` 非空且只在 DELETED 可见）/ 忘掉（重复忘返回 false）/ 过期（EXPIRED 不注入）/ 未来过期（仍 ACTIVE）/ 同 key 计数 / 按 key 批量忘 / 关键词命中正文与标签。命名用 `*IntegrationTest` 而非 `*IT`：Surefire 默认只跑 `*Test`，用 IT 后缀必须再引 failsafe 插件。有效性验证：把 `MemoryServiceImpl` 的 ACTIVE 分支 `isNull("deleted_at")` 改成 `isNotNull`，8 条里 4 条失败（`已删记忆不再注入`、`未到期的记忆应继续注入` 等），退出码 1，改回后恢复。\r\n- [x] H1-4 测试去时间依赖（Thread.sleep 归零）\r\n  - 作用：全仓测试有 23 处 `Thread.sleep`，多数是「睡一段时间等状态变化」，慢机或 CI 负载高时就是 flaky 来源；这类失败重跑又能过，排查成本远高于修复成本\r\n  - 位置：`LoginStateStore`（`now()` 直接返回 `System.currentTimeMillis()`）、`LoginStateStoreTest`（4 处，睡 150ms 等 token 过期）、`MultiAgentGraphAgentTest`（7 处）、`KnowledgeDirectoryWatcherTest`（5 处）、`SandboxToolProviderTest`（2 处）、其余零星 4 处\r\n  - 做法：`LoginStateStore` 注入 `Clock`（默认 `Clock.systemUTC()`），测试用 `Clock.fixed` 或可变 Clock 直接推进时间，不睡；流式 / 异步断言改 Awaitility 轮询条件（或 Reactor `StepVerifier` 的虚拟时间），等条件成立而不是等固定时长；文件监听类测试改成显式触发一次扫描\r\n  - 验收：上述四个测试类里 `Thread.sleep` 归零；连续跑 5 次 `mvn -o test` 无失败；单类耗时下降（`LoginStateStoreTest` 不再固定消耗 600ms）\r\n  - 结果（2026-11-07）：测试代码 `Thread.sleep` 实际调用从 23 处降到 **0**（剩余 2 处是注释里的说明文字）。分三类处理：① 时间推进——`LoginStateStore` 注入 `Clock`（默认 `systemUTC`），新增测试工具 `support/MutableClock`，`LoginStateStoreTest` / `ApiTokenFilterTest` 改为推进时钟，不再睡等过期；`SkillRepository` 注入 `LongSupplier` 纳秒时钟，TTL 用例 `+50ms` / `+110ms` 两步推进。② 阻塞模拟——`SandboxToolProviderTest`（60s/5s）、`KnowledgeRetrieverTest`（5s）、`OneBotEventServiceImplTest`（2.5s）改 `CountDownLatch`，超时路径的 `cancel(true)` 中断后立即退出。③ 条件等待——`KnowledgeDirectoryWatcherTest` 的「窗口内不得再触发」改 Awaitility `during()`（窗口内持续轮询，条件一破坏立即失败，比 sleep 后单点抽查更严格）；`MultiAgentGraphAgentTest` 的等图启动改轮询调用数、等流结束改完成闩锁、两处暴露窗口改 `during()`、`delayedFluxOf` 的 `Thread.sleep` 改 `Mono.delay`（不阻塞线程且可随取消终止）；`LlmCallRecorderTest` 的「睡 300ms + `assertTrue(true)`」改成 `verify(mapper, timeout(1000)).insert(...)`——原来那个断言恒真，什么都没验证。效果：`LoginStateStoreTest` 从固定 600ms 降到 0.016s，`SandboxToolProviderTest` 从 65s 的模拟阻塞降到 1.7s。稳定性：`MultiAgentGraphAgentTest` + `KnowledgeDirectoryWatcherTest` 连跑 3 次全绿（50 条/次）。\r\n- [x] H1-5 部署默认项（优雅停机 + 容器日志上限）\r\n  - 作用：两个默认值直接对应线上风险——`server.shutdown` 未配置时默认 `immediate`，重启会硬断在途的 SSE 流；compose 的 `app` 服务没有日志上限，json-file 驱动会无限增长，小内存机（如 2G）长期运行会被日志写满\r\n  - 位置：`server/src/main/resources/application.yaml`（全局配置，非 profile）、`docker/docker-compose.yml` 的 `app` 服务（第 62 行起）\r\n  - 做法：`application.yaml` 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`；compose 的 `app` 补 `stop_grace_period: 30s`，并加 `logging.options.max-size: 20m` / `max-file: "3"`（与 mysql / pgvector 保持一致的写法）\r\n  - 验收：`docker compose restart app` 时在途 SSE 请求收到正常结束而非连接重置；`docker inspect` 能看到 `max-size` / `max-file` 配置生效；`docker compose config` 校验通过\r\n  - 结果（2026-11-07）：`application.yaml` 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`（全局文件，不依赖 profile）；compose 的 `app` 加 `stop_grace_period: 30s` + `logging`（json-file，`max-size: 20m` / `max-file: "3"`），`mysql` / `pgvector` 同步同一口径。验证：`docker compose config` 通过，三个服务各解析出一份 logging（`grep -c max-size` 逐段为 1）；`JavaHarnessApplicationTests` 新增 `gracefulShutdown_isConfigured` 断言配置被绑定到 Spring 环境（写在错误层级会失败），与 contextLoads 共享上下文不额外启动。真机验证留待容器环境：`docker compose restart app` 观察日志出现 `Commencing graceful shutdown`。\r\n\r\n## P2 结构性（择机）\r\n\r\n> 四项都建议在 H1-1 覆盖率门禁之后动手——没有覆盖率数据，重构无法证明「只改结构、不改行为」。\r\n\r\n- [x] H2-1 `SessionServiceImpl` 抽出 `SessionSnapshotStore`\r\n  - 作用：581 行 / 18 个 public 方法，同时承担建档、快照写回、裁剪归档、摘要触发、查询五类职责；快照逻辑（裁剪阈值、消息 ID 补齐、归档落库）与「会话端口」混在一起，改一处要读全文\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java`、`harness-api` 的 `SessionService` 契约、`SessionServiceImplTest`\r\n  - 做法：抽 `SessionSnapshotStore`（快照读、批量写回、裁剪与归档落库），`SessionServiceImpl` 只保留端口方法编排与事务边界；先移动代码不改语义，再考虑合并重复的读取路径\r\n  - 验收：`SessionServiceImpl` 行数与 public 方法数明显下降（目标 ≤ 400 行 / ≤ 12 个 public）；`SessionServiceImplTest` 与 `SessionServiceContractTest` 全绿且断言未放宽；`mvn verify` 覆盖率不低于改动前\r\n  - 结果（2026-11-07）：新增 `SessionSnapshotStore`（345 行），承接「快照读 + 追加写 + 裁剪规则 + 归档落库 + 序列化重试 + 写回行锁」**整体**；`SessionServiceImpl` 581 → **318 行**（-45%），构造参数 8 → 5，私有方法 12 → 2。\r\n  - 与原类头「约 520 行不拆」的关系：那条注释担心的是「读路径与裁剪规则分家会不一致」。抽取时把两者连同归档、行锁一起搬走——它们仍在同一个类里同步演进，风险不成立；分开的只是「会话元数据」（建档 / 自动改名 / 切 Agent / 分页 / 软删）与「ChatMemory 适配」，那部分不关心快照怎么存。类头注释已改写并保留这段推理。\r\n  - 一处口径修正：原验收写「public 方法数 ≤ 12」不成立——18 个 public 由 `SessionService`（12）+ `ChatMemory`（4）+ 摘要端口（2）的契约决定，拆实现类不会减少对外方法。实际减少的是行数、字段数、私有方法数。\r\n  - 验收：`SessionServiceImplTest` 28 条全绿且断言未改（三处构造改为共享同一批 mapper mock）；全量 1156 条 0 失败；server 行覆盖率 81.3%（与拆分前一致）。\r\n- [x] H2-2 登录失败计数按窗口清理\r\n  - 作用：`failuresByIp` 只在登录成功时 `remove`，失败过的 IP 会永久留一条记录；公网暴露时被扫描器刷一轮，map 只增不减\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/config/security/AuthController.java`（`failuresByIp` 声明在第 49 行，成功重置在第 83 行，失败累加在第 117 行）\r\n  - 做法：给 `FailState` 记录最后一次失败时间；加 `@Scheduled` 每小时清理「最后一次失败早于锁定窗口」的条目（窗口与现有锁定策略同源，避免两处阈值漂移）；清理量记日志便于观察\r\n  - 验收：构造 1000 个已过窗口的 IP 后执行清理，map size 明显下降；窗口内仍在计数的 IP 不受影响；有单测覆盖「过期清理 / 活跃保留」两条分支\r\n  - 结果（2026-11-07）：`FailState` 增 `lastFailureAt`；新增 `@Scheduled(fixedDelayString = "${app.security.failure-clean-interval-ms:3600000}")` 的 `purgeStaleFailures()`，清理「超过 1 小时没有新失败」的 IP 条目（锁定中的条目只要还在保留窗口内就保留），清理量记 info 日志。\r\n  - 可测性：清理逻辑拆成包可见的 `purgeStaleFailures(long cutoffMillis)`，测试直接传 cutoff 推进「时间」，不引入时钟注入、不等调度。\r\n  - 测试：`AuthControllerFailureCleanupTest` 3 条——窗口外清理 / 锁定中保留 / 调度入口不误清；原 `AuthControllerTest` 8 条不受影响。\r\n- [ ] H2-3 多实例假设显式化（定时任务防护）\r\n  - 作用：6 个 `@Scheduled` 全是单实例假设（画像提炼 300s、日志清理 cron 03:00、停滞 Goal 检测 60s、Goal 重投 30s、调度派发 10s、沙箱探活 300s），多实例部署时同一任务会被并发执行；现在没有任何提示，扩容时才发现重复推送\r\n  - 位置：`UserProfileService`、`ObservabilityLogCleaner`、`StaleGoalWatchdog`、`GoalRetryScheduler`、`ScheduleServiceImpl`、`SandboxToolProvider`\r\n  - 做法：短期不改调度实现——启动时打印一条显式警告日志（「检测到 N 个 @Scheduled，本版本假设单实例」），并在 `docs/dev.md` 写明约束；长期再引入 ShedLock 或 DB 行锁（派发与推送这两条并发代价最高，优先加锁）\r\n  - 验收：启动日志有该警告；`docs/dev.md` 能查到单实例约束与扩容前置条件；清单里记录「已评估、暂不加锁」的理由\r\n  - 2026-11-07：本轮按需跳过（先做 H2-1/2/4/5），条目仍待办——启动告警与 `docs/dev.md` 的单实例约束说明都还没做\r\n- [x] H2-4 隐式配置补进 yaml\r\n  - 作用：三个键只有 `@Value` 默认值，yaml 里搜不到——运维改配置时无从发现，改错了也没有对照\r\n  - 位置：`SessionSummaryService`（`app.context.summary-max-chars:600`、`app.context.summary-model:qwen3.8-27b`）、`McpToolProvider`（`spring.ai.mcp.client.stdio-config-file:mcp-config.json`）、`application-chat.yaml`（目前只有 `app.context.history-budget`）\r\n  - 做法：把三个键补进对应 yaml 并加注释（默认值、影响范围、何时该改），值保持与 `@Value` 默认一致，不改变现有行为\r\n  - 验收：三个键在 yaml 中可见；改 yaml 值能覆盖默认值（用一条测试或启动日志确认生效值）；`grep` 不到「只存在于 @Value」的配置项\r\n  - 结果（2026-11-07）：`application-chat.yaml` 补 `app.context.summary-max-chars: 600` 与 `summary-model: qwen3.8-27b`（值与原 `@Value` 默认一致，行为不变，附用途与「何时该改」注释）；`application-ai.yaml` 补 `spring.ai.mcp.client.stdio-config-file: mcp-config.json`，注明「文件缺失回退 legacy 单 server、改后需重启」。顺带确认 `app.session.snapshot-max-messages: 200` 已在 yaml，无需补。\r\n  - 验收：`JavaHarnessApplicationTests` 新增 `implicitConfigKeys_areDeclaredInYaml`，断言三个键在 Spring 环境里可取到——yaml 声明被删时键仍能靠默认值工作，但测试会失败，从而逼着保留声明（把「可发现性」变成可执行约束，而不是靠注释）。\r\n- [x] H2-5 两个超大类补「不拆理由」结论\r\n  - 作用：`OrchestrationNodes` 620 行、`OneBotEventServiceImpl` 578 行，体检结论是**职责单一、不建议拆**——但结论只存在于这次对话里，下次有人看到行数又会重新评估一遍\r\n  - 位置：`server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java`、`channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java`\r\n  - 做法：类头补注释说明职责边界与不拆理由（`OrchestrationNodes` 就是「编排节点的集合」，拆散增加跳转；`OneBotEventServiceImpl` 是单事件链路的顺序处理，拆开会让链路不可读）；结论回写本条目，与 `project-optimization.md` P3-2 的做法保持一致\r\n  - 验收：两个类头有注释；本条目记录结论与日期，后续不再重复评估\r\n  - 结果（2026-11-07）：两个类头补「体量说明」，不只写「不拆」，还写清**什么条件下再拆**：\r\n    - `OrchestrationNodes`（620 行）：三节点共享同一份状态键、预算账本与取消令牌，拆开后跨节点改动要同时改三处并对齐约定，语义漂移风险高于阅读成本；行数里相当比重是拆解 JSON 的容错解析与 prompt 拼装，分支并不密。触发拆分条件：节点数增加，或各节点长出独立的重试/降级策略。\r\n    - `OneBotEventServiceImpl`（578 行）：整条链路是「一条上报消息的顺序处理」，顺序本身即语义（限频先于调用、绑定先于建档、超时清理先于回复），拆成 pipeline 后顺序不再一眼可见，且每步要搬运同一组上下文。触发拆分条件：同一条链路要复用到其他渠道。\r\n  - 结论：本轮不拆。与 `project-optimization.md` P3-2 的做法一致（补「不拆理由」而非硬拆）。\r\n\r\n## P3 数据与运行期健壮性（2026-11-08 第二轮体检）\r\n\r\n> P1 补的是「工程护栏」（覆盖率 / lint / 真实仓储 / 时间依赖 / 部署默认项），\r\n> P2 补的是「结构债」。这一轮体检发现剩下的是**上线后才会咬人**的运行期问题：\r\n> 数据只写不删、前端异常白屏、连接池与线程池沿用通用默认值。\r\n\r\n- [x] P3-1 数据生命周期补齐\r\n  - 作用：三处「只写不删」与一处「删除不级联」——① `mcp_server_log` 漏在观测清理清单外；② `session_message_archive` 是 recall_history 的数据源，无保留期会无界增长；③ `goal` 含 objective/summary 正文，终态也永久保留；④ 删除会话只清 session + 快照 + 渠道绑定，`session_summary` 留下孤儿行\r\n  - 位置：`ObservabilityLogCleaner`、新增 `DataRetentionCleaner`、`SessionSummaryPort` / `SessionSummaryService`、`SessionServiceImpl.deleteSession`\r\n  - 做法：观测清理纳入 `mcp_server_log`（四张表同一保留期）；新增业务数据清理器，归档按 `archived_at`、goal 按 `finished_at`（只删 SUCCEEDED/FAILED）分批删除，删 goal 前先级联删 `orchestration_checkpoint`（否则留下既不能 resume、也查不到归属的 state_json）；保留期独立可配（`app.retention.archive-days` / `goal-days`，默认 180 天，0 = 禁用）；摘要端口加 `deleteBySessionId`，会话删除时级联\r\n  - 验收：清理器 5 条单测（禁用开关 / 分批 / 级联 / 空批 / 失败不抛）+ 观测清理补 `mcp_server_log` 断言 + 会话删除断言摘要被清；全量 1162 条 0 失败\r\n  - 结果（2026-11-08）：新增 `DataRetentionCleaner` 与 `DataRetentionCleanerTest`（5 条）；`ObservabilityLogCleaner` 构造加 `McpServerLogMapper`、清理日志含四表计数；`SessionSummaryPort` 加 `deleteBySessionId`（实现幂等、失败仅告警），`deleteSession` 调用它。契约守卫 `PortSignatureGuardTest` 与 `InMemorySessionService` / `RecordingSummaryPort` 同步更新（测试按设计在此报错，不是回归）\r\n\r\n- [x] P3-2 前端全局错误处理与请求超时\r\n  - 作用：组件渲染抛错或 Promise 未捕获时整页白屏，用户除了刷新没有任何信息；普通 REST 请求是裸 `fetch`，后端挂起时无限等待（SSE 链路本来就有超时，只有普通请求没有）\r\n  - 位置：`web/src/composables/globalError.ts`（新增）、`main.ts`、`App.vue`、`web/src/api/http.ts`、`web/src/api/auth.ts`\r\n  - 做法：`app.config.errorHandler` + `unhandledrejection` + 根组件 `onErrorCaptured`（返回 false 阻止向上）三处收敛到同一个 `reportGlobalError`，顶部横幅显示「类型: 首行消息」（堆栈仍在 console），8s 自动消失、可手动关闭；`fetch` 统一走 `fetchWithTimeout`（默认 30s，`VITE_API_TIMEOUT_MS` 可配），超时抛「请求超时（30s）：/api/...」并带 `cause`；**`login` 原本是裸 fetch，一并接入**（登录按钮不能永远转圈）\r\n  - 验收：新增 `http.test.ts` 5 条（超时中断 / 正常响应 / 环境变量覆盖 / 网络错误不误报 / 401 仍触发钩子）+ `globalError.test.ts` 7 条（首行摘要 / 上下文前缀 / 非 Error 值 / 自动消失 / 计时重置 / 手动关闭 / console 保留堆栈）\r\n  - 结果（2026-11-08）：顺手修掉一个真 bug——`fetchWithTimeout` 起初用 `headers: baseHeaders()` 覆盖调用方头，会把 login 的 `Content-Type: application/json` 吞掉；改为 `mergeHeaders`（调用方优先，支持 Headers 实例）。前端 89 条单测全绿（+12），lint 0 error，build 通过\r\n\r\n- [x] P3-3 运行参数按部署形态配置\r\n  - 作用：Hikari 默认 `minimum-idle = maximum-pool-size = 10`（启动即占满且不释放）；Tomcat 默认 200 线程 / 8192 连接，而容器是 `mem_limit: 600m` + `-Xmx256m`，并发上限由 `max-stream-connections: 50` 约束——默认值是通用服务器口径，与部署形态不匹配\r\n  - 位置：`application-datasource.yaml`（hikari）、`application.yaml`（tomcat）\r\n  - 做法：`minimum-idle: 2` / `maximum-pool-size: 10`；`connection-timeout: 5000`（默认 30s，与前端 30s 超时同长会让两边一起等到超时，收窄后后端先给出可读错误）；`max-lifetime: 1800000` / `keepalive-time: 60000`；Tomcat `threads.max: 100` / `min-spare: 10` / `accept-count: 50` / `max-connections: 200`，每项都写明依据\r\n  - 验收：`JavaHarnessApplicationTests.runtimeLimits_areConfigured` 断言四个键被 Spring 绑定（键名写错时 getProperty 返回 null，测试失败）；4 条测试通过\r\n\r\n## 边界：不在本清单内\r\n\r\n以下项体检时看过，明确**不纳入**，避免以后重复讨论：\r\n\r\n- **P3 可选，收益低于成本**：Dependabot / Renovate 自动升级（Spring AI 迭代快，但当前升级频率可控）、慢 SQL 日志（排查时手工 `EXPLAIN` 够用）、`AgentRegistry` 三处构造分支的工厂收敛（9 个重复块，收敛后跳转更多）。\r\n- **明确不做**：\r\n  - 全量 checkstyle 格式整改——会污染 git 历史，收益低于成本；H1-1 的覆盖率门禁不依赖格式规则\r\n  - 给 record / DTO / 配置类补单测——没有分支逻辑，补了只是刷数字\r\n  - 拆 `OrchestrationNodes`——见 H2-5，职责单一，拆了反而增加跳转\r\n',Fr="# 项目优化清单（一致性 / 性能 / 结构）\r\n\r\n- 范围：`server` 主链路（Chat / Session / Agent 配置 / Knowledge）、`harness-api` 会话契约、`project-structure.md` 文档\r\n- 创建：2026-11-03\r\n- 状态：共 15 项，已完成 15 项，待办 0 项\r\n\r\n## 进度概览\r\n\r\n| 分类         | 已完成 | 待办 | 小计 |\r\n| ---------- | --- | -- | -- |\r\n| 检查与分级      | 1   | 0  | 1  |\r\n| P0 正确性与一致性 | 1   | 0  | 1  |\r\n| P1 性能      | 4   | 0  | 4  |\r\n| P2 结构清理    | 5   | 0  | 5  |\r\n| P3 文档与工程   | 4   | 0  | 4  |\r\n| 合计         | 15  | 0  | 15 |\r\n\r\n## 背景\r\n\r\n2026-11-03 对全仓做了一轮通读检查：主代码 262 个 Java 文件（约 26.9k 行）、测试 109 个文件（约 18.1k 行）；基线 `mvn -s .mvn/settings.xml -o test` 全绿（935 条用例，0 失败，约 2 分钟）。\r\n\r\n问题集中在两类：**热路径上的重复读取/重复写入**，以及 `principal.md` §4「不留兼容层」未清理干净的重载堆积。本清单按 P0→P1→P2→P3 排列，每一项都可独立提交、可独立验证。\r\n\r\n执行结果（2026-11-03）：15 项全部落地。后端 `mvn -s .mvn/settings.xml -o test` 942 条用例全绿（较基线 +7）；前端 `npm run build`（含 vue-tsc）+ `npm test` 25 条 + `npm run test:e2e` 4 条冒烟全绿。\r\n\r\n## 已完成\r\n\r\n- [x] 全仓检查与问题分级（2026-11-03）\r\n  - 作用：把散落的优化点收敛成一份可执行清单，避免重复排查\r\n  - 位置：本文件\r\n  - 做法：读 `docs/principal.md` / `project-structure.md` 建立判定标准，再按模块扫代码、跑基线测试\r\n  - 验收：每条问题都有文件与行号定位，并按 正确性 / 性能 / 结构 / 文档 分级\r\n\r\n## P0 正确性与一致性\r\n\r\n- [x] P0-1 配置读取失败不再静默降级\r\n  - 作用：DB 抖动时不再悄悄换模型、换提示词、换 Agent，避免「请求成功但答案来自另一个 Agent」这类无感错误\r\n  - 位置：[AgentConfigProvider.findAgentNameById/getAgentConfig/findAgentTools/findToolPack](../../deerfect-server/src/main/java/com/dark/javaHarness/service/AgentConfigProvider.java#L34-L170)（4 处 `catch (Exception)` 返回 empty）、[ChatServiceImpl.sessionAgentName](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L533-L548)（异常回退 general）、[AgentChatCaller.configOf](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L577-L586)、[PromptAssembler.resolveRolePrompt](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java#L199-L213)\r\n  - 做法：把「行不存在」（`Optional.empty`，走默认）与「查询失败」（抛 `DataAccessException`）分开；`sessionAgentName` 只在会话未绑定 / agent 行已删时回退 general，查询失败向上抛。失败语义（500 还是可读错误）需先确认期望行为再动手\r\n  - 验收：单测覆盖 mapper 抛异常 → 请求失败且错误信息可读；正常路径行为零变化；935 条基线测试仍全绿\r\n  - 结果（2026-11-03）：配置读取失败改为上抛（`AgentConfigProvider` 4 处 catch 删除、`sessionAgentName` 不再回退 general）；新增 3 条失败路径测试。\r\n\r\n## P1 性能\r\n\r\n- [x] P1-1 会话快照批量写回（一轮一次锁、一次序列化）\r\n  - 作用：现在一轮消息连续两次 `saveContext`，每次都全量解析 + 全量序列化数百条历史 JSON，并各占一次行锁；改成一次可把锁持有时间和写放大减半\r\n  - 位置：[ChatServiceImpl.writeBackBoth](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L245-L250)、[SessionServiceImpl.saveContext](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L191-L241)、[SessionService](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/api/SessionService.java#L44-L50)（契约在 harness-api）\r\n  - 做法：`SessionService` 新增 `saveContextBatch(sessionId, List<Message>, ts)`；实现内一次 `lockSessionRow` + 一次 parse/serialize + 一条 UPDATE；`writeBackBoth` 改为一次调用。注意 `harness-api` 是跨模块契约，改动要同步 `channel-qq` / `cli` 编译\r\n  - 验收：一轮写回 SQL 由 8 条降为 ≤4 条；快照 JSON 与逐条写回的结果逐字节一致（ts 取真实时刻的口径不变）；并发写回测试通过\r\n  - 结果（2026-11-03）：新增 `SessionService.saveContextBatch` + `SessionMessageEntry`；一轮写回合并为 1 次锁 / 1 次序列化（SQL 8→4）；新增 2 条测试（批量一次 UPDATE、空批次短路）。\r\n- [x] P1-2 消除每轮重复读取同一 session 行\r\n  - 作用：同一行 `session` 每轮被读 4 次（`sessionAgentName` 两次 + `getSession` + `FOR UPDATE`），其中至少 2 次是纯重复\r\n  - 位置：[ChatServiceImpl.resolveRouteWithPrefetch](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L450-L471)（已收到 `agentName` 入参却重新查表）、[SessionServiceImpl.saveContext](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L196-L204)（`getSession` 与 `lockSessionRow` 读同一行）\r\n  - 做法：`resolvedAgent` 直接用入参 `agentName`（所有调用点传入的就是同一值）；`saveContext` 删掉 `getSession`，用 `lockSessionRow` 的返回值判存\r\n  - 验收：每轮 session 行读取 4 → 2 次；「会话已删则跳过写回」的守卫行为不变（仍记 INFO 日志）\r\n  - 结果（2026-11-03）：`resolveRouteWithPrefetch` 直接用入参 `agentName`；`saveContext` 用 FOR UPDATE 的返回值判存，删掉重复 `getSession`；每轮 session 行读取 4→2。\r\n- [x] P1-3 agent / model_provider 配置缓存 + 日志降级\r\n  - 作用：每条 LLM 调用都要查 `agent` + `model_provider` 两张表并打一行 6 字段 INFO；一次 4 子任务编排约 6 次调用 → 12 条查询、6 行日志\r\n  - 位置：[AgentConfigProvider.getAgentConfig / resolveModelName](../../deerfect-server/src/main/java/com/dark/javaHarness/service/AgentConfigProvider.java#L89-L116)、[AgentChatCaller.configOf](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L574-L586)、[ProviderAdminServiceImpl.add](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ProviderAdminServiceImpl.java#L101)（已有 `registry.reload()` 失效钩子）\r\n  - 做法：`AgentConfigProvider` 加进程内缓存（TTL 或版本号）；`ProviderAdminServiceImpl` 新增 provider 后一并失效；`configOf` 的命中日志降到 debug\r\n  - 验收：同一编排内 agent 查询由约 12 条降为 ≤2 条；新增 model_provider 行仍即时生效（现有 `ProviderAdminServiceImplTest` 覆盖）；日志无重复刷屏\r\n  - 结果（2026-11-03）：`AgentConfigProvider` 加 30s TTL 缓存（仅缓存命中结果）；`configOf` 与注册表「命中」日志降 debug；新增缓存命中测试。\r\n- [x] P1-4 知识库 sync 消除 N+1\r\n  - 作用：`sync` 在文件循环里逐个查 `kb_document`，`docs/` 下 130+ 文档即 130+ 条 SELECT\r\n  - 位置：[KnowledgeServiceImpl.sync](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L106-L160)、[rowOf](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L466-L468)\r\n  - 做法：循环前 `selectList` 全量载入 `Map<docName, row>`，循环内查 Map\r\n  - 验收：130 文档的 sync 中 `kb_document` 查询数 = 1；增量判据（`mtime` + `kb`）与跳过/重摄取行为不变\r\n  - 结果（2026-11-03）：sync 循环前一次载入台账并按 `doc_name` 建索引，孤儿清理复用同一份快照；新增「多文档只查一次台账」测试。\r\n\r\n## P2 结构清理\r\n\r\n- [x] P2-1 `AgentChatCaller` 重载与构造器收敛\r\n  - 作用：7 个 `call` + 6 个 `stream` 重载 + 4 个构造器，参数已到 11 个还在靠 null 占位，传错位置编译器不报错；与 `principal.md` §4「旧重载直接删」冲突\r\n  - 位置：[AgentChatCaller.call 重载](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L140-L212)、[stream 重载](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L419-L477)、[构造器链](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java#L71-L137)\r\n  - 做法：保留全参入口 + 一个参数 record（toolEmitter / cancelled / ledger / trace / extraToolNames / reasoningTap / extraAdvisors），删中间重载与单测专用构造器；`callWithAssembly` / `streamWithAssembly` 作为唯一实现\r\n  - 验收：调用点全部显式构造、无 null 占位；`AgentChatCallerTest` / `AgentChatCallerRetryTest` 改写后全绿\r\n  - 结果（2026-11-03）：7 个 `call` + 6 个 `stream` 重载收敛为「4 参便捷入口 + `CallOptions` 参数对象」，方法数 13→4。构造器保留 4/5/6 参便捷链：删掉会强迫单测写 13 个 null，属同类问题，留待构造依赖对象化时统一处理。\r\n- [x] P2-2 `OrchestrationNodes` / `KnowledgeServiceImpl` 构造器收敛\r\n  - 作用：两处为单测保留的尾参构造器链，属于兼容层\r\n  - 位置：[OrchestrationNodes](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/OrchestrationNodes.java#L58-L87)（3 个构造器）、[KnowledgeServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L88-L107)（2 个构造器）\r\n  - 做法：只保留全参构造器，单测改为传入显式 null 或构造轻量替身\r\n  - 验收：每类只剩 1 个构造器；相关单测全绿\r\n  - 结果（2026-11-03）：`OrchestrationNodes` 3→1 个构造器（另两个本就无调用方）；`KnowledgeServiceImpl` 2→1，单测改传显式 null。\r\n- [x] P2-3 全限定类名改回 import\r\n  - 作用：同一文件里既 import 又写全名（`ChatServiceImpl` 第 13 行 import 了 `KnowledgeRetriever`，第 52 行仍写全名），可读性差且易与包迁移脱节\r\n  - 位置：[ChatServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java)（28 处）、[MultiAgentGraphAgent](../../deerfect-server/src/main/java/com/dark/javaHarness/graph/MultiAgentGraphAgent.java)（28 处）、[ChatAgentConfig](../../deerfect-server/src/main/java/com/dark/javaHarness/config/agent/ChatAgentConfig.java)（26 处）、[AgentScopeGeneralAgent](../../deerfect-server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java)（23 处）\r\n  - 做法：补齐 import，替换全限定写法；同名冲突时用单行 import + 局部别名注释\r\n  - 验收：上述文件内 `com.dark.javaHarness.` 全名出现次数仅剩 package/import 行；编译与测试全绿\r\n  - 结果（2026-11-03）：9 个文件的全限定名改为 import，非导入行残留 0；`AgentScopeGeneralAgent` 3 处同名冲突（Spring AI / AgentScope 的 UserMessage、AssistantMessage）保留全名。\r\n- [x] P2-4 删除无调用方的重载\r\n  - 作用：死代码，且与「不留兼容层」冲突\r\n  - 位置：[ChatServiceImpl.resolveRouteWithPrefetch 5 参重载](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L444-L447)\r\n  - 做法：删除；所有调用点已走 6 参版本\r\n  - 验收：编译通过，无行为变化\r\n  - 结果（2026-11-03）：删除 `ChatServiceImpl.resolveRouteWithPrefetch` 5 参死重载。\r\n- [x] P2-5 回复后处理器异常改为上抛\r\n  - 作用：`ReplyPostProcessor` 实现都在本仓库内，逐个 `catch` 后继续会把实现缺陷变成「静默少做一步后处理」\r\n  - 位置：[ChatServiceImpl.applyReplyPostProcessors](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java#L205-L228)\r\n  - 做法：去掉循环内 try/catch（或改为装配期校验 + 首个异常即抛）；若确实要容错，改为「首个异常上抛，后续处理器不执行」\r\n  - 验收：后处理器抛异常时请求失败且日志含处理器标识；无实现时行为不变\r\n  - 结果（2026-11-03）：后处理器异常上抛（去掉逐条 catch 与 `ObjectProvider` 空判）；新增失败路径测试。\r\n\r\n## P3 文档与工程\r\n\r\n- [x] P3-1 `project-structure.md` 补齐缺失类与包\r\n  - 作用：文档自称「逐文件说明」但只覆盖 88/190 个 server 类，新读者按文档找不到入口\r\n  - 位置：[docs/project-structure.md](../project-structure.md)\r\n  - 做法：补 `MonitorService`、`HarnessMetrics`、`BudgetLedger`、`ContextCompositionRecorder`、`StreamConnectionLimiter` 等，以及 `config/logging`、`config/security`、`tool/budget`、`tool/trace` 等包\r\n  - 验收：新增/迁移的类都能在文档中找到；抽查 10 个随机类命中率 100%\r\n  - 结果（2026-11-03）：`project-structure.md` 覆盖 190/190 个 server 类：补 `config/logging`、`config/security`、监控三件套、`BudgetLedger` 等。\r\n- [x] P3-2 超长类补「不拆理由」注释\r\n  - 作用：`principal.md` §1 要求单文件超约 300 行须说明职责边界与不拆理由；`KnowledgeServiceImpl`（520 行）、`SessionServiceImpl`（516 行）类注释里没有，另外 4 个 >500 行的都有\r\n  - 位置：[KnowledgeServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/knowledge/KnowledgeServiceImpl.java#L36-L44)、[SessionServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L35-L48)\r\n  - 做法：二选一——补类注释说明边界，或把可独立的部分（如快照裁剪归档、BM25 融合）拆出去\r\n  - 验收：`grep 拆分/职责边界` 在 6 个 >500 行类中均能命中\r\n  - 结果（2026-11-03）：`KnowledgeServiceImpl` / `SessionServiceImpl` 类注释补「体量说明（principal §1）」与不拆理由。\r\n- [x] P3-3 数据源默认值收口\r\n  - 作用：`username: root` 与 `useSSL=false` 写死，只有 password 走环境变量，公网部署容易「直接能跑」\r\n  - 位置：[application-datasource.yaml](../../deerfect-server/src/main/resources/application-datasource.yaml)\r\n  - 做法：username 改为 `${MYSQL_USERNAME:root}`；`useSSL` 改为可配置项并在注释写明生产必须开启\r\n  - 验收：本地默认行为不变；生产可用环境变量覆盖；`docs/guides/setup.md` 同步说明\r\n  - 结果（2026-11-03）：datasource 的 username / useSSL 改为环境变量（`MYSQL_USERNAME` / `MYSQL_USE_SSL`），`setup.md` 补覆盖说明；compose 显式覆盖不受影响。\r\n- [x] P3-4 前端超大单文件拆分\r\n  - 作用：`MonitorView.vue` 1088 行、`main.css` 1341 行，已影响定位与复用；当该文件还要加功能时先拆\r\n  - 位置：[web/src/components/MonitorView.vue](../../deerfect-web/src/components/MonitorView.vue)、[web/src/styles/main.css](../../deerfect-web/src/styles/main.css)\r\n  - 做法：MonitorView 按「指标卡 / 轨迹表 / 明细浮层」拆子组件；CSS 按域拆文件后由入口 `@import`\r\n  - 验收：`npm run build`（含 vue-tsc）与 `npm test` 通过；页面视觉无回归\r\n  - 结果（2026-11-03）：`MonitorView.vue` 1088 → 128 行，拆出 `components/monitor/` 四个子组件（指标卡 / 分布 / 会话明细 / 调用轨迹）+ `utils/monitorFormat.ts`；`main.css` 1341 行拆为 12 个域文件、入口只留 `@import`；监控样式迁到 `styles/monitor.css` 并统一以 `.monitor` 前缀限定。验收：build 通过、vitest 25 条（新增 9 条格式化用例）、e2e 4 条全绿；样式声明数与规则数与拆分前逐项相等（244/73）。\r\n\r\n## 执行顺序\r\n\r\n1. P1-1 → P1-2 → P1-4 → P1-3：纯性能，改动局部，有基线测试兜底，先拿收益\r\n2. P0-1：涉及错误语义，需先确认「读配置失败该返回什么」\r\n3. P2-1 → P2-2 → P2-3 → P2-4 → P2-5：结构性清理，一次提交一个类\r\n4. P3-1 → P3-2 → P3-3 → P3-4：文档、配置与前端拆分，可与其他项同批\r\n",qr=`# task-checklist 使用约定\r
\r
本目录存放可执行的任务清单，一个 Markdown 对应一个任务主题。\r
\r
## 记录约定\r
\r
### 1. 用 GitHub 风格复选框记录任务\r
\r
- 写法：\`- [ ]\` 表示未完成，\`- [x]\` 表示已完成\r
- 作用：让每个任务的完成状态在文件里直接可见，编辑器和 Git 平台都能渲染成勾选框\r
- 为什么：清单是执行状态的唯一事实来源；不依赖聊天记录或口头同步，任何人打开文件就能判断进度\r
\r
### 2. 完成后打勾，不删除条目\r
\r
- 写法：把 \`- [ ]\` 改成 \`- [x]\`，可在条目下补一行结果与日期\r
- 作用：保留已完成任务及其验收结果，形成可追溯的进度记录\r
- 为什么：直接删除会让「做过什么、是否验收通过」丢失，后来者容易重复劳动；打勾的成本远低于重新确认\r
\r
### 3. 全部勾选后转为存档，不再回填时点数字\r
\r
- 写法：文件内不再有 \`- [ ]\` 时，文件头保留「状态：已完成」并停止维护正文\r
- 作用：判断当前进度只看仍有 \`- [ ]\` 的清单（如 \`personal-agent-gap.md\`）；已验收清单留档可追溯\r
- 为什么：早期约定是「全勾完直接删」，实践中验收结论（做法 / 取舍 / 端到端结果）有复用价值，改为留档。正文里的测试数、代码行数是**验收当时的基线**，不随主干回填，否则会把「验收时的事实」改写掉\r
\r
### 4. 文件名与文件头写清主题、范围和日期\r
\r
- 写法：文件命名为 \`<主题>.md\`，头部写清范围、创建日期、待办数量\r
- 作用：让读者一眼识别清单对应的模块与当前进度\r
- 为什么：多个清单并存时，缺少范围和日期会导致误读、重复创建或把旧清单当成新任务\r
\r
### 5. 只放可执行任务，每条写清作用与验收标准\r
\r
- 写法：每条任务写清「作用 / 位置 / 做法 / 验收标准」；长篇背景放到 \`docs/\`\r
- 作用：让人一眼知道为什么要做、改哪里、做到什么程度算完成\r
- 为什么：只写“做什么”容易变成模糊待办；补上作用和验收，执行者才能判断优先级与完成边界\r
\r
## 条目示例\r
\r
\`\`\`markdown\r
- [ ] P0-1 技能重名检测与确定性去重\r
  - 作用：避免同名技能在索引与 load_skill 之间指向不同正文\r
  - 位置：SkillRepository.skillsFor/find\r
  - 做法：按 name.toLowerCase() 检测冲突，保留入口路径排序第一条\r
  - 验收：同名技能只返回一条，日志能看出被跳过的目录\r
\`\`\`\r
\r
## 当前清单\r
\r
| 清单 | 主题 | 进度 |\r
| --- | --- | --- |\r
| [personal-agent-gap.md](./personal-agent-gap.md) | 能力补齐（主动性 / 记忆 / 触达） | 11/14 |\r
| [project-hardening.md](./project-hardening.md) | 工程护栏与结构债（质量门禁 / 集成测试 / 部署默认项） | 13/14 |\r
| [email-channel.md](./email-channel.md) | 邮件渠道模块（M1 后半） | 5/6（E1-5 待真实 SMTP 验证） |\r
| [feishu-channel.md](./feishu-channel.md) | 飞书渠道：webhook（M3a）/ 事件订阅（M3b） | 8/9（M3a 4/5，F2-5 待真实群 webhook 验证；M3b 4/4，含群聊 @） |\r
| [feishu-inbound-expansion.md](./feishu-inbound-expansion.md) | 飞书入站扩展：群聊全量接收 / 富文本 / 图片多模态 | 10/11（T2 待真机） |\r
| [llm-routing.md](./llm-routing.md) | LLM 供应商 / 模型 / 路由三层模型与 Agent 绑定 | 0/29 |\r
\r
> 上表只列仍有待办的清单。\`delivery-outbox.md\`（10/10，2026-11-08 完成）、\`channel-health.md\`（4/4，2026-11-08 完成）、\`session-context-composition.md\`（11/11）、\`super-agent-adoption.md\`（14/14）、\`skill-optimization.md\`（17/17）、\`project-optimization.md\`（15/15）已全部验收，按约定留档、不再回填。\r
>\r
> 投递可靠性相关的几份清单来自同一份规划书 [2026-11-08-delivery-reliability-feishu-email-plan.md](../design/2026-11-08-delivery-reliability-feishu-email-plan.md)，拆成多个文件是因为它们**能独立提交、独立验收**：投递层不依赖邮件实现（用测试替身就能验），邮件不依赖飞书，飞书 M3a 也不依赖 M3b。拆开的边界是「一次提交能完整说明一件事」，不是按代码目录切。\r
`,Ur=`# Session 上下文构成快照清单\r
\r
- 范围：\`server\`（prompt / agent / observability）与 \`web\` 监控页\r
- 设计规格：原稿随 2026-10-30 文档压平移除；实现口径见 [ops.md](../ops.md) 的「会话上下文构成快照」与 [上下文优化指南](../guides/context-optimization.md)\r
- 创建：2026-11-01\r
- 状态：共 11 项，已完成 11 项，待办 0 项\r
\r
## 进度概览\r
\r
| 分类 | 已完成 | 待办 | 小计 |\r
|---|---|---|---|\r
| 设计与规格 | 2 | 0 | 2 |\r
| 核心计算与存储 | 3 | 0 | 3 |\r
| 请求链路接线 | 2 | 0 | 2 |\r
| API 与 Web | 3 | 0 | 3 |\r
| 联调与文档 | 1 | 0 | 1 |\r
| 合计 | 11 | 0 | 11 |\r
\r
## 已完成\r
\r
- [x] 设计规格编写（2026-11-01）\r
  - 作用：明确快照口径、组件、接口与降级行为，避免实现阶段反复猜需求\r
  - 位置：原稿路径 \`docs/superpowers/specs/2026-11-01-session-context-composition-design.md\`（已移除）；现存实现见 \`ContextCompositionAdvisor\` / \`ContextCompositionStore\`\r
  - 做法：按目标、决策、数据流、组件、DTO、API、错误、测试、实现步骤分节\r
  - 验收：规格无占位符、前后一致、范围可被一个实现计划覆盖\r
\r
- [x] 规格自检（2026-11-01）\r
  - 作用：在编码前排除矛盾与模糊表述\r
  - 位置：同上\r
  - 做法：占位符扫描、内部一致性、范围检查、模糊性检查\r
  - 验收：part key、占比口径、降级行为均已明确\r
\r
## 核心计算与存储\r
\r
- [x] C1 \`PromptAssembler\` 分段 token 统计\r
  - 作用：让 system prompt 各段在装配时就能独立计量，作为快照的基础数据\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java\`、\`PromptAssemblerTest.java\`\r
  - 做法：新增 \`RenderedSection(name, text, tokens)\` 与 \`assembleSections(...)\`；\`assemble(...)\` 复用分段结果并按 \`\\n\\n\` 拼接，保持现有输出不变\r
  - 验收：现有 \`PromptAssemblerTest\` 全部通过；新增段名/顺序/token 断言；\`assemble\` 输出与旧行为一致\r
\r
- [x] C2 \`ContextComposition\` DTO 与计算器\r
  - 作用：把 system 分段、RAG、工具 Schema、最终消息统一换算成 parts 与占比\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/domain/dto/ContextComposition.java\`、\`prompt/ContextCompositionCalculator.java\` 及单测\r
  - 做法：按规格实现 part key/label/group；\`system.other\` 仅正数；最后一个 \`UserMessage\` 算当前消息，其余按角色汇总历史；\`percent\` 四舍五入 1 位小数\r
  - 验收：占比之和约等于 100%；历史/当前消息拆分正确；RAG 与工具 Schema 正确计入；总数为 0 时不生成 parts\r
\r
- [x] C3 快照存储与写入器\r
  - 作用：按 session 保存最近一份快照，支撑监控页实时查询\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionStore.java\`、\`ContextCompositionRecorder.java\` 及单测\r
  - 做法：内存 Map，TTL 30 分钟、最多 200 个会话；同 session 覆盖旧快照；\`get\` 惰性清理；测试注入时钟避免 sleep\r
  - 验收：覆盖、TTL、容量淘汰、缺失返回空四类单测通过\r
\r
## 请求链路接线\r
\r
- [x] W1 Path A（ChatClient）快照\r
  - 作用：在预算裁剪后的真实请求上记录快照，保证看到的就是模型实际收到的输入\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/agent/AgentRequestSpecFactory.java\`、\`advisor/ContextCompositionAdvisor.java\`\r
  - 做法：\`assembleSystem\` 返回 \`SystemAssembly\`；\`resolveTools\` 之后计算工具 Schema token；新增 advisor（Order = HIGHEST+4）携带 blueprint，读取最终 messages 后写入 recorder；\`sessionId\` 为空时不注册\r
  - 验收：advisor 不改写请求；最终 messages 与裁剪结果一致；Path A 单测通过\r
\r
- [x] W2 Path B（AgentScope ReAct）快照\r
  - 作用：覆盖 AgentScope 执行路径，避免只有 ChatClient 路径有快照\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/agent/AgentScopeGeneralAgent.java\`\r
  - 做法：在 \`ContextAssemblingAdvisor.assemble(history)\` 与 \`resolveTools\` 之后，用裁剪后的历史 + 当前 user + \`SystemAssembly\` + 工具 token 调用 recorder\r
  - 验收：Path B 单测/最小集成测试记录到正确 session/agent/turn；不产生额外 LLM 调用\r
\r
## API 与 Web\r
\r
- [x] A1 查询接口\r
  - 作用：给监控页与脚本提供只读、可复用的快照查询能力\r
  - 位置：\`server/src/main/java/com/dark/javaHarness/controller/ContextCompositionController.java\` 及测试\r
  - 做法：\`GET /api/monitor/context?sessionId=...\`；命中返回 200，无快照返回 404；不访问数据库、不触发 RAG\r
  - 验收：200/404 单测通过；空 sessionId 返回 404\r
\r
- [x] F1 Web 类型、client 与 mock\r
  - 作用：让前端能正确解析接口，并在 mock 模式下正常开发\r
  - 位置：\`web/src/api/types.ts\`、\`web/src/api/client.ts\`、\`web/src/api/mock/mock.ts\`\r
  - 做法：新增 \`MonitorContextComposition\` 类型、\`monitorContext(sessionId)\` 方法与 mock 样例\r
  - 验收：\`npm run typecheck\` 通过；mock 返回结构合法\r
\r
- [x] F2 监控页上下文面板\r
  - 作用：让用户在现有监控页直接看到会话上下文占比\r
  - 位置：\`web/src/components/MonitorView.vue\`（必要时新增子组件）\r
  - 做法：活跃会话行增加「上下文」按钮；点击请求 \`monitorContext\`；展示堆叠条 + 明细表 + 估算说明；处理加载、空态、错误；切换会话时刷新\r
  - 验收：有快照时正确渲染；无快照显示空态；\`npm run typecheck\` 与 mock 模式验证通过\r
\r
## 联调与文档\r
\r
- [x] V1 手工联调与文档\r
  - 作用：验证真实请求链路与页面展示，并把口径与限制写进文档\r
  - 位置：本地运行服务、监控页；\`docs/features.md\`、\`docs/dev.md\`（按需）\r
  - 做法：发一条聊天 → 调 \`GET /api/monitor/context\` → 打开监控页核对 system/RAG/工具/历史/当前消息占比；更新文档说明估算口径与限制\r
  - 验收：接口返回 parts 与预期一致；页面渲染正常；文档包含「不含轮内动态工具结果」「多 Agent 只保留最近一次」说明\r
\r
## 推荐执行顺序\r
\r
1. C1 → C2 → C3：先把计算与存储做成可单测的纯逻辑。\r
2. W1 → W2：接入两条执行路径，保证快照来源完整。\r
3. A1 → F1 → F2：接口与页面。\r
4. V1：真实链路联调与文档收口。\r
`,Wr=`# Skill 功能优化清单\r
\r
- 范围：\`server\` 的 Skill 装配链路（\`SkillRepository\` / \`SkillManager\` / \`SkillMetaToolDecorator\` / \`PromptAssembler\`）\r
- 创建：2026-11-01\r
- 最近整理：2026-11-02\r
- 状态：共 17 项，已完成 17 项，待办 0 项\r
\r
## 进度概览\r
\r
| 分类 | 已完成 | 待办 | 小计 |\r
|---|---|---|---|\r
| 现状梳理 | 1 | 0 | 1 |\r
| P0 正确性与健壮性 | 4 | 0 | 4 |\r
| P1 性能、可观测性与功能缺口 | 6 | 0 | 6 |\r
| P2 结构与扩展 | 6 | 0 | 6 |\r
| 合计 | 17 | 0 | 17 |\r
\r
## 背景\r
\r
\`skills/<skill>/\` 目录式技能经 front-matter 解析后，以「名称 + 描述」索引注入 system prompt；模型通过 \`load_skill\` 元工具按需取正文，正文按 \`tool-result-budget\` 截断。当前仓库有两个示例：\`skills/systematic-debugging/SKILL.md\`、\`skills/web-research/web-research.md\`。\r
\r
## 已完成\r
\r
- [x] 现状梳理与优化点识别（2026-11-01）\r
  - 作用：统一识别 Skill 链路的优化点，作为后续执行的唯一任务清单\r
  - 位置：[skill-optimization.md](./skill-optimization.md)\r
  - 做法：梳理扫描、解析、索引、load_skill 与观测链路，按 P0/P1/P2 分级\r
  - 验收：清单落地并明确每项的位置、做法与验收标准\r
\r
- [x] P2-1 目录式技能结构（替代顶层平铺，2026-11-01）\r
  - 作用：让技能按目录组织，入口正文与辅助资源分离，为附件读取和多文件技能铺路\r
  - 位置：[SkillRepository.scan/entryFile/load](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L104-L159)\r
  - 做法：每个技能一个子目录，入口优先 \`SKILL.md\`，其次 \`<目录名>.md\`；缓存键改相对路径；顶层 \`.md\` 不再扫描\r
  - 验收：\`systematic-debugging/SKILL.md\` 与 \`web-research/web-research.md\` 均能加载；顶层旧文件与辅助 \`.md\` 不被当作技能\r
\r
- [x] P2-6 本地 \`spring-boot:run\` 工作目录对齐仓库根（2026-11-01）\r
  - 作用：让本地运行与 Docker 运行期使用同一套相对路径，避免 skills/knowledge/channel 解析错位\r
  - 位置：[server/pom.xml](../../deerfect-server/pom.xml#L218-L225)\r
  - 做法：\`spring-boot-maven-plugin\` 的 \`workingDirectory\` 设为 \`\${project.basedir}/..\`\r
  - 验收：本地启动日志显示 skills/knowledge/channel 均从仓库根解析；Docker 运行不受影响\r
\r
## P0 正确性与健壮性\r
\r
- [x] P0-1 技能重名检测与确定性去重（2026-11-02）\r
  - 作用：避免同名技能在索引与 \`load_skill\` 之间指向不同正文，保证加载结果可预期\r
  - 位置：[SkillRepository.skillsFor/find](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L82-L101)、[SkillManager.LoadSkillCallback](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L124-L129)\r
  - 做法：扫描后按 \`name.toLowerCase()\` 检测冲突，保留入口路径排序第一条，其余记 warn 并跳过\r
  - 验收：同名不同内容的两个技能只返回一条；索引与 \`load_skill\` 指向同一正文；日志能看出被跳过的技能目录\r
\r
- [x] P0-2 清理 UTF-8 BOM（2026-11-02）\r
  - 作用：避免 Windows 编辑器保存的 BOM 让 front-matter 整体失效，技能元数据静默丢失\r
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L165)\r
  - 做法：读取后去掉开头 \`\\uFEFF\`，再判断是否以 \`---\` 开头\r
  - 验收：带 BOM 的入口文件 front-matter 正常解析，\`name/description/agents\` 均生效；补单测\r
\r
- [x] P0-3 缓存指纹与淘汰（2026-11-02）\r
  - 作用：保证热重载可靠，并避免删除/改名后旧缓存长期残留或旧内容被继续使用\r
  - 位置：[SkillRepository.Cached/load/scan](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L67-L159)\r
  - 做法：缓存指纹由 \`mtime\` 改为 \`mtime + size\`；扫描结束清理本次未出现的入口路径缓存\r
  - 验收：复制保留 mtime 的同名文件内容变化能触发热重载；删除/改名后旧缓存被清理；补单测\r
\r
- [x] P0-4 \`agents\` 大小写归一（2026-11-02）\r
  - 作用：避免 \`agents: Researcher\` 这类写法导致技能对目标 agent 不可见\r
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L191-L198)、[Skill.visibleTo](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L60-L64)\r
  - 做法：解析 \`agents\` 时统一转小写，与 agent 名口径一致\r
  - 验收：\`agents: Researcher\` 对 \`researcher\` 可见；补单测\r
\r
## P1 性能、可观测性与功能缺口\r
\r
- [x] P1-1 请求级技能扫描缓存（2026-11-02）\r
  - 作用：降低每请求重复目录扫描的 I/O 开销，避免技能数量增长后拖慢 prompt 装配\r
  - 位置：[SkillRepository.scan](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L104-L127)、[SkillManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L69-L112)、[PromptAssembler](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java#L129-L140)\r
  - 做法：增加短 TTL 快照或请求级复用，\`provide/skillNames/loadSkillTool\` 共用一次扫描结果\r
  - 验收：单请求目录扫描次数由 3 次降为 1 次；热重载测试仍通过\r
\r
- [x] P1-2 技能索引数量/体积上限（2026-11-02）\r
  - 作用：防止技能数量增长后 system prompt 的固定开销失控\r
  - 位置：[SkillManager.provide](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L69-L86)\r
  - 做法：新增 \`app.prompt.skills.max-index\`（条数或 token 预算），超出时截断并提示\r
  - 验收：配置小值时索引有截断提示；默认行为不变；补单测\r
\r
- [x] P1-3 超长技能分段加载（2026-11-02）\r
  - 作用：让长技能正文可以完整获取，而不是被静默截断后半段\r
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)\r
  - 做法：\`load_skill\` 增加 \`offset\`/\`section\` 参数，或改为按段落检索\r
  - 验收：超过 \`tool-result-budget\` 的正文可分多次取完；截断标记仅在仍有余量时出现\r
\r
- [x] P1-4 技能实际加载可观测（2026-11-02）\r
  - 作用：补上「技能是否真的被加载」的观测，支持使用率与截断率分析\r
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)\r
  - 做法：元工具回调保存 \`agentName\`；加载时记录 \`[skill] load agent/skill/chars/truncated\`，并增加 Micrometer 计数\r
  - 验收：能按 agent/skill 统计实际加载次数与截断次数；不新增 SSE 工具行或 \`tool_call_log\`\r
\r
- [x] P1-5 技能管理/校验接口（2026-11-02）\r
  - 作用：提供只读管理入口，便于运维定位解析错误与查看当前技能状态\r
  - 位置：新增只读 Controller；[SkillRepository](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java)\r
  - 做法：\`GET /api/skills\` 返回解析后的技能与错误列表；启动时做一次全量校验汇总\r
  - 验收：可查看 \`name/description/agents/entryFile/size/error\`；重名、BOM、未闭合 front-matter、空正文、超大文件可见\r
\r
- [x] P1-6 移除 \`load_skill\` 的 \`toolName\` 别名（2026-11-02）\r
  - 作用：避免技能名被误当工具名接受，减少模型误调用与语义混淆\r
  - 位置：[SkillManager.extractSkillName](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L200)\r
  - 做法：只保留 \`skillName\` / \`skill_name\` 与裸字符串兜底，删除 \`toolName\` / \`tool_name\`\r
  - 验收：原别名的单测同步调整，技能名不再被误当工具名接受\r
\r
## P2 结构与扩展\r
\r
- [x] P2-2 front-matter 解析边界（2026-11-02）\r
  - 作用：明确解析能力边界，并对坏 front-matter 给出可定位的告警\r
  - 位置：[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L201)\r
  - 做法：未闭合 front-matter 补 warn；明确当前只支持扁平 \`key: value\`，需要多行/列表时再引入 YAML\r
  - 验收：未闭合文件有 warn 且行为与文档一致；补单测\r
\r
- [x] P2-3 抽取重复的工具入参/截断逻辑（2026-11-02）\r
  - 作用：收敛重复的入参提取与截断实现，降低三处逻辑漂移风险\r
  - 位置：[SkillManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L173-L215)、[ToolLazyManager](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/ToolLazyManager.java#L251-L270)、[ToolCallBudget](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/budget/ToolCallBudget.java#L136-L150)\r
  - 做法：抽取公共 JSON 参数提取与 \`truncateToBudget\` 工具，收敛三处实现\r
  - 验收：三处行为保持不变，相关单测通过；重复代码删除\r
\r
- [x] P2-4 技能内容信任边界（2026-11-02）\r
  - 作用：防止超大或不可信技能内容消耗内存、灌爆上下文或引入提示注入\r
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)、[SkillRepository.parse](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillRepository.java#L162-L201)\r
  - 做法：限制单文件大小；若未来支持上传/外部同步，增加内容校验与来源标记\r
  - 验收：超大文件在解析前被拒绝并 warn；现有本地文件不受影响\r
\r
- [x] P2-5 技能附件可访问（2026-11-02）\r
  - 作用：让技能目录中的辅助文档可被发现和读取，发挥目录式结构的价值\r
  - 位置：[SkillManager.doLoad](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/SkillManager.java#L151-L170)\r
  - 做法：\`load_skill\` 返回入口正文时附带 \`<skill>/\` 下辅助文件清单，或增加读取附件参数\r
  - 验收：\`systematic-debugging\` 的辅助文档可被发现/读取；不改变现有正文截断预算\r
\r
## 推荐执行顺序\r
\r
1. **P0-1 / P0-2 / P0-4**：同一轮修改 \`SkillRepository\` 的扫描与解析，补对应单测，风险最低。\r
2. **P0-3 / P1-1**：一起治理缓存指纹与每请求重复扫描，避免后续性能问题。\r
3. **P1-4 / P1-5**：先补实际加载可观测性，再加只读管理/校验接口。\r
4. **P1-2 / P1-3 / P1-6 / P2-2 / P2-3 / P2-4 / P2-5**：按技能数量、正文长度和附件使用情况逐步推进，不必一次性完成。\r
`,Qr="# Super-agent 思想落地清单（链式召回 / 事件过滤 / Goal 心跳）\r\n\r\n- 范围：`server`、`channel-qq`、`harness-api`、`server/src/main/resources/db/migration`\r\n- 背景：[super-agent-ideas-adopted.md](../design/super-agent-ideas-adopted.md)（已落地部分对照）、[roadmap.md](../roadmap.md)（P2 长期记忆 / Goal 失败重投）\r\n- 创建：2026-11-03\r\n- 状态：共 14 项，已完成 14 项，待办 0 项\r\n\r\n## 进度概览\r\n\r\n| 分类 | 已完成 | 待办 | 小计 |\r\n|---|---|---|---|\r\n| A 链式召回（历史引用 + 按需取回） | 5 | 0 | 5 |\r\n| B 渠道事件过滤与信道 prompt | 5 | 0 | 5 |\r\n| C Watchdog 与 Goal 心跳 | 4 | 0 | 4 |\r\n| 合计 | 14 | 0 | 14 |\r\n\r\n## 背景\r\n\r\n当前三个缺口对应文章的三条思想：\r\n\r\n1. **链式召回**：`ContextAssemblingAdvisor` 按预算从最旧裁剪，`SessionServiceImpl.trimSnapshot` 在写回时还会物理丢弃旧消息；被裁掉的历史没有 ID、没有归档、没有按需读取入口。\r\n2. **事件过滤分层 + 信道感知**：QQ 的自消息过滤、白名单、限频、触发判定硬编码在 `OneBotEventServiceImpl` / `EventTextParser`；`PromptAssembler` 不知道消息来自 QQ 还是 CLI，输出适配只发生在发送层（`max-length` 截断）。\r\n3. **Watchdog**：`GoalServiceImpl.failAllRunning` 只在启动时清理 `RUNNING`；运行中卡住、无进展的 Goal 没有外部回路发现。\r\n\r\n范围边界：不做引用图/多跳召回、不做归档向量化、不做自动重投（沿用 P2 另立任务）、不动全双工 ReAct 与 Router/Worker 架构。B2 只留语义层入口，默认关闭。\r\n\r\n---\r\n\r\n## A 链式召回\r\n\r\n- [x] A1 会话快照增加消息级稳定 ID\r\n  - 作用：为窗口外历史提供可引用、可召回的定位符；当前快照 item 只有 role/content/ts，裁掉即无法定位\r\n  - 位置：[SessionServiceImpl.saveContext/loadContext/toMessage](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L105-L228)、[ContextAssemblingAdvisor](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L82-L89)\r\n  - 做法：快照 JSON 的每个 item 增加 `id`（写入时生成，建议 `m-<uuid>`）；读取时把 id 放进 Spring AI `Message` 的 metadata；存量 item 在下次写回时补 id（不改表结构、不做双读兼容）\r\n  - 验收：新写入消息带稳定 id；旧快照写回后 id 补齐；裁剪后的消息仍能从 metadata 读回 id\r\n\r\n- [x] A2 归档表与裁剪转归档\r\n  - 作用：快照写回本身会物理丢弃旧消息，只改上下文窗口不够；归档是召回的数据源\r\n  - 位置：[SessionServiceImpl.trimSnapshot](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/SessionServiceImpl.java#L237-L274)、新增 `SessionMessageArchiveMapper/Entity`、Flyway `V3__session_message_archive.sql`\r\n  - 做法：新增归档表（`session_id` / `message_id` / `role` / `content` / `ts` / `tenant_id` / `archived_at`，按 `(session_id, message_id)` 建索引）；在 `trimSnapshot` 移除项前先写归档（整条被移除项与单条超限截断的尾部都要保留）；保持 `snapshot-max-messages` 与字符上限语义不变；归档写入与快照写回同一事务\r\n  - 验收：触发条数/字符上限后旧消息进归档且快照行为不变；归档可按 session + messageId 查回原文（含被截断尾部）；写入失败不产生半写快照\r\n\r\n- [x] A3 窗口内历史引用提示\r\n  - 作用：模型需要知道「窗口外还有历史、用什么取」，否则不会调用召回工具\r\n  - 位置：[ContextAssemblingAdvisor.assemble](../../deerfect-server/src/main/java/com/dark/javaHarness/advisor/ContextAssemblingAdvisor.java#L89-L160)\r\n  - 做法：裁剪时统计被丢弃消息的 id 范围与条数，在 system 消息中追加一行「历史引用：更早 N 条已归档，ID a..b，可用 recall_history 读取」；未发生裁剪时不输出；不改变原有 role 归一化与 system 置前\r\n  - 验收：超预算时出现引用行且 ID 能被 A4 命中；预算内不出现；system/历史顺序与现有单测一致\r\n\r\n- [x] A4 `recall_history` 工具与权限\r\n  - 作用：把「引用」变成可执行的按需召回，替代「裁掉即丢」\r\n  - 位置：新增 `tool/builtin/history/RecallHistoryTools.java`、[ToolAssignments](../../deerfect-server/src/main/java/com/dark/javaHarness/tool/ToolAssignments.java)、`TOOL_PURPOSES`、agent 表 `tools` 列\r\n  - 做法：入参 `messageId` 或 `fromId/toId` + `limit`；`sessionId` 经 ToolContext 注入（不作为模型可见参数），服务端强制按会话过滤；返回内容走既有 `ToolCallBudget` 截断；只授予 `general` 与编排 `lead`，子任务专家与 `aggregator` 不注入\r\n  - 验收：general/lead 能取回本会话归档原文；跨会话 ID 被拒绝；其他 agent 不可见；调用进 `tool_call_log` 并占用预算\r\n  - 后续（2026-11-06，P2-2）：`recall_history` 增加 `scope=all`，显式跨会话检索已放行并在结果标注来源会话号；默认 `scope=current` 仍保持本会话隔离，上条验收口径对默认行为继续成立。\r\n\r\n- [x] A5 链式召回测试与文档\r\n  - 作用：锁定 ID、归档、引用、权限四段行为，避免后续裁剪逻辑改动造成静默丢失\r\n  - 位置：`SessionServiceImplTest`、`ContextAssemblingAdvisorTest`、新增 `RecallHistoryToolsTest`、`ToolAssignmentsTest`；`docs/data-flow.md`、`docs/guides/context-optimization.md`\r\n  - 做法：覆盖旧快照补 id、裁剪归档、引用行生成、跨会话拒绝、权限矩阵；文档补「链式召回最小版」一节与限制说明\r\n  - 验收：新增用例全绿；`mvn -s .mvn/settings.xml -pl server -am test` 通过；文档与实现一致\r\n\r\n## B 渠道事件过滤与信道 prompt\r\n\r\n- [x] B1 程序层策略抽成 `ChannelEventPolicy`\r\n  - 作用：去掉 `OneBotEventServiceImpl` 中散落的准入 if，让来源/群/用户/触发方式成为可配置策略，而不是业务代码\r\n  - 位置：[OneBotEventServiceImpl.doHandle](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/OneBotEventServiceImpl.java#L146-L191)、[EventTextParser](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/event/EventTextParser.java)、[NapCatProperties](../../channel-qq/src/main/java/com/dark/javaHarness/channel/qq/client/NapCatProperties.java)、NapCat yaml\r\n  - 做法：新增 `Decision { RESPOND, CANDIDATE, IGNORE }` 与首条匹配规则表（来源 group/private、群/用户白名单、触发方式 at/reply/prefix/all → 决策）；默认规则逐条等价当前行为；`doHandle` 只做「取文本 → 策略判定 → 分发」\r\n  - 验收：默认配置下现有 `OneBotEventServiceImplTest` 全绿；改 yaml 可调整响应范围且不改代码\r\n\r\n- [x] B2 语义层触发判断扩展点\r\n  - 作用：为「主动观察群聊」留一个 LLM 入口，避免以后继续往 `doHandle` 加分支\r\n  - 位置：`channel-qq` 新增 `SemanticTriggerJudge` 接口与 `LlmTriggerJudge` 实现；`OneBotEventServiceImpl` 处理 `CANDIDATE`\r\n  - 做法：新增 `napcat.semantic-trigger.enabled`（默认 false）；关闭时 `CANDIDATE` 按 `IGNORE`（行为零变化）；开启时对候选事件调用一次轻量 LLM，返回 respond/silent；提示词与模型走 agent 表 internal 行（沿用 route-judge 模式）；超时/异常/非 JSON 兜底 silent\r\n  - 验收：默认配置无行为变化；开启后候选事件可在无 @ 时被响应一次；judge 失败不回复、不向群聊泄露错误\r\n\r\n- [x] B3 `ChatRequest` 增加 `channel` 并贯通到执行层\r\n  - 作用：让 core 知道消息来自哪个渠道，prompt 才能按信道定制\r\n  - 位置：[ChatRequest](../../deerfect-api/src/main/java/com/dark/javaHarness/view/ChatRequest.java)、[ChatServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java)、`AgentService/AgentServiceImpl`、`Goal/GoalEntity`、QQ `chatWithTimeout`、CLI `ChatApiClient`\r\n  - 做法：新增可空 `channel` 字段（`qq` / `cli` / `web` / `api`）；QQ 显式传 `qq`；经 Goal 携带到 Agent 执行与 `AgentChatCaller`；同步更新所有构造调用与测试，不保留旧重载\r\n  - 验收：QQ 触发的 Goal 带 `qq`；CLI/Web 带各自值或 null；全模块编译与测试通过\r\n\r\n- [x] B4 信道段落注入 `PromptAssembler`\r\n  - 作用：在生成侧约束输出，替代发送侧「先写长再截断」\r\n  - 位置：[PromptAssembler](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/PromptAssembler.java)、[ContextCompositionCalculator](../../deerfect-server/src/main/java/com/dark/javaHarness/prompt/ContextCompositionCalculator.java)、`AgentRequestSpecFactory`\r\n  - 做法：新增「信道规范」段（order 放在输出约定附近）：IM = ≤100 词、只问直接问题、给简短结论；CLI/API = 结构化完整交付；null/未知渠道不注入；快照 part 登记 `system.channel`\r\n  - 验收：QQ 请求 system 含 IM 段；CLI/Web/API 只含结构化交付段、不含 IM 长度限制；空渠道不注入；上下文快照能统计该段 token（E2E：QQ 会话 `system.channel` 70 token，CLI 会话为结构化段）\r\n\r\n- [x] B5 渠道与信道测试、联调\r\n  - 作用：确认默认行为不回归，且信道约束真的生效\r\n  - 位置：`ChatServiceImplTest`、`PromptAssemblerTest`、`OneBotEventServiceImplTest`、`ContextCompositionCalculatorTest`\r\n  - 做法：覆盖默认策略回归、channel 贯通、IM 段生成、无 channel 无段；手工用 QQ 发一条开放式长问题\r\n  - 验收：用例全绿；QQ 回复明显更短且不再依赖 `max-length` 截断\r\n\r\n## C Watchdog 与 Goal 心跳\r\n\r\n- [x] C1 `goal` 表增加 `last_progress_at`\r\n  - 作用：让「有没有进展」成为可查询事实，作为 Watchdog 判据\r\n  - 位置：Flyway `V4__goal_last_progress.sql`（在 A2 的 V3 之后）、[GoalEntity](../../deerfect-server/src/main/java/com/dark/javaHarness/domain/entity/GoalEntity.java)、[Goal](../../deerfect-api/src/main/java/com/dark/javaHarness/contract/model/Goal.java)、[GoalServiceImpl](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/GoalServiceImpl.java)\r\n  - 做法：新增可空列 `last_progress_at`；Goal 增加字段与 `touchProgress()`；`markRunning()` 时初始化；GoalService 增加 `touchProgress(goalId)`，条件更新仅当 `status=RUNNING`\r\n  - 验收：新建并运行的 Goal 有值；非 RUNNING 状态不更新；Flyway 在存量库上正常应用\r\n\r\n- [x] C2 进度打点（节流）\r\n  - 作用：心跳不能按 token 逐帧写库，需要固定在阶段/时间边界\r\n  - 位置：[AgentServiceImpl.streamWithLifecycle](../../deerfect-server/src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java#L140-L172)、`OrchestrationCheckpointStore` 保存处、`AgentChatCaller` 每轮往返完成处（按需）\r\n  - 做法：统一节流（如 30 秒一次）调用 `touchProgress`；Graph 在 `LEAD_DONE` / `SUBTASKS_DONE` 检查点保存时同步打点；同步路径在检查点与每轮 LLM 完成时打点；完成/失败后不再更新\r\n  - 验收：长任务 RUNNING 期间 `last_progress_at` 持续刷新且写入频率受控；结束后不再变化\r\n\r\n- [x] C3 `StaleGoalWatchdog` 定时扫描\r\n  - 作用：在主系统之外发现「在跑但无进展」的僵尸 Goal，弥补只有启动清理的缺口\r\n  - 位置：新增 `StaleGoalWatchdog`（`@Scheduled`，放 `observability` 或 `service` 包）、`GoalService` 条件失败方法\r\n  - 做法：扫描 `status=RUNNING` 且 `last_progress_at < now - stagnant-minutes`；用条件更新把仍为 RUNNING 的目标置 FAILED 并写原因/`finished_at`（不覆盖已结束目标）；日志 + 计数；`app.watchdog.enabled` 默认 false，观察一轮后再开；阈值必须大于 LLM 读超时与流空闲超时\r\n  - 验收：构造停滞 Goal 被标 FAILED；活跃 Goal 不受影响；已结束 Goal 不被改写；开关关闭时零行为；单实例假设与 `failAllRunning` 一致\r\n\r\n- [x] C4 Watchdog 测试与文档\r\n  - 作用：锁定阈值、条件更新与开关语义，并与 P2 重投划清边界\r\n  - 位置：`GoalServiceImplTest`、新增 `StaleGoalWatchdogTest`、`docs/data-flow.md` 5h 节、`docs/roadmap.md` P2「Goal 失败重投」\r\n  - 做法：覆盖阈值边界、非 RUNNING 不更新、开关关闭；文档写明「Watchdog 只标失败、不自动重投，重投由 P2 另立」\r\n  - 验收：用例全绿；`mvn -s .mvn/settings.xml -pl server -am test` 通过；文档口径一致\r\n\r\n## 执行记录（2026-11-03）\r\n\r\n- 核心落点：`SessionServiceImpl`（消息 ID + 裁剪转归档）、`SessionHistoryArchiveService` / `RecallHistoryTools`（按需召回）、`ContextAssemblingAdvisor`（历史引用行）、`ChannelEventPolicy` + `SemanticTriggerJudge`（程序层/语义层过滤）、`PromptAssembler` 信道段、`Goal.last_progress_at` + `StaleGoalWatchdog`（停滞看门狗）。\r\n- 迁移：`V3__session_message_archive.sql`、`V4__goal_last_progress.sql`、`V5__recall_history_tool_assignment.sql`、`V6__goal_channel.sql`。\r\n- 开关：`app.trigger-judge.enabled` / `napcat.semantic-trigger.enabled` / `app.watchdog.enabled` 均默认关闭；`napcat.event-policy.rules` 为空时保持既有行为。\r\n- 验证：全量 `mvn -s .mvn/settings.xml test` 通过（server 785、channel-qq 79、cli 28、wms 43；失败 0）。\r\n- E2E（真实服务 + MySQL）：V3~V6 迁移成功；QQ/CLI 聊天 `goal.channel` 分别落 `qq`/`cli`；上下文快照出现 `system.channel`（QQ 70 token，CLI 为结构化段）；QQ 群聊无 @ 被策略忽略、@ 后进入聊天并成功产出；`StaleGoalWatchdog` 将 10 分钟无进展目标标 FAILED、新鲜 RUNNING 目标保持不变；会话快照裁剪转归档后，模型真实调用 `expand_tool` + `recall_history` 取回 `MAGIC_OLD_FACT=42`，`tool_call_log` 记录 `recall_history OK`。\r\n\r\n## 推荐执行顺序\r\n\r\n1. **C1 → C2 → C3 → C4**：改动最小、与业务链路解耦，先解决真实的 RUNNING 僵尸态。\r\n2. **A1 → A2 → A3 → A4 → A5**：按「ID → 归档 → 引用 → 工具」顺序推进，每步都可独立验收；A2 的 Flyway 版本先于 C1。\r\n3. **B1 → B3 → B4 → B5**：先做行为不变的程序层抽离与 channel 贯通，再接信道段落；B2 语义层最后做，默认关闭。\r\n",Kr='---\r\ntitle: "技术栈"\r\norder: 5\r\n---\r\n\r\n# 技术栈\r\n\r\n> 基于 Spring AI 的 Agent 编排框架 Demo。本文档梳理项目现有技术栈，并给出可扩展方向。\r\n\r\n***\r\n\r\n## 一、项目现状\r\n\r\n- **定位**：双路径 AI Agent 框架 —— 主 Agent（`RouteJudge` LLM 决策）选择执行路径：**Agentic 路径**由单 Agent 显式 ReAct 循环自主执行，**Graph 编排路径**走自研并行引擎多 Agent 编排（内部枚举 `SIMPLE` / `COMPLEX`）；执行目标（Goal）全生命周期（PENDING → RUNNING → SUCCEEDED/FAILED）落库可追溯。\r\n- **入口**：REST API（`/api/chat`、`/api/chat/stream`、`/api/harness/*`、`/api/llm-calls`）+ CLI 聊天客户端（`mvn exec:java`）。\r\n- **会话记忆**：MySQL 两张表（`session` + `session_messages`，一对一），`session_messages.content` 以 JSON 快照存储完整上下文。\r\n- **目标存储**：`Goal` 持久化到 MySQL `goal` 表，重启不丢，可追溯历史。\r\n- **执行反馈**：Agentic 路径逐 token 流式；Graph 编排路径各阶段进度经生命周期钩子实时推送（`event: progress` SSE）。\r\n- **工具执行**：容器级沙箱（agentscope-runtime）——模型生成的 Python/Shell 与浏览器操作只在 Docker 容器内执行，宿主机零暴露；按专家分配工具（服务端硬边界，未分配的工具对模型不可见）。\r\n\r\n***\r\n\r\n## 二、当前技术栈\r\n\r\n| 分类       | 技术                         | 版本     | 用途 / 说明                                                                        |\r\n| -------- | -------------------------- | ------ | ------------------------------------------------------------------------------ |\r\n| 语言       | Java                       | 25     | JDK 版本（`pom.xml` release 25；Maven Enforcer 在 validate 阶段强制 JDK 25+，低版本直接构建失败并提示）                 |\r\n| 构建       | Maven                      | -      | 依赖与构建管理，本地仓库重定向 `.mvn-repo`                                                    |\r\n| 核心框架     | Spring Boot                | 4.1.2  | Web 应用框架（与 Spring AI 2.0.2 对齐）                                               |\r\n| AI 框架    | Spring AI                  | 2.0.2  | `spring-ai-starter-model-openai`，以 OpenAI 兼容协议统一对接多供应商（DashScope / DeepSeek / 第三方代理），按 `llm_route` 路由成员选择端点 |\r\n| Agent 执行 | agentscope-core            | 2.0.4  | Agentic 路径显式 ReAct（`AgentScopeGeneralAgent`）：工具仍走 Spring AI ToolCallback 桥接，排除其自带 MCP SDK/Victools 旧版；`app.agentic.enabled=false` 回退 ChatClient 路径 |\r\n| 编排引擎    | 自研 `ParallelOrchestrationEngine` | -      | lead→并行子任务→聚合；阶段检查点落 `orchestration_checkpoint`（LEAD_DONE/SUBTASKS_DONE/DONE），替代 StateGraph |\r\n| 沙箱工具  | agentscope-runtime-sandbox-core | 1.0.2  | 容器级工具隔离（`SandboxToolkit` 本地薄封装）：base 容器（Python/Shell/文件读写检索）+ browser 容器（导航/快照/点击/输入），Docker 硬依赖 |\r\n| Web      | Spring MVC                 | 随 Boot | REST + 响应式 SSE（Controller 返回 `Flux<String>`，`text/event-stream`）+ 全局异常处理 |\r\n| ORM      | MyBatis-Plus               | 3.5.17 | `mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`，goal / session / session\\_messages / agent 表的 CRUD |\r\n| 数据库      | MySQL                      | 9.2.0  | `mysql-connector-j`（runtime），连接池 HikariCP                                      |\r\n| 向量库      | PostgreSQL + pgvector      | 可选     | `spring-ai-pgvector-store`（BOM 管理）+ `org.postgresql:postgresql`（runtime），RAG 知识库专用数据源（与 MySQL 主库独立，懒连接，PG 未就绪不影响启动） |\r\n| 嵌入模型     | DashScope text-embedding-v4 | 1024 维 | `OpenAiEmbeddingModel`（OpenAI 兼容协议手动装配），知识库切分文档向量化（app.knowledge.embedding.*） |\r\n| Schema 迁移 | Flyway                     | 随 Boot | `flyway-core` + `flyway-mysql`，版本化迁移（`db/migration/V*__*.sql`），存量库经 baseline 接入；替代 `spring.sql.init` |\r\n| 参数校验     | Jakarta Validation         | 随 Boot | `spring-boot-starter-validation` + `@Valid` 注解校验                               |\r\n| HTTP 客户端 | OkHttp                     | 5.5.0  | CLI 端调用主服务 REST/SSE（`cli/api/ChatApiClient`）；模型调用经 Spring AI 内置 OkHttp 传输 |\r\n| 序列化      | Jackson                    | 3.x（随 Boot） | DTO 序列化 / SSE meta 解析 / 工具入参（`tools.jackson` 包）                          |\r\n| 代码生成     | Lombok                     | -      | 实体类简化                                                                          |\r\n| 测试       | JUnit 5 / Mockito          | 随 Boot | 2026-11-12 实跑 1474 例（1 skip）：deerfect-api 52、deerfect-server 1082、channel-qq 85、channel-email 15、channel-feishu 152、deerfect-cli 30、wms-mcp-adapter 58；除 `JavaHarnessApplicationTests` 需本机 MySQL 外不依赖真实 DB/网络，见 docs/guides/functional-testing.md |\r\n| 日志       | SLF4J + Logback            | 随 Boot | Agent 执行状态日志                                                                   |\r\n| 初始化 SQL  | ~~schema.sql~~ 已移除         | -      | 由 Flyway 迁移脚本接管（见上）                                                            |\r\n\r\n### 架构分层\r\n\r\n```\r\ncontroller（REST + SSE 流式 + 全局异常处理 + 观测查询三接口，查询统一经 ObserveQueryService 收口）\r\n  └─ service（接口 + impl：AgentService / ChatService / GoalService / SessionService\r\n             ├─ RouteJudge + LlmRouteJudge（主 Agent 分流判定）\r\n             ├─ AgentConfigProvider（agent 表运行配置）\r\n             ├─ ObserveQueryService（观测三表查询收口）/ SessionBindingCleaner（渠道绑定清理端口）\r\n             └─ LlmCallRecorder（LLM 调用观测异步落库）\r\n        ├─ agent（Agent 抽象 + GeneralAssistantAgent 路径A + MultiAgentGraphAgent 路径B\r\n        │     + AgentChatCaller 调用封装（含观测埋点） + BranchProgressListener 钩子旁路）\r\n        │     └─ ProgressLine（进度行线协议，服务端↔CLI 共用）\r\n        ├─ advisor（ContextAssemblingAdvisor：上下文组装/token 裁剪）\r\n        ├─ tool（WebTools 网页抓取 / DemoTools 示例 / SandboxToolProvider 容器沙箱 / ToolAssignments 工具分配）\r\n        ├─ knowledge（RAG 知识库：KnowledgeDocumentScanner 扫描 / MarkdownChunker 切分 / KnowledgeIngestor 增量摄取 / HybridKnowledgeRetriever 混合检索 / KnowledgeRetriever 检索注入）\r\n        ├─ deerfect-cli（独立模块：ChatCli 启动组装 / CommandDispatcher 命令分发 / StreamRenderer SSE 渲染 / cli/api ChatApiClient：OkHttp + SSE 解析）\r\n        ├─ channel-qq（独立模块：NapCat/OneBot 接入；会话绑定持久化在同模块 persistence）\r\n        ├─ domain（server 自身领域模型：RouteDecision 分流决策 / LlmCallLog 等观测记录；契约 DTO 在 deerfect-api 的 view/，实体在 deerfect-api domain/）\r\n        ├─ mapper（Agent / Goal / Session / SessionMessage / LlmProvider / LlmModel / LlmRoute / 各观测日志 Mapper；OneBot 绑定 Mapper 在 channel-qq）\r\n        ├─ enums（deerfect-server 侧 AgentConstants；GoalStatus/SseProtocol 在 deerfect-api）\r\n        └─ 配置（application.yaml 入口 + application-*.yaml 分域 + config/agent：ChatAgentConfig、ChatClientFactory）\r\n```\r\n\r\n### 关键设计\r\n\r\n- **双路径分流**：`LlmRouteJudge` LLM 决策 Agentic / Graph 两条执行路径（内部枚举 `SIMPLE`/`COMPLEX`，异常兜底 `SIMPLE`）；`ChatServiceImpl.resolveAgent()` 据此路由 `general` 或 `multi-agent`。\r\n- **流式聊天**：`/api/chat/stream` 返回 `Flux<String>`（SSE）；每个事件单元素成对输出——内容 token 为普通 `data:` 行，进度为 `event: progress` + `{stage, detail}` JSON，结束发 `[DONE]` + `meta`（sessionId/goalId/status/error）。\r\n- **逐 token 推送**：Agentic 路径 `AgentScopeGeneralAgent.executeStreamReactive` 把 ReAct 文本增量边收边发；Graph 路径子任务完成事件由 `BranchProgressListener` 在引擎取到结果后逐条播报（串行发射防丢事件）。\r\n- **Agent 路由**：请求显式携带 `agentId` 时优先按 agent 表映射路由（CLI `/agent <id>` 手动指定），否则按分流结果选择执行体；未命中回退 `general`。\r\n- **动态配置**：每次调用经 `AgentService.getAgentConfig(agent_name)` 读 agent 表的 `model / prompt`，改库即时生效，无需重启。\r\n- **会话写回统一**：同步 / 流式路径都在 `ChatServiceImpl` 统一写回会话记忆（进度行不计入摘要）。\r\n- **工具调用（容器级沙箱）**：`SandboxToolProvider` 懒初始化 agentscope 沙箱（base 容器：Python/Shell/文件读写检索；browser 容器：导航/快照/点击/输入，独立镜像、独立初始化失败只降级本组），宿主机零暴露；`ToolAssignments` 按专家双通道注入（@Tool 对象走 `.tools()`、ToolCallback 走 `.toolCallbacks()`），未分配的工具对模型不可见（服务端硬边界）。researcher/general 拥有浏览器组，补 JS 渲染页面抓取空缺。\r\n- **统一异常响应**：`GlobalExceptionHandler` 把所有异常转成 `{code, message}` 结构（400/404/500），非法请求返回统一 400 错误体。\r\n- **LLM 调用观测（成本账本）**：每次 LLM 调用（路由判断 / lead 拆解 / 专家子任务 / 聚合 / Agentic 路径）结束经 `LlmCallRecorder` 异步写 `llm_call_log` 表——会话、角色、模型、SYNC/STREAM、成败、token（流式按输出文本近似估算）、耗时、错误；观测失败不影响主链路。`GET /api/llm-calls?sessionId=` 查询。完整链路追踪（Micrometer Tracing）见 TODO。\r\n- **RAG 知识库（检索增强）**：knowledge/ 目录 `.md/.txt` 文档经 `POST /api/knowledge/sync` 增量摄取（mtime 比对→`MarkdownChunker` 段落感知切分 ~700 字符+重叠→DashScope 嵌入→pgvector，确定性 chunk id=docName#idx 支持重摄取精确覆盖）；Agentic / Graph 两条路径共用 `AgentRequestSpecFactory` 汇合点，每次调用前 `KnowledgeRetriever` 按当前 user 文本向量检索 top-k（aggregator 角色策略跳过——其材料是子任务结果），命中渲染【出处N】知识段注入 system prompt（context-budget 预算截断）；回答内联【出处N】引用 + `meta.sources`/`ChatResponse.sources` 出处 + CLI「来源:」尾注；`GET /api/knowledge/documents|search` 调试检索、`DELETE /api/knowledge/documents/{name}` 删除。降级口径：知识库未启用（app.knowledge.enabled=false）或 PG 不可用时应用照常启动、检索返回空、不注入知识段。台账 `kb_document` 表走 Flyway V11；向量表由 PgVectorStore initializeSchema 按 yaml `app.knowledge.pgvector.table-name` 自建（当前 `vector_store_harness`，TEXT 主键，与库内其他项目向量表隔离；不经 Flyway，主库 schema 管理仍是 MySQL）。\r\n\r\n***\r\n\r\n## 三、可扩展技术栈建议\r\n\r\n### 1. AI 与模型层（优先级：高）\r\n\r\n| 建议            | 说明                                                                              |\r\n| ------------- | ------------------------------------------------------------------------------- |\r\n| 更多模型接入        | 官方 DeepSeek starter、本地 Ollama、Qwen 官方 DashScope starter，按 Agent 路由不同模型          |\r\n| 多模态           | 图片/语音输入（DashScope 兼容模式逐步支持）                                                     |\r\n| **RAG（向量检索）** | **已实现**：pgvector + DashScope 嵌入，knowledge/ 目录增量摄取 + Agentic/Graph 路径检索增强，一级子目录多知识库 + agent 表 knowledge 列绑定（向量 metadata.kb 过滤隔离）；待扩展：BM25 混合检索与重排、目录文件监听 |\r\n| **MCP 接入**    | 让 Agent 通过 MCP 连接外部工具/服务，扩展工具生态                                                 |\r\n| Agent 编排框架    | LangGraph4j / AutoGen 风格的多 Agent 协作、任务规划、反思循环                                   |\r\n\r\n### 2. 数据与持久化（优先级：高）\r\n\r\n| 建议                    | 说明                                                |\r\n| --------------------- | ------------------------------------------------- |\r\n| ~~数据库迁移工具~~ 已实现        | Flyway 已接入（`db/migration` 版本化迁移，存量库经 baseline 无缝过渡） |\r\n| ~~PostgreSQL + pgvector~~ 已实现 | RAG 知识库向量存储已落地（独立数据源、懒连接、PG 未就绪不影响启动） |\r\n| 缓存                    | `spring-boot-starter-data-redis`：会话快照缓存、限流计数、热点数据 |\r\n\r\n### 3. 服务治理与可靠性（优先级：中）\r\n\r\n| 建议                | 说明                                                                |\r\n| ----------------- | ----------------------------------------------------------------- |\r\n| **Actuator + 监控** | `spring-boot-starter-actuator`：健康检查、指标；可接 Prometheus + Grafana    |\r\n| 可观测性              | **已实现**：LLM 调用观测自建账本（`llm_call_log` + `/api/llm-calls`）；**待做**：Micrometer Tracing / OpenTelemetry 完整链路追踪（单次请求耗时瀑布，见 TODO「完整链路追踪」）        |\r\n| 重试与熔断             | Spring Retry / Resilience4j：模型调用失败重试、限流熔断                         |\r\n| 消息队列              | RabbitMQ / Kafka：异步 Goal 派发、长任务解耦、失败重投                            |\r\n| 异步任务治理            | 当前 `CompletableFuture.runAsync` 无线程池管理，可换 `@Async` + 自定义线程池 / 任务表 |\r\n\r\n### 4. API 与接口规范（优先级：中）\r\n\r\n| 建议     | 说明                                                                        |\r\n| ------ | ------------------------------------------------------------------------- |\r\n| API 文档 | springdoc-openapi（Swagger UI / OpenAPI 3）                                 |\r\n| DTO 映射 | MapStruct 替代手写 VO 转换                                                      |\r\n\r\n### 5. 安全（优先级：视需求）\r\n\r\n| 建议                    | 说明                                          |\r\n| --------------------- | ------------------------------------------- |\r\n| Spring Security + JWT | 接口鉴权、多用户隔离                                  |\r\n| API Key 管理            | 密钥不落代码/配置，走密钥管理服务（Vault / KMS），支持多租户 Key 路由 |\r\n\r\n### 6. 测试与质量（优先级：中）\r\n\r\n| 建议             | 说明                                           |\r\n| -------------- | -------------------------------------------- |\r\n| Testcontainers | 集成测试起 MySQL/向量库真实环境                          |\r\n| Mockito 补全     | service 层核心场景已有 50 用例，继续补长尾分支与异常路径           |\r\n| 契约测试           | 对 `/api/chat/stream` SSE 格式做契约测试，防止客户端/服务端漂移 |\r\n\r\n### 7. 工程化与交付（优先级：低-中）\r\n\r\n| 建议        | 说明                                                    |\r\n| --------- | ----------------------------------------------------- |\r\n| 容器化       | Dockerfile + docker-compose（app + MySQL + Redis），一键启动 |\r\n| CI/CD     | GitHub Actions / Gitee Go：编译、测试、镜像构建                  |\r\n| 前端        | Vue3/React + EventSource 消费 SSE，替代/补充 CLI             |\r\n| WebSocket | 若需全双工交互可扩展                                            |\r\n\r\n***\r\n\r\n## 四、注意事项\r\n\r\n- **版本约束**：Java 25 / Spring Boot 4.1.2 / Spring AI 2.0.2（Jackson 3、OpenAI Java SDK）为当前基线，升级需整体回归模型调用与工具链。\r\n- **模型路由**：Agent 绑定 `llm_route`，成员按 `priority` 构成失败链；端点与凭证来自 `llm_provider`，部署名与能力来自 `llm_model`。API Key 解析规则：`credential_ref` → `app.providers.<provider>.api-key`（yaml）→ `<PROVIDER大写>_API_KEY`（环境变量）。\r\n- **DB 迁移**：schema 由 Flyway 管理（`db/migration/V1~V23`）。新增表结构变更只需追加 `Vxx__xxx.sql`，启动自动应用。\r\n- **RAG 可选依赖**：知识库需要自备 PostgreSQL（启用 vector 扩展）并在 `app.knowledge.*` 配置连接与嵌入端点；`knowledge/` 目录不存在或 PG 未就绪不影响启动与其它功能（检索静默降级为空，管理端点报清晰错误）。\r\n- **Docker 硬依赖**：沙箱工具依赖本机 Docker；两个镜像需预拉取（aliyun 新加坡 registry）：`runtime-sandbox-base:latest`（约 1.4GB）与 `runtime-sandbox-browser:latest`（约 2GB），否则首个工具调用会现场拉镜像拖慢执行。无 Docker 时沙箱工具组为空（warn 日志），其余功能不受影响。该次接入的过程记录为当时存档、仓库已清理；现行沙箱工具面见 [project-structure.md](./project-structure.md)。\r\n- **容器生命周期**：容器随首次工具调用懒创建、服务优雅停止时随 `@PreDestroy` 销毁；强杀 `mvn spring-boot:run` 不触发优雅关闭会残留容器，需正常停服或 `docker rm -f` 清理。\r\n\r\n',Vr={"architecture.md":"2026-11-19","data-flow.md":"2026-11-19","dev.md":"2026-11-19","features.md":"2026-11-19","index.md":"2026-11-19","ops.md":"2026-11-19","principal.md":"2026-11-19","project-structure.en.md":"2026-11-19","project-structure.md":"2026-11-19","roadmap.md":"2026-11-19","tech-stack.md":"2026-11-19","guides/api.md":"2026-11-19","guides/cli.md":"2026-11-19","guides/commit-convention.md":"2026-11-19","guides/context-optimization.md":"2026-11-18","guides/docker-deploy.md":"2026-11-19","guides/first-run-audit.md":"2026-11-19","guides/functional-testing.md":"2026-11-19","guides/knowledge-rag.en.md":"2026-11-19","guides/knowledge-rag.md":"2026-11-19","guides/mcp-tools.md":"2026-11-19","guides/models.md":"2026-11-19","guides/qq-channel.md":"2026-11-19","guides/resume.md":"2026-11-19","guides/setup.md":"2026-11-19","guides/sse-protocol.en.md":"2026-11-19","guides/sse-protocol.md":"2026-11-19","guides/wms-mcp.md":"2026-11-19","design/2026-08-25-chat-reactive-streaming-design.md":"2026-09-11","design/2026-08-25-chat-reactive-streaming-plan.md":"2026-11-18","design/2026-08-26-main-agent-route-judge-design.md":"2026-11-19","design/2026-08-26-multi-agent-graph-design.md":"2026-11-19","design/2026-11-08-delivery-reliability-feishu-email-plan.md":"2026-11-18","design/2026-11-11-cli-terminal-probe.md":"2026-11-11","design/2026-11-11-external-tools.md":"2026-11-11","design/2026-11-11-feishu-channel-design.md":"2026-11-11","design/2026-11-11-session-serialization.md":"2026-11-11","design/2026-11-12-llm-provider-model-routing.md":"2026-11-19","design/agentic-simple-path-feasibility.md":"2026-11-19","design/channel-core-module-split.md":"2026-11-18","design/napcat-deerfect-harness-integration-plan.md":"2026-11-19","design/spring-ai-2-migration.md":"2026-11-19","design/super-agent-ideas-adopted.md":"2026-11-19","task-checklist/channel-health.md":"2026-11-18","task-checklist/delivery-outbox.md":"2026-11-18","task-checklist/email-channel.md":"2026-11-18","task-checklist/feishu-channel.md":"2026-11-11","task-checklist/feishu-inbound-expansion.md":"2026-11-10","task-checklist/llm-routing.md":"2026-11-19","task-checklist/personal-agent-gap.md":"2026-11-19","task-checklist/project-hardening.md":"2026-11-18","task-checklist/project-optimization.md":"2026-11-18","task-checklist/readme.md":"2026-11-19","task-checklist/session-context-composition.md":"2026-11-18","task-checklist/skill-optimization.md":"2026-11-18","task-checklist/super-agent-adoption.md":"2026-11-19","reports/2026-10-05-knowledge-test-kit.md":"2026-10-05","reports/2026-10-05-video-script.md":"2026-11-19","reports/2026-11-11-knowledge-graph-report.md":"2026-11-18","reports/2026-11-14-test-data-dependency-audit.md":"2026-11-19","reports/project-intro.md":"2026-11-19"},Jr=Object.assign({"../../../docs/architecture.md":Be,"../../../docs/data-flow.md":Fe,"../../../docs/design/2026-08-25-chat-reactive-streaming-design.md":qe,"../../../docs/design/2026-08-25-chat-reactive-streaming-plan.md":Ue,"../../../docs/design/2026-08-26-main-agent-route-judge-design.md":We,"../../../docs/design/2026-08-26-multi-agent-graph-design.md":Qe,"../../../docs/design/2026-11-08-delivery-reliability-feishu-email-plan.md":Ke,"../../../docs/design/2026-11-11-cli-terminal-probe.md":Ve,"../../../docs/design/2026-11-11-external-tools.md":Je,"../../../docs/design/2026-11-11-feishu-channel-design.md":ze,"../../../docs/design/2026-11-11-session-serialization.md":Xe,"../../../docs/design/2026-11-12-llm-provider-model-routing.md":Ye,"../../../docs/design/agentic-simple-path-feasibility.md":$e,"../../../docs/design/channel-core-module-split.md":Ze,"../../../docs/design/napcat-deerfect-harness-integration-plan.md":er,"../../../docs/design/spring-ai-2-migration.md":rr,"../../../docs/design/super-agent-ideas-adopted.md":nr,"../../../docs/dev.md":tr,"../../../docs/features.md":ar,"../../../docs/guides/api.md":or,"../../../docs/guides/cli.md":sr,"../../../docs/guides/commit-convention.md":ir,"../../../docs/guides/context-optimization.md":lr,"../../../docs/guides/docker-deploy.md":cr,"../../../docs/guides/first-run-audit.md":dr,"../../../docs/guides/functional-testing.md":mr,"../../../docs/guides/knowledge-rag.en.md":pr,"../../../docs/guides/knowledge-rag.md":gr,"../../../docs/guides/mcp-tools.md":ur,"../../../docs/guides/models.md":vr,"../../../docs/guides/qq-channel.md":hr,"../../../docs/guides/resume.md":Sr,"../../../docs/guides/setup.md":Ar,"../../../docs/guides/sse-protocol.en.md":kr,"../../../docs/guides/sse-protocol.md":Cr,"../../../docs/guides/wms-mcp.md":_r,"../../../docs/index.md":Tr,"../../../docs/ops.md":Er,"../../../docs/principal.md":br,"../../../docs/project-structure.en.md":yr,"../../../docs/project-structure.md":fr,"../../../docs/reports/2026-10-05-knowledge-test-kit.md":Mr,"../../../docs/reports/2026-10-05-video-script.md":Pr,"../../../docs/reports/2026-11-11-knowledge-graph-report.md":Lr,"../../../docs/reports/2026-11-14-test-data-dependency-audit.md":xr,"../../../docs/reports/project-intro.md":Rr,"../../../docs/roadmap.md":Ir,"../../../docs/task-checklist/channel-health.md":jr,"../../../docs/task-checklist/delivery-outbox.md":Dr,"../../../docs/task-checklist/email-channel.md":wr,"../../../docs/task-checklist/feishu-channel.md":Nr,"../../../docs/task-checklist/feishu-inbound-expansion.md":Or,"../../../docs/task-checklist/llm-routing.md":Gr,"../../../docs/task-checklist/personal-agent-gap.md":Hr,"../../../docs/task-checklist/project-hardening.md":Br,"../../../docs/task-checklist/project-optimization.md":Fr,"../../../docs/task-checklist/readme.md":qr,"../../../docs/task-checklist/session-context-composition.md":Ur,"../../../docs/task-checklist/skill-optimization.md":Wr,"../../../docs/task-checklist/super-agent-adoption.md":Qr,"../../../docs/tech-stack.md":Kr}),zr=new Set(["reports/project-intro.md"]),le=[{key:"overview",label:"概览",dir:""},{key:"guides",label:"指南",dir:"guides"},{key:"design",label:"设计",dir:"design"},{key:"tasks",label:"任务清单",dir:"task-checklist"},{key:"reports",label:"报告",dir:"reports"}],ce={overview:"",guides:"guides",design:"design",tasks:"task-checklist",reports:"reports"};function Xr(t){const o="/docs/",i=t.lastIndexOf(o);return i>=0?t.slice(i+o.length):t.replace(/^\.\.\/+/,"")}function de(t){return t.replace(/^[\p{Extended_Pictographic}\uFE0F\u200D\s]+/u,"").trim()}function Yr(t,o){for(const i of t.split(/\r?\n/,80)){const s=i.trim();if(s.startsWith("# ")){const l=de(s.slice(2));if(l)return l}}return o.replace(/\.md$/i,"")}function $r(t){const o=t.replace(/^\uFEFF/,""),i=o.match(/^---\r?\n([\s\S]*?)\r?\n---[ \t]*\r?\n?/);if(!i)return{meta:{},body:o};const s={};for(const l of i[1].split(/\r?\n/)){const d=l.match(/^([A-Za-z_-]+):\s*(.*)$/);if(!d)continue;const p=d[1].toLowerCase();let m=d[2].trim();if((m.startsWith('"')&&m.endsWith('"')||m.startsWith("'")&&m.endsWith("'"))&&(m=m.slice(1,-1)),p==="title"&&m)s.title=m;else if(p==="order"){const C=Number(m);Number.isFinite(C)&&(s.order=C)}else p==="updated"&&m&&(s.updated=m)}return{meta:s,body:o.slice(i[0].length)}}function Zr(t){const o=t.includes("/")?t.split("/")[0]:"";return le.find(i=>i.dir===o)?.key??""}function en(t){return/\.en\.md$/i.test(t)?"en":"zh"}function rn(t,o){return o==="en"?t.replace(/\.en\.md$/i,""):t.replace(/\.md$/i,"")}const me=Object.entries(Jr).map(([t,o])=>{const i=Xr(t),s=i.split("/").pop()??i,l=en(s),{meta:d,body:p}=$r(o);return{path:i,name:s,title:de(d.title??"")||Yr(p,s),size:new TextEncoder().encode(p).length,group:Zr(i),order:d.order,lang:l,updated:d.updated??Vr[i],markdown:p}}).filter(t=>!zr.has(t.path)),O=new Map(me.map(t=>[t.path,t]));function ae(t){const o=t.toLowerCase();return o==="index.md"||o==="readme.md"}function nn(t){return(o,i)=>{if(t==="overview"){const d=o.order??Number.MAX_SAFE_INTEGER,p=i.order??Number.MAX_SAFE_INTEGER;if(d!==p)return d-p}const s=ae(o.name)?0:1,l=ae(i.name)?0:1;return s!==l?s-l:o.title.localeCompare(i.title,"zh-Hans-CN")}}function pe(t){return{path:t.path,name:t.name,title:t.title,size:t.size,group:t.group,order:t.order,lang:t.lang,updated:t.updated}}const q=le.map(t=>{const o=new Map;for(const s of me){if(s.group!==t.key)continue;const l=rn(s.name,s.lang),d=o.get(l)??{};s.lang==="en"?d.en=s:d.zh=s,o.set(l,d)}const i=Array.from(o.values()).map(({zh:s,en:l})=>{const d=s??l;if(!d)return null;const p=pe(d);return s&&l?{...p,enPath:l.path}:p}).filter(s=>s!==null).sort(nn(t.key));return i.forEach((s,l)=>{s.order==null&&(s.order=l+1)}),{key:t.key,label:t.label,count:i.length,docs:i}}),tn=new Map(q.map(t=>[t.key,t.label])),J=new Map;for(const t of q)for(const o of t.docs)o.order!=null&&J.set(o.path,o.order),o.enPath&&o.order!=null&&J.set(o.enPath,o.order);function x(t){const o=O.get(t);if(!o)return;const i=pe(o),s=J.get(t);return s!=null?{...i,order:s}:i}function oe(t){return O.get(t)?.markdown??null}function se(t){const o=O.get(t);return o?tn.get(o.group)??"文档":"文档"}function an(t){return O.get(t)?.group??""}function on(t,o){const i=o.split(/[?#]/)[0];if(/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(i)||i.startsWith("#"))return i;const s=t.split("/").slice(0,-1);for(const l of i.split("/"))!l||l==="."||(l===".."?s.pop():s.push(l));return s.join("/")}function sn(t,o){if(o<0){const d=t.replace(/\s+/g," ").trim();return d.length<=120?d:`${d.slice(0,120)}…`}const i=Math.max(0,o-60),s=Math.min(t.length,o+60),l=t.slice(i,s).replace(/\s+/g," ").trim();return`${i>0?"…":""}${l}${s<t.length?"…":""}`}function ln(t){const o=t.trim().toLowerCase();if(!o)return[];const i=[];for(const s of q)for(const l of s.docs){const d=O.get(l.path);if(!d)continue;const p=d.markdown.toLowerCase().indexOf(o),m=l.title.toLowerCase().includes(o),C=l.name.toLowerCase().includes(o);if(!(p<0&&!m&&!C)&&(i.push({path:l.path,title:l.title,group:s.label,snippet:sn(d.markdown,p)}),i.length>=20))return i}return i}function ie(t,o,i){const s=ce[t]??"",l=i.trim()||o.replace(/\.md$/i,""),d=`---
title: "${l}"
---

# ${l}

> 由 Web 文档中心生成 · ${new Date().toISOString().slice(0,10)}

## 概述

（待补充）
`;return{path:s?`${s}/${o}`:o,markdown:d}}function cn(t){return/^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9._-]*\.md$/u.test(t.trim())&&!t.includes("..")}const dn={class:"docs-view","aria-label":"文档"},mn={class:"docs-head"},pn={class:"docs-tools"},gn={class:"docs-search"},un={key:0,class:"res-alert",role:"alert"},vn={key:1,class:"res-note",role:"status"},hn={class:"docs-create-grid"},Sn={class:"docs-field"},An=["value"],kn={class:"docs-field"},Cn={class:"docs-field"},_n={class:"docs-create-foot"},Tn={class:"docs-create-path"},En={class:"docs-shell"},bn={class:"docs-tree","aria-label":"文档目录"},yn={class:"docs-tree-head"},fn={class:"docs-tree-total"},Mn={class:"docs-tree-scroll"},Pn={key:0,class:"docs-tree-empty"},Ln=["onClick"],xn={class:"docs-hit-title"},Rn={class:"docs-hit-meta"},In={class:"docs-hit-snippet"},jn={class:"docs-tree-scroll"},Dn=["aria-expanded","onClick"],wn={class:"docs-group-count"},Nn={class:"docs-group-items"},On=["title","onClick"],Gn={class:"docs-item-order"},Hn={class:"docs-item-title"},Bn=["title","onClick"],Fn={key:0,class:"docs-main"},qn={class:"docs-main-head"},Un={class:"docs-crumb"},Wn={class:"docs-crumb-title"},Qn=["aria-expanded"],Kn={key:0,id:"docs-section-menu",class:"docs-menu-panel",role:"menu","aria-label":"文档章节"},Vn=["data-section","aria-current","onClick"],Jn={class:"docs-doc-title"},zn={class:"docs-doc-meta","aria-label":"文档元信息"},Xn={class:"docs-doc-meta-strong"},Yn=["innerHTML"],$n={class:"docs-foot"},Zn={class:"res-mono"},et={key:1,class:"docs-empty"},nt=xe({__name:"DocsView",props:{initialPath:{}},emits:["select"],setup(t,{emit:o}){const i=t,s=o,l=q,d=k(new Set(l.slice(1).map(r=>r.key))),p=k(""),m=k(null),C=k(""),_=k(""),y=k(""),f=k(null),U=N(()=>C.value.trim()?ln(C.value):[]);function ge(r){return r.replace(/^\uFEFF/,"").replace(/^\s*#\s+[^\r\n]*\r?\n/,"")}const z=N(()=>{if(!m.value)return{html:"",sections:[]};const r=Ge.parse(ge(m.value.markdown),{gfm:!0,breaks:!1}),e=He.sanitize(typeof r=="string"?r:""),a=new DOMParser().parseFromString(e,"text/html"),c=[];return a.querySelectorAll("h2").forEach((S,E)=>{const Q=`doc-sec-${E}`;S.id=Q;const j=(S.textContent??"").replace(/^[\p{Extended_Pictographic}\uFE0F\u200D\s]+/u,"").trim();j&&c.push({id:Q,text:j})}),{html:a.body.innerHTML,sections:c}}),X=N(()=>z.value.html),G=N(()=>z.value.sections);function ue(r){const e=x(r);return e?Ne(e.size):"—"}function Y(r){return r.order==null?"":String(r.order).padStart(2,"0")}function ve(r){return r.path===p.value||r.enPath!==void 0&&r.enPath===p.value}function he(r){r.enPath&&P(p.value===r.enPath?r.path:r.enPath)}const A=k(!1),R=k(null),$=k(null),M=k("");let I=0;function Se(){G.value.length!==0&&(A.value=!A.value,A.value&&K(()=>{const r=R.value?.querySelector(".docs-menu-item.is-active"),e=R.value?.querySelector(".docs-menu-item");(r??e)?.focus()}))}function W(r=!1){A.value&&(A.value=!1,r&&$.value?.focus())}function Ae(r){W(!0);const e=f.value?.querySelector(`#${r.id}`);e&&(M.value=r.id,e.scrollIntoView({behavior:"smooth",block:"start"}))}function ke(r){const e=Array.from(R.value?.querySelectorAll(".docs-menu-item")??[]);if(e.length===0)return;const a=e.findIndex(c=>c===document.activeElement);if(a<0){const c=e.findIndex(S=>S.classList.contains("is-active"));e[Math.max(0,c)]?.focus();return}e[(a+r+e.length)%e.length]?.focus()}function Z(r){if(A.value){if(r.key==="Escape"){r.preventDefault(),W(!0);return}(r.key==="ArrowDown"||r.key==="ArrowUp")&&(r.preventDefault(),ke(r.key==="ArrowDown"?1:-1))}}function ee(r){if(!A.value)return;const e=R.value;e&&!e.contains(r.target)&&W()}function H(){I||(I=requestAnimationFrame(()=>{I=0,re()}))}function Ce(){const r=f.value;if(!r)return null;const e=getComputedStyle(r);if((e.overflowY==="auto"||e.overflowY==="scroll")&&r.scrollHeight>r.clientHeight+1)return r;let a=r.parentElement;for(;a;){const c=getComputedStyle(a);if((c.overflowY==="auto"||c.overflowY==="scroll")&&a.scrollHeight>a.clientHeight+1)return a;a=a.parentElement}return null}function re(){const r=f.value,e=G.value;if(!r||e.length===0){M.value="";return}const a=Ce(),c=a?.getBoundingClientRect().top??0,S=a===r?c+84:c+140;let E=e[0].id;for(const j of e){const ne=r.querySelector(`#${j.id}`);if(ne)if(ne.getBoundingClientRect().top<=S)E=j.id;else break}(a?a.scrollTop+a.clientHeight>=a.scrollHeight-4:window.scrollY+window.innerHeight>=document.documentElement.scrollHeight-4)&&(E=e[e.length-1].id),M.value=E}te(X,()=>{A.value=!1,M.value="",K(()=>re())});function P(r,e=!0){const a=oe(r),c=x(r);if(!a||!c){y.value=`文档不存在：${r}`;return}y.value="",_.value="",p.value=r,m.value={...c,markdown:a},localStorage.setItem("ui-last-doc",r);const S=an(r);if(d.value.has(S)){const E=new Set(d.value);E.delete(S),d.value=E}e&&s("select",r),K(()=>{f.value?.scrollTo&&f.value.scrollTo({top:0})})}function _e(r){const e=new Set(d.value);e.has(r)?e.delete(r):e.add(r),d.value=e}function Te(){const r=i.initialPath.trim();if(r&&x(r))return r;const e=localStorage.getItem("ui-last-doc")??"";return e&&x(e)?e:l[0]?.docs[0]?.path??""}Re(()=>{document.addEventListener("pointerdown",ee,!0),document.addEventListener("keydown",Z),document.addEventListener("scroll",H,!0),window.addEventListener("resize",H);const r=i.initialPath.trim(),e=Te();e&&(P(e,!1),r&&r!==e&&(s("select",e),_.value=`地址中的文档不在目录内：${r}，已打开「${x(e)?.title??e}」`))}),Ie(()=>{document.removeEventListener("pointerdown",ee,!0),document.removeEventListener("keydown",Z),document.removeEventListener("scroll",H,!0),window.removeEventListener("resize",H),I&&cancelAnimationFrame(I)}),te(()=>i.initialPath,r=>{if(!(!r||r===p.value)){if(x(r)){P(r,!1);return}s("select",p.value),_.value=`地址中的文档不在目录内：${r}`}});function Ee(r){const e=r.target.closest("a");if(!e)return;const a=e.getAttribute("href")??"";if(!a)return;if(/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(a)||a.startsWith("mailto:")){e.target="_blank",e.rel="noopener noreferrer";return}if(!a.split(/[?#]/)[0].toLowerCase().endsWith(".md"))return;r.preventDefault();const c=on(p.value,a);if(oe(c)==null){_.value=`「${c}」是历史存档，未打包进前端（archive/ 不参与目录）`;return}P(c)}async function be(){_.value="";try{await navigator.clipboard.writeText("docs/"),_.value="已复制 docs/（仓库根下）；浏览器不能直接打开系统目录，粘贴到资源管理器即可"}catch{_.value="docs/ 位于仓库根下；当前浏览器不允许写剪贴板"}}const B=k(!1),h=Oe({group:"overview",filename:"",title:""}),ye=l.map(r=>({key:r.key,label:r.label,dir:ce[r.key]??""})),fe=N(()=>{const r=h.filename.trim()||"untitled.md";return ie(h.group,r,h.title).path});function Me(){B.value=!0,y.value="",_.value="",h.group="overview",h.filename="",h.title=""}function Pe(r,e){const a=new Blob([e],{type:"text/markdown;charset=utf-8"}),c=URL.createObjectURL(a),S=document.createElement("a");S.href=c,S.download=r,S.click(),setTimeout(()=>URL.revokeObjectURL(c),0)}function Le(){y.value="";const r=h.filename.trim();if(!cn(r)){y.value="文件名需以 .md 结尾，只允许中文、字母、数字、点、下划线或短横线";return}const e=ie(h.group,r,h.title);Pe(r,e.markdown),B.value=!1,_.value=`模板已下载：${e.path}。放进仓库 docs/ 后刷新页面即可出现在左侧目录。`}return(r,e)=>(u(),v("section",dn,[n("header",mn,[e[6]||(e[6]=n("div",{class:"docs-head-text"},[n("span",{class:"docs-eyebrow"},"文档中心"),n("h1",{class:"docs-title"},"文档"),n("p",{class:"docs-sub"},"docs/ 目录下的工程文档 · 按分组索引")],-1)),n("div",pn,[n("label",gn,[e[5]||(e[5]=n("svg",{viewBox:"0 0 24 24","aria-hidden":"true"},[n("circle",{cx:"11",cy:"11",r:"7"}),n("path",{d:"m20 20-3.5-3.5"})],-1)),D(n("input",{"onUpdate:modelValue":e[0]||(e[0]=a=>C.value=a),type:"search",placeholder:"搜索文档名或内容…","aria-label":"搜索文档"},null,512),[[V,C.value]])]),n("button",{class:"res-btn",type:"button",title:"复制 docs/ 目录路径",onClick:be}," 复制目录路径 "),n("button",{class:"res-btn res-btn-primary",type:"button","data-testid":"docs-create",onClick:Me}," + 新建文档 ")])]),y.value?(u(),v("p",un,g(y.value),1)):_.value?(u(),v("p",vn,g(_.value),1)):b("",!0),B.value?(u(),v("form",{key:2,class:"docs-create",onSubmit:je(Le,["prevent"])},[e[11]||(e[11]=n("div",{class:"docs-create-head"},[n("h3",null,"新建文档"),n("span",null,"浏览器不写 docs/：生成模板后下载，放进对应目录即可出现在左侧")],-1)),n("div",hn,[n("label",Sn,[e[7]||(e[7]=n("span",null,"分组",-1)),D(n("select",{"onUpdate:modelValue":e[1]||(e[1]=a=>h.group=a),class:"res-input","aria-label":"文档分组"},[(u(!0),v(T,null,w(F(ye),a=>(u(),v("option",{key:a.key,value:a.key},g(a.label)+g(a.dir?` · ${a.dir}/`:" · docs 根层"),9,An))),128))],512),[[De,h.group]])]),n("label",kn,[e[8]||(e[8]=n("span",null,"文件名",-1)),D(n("input",{"onUpdate:modelValue":e[2]||(e[2]=a=>h.filename=a),class:"res-input",placeholder:"my-note.md",required:""},null,512),[[V,h.filename]])]),n("label",Cn,[e[9]||(e[9]=n("span",null,"标题（可选）",-1)),D(n("input",{"onUpdate:modelValue":e[3]||(e[3]=a=>h.title=a),class:"res-input",placeholder:"留空则用文件名"},null,512),[[V,h.title]])])]),n("div",_n,[n("span",Tn,"落盘位置：docs/"+g(fe.value),1),n("button",{class:"res-btn",type:"button",onClick:e[4]||(e[4]=a=>B.value=!1)},"取消"),e[10]||(e[10]=n("button",{class:"res-btn res-btn-primary",type:"submit"},"下载模板",-1))])],32)):b("",!0),n("div",En,[n("aside",bn,[C.value.trim()?(u(),v(T,{key:0},[n("div",yn,[e[12]||(e[12]=n("span",null,"搜索结果",-1)),n("span",fn,g(U.value.length),1)]),n("div",Mn,[U.value.length===0?(u(),v("p",Pn,"没有匹配「"+g(C.value.trim())+"」的文档",1)):b("",!0),(u(!0),v(T,null,w(U.value,a=>(u(),v("button",{key:a.path,class:"docs-hit",type:"button",onClick:c=>P(a.path)},[n("span",xn,g(a.title),1),n("span",Rn,g(a.group)+" · "+g(a.path),1),n("span",In,g(a.snippet),1)],8,Ln))),128))])],64)):(u(),v(T,{key:1},[e[14]||(e[14]=n("div",{class:"docs-tree-head"},[n("span",null,"目录")],-1)),n("div",jn,[(u(!0),v(T,null,w(F(l),a=>(u(),v("div",{key:a.key,class:"docs-group"},[n("button",{class:"docs-group-row",type:"button","aria-expanded":!d.value.has(a.key),onClick:c=>_e(a.key)},[(u(),v("svg",{class:L(["docs-chevron",{"is-collapsed":d.value.has(a.key)}]),viewBox:"0 0 24 24","aria-hidden":"true"},[...e[13]||(e[13]=[n("path",{d:"m6 9 6 6 6-6"},null,-1)])],2)),n("span",null,g(a.label),1),n("span",wn,g(a.count),1)],8,Dn),D(n("div",Nn,[(u(!0),v(T,null,w(a.docs,c=>(u(),v("div",{key:c.path,class:L(["docs-item",{"is-active":ve(c)}])},[n("button",{class:"docs-item-main",type:"button",title:c.path,onClick:S=>P(c.path)},[n("span",Gn,g(Y(c)),1),n("span",Hn,g(c.title),1)],8,On),c.enPath?(u(),v("button",{key:0,class:L(["docs-item-lang",{"is-on":p.value===c.enPath}]),type:"button",title:p.value===c.enPath?"切换到中文版":"打开英文版",onClick:S=>he(c)},g(p.value===c.enPath?"中":"EN"),11,Bn)):b("",!0)],2))),128))],512),[[we,!d.value.has(a.key)]])]))),128))])],64))]),m.value?(u(),v("div",Fn,[n("div",qn,[n("div",Un,[n("span",null,g(F(se)(m.value.path)),1),e[15]||(e[15]=n("span",{class:"docs-crumb-sep"},"/",-1)),n("span",Wn,g(m.value.title),1)]),G.value.length>0?(u(),v("div",{key:0,ref_key:"menuEl",ref:R,class:"docs-menu"},[n("button",{ref_key:"pillEl",ref:$,class:L(["docs-menu-pill",{"is-open":A.value}]),type:"button","aria-haspopup":"menu","aria-expanded":A.value,"aria-controls":"docs-section-menu",onClick:Se},[e[17]||(e[17]=n("span",null,"本页目录",-1)),(u(),v("svg",{class:L(["docs-menu-chevron",{"is-open":A.value}]),viewBox:"0 0 24 24","aria-hidden":"true"},[...e[16]||(e[16]=[n("path",{d:"m6 9 6 6 6-6"},null,-1)])],2))],10,Qn),A.value?(u(),v("div",Kn,[(u(!0),v(T,null,w(G.value,a=>(u(),v("button",{key:a.id,class:L(["docs-menu-item",{"is-active":a.id===M.value}]),type:"button",role:"menuitem","data-section":a.id,"aria-current":a.id===M.value?"true":void 0,onClick:c=>Ae(a)},g(a.text),11,Vn))),128))])):b("",!0)],512)):b("",!0)]),n("article",{ref_key:"articleEl",ref:f,class:"docs-article",onClick:Ee},[n("h2",Jn,g(m.value.title),1),n("div",zn,[n("span",Xn,"docs/"+g(m.value.path),1),e[20]||(e[20]=n("span",{class:"docs-doc-meta-sep"},"|",-1)),n("span",null,"group "+g(F(se)(m.value.path)),1),m.value.order!=null?(u(),v(T,{key:0},[e[18]||(e[18]=n("span",{class:"docs-doc-meta-sep"},"|",-1)),n("span",null,"order "+g(Y(m.value)),1)],64)):b("",!0),e[21]||(e[21]=n("span",{class:"docs-doc-meta-sep"},"|",-1)),n("span",null,"lang "+g(m.value.lang),1),m.value.updated?(u(),v(T,{key:1},[e[19]||(e[19]=n("span",{class:"docs-doc-meta-sep"},"|",-1)),n("span",null,"更新 "+g(m.value.updated),1)],64)):b("",!0)]),n("div",{class:"docs-md",innerHTML:X.value},null,8,Yn),n("footer",$n,[n("span",Zn,g(m.value.path),1),n("span",null,g(ue(m.value.path))+" · 随前端构建打包，不经过后端",1)])],512)])):(u(),v("div",et,"左侧选择一篇文档"))])]))}});export{nt as default};
