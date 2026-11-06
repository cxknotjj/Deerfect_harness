# 📁 项目结构

> README「项目结构」一节的完整版：多模块布局与 server 完整目录树、逐文件说明。

---

## Maven 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `harness-api` | 渠道与 core 共用的契约层：端口接口（`service/`）、领域模型与状态机（`domain/`）、对外 DTO（`view/`）、共享枚举、`ReplyPostProcessor` SPI | 无同反应堆依赖 |
| `server` | Spring Boot 主服务、core 实现与 fat jar 宿主 | compile → `harness-api`；runtime → `channel-qq` |
| `channel-qq` | QQ 渠道（NapCat/OneBot 11）：客户端/事件/回复/持久化；事件准入 `ChannelEventPolicy` + 语义层端口 | compile → `harness-api` |
| `cli` | 终端客户端，纯 HTTP + SSE | compile → `harness-api` |
| `wms-mcp-adapter` | 领域 MCP 适配范例 | 独立 |

> 规则：core（server）编译期不得引用 `channel.qq`（enforcer 守护 `com.dark:channel-qq:*:*:compile`）；渠道新增输出控制走 `ReplyPostProcessor`，主动外发走 `MessageSender`（harness-api 端口，实现由渠道装配），两者都不改 core。详见 [`docs/design/channel-core-module-split.md`](./design/channel-core-module-split.md)。

---

采用经典分层架构（Controller → Service → Mapper/Entity），领域模型统一收纳在 `domain` 父包下。以下目录树为 **`server` 模块**（`server/src/main/java/com/dark/javaHarness/`）：

<details>
<summary><b>📂 点击展开完整目录树</b></summary>

