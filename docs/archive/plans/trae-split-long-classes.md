# 超长类拆分重构计划（HARNESS_TODO「明确暂缓项」立项执行）

## Context

优化评审（docs/optimization-review-2026-09-25.md）指出四个核心类职责过载：AgentChatCaller 749 行、MultiAgentGraphAgent 645 行、ChatServiceImpl 524 行、OneBotEventServiceImpl 520 行。用户已在 HARNESS_TODO「明确暂缓项」条目上立项执行。验收标准：**核心类职责单一、行数收敛（每类 ≥30%），全量测试绿**（不追硬数字）。

探查与规划已核验的关键事实：
- ChatServiceImpl 的 prefetch 汇合与写回重复**已被 f8f557a 收编**，残留重复仅 agentId 切换（L123-130≈L228-235）与建档（L114-118≈L217-222，控制流不同构不强行统一）
- ChatServiceImplTest（33 例）全走公共构造纯行为断言；MultiAgentGraphAgentTest 与 GeneralAssistantAgentScaffoldTest（自述「执行收敛等价性裁判」）是行为契约回归网——拆分安全性的依据
- MultiAgentGraphAgent 状态键常量是 MultiAgentStreamPipeline 的跨类契约，**留在宿主**，仅 private→包级放宽
- AgentRequestSpecFactory javadoc 明确「不感知编排角色语义」——装配策略不并入工厂，独立成类
- 尾部死代码 `safe(Throwable)`（MultiAgentGraphAgent L642-644）顺手删除

## 总原则

- 新类全部**包私有 final class/record**，由宿主构造，不注册 Spring bean、无配置变更、无 DB 迁移（先例：AggregateStreamGuard/MultiAgentStreamPipeline/CallTrace）
- 宿主保留「核心身份」：caller 的重试循环与门面、graph 的执行入口与图装配、ChatServiceImpl 的 toSseBody 生命周期编排与 route 簇、OneBot 的 handle 主流程与 chatWithTimeout
- 一类一 commit，顺序=风险从低到高；每步收口 `mvn -s .mvn/settings.xml -pl server -am test` 0 失败 0 错误
- 分支：从 master 建 `feature/split-long-classes`（create-feature-branch 约定）
- 未经用户明示不执行 git commit

## Commit 1：refactor(qq) — OneBotEventServiceImpl 520 → ~345

1. 抽 **ReplySplitter**（channel/qq，包私有静态工具类）：`splitOversizedBlock`/`splitSentences`(转私有)/`splitProgressive`/`splitReply`（L391-501，纯函数零依赖）
2. 抽 **EventTextParser**（channel/qq，包私有，构造注入 NapCatProperties）：`extractPrivateText`/`extractGroupText`/`atSelf`/`joinTextSegments`（L230-297）
3. 留守：handle/doHandle、chatWithTimeout（chatExecutor+超时硬约束）、sendReply/sendSegments、拟人延迟簇（chunkPause 是测试注入缝，迁移不划算）

测试适配：OneBotEventServiceImplTest 约 16 处 `OneBotEventServiceImpl.splitXxx` → `ReplySplitter.xxx`（同包免 import）。

## Commit 2：refactor(chat) — ChatServiceImpl 524 → ~425

1. 抽 **SseEncoder**（service/impl，包私有静态类，OBJECT_MAPPER 随迁）：`toSseRows`/`withAgentProgress`/`metaEvent`（metaEvent 签名追加 sources 参数，调用方算好传入，两处调用点同步改）
2. `toSseBody` **留守**（doOnComplete 挂写回、onErrorResume 挂收尾，是流生命周期编排本体）
3. 抽 **RagPrefetcher**（service/impl，包私有）：`prefetchPool` 字段+构造整体随迁、`doPrefetch`/`awaitPrefetch`/`PREFETCH_GRACE_SECONDS`；构造 `(KnowledgeRetriever, AgentService, Function<String,String> sessionAgentName)`（宿主传 `this::sessionAgentName`）；API：`submit/await/abandon`
4. `resolveAgentWithPrefetch` 汇合胶水留守宿主（judge+prefetch 汇合次序是聊天编排语义）
5. 收编 agentId 切换重复 → `private void bindAgentQuietly(...)`；建档重复维持现状
6. 留守：route 簇、resume、writeBackBoth、safeMessage/trimForLog/newTurnId

## Commit 3：refactor(agent) — MultiAgentGraphAgent 645 → ~455

