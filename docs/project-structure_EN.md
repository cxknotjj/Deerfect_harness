# 📁 Project Structure

> Full version of the README "Project Structure" section: complete server-side directory tree with per-file notes.

---

Classic layered architecture (Controller → Service → Mapper/Entity), with the domain model grouped under the `domain` parent package:

<details>
<summary><b>📂 Click to expand the full directory tree</b></summary>

```text
src/main/java/com/dark/javaHarness/
├── JavaHarnessApplication.java   # Spring Boot entry (@MapperScan scans mapper + channel.qq.persistence)
├── controller/                   # Presentation layer: REST endpoints + SSE streaming
│   ├── ChatController.java       # Chat endpoints (/api/chat, /stream, /resume, /goal-status)
│   ├── HarnessController.java    # Management endpoints (agents / submit / goals / sessions / session-bound agent)
│   ├── ProviderAdminController.java  # Model-mapping management (/api/providers: list & hot-refresh add)
│   ├── KnowledgeAdminController.java # Knowledge-base management (document list / upload / delete / incremental sync)
│   ├── LlmCallController.java    # LLM call observability queries (/api/llm-calls)
│   ├── ToolCallController.java   # Tool-call observability queries (/api/tool-calls)
│   └── KbRetrievalController.java # RAG retrieval observability queries (/api/kb-retrievals)
├── service/                      # Business layer (interfaces + impl/)
│   ├── AgentService / GoalService / SessionService / ChatService  # Orchestration, goals, session memory, chat use cases
│   ├── RouteJudge.java           # Main-agent routing decision (SIMPLE / COMPLEX)
│   ├── AgentConfigProvider.java  # Runtime config from the agent table (routing map)
│   ├── ProviderAdminService.java # model_provider mapping management (hot refresh on add)
│   ├── ObserveQueryService.java  # Unified observability-log queries (llm / tool / kb-retrieval; controllers never touch mappers directly)
│   ├── SessionBindingCleaner.java # Channel session-binding cleanup port (core session delete → channel cleanup; impl wired conditionally on napcat.enabled)
│   └── impl/                     # Implementations (AgentServiceImpl / ChatServiceImpl / SseEncoder (SSE encoding) etc.)
│       ├── observe/              # LlmCallRecorder / McpServerRecorder (LLM/MCP call observation persistence) + ObserveQueryServiceImpl (observability queries) + ObservabilityLogCleaner (observation-table retention cleanup)
│       └── route/                # LlmRouteJudge (main-agent routing decision) / RagPrefetcher (RAG entry prefetch)
├── advisor/                      # Spring AI Advisor interceptors (cross-cutting agent-flow management)
│   ├── ContextAssemblingAdvisor.java  # Context assembly: filter / token-budget truncation / role normalization
│   └── PromptBudgetAdvisor.java  # Prompt section budgets (history / user / tool-result truncation)
├── config/                       # Application configuration
│   ├── GoalExecutorConfig.java   # Execution pools: goal-exec- background goal pool + mvc-async- MVC async slot
│   ├── ContextBudgetProperties.java  # Unified context budget config (app.context.*; yaml is the single source of numbers)
│   ├── knowledge/                # RAG knowledge-base wiring
│   │   ├── KnowledgeProperties.java  # RAG knowledge-base config carrier (app.knowledge.*)
│   │   └── KnowledgeConfig.java      # Knowledge-base wiring: vector datasource / embedding model / PgVectorStore (conditional + lazy connections)
│   ├── datasource/               # Datasource & MyBatis-Plus wiring
│   │   ├── PrimaryDataSourceConfig.java  # Explicit primary (MySQL) datasource declaration (@Primary; Flyway/MyBatis ownership with multiple datasources)
│   │   └── MybatisPlusConfig.java    # MyBatis-Plus configuration (pagination etc.)
│   └── agent/                    # Agent configuration & assembly
│       ├── ChatAgentConfig.java      # Registers agent beans + graph-core checkpoint store (MysqlSaver)
│       ├── ChatClientFactory.java    # Builds OpenAI-compatible ChatClients per provider (Registry pattern)
│       ├── ChatClientRegistry.java   # Model-name → ChatClient registry (hot-refreshable)
│       └── ThinkingSwitchChatModel.java  # Injects thinking switch per model_provider.disable_thinking
├── prompt/                       # Prompt assembly pipeline (shared by both paths)
│   ├── PromptAssembler.java      # Five-section system prompt (role / tool index / discipline / output / skill)
│   ├── MemoryPolicy.java         # Per-role session-memory injection matrix
│   ├── SkillManager.java / SkillRepository.java  # Markdown skill library assembly (injected on demand)
│   ├── ToolLazyManager.java      # Two-phase lazy tool-schema loading (lightweight index → expand_tool)
│   └── PromptSection.java / SkillSectionProvider.java  # Section model and skill extension point
├── mapper/                       # Data access: MyBatis-Plus mappers
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper
│       / LlmCallLogMapper / ToolCallLogMapper / KbRetrievalLogMapper / McpServerLogMapper / KbDocumentMapper
│       (the OneBot session-binding mapper now lives with the channel in channel/qq/persistence)
├── domain/                       # Domain model (parent package)
│   ├── Goal.java                 # Goal + status (PENDING/RUNNING/SUCCEEDED/FAILED)
│   ├── AgentConfig.java          # Agent runtime config (model + prompt), from the agent table
│   ├── RouteDecision.java        # Routing decision enum (SIMPLE / COMPLEX)
│   ├── LlmCallLog.java           # Observability record of one LLM call (latency/tokens/outcome)
│   ├── dto/                      # Transfer objects (ChatRequest/ChatResponse/SseMeta/pagination etc.)
│   └── entity/                   # DB entities (agent / goal / session / model_provider / observability log tables etc.)
├── enums/                        # Enums & shared constants: GoalStatus, AgentConstants, SseProtocol
├── exception/                    # Global exception handling (@RestControllerAdvice, uniform {code, message})
├── agent/                        # Agent abstractions, orchestration & LLM calls
│   ├── Agent.java / AgentRegistry.java    # Agent interface and registry
│   ├── GeneralAssistantAgent.java  # Path A: single-model chat (true token-by-token stream)
│   ├── AgentChatCaller.java        # LLM call lifecycle facade (call/stream retry loops, cancellation interception, observation recording)
│   ├── AgentChatPipeline.java      # Streaming pipeline core (streamAttempt/streamCore/tokenStream + watchdog idle timeout / frame accounting / empty-response guards)
│   ├── CallContext.java            # Observation value object (llm_call_log parameter bundling, shared by call/stream)
│   ├── CallSpecAssembler.java      # Role-based assembly policy (MemoryPolicy injection / maxTokens tiers / attachment list)
│   ├── AgentRequestSpecFactory.java  # Shared request-assembly factory (system / memory injection / tool decoration / output tier)
│   ├── LlmRetry.java               # LLM call retry policy
│   ├── ProgressLine.java           # Progress line wire protocol (MARK+stage+SEP+detail) codec
│   └── orchestrate/               # Path B multi-agent orchestration (complex requests)
│       ├── MultiAgentGraphAgent.java   # Path B: StateGraph orchestration facade (graph assembly + execute/resume entries; node impls in OrchestrationNodes)
│       ├── OrchestrationNodes.java     # Orchestration node impls (lead/subtask/aggregate + predict* bridges + streaming aggregation guard)
│       ├── LeadOutputParser.java       # Lead decomposition JSON parsing (subtask count + expert dispatch whitelist)
│       ├── OrchestrationBudget.java    # Orchestration budget ledger (AtomicLong shared accounting + degradation note)
│       ├── MultiAgentStreamPipeline.java  # Orchestration streaming pipeline (progress lines → SSE events, resume checkpoint selection)
│       └── BranchProgressListener.java # graph-core lifecycle-hook sidecar (serializes parallel-branch completion events)
├── knowledge/                    # RAG knowledge base
│   ├── KnowledgeDocumentScanner.java  # Knowledge directory scan (.md/.txt, front-matter titles, bad files skipped)
│   ├── MarkdownChunker.java      # Paragraph-aware splitting (~700 chars + overlap, pure function)
│   ├── KnowledgeService(Impl).java  # Incremental ingestion (mtime compare, delete-then-write) / delete / paging / search
│   └── KnowledgeRetriever.java   # Retrieval injector: user text → top-k hits → 【出处N】knowledge section (budget-truncated)
├── channel/qq/                   # QQ channel vertical package (NapCat/OneBot 11, conditional on napcat.enabled, one-way dependency on core ports)
│   ├── client/                   # NapCat HTTP client & whole-chain assembly (NapCatChannelConfig @Bean)
│   ├── event/                    # Report endpoint & event handling (filter / whitelist / rate limit / session binding / direct ChatService calls)
│   ├── reply/                    # Reply output (progressive splitting / rate limiter / emoji matching)
│   ├── dto/                      # OneBot protocol DTOs
│   └── persistence/              # Channel-private persistence (OneBotSessionBinding + mapper + SessionBindingCleaner impl)
├── memory/                       # User profile (cross-session long-term memory)
│   ├── UserProfileService.java   # Offline batch preference extraction from idle sessions → global profile Markdown (atomic write)
│   └── UserProfileSectionProviderImpl.java  # Profile section injected into general/lead system prompts
├── cli/                          # CLI client (standalone process, pure HTTP to 8080)
│   ├── ChatCli.java              # Facade: main / chatLoop / command dispatch / turn execution
│   ├── ResumeStateStore.java     # /resume target persistence (state-file IO + tolerant parsing)
│   ├── input/TerminalInput.java  # Terminal input layer: JLine history/completion/paste; falls back to line reads without a TTY
│   ├── api/ChatApiClient.java    # OkHttp wrapper for chat / streaming / resume / provider / session endpoints (SSE parsing)
│   └── render/                   # Claude Code-style rendering
│       ├── TerminalRenderer.java # Facade: incremental streaming output + spinner collapse + tool-call lines
│       ├── MarkdownAnsiRenderer.java / Ansi.java  # Markdown line-level ANSI coloring
│       └── Spinner.java          # Stage progress spinner (in-place refresh)
└── tool/                         # Tools
    ├── WebTools.java             # Web fetch facade (fetchUrl: fetch + 30-min content cache + query-relevant clipping)
    ├── HtmlToMarkdown.java / ContentRelevance.java  # Noise removal / main-content Markdown extraction / query-intent clipping
    ├── SandboxToolProvider.java  # Container-level sandbox tools (Python/Shell/file + browser; bounded lazy init, graceful degradation)
    ├── McpToolProvider.java / McpConfigParser.java  # MCP tool access (multi-server lazy connect, failure isolation) / mcp-config.json parsing
    ├── McpServerTools.java       # Demo tools exposed by the in-process MCP server (Streamable-HTTP /mcp)
    ├── ToolAssignments.java      # Tool assignment table: per-expert toolsets (dual-channel injection, least privilege)
    ├── ToolCallBudget.java / ToolCallTracer.java    # Tool count/result hard budget / start-stop progress lines
    ├── ToolCallbackDecorator.java / ToolDecorationContext.java  # Pluggable decorator interface & context (Ordered chain)
    ├── ToolObservationDecorator.java / ToolBudgetDecorator.java / ToolLazyLoadDecorator.java / SkillMetaToolDecorator.java  # Default decorator chain (observation 100 → budget 200 → lazy-load 300 → meta-tool 400)
    ├── DefaultToolDecorators.java  # Default decorator-chain factory (single source of truth; new decorator = new class + one registration line)
    ├── TokenEstimator.java       # Project-wide token estimation standard
    └── DemoTools.java            # Demo toolset (time / calculator / weather)
```

</details>

> [!NOTE]
> **Layer responsibilities**: `controller` handles REST/SSE and carries no business logic; `service` orchestrates the core logic (interfaces separated from implementations); `mapper` / `domain.entity` handle database reads, writes and mapping.


---

[⬅ Back to README](../README.md)