```text
server/src/main/java/com/dark/javaHarness/
├── JavaHarnessApplication.java   # Spring Boot 启动类（@MapperScan("com.dark.javaHarness.mapper") 仅扫 core；渠道 mapper 由 channel-qq 自身扫描）
├── controller/                   # 表现层：REST 接口 + SSE 流式
│   ├── ChatController.java       # 聊天接口（/api/chat、/stream、/resume、/goal-status）
│   ├── HarnessController.java    # 管理接口（agents / submit / goals / sessions / 会话绑定 Agent）
│   ├── ScheduleController.java   # 定时任务管理（/api/schedules 列表 / 创建 / 删除）
│   ├── ProviderAdminController.java  # 模型映射管理（/api/providers 查看与热刷新新增）
│   ├── KnowledgeAdminController.java # 知识库管理（文档列表/上传/删除/增量同步）
│   ├── MonitorController.java    # 运行监控（/api/monitor 汇总快照）
│   ├── SkillController.java      # 技能只读校验（扫描结果/索引）
│   ├── ContextCompositionController.java # 上下文构成快照查询
│   ├── LlmCallController.java    # LLM 调用观测查询（/api/llm-calls）
│   ├── ToolCallController.java   # 工具调用观测查询（/api/tool-calls）
│   └── KbRetrievalController.java # RAG 检索观测查询（/api/kb-retrievals）
├── service/                      # 业务层（接口 + impl/ 实现）
│   ├── GoalService.java          # 目标台账（AgentService/SessionService/ChatService/SessionBindingCleaner 端口已迁 harness-api）
│   ├── ScheduleService.java      # 定时任务：定义 + 到点派发（复用 AgentService.submit，不另建执行链）
│   ├── RouteJudge.java           # 主 Agent 路由判断（Agentic / Graph 路径决策）
│   ├── AgentConfigProvider.java  # 从 agent 表读取运行配置（路由映射；配置读失败与「未配置」语义分离）
│   ├── ProviderAdminService.java # model_provider 映射管理（新增即热刷新注册表）
│   └── impl/                     # AgentServiceImpl / ChatServiceImpl / SessionServiceImpl / GoalServiceImpl / ProviderAdminServiceImpl / SseEncoder 等
│       ├── ChatServiceImpl.java  # 聊天门面（仅 chat/streamReactive/resume 分发）
│       ├── ChatTurnExecutor.java / ChatStreamPipeline.java  # 同步执行段 / 流式与 SSE 段
│       ├── RouteDecider.java / TurnPersistence.java / ReplyDecorator.java  # 路由判定与预取汇合 / 会话建档与写回 / 回复装饰
│       ├── ScheduleServiceImpl.java  # 定时任务：到期扫描 → 提交 goal → 推进 next_run_at（失败也推进，防卡调度器）
│       ├── ActiveGoalSectionProviderImpl.java  # 活跃目标段渲染（objective 截断 200 字 + 天数 + /goal done 提示）
│       ├── GoalRetryScheduler.java  # 失败重投：扫 next_retry_at 到期的 FAILED goal 重新提交（退避 1→2 分钟，上限 2 次）
│       ├── SessionHistoryArchiveService.java  # 会话归档检索（recall_history 数据源；current 按会话过滤 / all 跨会话按时间倒序）
│       └── route/                # LlmRouteJudge（主 Agent 路由判定）/ LlmSemanticTriggerJudge（渠道路由语义判定）
├── advisor/                      # Spring AI Advisor 拦截器（Agent 流程横切管理）
│   ├── ContextAssemblingAdvisor.java  # 上下文组装：过滤/token 预算截断/role 归一化
│   ├── PromptBudgetAdvisor.java  # Prompt 分段预算（历史/user/工具结果三段裁剪）
│   └── ContextCompositionAdvisor.java # 上下文构成快照采集（请求结束后记账）
├── config/                       # 应用配置
│   ├── GoalExecutorConfig.java   # 执行线程池：goal-exec- 后台 Goal 池 + mvc-async- MVC 异步槽位
│   ├── StreamConnectionLimiter.java  # 流式连接数限流（超限 429；终止信号传播前归还名额）
│   ├── ChatTimeoutProperties.java    # 模型调用超时（首帧/空闲/读超时）
│   ├── AgenticProperties.java    # AgentScope 路径开关（回退开关/最大迭代/重试）
│   ├── OpenAiClients.java        # OpenAI 兼容客户端构建（连接池与超时）
│   ├── ContextBudgetProperties.java  # 上下文预算统一配置（app.context.*，yaml 为唯一数值源）
│   ├── logging/                  # 日志与 MDC 传播
│   │   ├── TraceIdFilter.java    # 请求级 traceId 写入 MDC
│   │   ├── ContextPropagationConfig.java # 响应式上下文（MDC）跨线程传播
│   │   ├── ClientAbortLogFilter.java # 客户端断连噪声降级
│   │   └── LogNoiseConfig.java   # 第三方库日志降噪（TurboFilter）
│   ├── security/                 # API 鉴权（双通道）
│   │   ├── SecurityConfig.java   # 过滤器链装配
│   │   ├── ApiTokenFilter.java   # X-API-Token 常量时间比较 + 登录 Cookie 校验
│   │   ├── LoginStateStore.java  # 内存登录态（滑动续期/失败锁定）
│   │   └── AuthController.java   # /api/auth/login|logout
│   ├── knowledge/                # RAG 知识库装配
│   │   ├── KnowledgeProperties.java  # RAG 知识库配置载体（app.knowledge.*）
│   │   └── KnowledgeConfig.java      # 知识库装配：向量库数据源/嵌入模型/PgVectorStore（条件装配+懒连接）
│   ├── datasource/               # 数据源与 MyBatis-Plus 装配
│   │   ├── PrimaryDataSourceConfig.java  # 主库（MySQL）显式声明（@Primary，多数据源下 Flyway/MyBatis 归属）
│   │   └── MybatisPlusConfig.java    # MyBatis-Plus 分页等配置
│   └── agent/                    # Agent 配置与装配
│       ├── ChatAgentConfig.java  # 注册各 Agent bean + 编排检查点存储（OrchestrationCheckpointStore）
│       ├── ChatClientFactory.java    # 按服务商构建 OpenAI 兼容 ChatClient（Registry 模式）
│       ├── ChatClientRegistry.java   # 模型名 → ChatClient 注册表（支持热刷新）
│       └── ThinkingSwitchChatModel.java  # 按 model_provider.disable_thinking 注入思考开关
├── prompt/                       # Prompt 组装管线（两路径统一）
│   ├── PromptAssembler.java      # 分段式 system prompt 组装（角色/画像/工具索引/纪律/输出约定/信道规范/skill）
│   ├── SystemAssembly.java       # system 段组装结果（文本 + 各段 token，快照与请求共用）
│   ├── MemoryPolicy.java         # 会话记忆按角色注入矩阵
│   ├── ActiveGoalSectionProvider.java  # 活跃目标段扩展点（按会话取跨轮目标，无则空段）
│   ├── SkillManager.java / SkillRepository.java  # Markdown 技能库动态装配（按需注入 system）
│   ├── ToolLazyManager.java      # 工具 Schema 两段式延迟加载（轻量索引 → expand_tool 展开）
│   ├── ContextCompositionCalculator.java / ContextCompositionRecorder.java / ContextCompositionStore.java  # 上下文构成快照：占比计算 / 写入 / 短 TTL 存储
│   └── PromptSection.java / SkillSectionProvider.java  # 段模型与 skill 扩展点
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / SessionMessageArchiveMapper / ModelProviderMapper
│       / LlmCallLogMapper / ToolCallLogMapper / KbRetrievalLogMapper / McpServerLogMapper / KbDocumentMapper
│       / OrchestrationCheckpointMapper / ScheduleMapper
│       （OneBot 会话绑定 Mapper 在 channel-qq 模块）
├── domain/                       # 领域模型（父包）
│   ├── RouteDecision.java        # 路由决策枚举（SIMPLE / COMPLEX）
│   ├── RoutePlan.java            # 路径判定结果（agentic/graph + 置信度 + 来源）
│   ├── LlmCallLog.java           # 一次 LLM 调用的观测记录（耗时/token/成败）
│   ├── KbRetrievalLog / McpServerLog / ToolCallLog  # 其余观测记录
│   └── entity/                   # 数据库实体：AgentEntity / GoalEntity / SessionEntity / SessionMessageEntity
│                                 #   / SessionMessageArchiveEntity / ModelProviderEntity / KbDocumentEntity
│                                 #   / LlmCallLogEntity / ToolCallLogEntity / KbRetrievalLogEntity / McpServerLogEntity
│                                 #   / ScheduleEntity
│   └── dto/                      # 服务端内部视图 DTO：MonitorSnapshot / RuntimeMetrics / ComponentStatus / ContextComposition
│                                 #   （对外契约 DTO 在 harness-api 的 view/ 包）
├── enums/                        # 共享常量：AgentConstants（GoalStatus / SseProtocol 已迁 harness-api）
├── exception/                    # 全局异常处理与领域异常
│   ├── GlobalExceptionHandler.java   # @RestControllerAdvice，统一 {code, message}
│   └── ConcurrentRequestException / ResumeConflictException / ModelAuthException / ModelQuotaException
├── agent/                        # Agent 抽象、编排与 LLM 调用
│   ├── Agent.java / AgentRegistry.java    # Agent 接口与注册表
│   ├── AgentScopeGeneralAgent.java # Agentic 路径：AgentScope ReAct 显式推理-行动循环
│   ├── agentic/                    # Agentic 路径适配层
│   │   ├── AgentScopeModelFactory.java  # model_provider 行 → AgentScope OpenAI 兼容 Model
│   │   └── SpringAiToolBridge.java      # Spring AI ToolCallback → AgentScope ToolBase（MCP/沙箱工具复用）
│   ├── GeneralAssistantAgent.java  # Agentic 路径回退：ChatClient 单模型对话（真·逐 token stream）
│   ├── AgentChatCaller.java        # LLM 调用生命周期编排门面（call/stream 重试循环、取消拦截、观测记录）
│   ├── AgentChatPipeline.java      # 流管道核（streamAttempt/streamCore/tokenStream + watchdog 空闲超时/流帧记账/空响应防御）
│   ├── AggregateStreamGuard.java   # 聚合流式护栏（失败自愈与必发策略）
│   ├── BudgetLedger.java           # 编排预算账本契约（roundtrip 增量记账 + 熔断异常）
│   ├── CallCancellation.java       # 取消词汇表（取消异常的单一构造口径）
│   ├── LlmErrorClassifier.java     # 模型错误分类纯函数（账户级硬错误/未知名工具调用）
│   ├── SubtaskResults.java         # 子任务结局常量（graph 与 agent 共用，避免 agent 反向依赖 graph）
│   ├── CallContext.java            # 观测值对象（llm_call_log 落库参数收敛，call/stream 两通道共用）
│   ├── CallSpecAssembler.java      # 角色装配策略（MemoryPolicy 记忆判定/maxTokens 档位/观测名单计算）
│   ├── AgentRequestSpecFactory.java  # 双路径共用请求组装工厂（system/记忆注入/工具装饰/输出档位）
│   ├── LlmRetry.java               # LLM 调用重试策略
│   ├── RagPrefetcher.java          # RAG 入口预取共享池（入口与编排侧复用；kb 绑定解析内聚）
│   ├── ProgressLine.java           # 进度行线协议（MARK+stage+SEP+detail）编解码
│   └── BranchProgressListener.java # 子任务结局播报（并行完成事件串行发射；与 Graph 共用）
├── graph/                        # Graph 编排路径（Lead → 专家并行 → 聚合）
│   ├── MultiAgentGraphAgent.java   # Graph 编排门面（执行/流式/续跑入口；引擎见 engine/ParallelOrchestrationEngine）
│   ├── OrchestrationNodes.java     # 编排三节点实现（lead/subtask/aggregate + predict* 桥接 + 流式聚合护栏）
│   ├── OrchestrationPrompts.java   # 编排各环节兜底提示词（lead/aggregator/子任务 persona）
│   ├── LeadOutputParser.java       # lead 拆解 JSON 解析（子任务数 + 专家指派白名单）
│   ├── OrchestrationBudget.java    # 编排预算账本（AtomicLong 共享记账 + 降级说明）
│   ├── MultiAgentStreamPipeline.java  # 编排流式管道（进度行 → SSE 事件装配）
│   ├── ThinkingThrottle.java       # 思考增量合帧节流器
│   └── engine/                     # OrchestrationEngine / ParallelOrchestrationEngine / OrchestrationState / EngineEvent
├── state/                        # 执行态与断点续跑
│   ├── OrchestrationStage.java / OrchestrationCheckpoint.java / CheckpointSelector.java
│   ├── OrchestrationStateKeys.java  # 编排状态 Map 的键常量（引擎与节点共用的字符串键单一来源）
│   └── persistence/              # OrchestrationCheckpointStore / OrchestrationCheckpointEntity
├── observability/                # 观测（记录器 + 查询 + 请求日志）
│   ├── LlmCallRecorder / KbRetrievalRecorder / McpServerRecorder  # 三表异步落库
│   ├── LlmCallObserver / LlmRequestLogAdvisor  # 调用侧口径与请求发起观测
│   ├── ObserveQueryService / ObserveQueryServiceImpl  # 三表查询收口（controller 不直连 mapper）
│   ├── HarnessMetrics            # 指标埋点（路径判定 / LLM 台账 / 流控 / 编排降级）
│   ├── MonitorService / ComponentStatusService / RuntimeMetricsService  # 运行监控聚合（模型、工具、组件状态、JVM 指标）
│   ├── ObservabilityLogCleaner  # 观测表保留期清理
│   └── StaleGoalWatchdog       # Goal 停滞看门狗（RUNNING 无进展 → FAILED，默认关闭）
├── validation/                   # 纯规则（无状态、不查库）
│   ├── FileNameRules.java        # 上传文件名/大小/kb 命名
│   └── ProviderRules.java        # provider/apiUrl/模型名格式
├── knowledge/                    # RAG 知识库
│   ├── KnowledgeDocumentScanner.java  # 知识目录扫描（.md/.txt、front-matter 标题、坏文件跳过）
│   ├── MarkdownChunker.java      # 段落感知切分（~700 字符 + 重叠，纯函数）
│   ├── KnowledgeService.java / KnowledgeServiceImpl.java  # 对外门面（委托读写协作者 + 启动自动摄取）
│   ├── KnowledgeIngestor.java    # 写路径：增量摄取（mtime+kb 比对删旧写新）/ 孤儿清理 / 删除 / 分页
│   ├── HybridKnowledgeRetriever.java  # 读路径：向量 + BM25 + RRF 融合（不依赖写路径）
│   ├── KnowledgeBm25Index.java   # BM25 内存索引（懒重建，混合检索第二路）
│   ├── KnowledgeDirectoryWatcher.java # 知识目录监听（去抖后触发增量 sync）
│   ├── KnowledgeSyncView.java    # 增量同步结果（扫描/更新/跳过/chunk 数）
│   └── KnowledgeRetriever.java   # 检索注入器：user 文本→top-k 命中→【出处N】知识段（预算截断）
├── channel/qq/                   # 已整体迁出为独立模块 `channel-qq`（NapCat/OneBot 11，napcat.enabled 条件装配，单向依赖 core 端口）
├── message/                      # 主动推送（任务结果 / 失败告警外发）
│   └── MessageSenderRegistry.java  # channel:target 路由到渠道实现；未注册渠道与实现违约一律 false
├── memory/                       # 用户画像（跨会话长期记忆）
│   ├── UserProfileService.java   # 增量提炼空闲会话的新消息 → 按 key 合并进全局画像（补丁式，非全文重写；原子写 + .bak）
│   └── UserProfileSectionProviderImpl.java  # 画像段注入 general/lead 的 system prompt
├── cli/                          # 已独立为 `cli` 模块（`cli/src/main/java/com/dark/javaHarness/cli`，纯 HTTP 连 8080）
├── tool/                         # 工具层
    ├── WebTools.java             # 网页抓取门面（fetchUrl：抓取 + 30min 内容缓存 + 相关段落裁剪）
    ├── HtmlToMarkdown.java / ContentRelevance.java  # 噪声剔除/主内容 Markdown 化 / 按查询意图裁剪
    ├── SandboxToolProvider.java  # 容器级沙箱工具（Python/Shell/文件 + 浏览器，懒初始化、失败降级、健康探测自愈）
    ├── SandboxShellTools.java / SandboxFileTools.java / SandboxBrowserTools.java / SandboxToolSupport.java
    │                             # 沙箱工具封装（执行 / 文件读写 / 浏览器 / 统一结果截断）
    ├── SandboxGuards.java        # 沙箱边界守卫：写入路径限 /workspace、Shell 破坏性命令拦截
    ├── McpToolProvider.java / McpConfigParser.java  # MCP 工具接入（多 server 懒连接、失败隔离）/ mcp-config.json 解析
    ├── McpServerTools.java       # 内置 MCP Server 暴露的演示工具（Streamable-HTTP /mcp）
    ├── ToolAssignments.java      # 工具分配表：按专家分配工具集（双通道注入，最小权限）
    ├── ToolInputs.java / TextSanitizer.java / ToolContextKeys.java  # 工具入参解析 / 工具结果文本清洗 / ToolContext 键常量
    ├── mcp/ServerTaggedCallback.java  # MCP 来源标注（观测按 server 维度拆分）
    ├── ToolCallBudget.java / ToolCallTracer.java    # 工具次数/结果硬预算 / 调用起止进度行（装饰内核）
    ├── ToolCallbackDecorator.java / ToolDecorationContext.java  # 可插拔装饰器接口与上下文（Ordered 责任链）
    ├── ToolObservationDecorator.java / ToolBudgetDecorator.java / ToolLazyLoadDecorator.java / SkillMetaToolDecorator.java  # 默认装饰链四组件（观测100→预算200→懒加载300→元工具400）
    ├── DefaultToolDecorators.java  # 默认装饰链工厂（单一事实来源；新增装饰器=新类+此处登记一行）
    ├── builtin/SandboxToolkit.java  # 沙箱工具包（Python/Shell/文件/浏览器回调集合）
    ├── builtin/DemoTools.java     # 内置演示工具（@Tool 注解通道样例）
    ├── builtin/history/RecallHistoryTools.java  # recall_history：按关键词/消息 ID 检索会话归档（scope=current|all）
    ├── builtin/tts/TextToSpeechTools.java  # text_to_speech 工具入口：校验 + 合成 + 写工件
    ├── builtin/tts/EdgeTtsClient.java      # Edge TTS 子进程调用（超时杀进程 / 并发信号量）
    └── TokenEstimator.java       # 全项目统一 token 估算口径
└── voice/                        # 语音共享能力（工具入口在 tool/builtin/tts）
    ├── TtsProperties.java        # app.tts 配置（工具与工件库共用）
    └── InMemoryVoiceArtifactStore.java  # 短 TTL 工件库（core/渠道按接口消费）
```