1. 抽 **OrchestrationNodes**（agent 包，包私有，命名对齐 OrchestrationBudget/OrchestrationPrompts 先例）：
   - 构造 `(AgentChatCaller, PromptAssembler, ContextBudgetProperties, OrchestrationBudget)`，内部自建 AggregateStreamGuard（aggregateBudgetAdvisor 供给与 ROLE_AGGREGATOR/ROLE_LEAD 常量随迁）
   - 迁入 ~210 行：`isCancelled`/`lead`/`predictLeadLogged`/`predictLead`/`subtask`/`predictSubtask`/`aggregate`/`predictAggregate`/`aggregateBudgetAdvisor`
   - 三节点方法签名逐字不变；宿主图装配 lambda 机械替换 `state -> lead(...)` → `state -> nodes.lead(...)`
2. 状态键常量留守宿主，`K_OBJECTIVE`/`K_SESSION_ID`/`K_TURN_ID`/`K_TRACE_ID`/`K_LEAD_SPAN`/`K_SUBTASK_AGENT_PREFIX`/`MAX_SUBTASKS` private→包级；MultiAgentStreamPipeline 引用的 4 个常量零改动
3. 删除死代码 `safe(Throwable)`
4. 留守：执行入口、图装配、MultiAgentStreamPipeline 构造

## Commit 4：refactor(agent) — AgentChatCaller 749 → ~480-510

1. 抽 **AgentChatPipeline**（agent 包，包私有）：构造 `(ChatClientRegistry, AgentRequestSpecFactory, Duration streamIdleTimeout)`；迁入 `streamAttempt`/`streamCore`(均转包级——streamWithAssembly L589 直调)/`tokenStreamWithWatchdog`/`tokenStream`(转私有，无外部调用方)/`contentOf`/`usageOf`(包级 static，测试引用)/`captureUsage`/`captureUsageAndAccount`(转私有)
2. **测试契约薄委托**：caller 保留 `tokenStreamWithWatchdog` ~8 行委托（AgentChatCallerTest 12 参构造直调实例方法，零测试改动）；`DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS` 常量留守
3. 抽 **CallContext** → 顶层包私有 record 文件（先例 CallTrace.java；其 javadoc 引用同步更新）；`cancelAndRecord` 留守 caller
4. 记账兜底：`recordEstimatedIfNoUsage` → `BudgetLedger` 接口静态方法（语义已在 BudgetLedger javadoc 声明）
5. 抽 **CallSpecAssembler**（agent 包，包私有）：构造 `(AgentService, PromptAssembler, SessionService, ContextBudgetProperties)`，内部 new MemoryPolicy()；迁入 `assemblyForRole`/`maxTokensForRole`/`attachmentsFor`/`noToolsVariant`（noToolsVariant 下沉为 `AgentRequestSpecFactory.Assembly.withoutTools()` 实例方法，不违工厂边界）
6. **委托面（GeneralAssistantAgent 契约）**：caller 保留 `attachmentsFor`/`buildSpec` 两个委托方法
7. 留守：call×6/stream×6 门面、两个 WithAssembly 重试循环、`invokeAndRecord`（内部改调 pipeline.streamAttempt）、`configOf`、cancelAndRecord

## 测试适配清单（仅 2 文件 + 2 行）

- OneBotEventServiceImplTest：~16 处静态调用改 `ReplySplitter.xxx`
- AgentChatCallerTest L192 / AgentChatCallerRetryTest L162：`AgentChatCaller.contentOf` → `AgentChatPipeline.contentOf`
- 零改动回归网：ChatServiceImplTest、MultiAgentGraphAgentTest、GeneralAssistantAgentScaffoldTest、AgentChatCallerRetryTest 重试用例、OneBotEventServiceImplTest 其余用例

## 验证

- 每步快验：`mvn -s .mvn/settings.xml -pl server -am test -Dtest=<该步相关测试类>`
- 每步收口：`mvn -s .mvn/settings.xml -pl server -am test` 0 失败 0 错误（基线 610 例）
- `wc -l` 记录四类落点行数，对照收敛比例
- 收尾：HARNESS_TODO 该条目按文档约定移入已完成存档（含行数对照）

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| caller 重试不变量被搬运破坏（attemptCounter 位置、prevTotal 游标、首 token 后不可重试、取消/熔断旁路） | 逐字面等价搬迁；RetryTest + ScaffoldTest 回归 |
| watchdog 测试契约断裂 | caller 保留薄委托 |
| streamWithAssembly 直调 streamCore 跨类后可见性 | streamCore 转包级，漏改即编译失败 fail-fast |
| SSE 编码契约（event/data 成对、换行转义）双端依赖 | ChatServiceImplTest SSE 专项用例密集兜底 |
| channel/qq 隔离约束 | 新类均在 channel/qq 包内，零新跨包边 |
