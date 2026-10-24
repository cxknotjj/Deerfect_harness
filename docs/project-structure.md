# 📁 项目结构

> README「项目结构」一节的完整版：多模块布局与 server 完整目录树、逐文件说明。

---

## Maven 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `harness-api` | 渠道与 core 共用的契约层：端口接口、DTO、共享枚举、`ReplyPostProcessor` SPI | 无同反应堆依赖 |
| `server` | Spring Boot 主服务、core 实现与 fat jar 宿主 | compile → `harness-api`；runtime → `channel-qq` |
| `channel-qq` | QQ 渠道（NapCat/OneBot 11）：客户端/事件/回复/持久化 | compile → `harness-api` |
| `cli` | 终端客户端，纯 HTTP + SSE | compile → `harness-api` |
| `wms-mcp-adapter` | 领域 MCP 适配范例 | 独立 |

> 规则：core（server）编译期不得引用 `channel.qq`（enforcer 守护 `com.dark:javaHarness-channel-qq:*:*:compile`）；渠道新增输出控制走 `ReplyPostProcessor`，不改 core。详见 [`docs/design/channel-core-module-split.md`](./design/channel-core-module-split.md)。

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
│   ├── ProviderAdminController.java  # 模型映射管理（/api/providers 查看与热刷新新增）
│   ├── KnowledgeAdminController.java # 知识库管理（文档列表/上传/删除/增量同步）
│   ├── LlmCallController.java    # LLM 调用观测查询（/api/llm-calls）
│   ├── ToolCallController.java   # 工具调用观测查询（/api/tool-calls）
│   └── KbRetrievalController.java # RAG 检索观测查询（/api/kb-retrievals）
├── service/                      # 业务层（接口 + impl/ 实现）
│   ├── GoalService.java          # 目标台账（AgentService/SessionService/ChatService/SessionBindingCleaner 端口已迁 harness-api）
│   ├── RouteJudge.java           # 主 Agent 路由判断（SIMPLE / COMPLEX 分流）
│   ├── AgentConfigProvider.java  # 从 agent 表读取运行配置（路由映射）
│   ├── ProviderAdminService.java # model_provider 映射管理（新增即热刷新注册表）
│   ├── ObserveQueryService.java  # 观测日志查询收口（llm/tool/kb-retrieval 三表读取口径，controller 不直连 mapper）
│   └── impl/                     # AgentServiceImpl / ChatServiceImpl / SessionServiceImpl / ProviderAdminServiceImpl / SseEncoder 等
│       ├── observe/              # LlmCallRecorder / McpServerRecorder（LLM/MCP 调用观测落库）+ ObserveQueryServiceImpl（观测查询）+ ObservabilityLogCleaner（观测表保留期清理）
│       └── route/                # LlmRouteJudge（主 Agent 路由判定）/ RagPrefetcher（RAG 入口预取）
├── advisor/                      # Spring AI Advisor 拦截器（Agent 流程横切管理）
│   ├── ContextAssemblingAdvisor.java  # 上下文组装：过滤/token 预算截断/role 归一化
│   └── PromptBudgetAdvisor.java  # Prompt 分段预算（历史/user/工具结果三段裁剪）
├── config/                       # 应用配置
│   ├── GoalExecutorConfig.java   # 执行线程池：goal-exec- 后台 Goal 池 + mvc-async- MVC 异步槽位
│   ├── ContextBudgetProperties.java  # 上下文预算统一配置（app.context.*，yaml 为唯一数值源）
│   ├── knowledge/                # RAG 知识库装配
│   │   ├── KnowledgeProperties.java  # RAG 知识库配置载体（app.knowledge.*）
│   │   └── KnowledgeConfig.java      # 知识库装配：向量库数据源/嵌入模型/PgVectorStore（条件装配+懒连接）
│   ├── datasource/               # 数据源与 MyBatis-Plus 装配
│   │   ├── PrimaryDataSourceConfig.java  # 主库（MySQL）显式声明（@Primary，多数据源下 Flyway/MyBatis 归属）
│   │   └── MybatisPlusConfig.java    # MyBatis-Plus 分页等配置
│   └── agent/                    # Agent 配置与装配
│       ├── ChatAgentConfig.java  # 注册各 Agent bean + graph-core 检查点存储器（MysqlSaver）
│       ├── ChatClientFactory.java    # 按服务商构建 OpenAI 兼容 ChatClient（Registry 模式）
│       ├── ChatClientRegistry.java   # 模型名 → ChatClient 注册表（支持热刷新）
│       └── ThinkingSwitchChatModel.java  # 按 model_provider.disable_thinking 注入思考开关
├── prompt/                       # Prompt 组装管线（两路径统一）
│   ├── PromptAssembler.java      # 五段式 system prompt 组装（角色/工具索引/纪律/输出约定/skill）
│   ├── MemoryPolicy.java         # 会话记忆按角色注入矩阵
│   ├── SkillManager.java / SkillRepository.java  # Markdown 技能库动态装配（按需注入 system）
│   ├── ToolLazyManager.java      # 工具 Schema 两段式延迟加载（轻量索引 → expand_tool 展开）
│   └── PromptSection.java / SkillSectionProvider.java  # 段模型与 skill 扩展点
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper
│       / LlmCallLogMapper / ToolCallLogMapper / KbRetrievalLogMapper / McpServerLogMapper / KbDocumentMapper
│       （OneBot 会话绑定 Mapper 在 channel-qq 模块）
├── domain/                       # 领域模型（父包）
│   ├── RouteDecision.java        # 路由决策枚举（SIMPLE / COMPLEX）
│   ├── LlmCallLog.java           # 一次 LLM 调用的观测记录（耗时/token/成败）
│   ├── KbRetrievalLog / McpServerLog / ToolCallLog  # 其余观测记录
│   └── entity/                   # 数据库实体（agent / goal / session_message / model_provider / 各观测日志表）
├── enums/                        # 共享常量：AgentConstants（GoalStatus / SseProtocol 已迁 harness-api）
├── exception/                    # 全局异常处理（@RestControllerAdvice，统一 {code, message}）
├── agent/                        # Agent 抽象、编排与 LLM 调用
│   ├── Agent.java / AgentRegistry.java    # Agent 接口与注册表
│   ├── GeneralAssistantAgent.java  # 路径 A：单模型对话（真·逐 token stream）
│   ├── AgentChatCaller.java        # LLM 调用生命周期编排门面（call/stream 重试循环、取消拦截、观测记录）
│   ├── AgentChatPipeline.java      # 流管道核（streamAttempt/streamCore/tokenStream + watchdog 空闲超时/流帧记账/空响应防御）
│   ├── CallContext.java            # 观测值对象（llm_call_log 落库参数收敛，call/stream 两通道共用）
│   ├── CallSpecAssembler.java      # 角色装配策略（MemoryPolicy 记忆判定/maxTokens 档位/观测名单计算）
│   ├── AgentRequestSpecFactory.java  # 两路径共用请求组装工厂（system/记忆注入/工具装饰/输出档位）
│   ├── LlmRetry.java               # LLM 调用重试策略
│   ├── ProgressLine.java           # 进度行线协议（MARK+stage+SEP+detail）编解码
│   └── orchestrate/               # 路径 B 多 Agent 编排（复杂请求）
│       ├── MultiAgentGraphAgent.java   # 路径 B：StateGraph 编排门面（图装配 + 执行/续跑入口；节点实现见 OrchestrationNodes）
│       ├── OrchestrationNodes.java     # 编排三节点实现（lead/subtask/aggregate + predict* 桥接 + 流式聚合护栏）
│       ├── LeadOutputParser.java       # lead 拆解 JSON 解析（子任务数 + 专家指派白名单）
│       ├── OrchestrationBudget.java    # 编排预算账本（AtomicLong 共享记账 + 降级说明）
│       ├── MultiAgentStreamPipeline.java  # 编排流式管道（进度行 → SSE 事件装配、续跑检查点选择）
│       └── BranchProgressListener.java # graph-core 生命周期钩子旁路（并行分支完成事件串行发射）
├── knowledge/                    # RAG 知识库
│   ├── KnowledgeDocumentScanner.java  # 知识目录扫描（.md/.txt、front-matter 标题、坏文件跳过）
│   ├── MarkdownChunker.java      # 段落感知切分（~700 字符 + 重叠，纯函数）
│   ├── KnowledgeService(Impl).java  # 增量摄取（mtime 比对删旧写新）/ 删除 / 分页 / 检索
│   └── KnowledgeRetriever.java   # 检索注入器：user 文本→top-k 命中→【出处N】知识段（预算截断）
├── channel/qq/                   # 已整体迁出为独立模块 `channel-qq`（NapCat/OneBot 11，napcat.enabled 条件装配，单向依赖 core 端口）
├── memory/                       # 用户画像（跨会话长期记忆）
│   ├── UserProfileService.java   # 离线批量提炼空闲会话偏好 → 全局画像 Markdown（原子写）
│   └── UserProfileSectionProviderImpl.java  # 画像段注入 general/lead 的 system prompt
├── cli/                          # 已独立为 `cli` 模块（`cli/src/main/java/com/dark/javaHarness/cli`，纯 HTTP 连 8080）
└── tool/                         # 工具层
    ├── WebTools.java             # 网页抓取门面（fetchUrl：抓取 + 30min 内容缓存 + 相关段落裁剪）
    ├── HtmlToMarkdown.java / ContentRelevance.java  # 噪声剔除/主内容 Markdown 化 / 按查询意图裁剪
    ├── SandboxToolProvider.java  # 容器级沙箱工具（Python/Shell/文件 + 浏览器，懒初始化、失败降级）
    ├── McpToolProvider.java / McpConfigParser.java  # MCP 工具接入（多 server 懒连接、失败隔离）/ mcp-config.json 解析
    ├── McpServerTools.java       # 内置 MCP Server 暴露的演示工具（Streamable-HTTP /mcp）
    ├── ToolAssignments.java      # 工具分配表：按专家分配工具集（双通道注入，最小权限）
    ├── ToolCallBudget.java / ToolCallTracer.java    # 工具次数/结果硬预算 / 调用起止进度行（装饰内核）
    ├── ToolCallbackDecorator.java / ToolDecorationContext.java  # 可插拔装饰器接口与上下文（Ordered 责任链）
    ├── ToolObservationDecorator.java / ToolBudgetDecorator.java / ToolLazyLoadDecorator.java / SkillMetaToolDecorator.java  # 默认装饰链四组件（观测100→预算200→懒加载300→元工具400）
    ├── DefaultToolDecorators.java  # 默认装饰链工厂（单一事实来源；新增装饰器=新类+此处登记一行）
    └── TokenEstimator.java       # 全项目统一 token 估算口径
```

</details>

> [!NOTE]
> **分层职责**：`controller` 收发 REST/SSE，不承载业务逻辑；`service` 编排核心逻辑（接口与实现分离）；`mapper` / `domain.entity` 负责数据库读写与映射。


---

[⬅ 返回 README](../README.md)