</details>

> [!NOTE]
> **分层职责**：`controller` 收发 REST/SSE，不承载业务逻辑；`service` 编排核心逻辑（接口与实现分离）；`mapper` / `domain.entity` 负责数据库读写与映射。运行时关注点独立成包：`agent`（Agentic 执行体）、`graph`（Graph 编排）、`state`（检查点/续跑）、`observability`（观测落库与查询）、`validation`（纯规则）。


## 数据库迁移（Flyway，V1–V11）

`server/src/main/resources/db/migration/`，启动时自动执行。**已应用的脚本不可再改**——改动会导致校验和不匹配，需 `flyway repair`（或按 Flyway 的逐行 CRC32 算法校正 `flyway_schema_history.checksum`）后才能启动。

| 版本 | 内容 |
| --- | --- |
| V1 | 初始化 schema（会话 / goal / agent / 观测表等） |
| V2 | nailong TTS 工具配置 |
| V3 | `session_message_archive` 归档表（裁剪转归档的数据源） |
| V4 | `goal.last_progress_at` |
| V5 | `recall_history` 工具分配 |
| V6 | `goal.channel` |
| V7 | `agent_schedule` 定时任务表 |
| V8 | `schedule.notified_at` 主动推送幂等标记 |
| V9 | Goal 失败重投三列（`agent_name` / `retry_count` / `next_retry_at`） |
| V10 | 画像增量提炼（`profile_extracted` 一次性锁定 → `profile_extracted_at` + `last_extracted_message_id` 游标；存量会话标记已处理、不回溯） |
| V11 | `idx_archive_time(archived_at, id)`，支撑 `scope=all` 跨会话时间倒序召回 |

---

[⬅ 返回 README](../README.md)
