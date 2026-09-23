package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.agent.BudgetLedger.BudgetExceededException;
import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

/**
 * 路径 A/B 统一 LLM 调用器（执行层单一来源）：查 agent 表配置 → 取注册客户端 → 组装请求 → 调用。
 * 本类收窄为「调用生命周期编排」——流管道核（tokenStream/streamCore）、重试循环、取消拦截、
 * 角色装配策略、流帧记账留在类内；独立关注点已提取为同包组件：账本契约 {@link BudgetLedger}
 * （含熔断异常）、取消词汇表 {@link CallCancellation}、错误分类纯函数 {@link LlmErrorClassifier}、
 * 观测封装 {@link LlmCallObserver}（llm_call_log 落库口径）。
 *
 * <p>供 {@link MultiAgentGraphAgent} 各环节（lead 拆解 / 专家子任务 / 聚合）与
 * {@link GeneralAssistantAgent}（路径 A，薄适配）复用；每次调用按传入的 agent 名独立查表，
 * 同一编排内不同环节可各用各的模型与提示词。装配差异两入口：角色策略入口
 * （assemblyForRole，仅 lead 注入记忆等编排语义）与 Assembly 直传入口（路径 A 声明
 * 恒记忆/final 档等差异）。流式管道核 {@link #tokenStream} 亦为路径 A 响应式链共用。
 */
final class AgentChatCaller {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentChatCaller.class);

    /** 流式空闲超时兜底默认（秒）：app.chat.timeouts.stream-idle-timeout-seconds 未配置时生效 */
    static final int DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS = 120;

    /**
     * 流式调用空闲超时：相邻信号间隔超过该时长即判定端点挂起，超时失败（不可重试——
     * 实测厂商端对该类请求为稳定挂死，重试同请求只会成倍放大等待）。
     * 默认 120s：工具执行期是流上最长的正常静默（fetchUrl/browser 实测 ~8s），已有 10 倍余量；
     * 300s 旧值曾让挂死请求阻塞用户 5 分钟才失败。可经 app.chat.timeouts.stream-idle-timeout-seconds
     * 覆盖（timeouts 为 null 的单测/旧构造链场景走默认值）。
     */
    private final java.time.Duration streamIdleTimeout;

    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 专家工具分配表：按 agent 名注入请求级工具 */
    private final ToolAssignments toolAssignments;
    /** Prompt 组装器：system prompt 按段组装（角色段兜底经 fallbackSystem 传入） */
    private final PromptAssembler promptAssembler;
    /** 记忆注入策略：按角色名判定是否挂载会话记忆 advisor（仅 lead） */
    private final MemoryPolicy memoryPolicy = new MemoryPolicy();
    /** 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源）；null 时不注入（单测场景） */
    private final SessionService memoryStore;
    /** 工具 Schema 延迟加载管理器（开关关闭时 process 全量透传，行为与现状一致）；编排三节点共享同一会话展开集 */
    private final ToolLazyManager lazyTools;
    /** skill 装配管理器（load_skill 元工具来源）；null 时不注册元工具（单测/旧构造链场景） */
    private final SkillManager skillManager;
    /** LLM 调用观测封装（llm_call_log 落库口径见 {@link LlmCallObserver}；recorder null 直通） */
    private final LlmCallObserver observer;
    /** 模型调用重试策略（指数退避，最多 3 次） */
    private final LlmRetry retry;
    /** 上下文预算配置（工具次数/结果预算等；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 请求规格组装工厂：system/记忆/选项/工具注入的统一组装链（与路径 A 共用） */
    private final AgentRequestSpecFactory specFactory;

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder) {
        this(clientRegistry, agentService, toolAssignments, recorder, new LlmRetry());
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, null);
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets,
                new PromptAssembler(agentService, toolAssignments), null, null, null, null, null);
    }

    /**
     * 全参构造（含 skill 装配与 RAG 知识检索，正式装配由 MultiAgentGraphAgent/GeneralAssistantAgent
     * 统一构建传入）：knowledgeRetriever 仅知识库启用时非 null（MultiAgentGraphAgent 经
     * ObjectProvider 传入），null 时无知识段注入、行为退化现状。
     * timeouts 为 null（单测/旧构造链场景）时流式空闲超时走默认
     * {@link #DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS}。
     * 其余仅保留 4/5/6 参单测便捷重载（尾部组件取禁用态/null），中间历史重载已收敛删除。
     */
    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore,
                    ToolLazyManager lazyTools,
                    SkillManager skillManager,
                    com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                    ChatTimeoutProperties timeouts) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.promptAssembler = promptAssembler;
        this.memoryStore = memoryStore;
        this.lazyTools = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.skillManager = skillManager;
        this.observer = new LlmCallObserver(recorder);
        this.retry = retry;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        this.specFactory = new AgentRequestSpecFactory(clientRegistry, promptAssembler,
                toolAssignments, this.lazyTools, skillManager, memoryStore, this.budgets,
                knowledgeRetriever, recorder);
        this.streamIdleTimeout = ChatClientFactory.resolve(
                timeouts != null ? timeouts.getStreamIdleTimeoutSeconds() : null,
                DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS);
    }

    /** 带会话观测的单次调用（推荐入口：sessionId 用于 llm_call_log 归因） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user) {
        return call(sessionId, forAgent, fallbackSystem, user, null, new Advisor[0], null);
    }

    /**
     * 带请求级 advisor 挂载的单次调用（如 lead/聚合的 PromptBudgetAdvisor）：
     * toolEmitter 可为 null（无工具进度行）；extraAdvisors 为请求级 advisor（可变参数，可为空）。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor... extraAdvisors) {
        return call(sessionId, forAgent, fallbackSystem, user, toolEmitter, extraAdvisors, null);
    }

    /**
     * 带取消令牌的单次调用（编排节点传入共享断连标志）。
     *
     * <p>实现说明：底层统一走流式通道收集完整内容返回——RestClient 阻塞调用不可中断
     * （JDK HttpClient 不响应线程中断），流式是 Spring AI 1.1.4 + JDK 连接器下唯一
     * 能中止在途 HTTP 请求的通道；代价是 token 用量从响应 usage 真实值变为估算。
     *
     * <p>取消语义：cancelled 已置位时直接抛 {@link CancellationException}（零 HTTP 请求）；
     * 执行中置位时在下一个 token 边界中止并抛出——不重试、部分输出不按成功返回。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor[] extraAdvisors, BooleanSupplier cancelled) {
        return call(sessionId, forAgent, fallbackSystem, user, toolEmitter, extraAdvisors, cancelled, null);
    }

    /**
     * 带取消令牌与预算账本的单次调用（编排节点传账本句柄：发起前与每轮 roundtrip 的
     * usage 帧上熔断判定；按 roundtrip 增量同步累计消耗）。ledger 可为 null（无账本场景）。
     * 角色策略入口：装配经 {@link #assemblyForRole} 按角色生成（与历史内联版等价）。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor[] extraAdvisors, BooleanSupplier cancelled,
                BudgetLedger ledger) {
        return callWithAssembly(sessionId, forAgent, fallbackSystem, user,
                assemblyForRole(forAgent, sessionId, toolEmitter, false), extraAdvisors, cancelled, ledger);
    }

    /**
     * Assembly 直传入口（路径 A 薄适配用：装配差异由调用方声明，不经角色策略）。
     * 独立方法名（callWithAssembly/streamWithAssembly）避免与角色策略入口在 null 实参下重载歧义。
     *
     * <p>实现说明：底层统一走流式通道收集完整内容返回——RestClient 阻塞调用不可中断
     * （JDK HttpClient 不响应线程中断），流式是 Spring AI 1.1.4 + JDK 连接器下唯一
     * 能中止在途 HTTP 请求的通道；代价是 token 用量从响应 usage 真实值变为估算
     * （streamUsage 回传真实值时仍记真实 token）。
     *
     * <p>取消语义：cancelled 已置位时直接抛 {@link CancellationException}（零 HTTP 请求）；
     * 执行中置位时在下一个 token 边界中止并抛出——不重试、部分输出不按成功返回。
     */
    String callWithAssembly(String sessionId, String forAgent, String fallbackSystem, String user,
                            AgentRequestSpecFactory.Assembly assembly, Advisor[] extraAdvisors,
                            BooleanSupplier cancelled, BudgetLedger ledger) {
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly));
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            try {
                java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicLong firstTokenAt =
                        new java.util.concurrent.atomic.AtomicLong();
                String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                        assembly, extraAdvisors, null, cancelled, usageRef, firstTokenAt, ledger);
                ctx.ok(ledger, start, content, usageRef.get(), firstTokenAt.get());
                return content;
            } catch (RuntimeException e) {
                // 客户端断连中止：记录后立即上抛（CancellationException 不可重试，直接放行）
                if (e instanceof CancellationException) {
                    ctx.error(start, e);
                    throw e;
                }
                // 编排预算熔断：政策性中止（非模型错误），记录后立即上抛（不可重试，
                // 编排节点捕获后写「预算超限跳过」占位）
                if (e instanceof BudgetExceededException) {
                    ctx.error(start, e);
                    throw e;
                }
                // 账户级硬错误（余额不足/配额耗尽 402/403、鉴权失败 401）：重试无意义，
                // 立即转人话异常向上传播（分类口径收敛于 LlmErrorClassifier）
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    ctx.error(start, e);
                    throw translated;
                }
                // 模型可能把提示词里的专家名（researcher 等）误当工具发起调用——
                // 工具列表里没有该名字，Spring AI 执行时抛「No ToolCallback found」。
                // 此时去掉工具列表重试一次：模型纯文本作答仍可产出结果，不炸整个编排。
                if (LlmErrorClassifier.isUnknownToolCall(e)) {
                    log.warn("[caller] {} 发起未知名工具调用，去工具重试一次：{}", forAgent, LlmCallRecorder.describeError(e));
                    CallContext noToolsCtx = ctx.blankTools();
                    long start2 = System.currentTimeMillis();
                    try {
                        java.util.concurrent.atomic.AtomicReference<Usage> usageRef2 =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        java.util.concurrent.atomic.AtomicLong firstTokenAt2 =
                                new java.util.concurrent.atomic.AtomicLong();
                        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                                noToolsVariant(assembly), extraAdvisors, null, cancelled, usageRef2, firstTokenAt2, ledger);
                        noToolsCtx.ok(ledger, start2, content, usageRef2.get(), firstTokenAt2.get());
                        return content;
                    } catch (RuntimeException e2) {
                        noToolsCtx.error(start2, e2);
                        throw e2;
                    }
                }
                ctx.error(start, e);
                throw e;
            }
        });
    }

    /** 单次调用 + 成功观测记录（失败由调用方记录）；disableTools=true 时不注入任何工具（幻觉工具调用的降级路径） */
    String invokeAndRecord(AgentConfig config, String sessionId, String forAgent,
                           String fallbackSystem, String user, Consumer<String> toolEmitter,
                           boolean disableTools, String model, long start, Advisor... extraAdvisors) {
        AgentRequestSpecFactory.Assembly assembly = assemblyForRole(forAgent, sessionId, toolEmitter, disableTools);
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly));
        java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong firstTokenAt = new java.util.concurrent.atomic.AtomicLong();
        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                assembly, extraAdvisors, null, null, usageRef, firstTokenAt, null);
        ctx.ok(null, start, content, usageRef.get(), firstTokenAt.get());
        return content;
    }

    /**
     * 单次流式调用尝试（不做重试——重试由 call 的 {@link LlmRetry} / stream 的循环自行处理）：
     * 收集全部 token 阻塞至流结束，返回完整内容；onToken 可 null（无需实时回调）。
     *
     * <p>取消语义：cancelled 已置位时直接抛取消异常（零 HTTP 请求）；执行中置位时
     * takeUntil 在下一个 token 边界中止订阅——取消向上传播关闭 HTTP 连接（厂商端
     * 停止生成），部分输出不返回。
     *
     * <p>usageRef 非 null 时捕获 streamUsage 末帧真实 usage，供记录真实 token（null 则纯收集）。
     *
     * <p>预算门控（ledger 非 null）：发起前超限直接抛 {@link BudgetExceededException}
     * （零 HTTP 请求）；每轮 LLM roundtrip 的 usage 帧到达时按增量记账并复检——
     * 单次 call 内部工具循环的下一轮在超限后不再发起（断流阻止后续消耗）。
     */
    private String streamAttempt(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                                 String user, AgentRequestSpecFactory.Assembly assembly,
                                 Advisor[] extraAdvisors, Consumer<String> onToken, BooleanSupplier cancelled,
                                 java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                 java.util.concurrent.atomic.AtomicLong firstTokenAt, BudgetLedger ledger) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw CallCancellation.cancelException();
        }
        if (ledger != null && ledger.overBudget()) {
            throw new BudgetExceededException();
        }
        StringBuilder collected = new StringBuilder();
        // roundtrip 增量记账游标：usage 帧的 total 为该轮完整 prompt+completion，
        // 与上一轮差值即本轮新增消耗（各轮 prompt 单调递增，差值非负、求和=末轮 total，不重复计数）
        java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
        try {
            return streamCore(config, sessionId, forAgent, fallbackSystem, user, assembly,
                    extraAdvisors, collected, onToken, cancelled, usageRef, prevTotal, firstTokenAt, ledger);
        } catch (RuntimeException e) {
            // 取消置位时一律按取消归因（流取消竞态下 blockLast 可能抛出其他形态异常）
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw CallCancellation.cancelException();
            }
            throw e;
        }
    }

    /**
     * 流式管道（含端点无响应兜底）：{@link #tokenStream} + 空闲超时 + 失败丢池。
     *
     * <p>路径 A（浏览器 SSE 主回答）与路径 B（阻塞收集）共用同一层兜底：此前超时只挂在
     * 路径 B，路径 A 在端点在途无响应时会永久挂起（实测主回答挂 200s+ 无任何超时信号，
     * 请求最终只能靠客户端断连收场）。
     *
     * <p>失败即丢池：连接可能已成网络黑洞（对端静默失活、不发 RST），丢池使下次调用
     * 重建连接池，不再复用同一根死连接。
     *
     * @param model 失败时用于重建客户端的模型名（null/未命中注册表则跳过丢池）
     */
    Flux<String> tokenStreamWithWatchdog(ChatClient.ChatClientRequestSpec spec,
                                         java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                         BudgetLedger ledger,
                                         java.util.concurrent.atomic.AtomicLong prevTotal,
                                         java.util.concurrent.atomic.AtomicLong firstTokenAt,
                                         String model) {
        return tokenStream(spec, usageRef, ledger, prevTotal, firstTokenAt)
                .timeout(streamIdleTimeout)
                .doOnError(e -> clientRegistry.invalidateByModel(model));
    }

    /**
     * 流式管道共用核（{@link #tokenStream} 之上的路径 B 段：空闲超时/取消拦截/阻塞收集）。
     * 取消语义：takeUntil 在下一个 token 边界中止订阅（取消向上传播关闭 HTTP 连接），
     * doOnNext 拦截 takeUntil 放行的终止前元素；流结束后取消竞态复检（不按成功返回）。
     */
    private String streamCore(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                              String user, AgentRequestSpecFactory.Assembly assembly,
                              Advisor[] extraAdvisors, StringBuilder collected, Consumer<String> onToken,
                              BooleanSupplier cancelled,
                              java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                              java.util.concurrent.atomic.AtomicLong prevTotal,
                              java.util.concurrent.atomic.AtomicLong firstTokenAt, BudgetLedger ledger) {
        tokenStreamWithWatchdog(
                        buildSpec(config, sessionId, forAgent, fallbackSystem, user, assembly, extraAdvisors),
                        usageRef, ledger, prevTotal, firstTokenAt, config != null ? config.model() : null)
                .takeUntil(__ -> cancelled != null && cancelled.getAsBoolean())
                .doOnNext(token -> {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        // takeUntil 放行的终止前元素在此拦截；异常致流以错误终止，
                        // Reactor cancel 向上游传播关闭 HTTP 连接
                        throw CallCancellation.cancelException();
                    }
                    collected.append(token);
                    if (onToken != null) {
                        onToken.accept(token);
                    }
                })
                .blockLast();
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw CallCancellation.cancelException();
        }
        return collected.toString();
    }

    /**
     * 流式管道共用核（路径 A {@link GeneralAssistantAgent#executeStreamReactive} 与路径 B 同源）：
     * 请求 spec → chatResponse 流 → usage 捕获/预算增量记账（ledger null 直通）→ 空帧跳过。
     * 后续算子（超时/取消/收集 or 合并工具进度行）由调用方接续——错误通道保持原样向上传播，
     * 观测归因由各调用方的终结钩子负责。
     */
    static Flux<String> tokenStream(ChatClient.ChatClientRequestSpec spec,
                                    java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                    BudgetLedger ledger,
                                    java.util.concurrent.atomic.AtomicLong prevTotal,
                                    java.util.concurrent.atomic.AtomicLong firstTokenAt) {
        return spec
                .stream()
                .chatResponse()
                .doOnNext(resp -> captureUsageAndAccount(resp, usageRef, ledger, prevTotal))
                // streamUsage 末帧是只含 usage 的空帧（contentOf 为 null）：Reactor 的 map
                // 不允许 null 返回（直接抛「The mapper returned a null value」），后面的
                // filter 根本不会执行——必须用 handle 跳过空帧
                .handle((org.springframework.ai.chat.model.ChatResponse resp,
                         reactor.core.publisher.SynchronousSink<String> sink) -> {
                    String token = AgentChatCaller.contentOf(resp);
                    if (token != null) {
                        sink.next(token);
                    }
                })
                // 首 token 打点（观测 TTFT）：首个 token 帧到达时记录绝对时间戳，
                // 纯内存赋值（firstTokenAt null 直通=无观测场景）
                .doOnNext(token -> {
                    if (firstTokenAt != null) {
                        firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                    }
                });
    }

    /** 模型空响应防御：逐层取 assistant 文本，任一层缺失返回 null（call/stream 记录与展示共用） */
    static String contentOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                ? resp.getResult().getOutput().getText() : null;
    }

    /** 模型空响应防御：逐层取 usage，任一层缺失返回 null */
    static Usage usageOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
    }

    /**
     * 流帧处理：捕获 usage（llm_call_log 末帧口径不变）+ 预算账本按 roundtrip 增量记账与熔断复检
     * （ledger 为 null 时仅捕获）。超限即抛 {@link BudgetExceededException} 断流——Reactor
     * 取消向上传播关闭 HTTP 连接，工具循环的下一轮不再发起。
     */
    private static void captureUsageAndAccount(org.springframework.ai.chat.model.ChatResponse resp,
                                               java.util.concurrent.atomic.AtomicReference<Usage> ref,
                                               BudgetLedger ledger,
                                               java.util.concurrent.atomic.AtomicLong prevTotal) {
        captureUsage(resp, ref);
        if (ledger == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
            return;
        }
        int total = usage.getTotalTokens();
        int delta = (int) Math.max(0, total - prevTotal.getAndSet(total));
        if (delta > 0) {
            ledger.recordUsage(delta, false);
        }
        if (ledger.overBudget()) {
            throw new BudgetExceededException();
        }
    }

    /**
     * 全程无真实 usage 回包时按输出文本估算入账（estimated=true，口径与 llm_call_log.tokens_estimated
     * 一致）；有 usage 时增量已在流帧上记账，此处不再累计（避免重复计数）。ledger 可 null 直通。
     */
    private static void recordEstimatedIfNoUsage(BudgetLedger ledger, String content, Usage usage) {
        if (ledger == null) {
            return;
        }
        boolean hasRealUsage = usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0;
        if (!hasRealUsage) {
            ledger.recordUsage(LlmCallRecorder.estimateTokens(content), true);
        }
    }

    /**
     * 流式 ChatClient 调用：请求组装与 {@link #call} 完全一致，但走 stream 通道——
     * 每个 token 到达即回调 {@code onToken}，方法阻塞至流结束并返回完整内容。
     *
     * <p>供图节点（如聚合）在生成过程中实时向外推送 token；调用方负责异常处理
     * （本方法不做降级，流式失败直接抛出，由调用方回退阻塞调用）。
     *
     * @param onToken 每个 token 片段到达时的回调（可能包含空串）
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken) {
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, null, new Advisor[0]);
    }

    /**
     * 带请求级 advisor 挂载的流式调用（如聚合的 PromptBudgetAdvisor）：
     * toolEmitter 可为 null（无工具进度行）；extraAdvisors 为请求级 advisor（可变参数，可为空）。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor... extraAdvisors) {
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, toolEmitter, extraAdvisors, null);
    }

    /**
     * 带取消令牌的流式调用（编排节点传入共享断连标志）：取消已置位时立即抛取消异常
     * （零 HTTP 请求）；执行中置位时在下一个 token 边界中止（takeUntil 取消向上传播
     * 关闭 HTTP 连接），抛取消异常——不重试、部分输出不按成功返回。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor[] extraAdvisors,
                  BooleanSupplier cancelled) {
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, toolEmitter, extraAdvisors,
                cancelled, null);
    }

    /**
     * 带取消令牌与预算账本的流式调用（编排节点传账本句柄：发起前熔断判定 + 成功结束后
     * 估算兜底入账/真实增量入账）。ledger 可为 null（无账本场景）。
     * 角色策略入口：装配经 {@link #assemblyForRole} 按角色生成（与历史内联版等价）。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor[] extraAdvisors,
                  BooleanSupplier cancelled, BudgetLedger ledger) {
        return streamWithAssembly(sessionId, forAgent, fallbackSystem, user, onToken,
                assemblyForRole(forAgent, sessionId, toolEmitter, false), extraAdvisors, cancelled, ledger);
    }

    /**
     * Assembly 直传入口（路径 A 薄适配用：装配差异由调用方声明，不经角色策略）。
     * 带取消令牌与预算账本的流式调用（发起前熔断判定 + 成功结束后估算兜底入账/真实增量入账）。
     */
    String streamWithAssembly(String sessionId, String forAgent, String fallbackSystem, String user,
                              Consumer<String> onToken, AgentRequestSpecFactory.Assembly assembly,
                              Advisor[] extraAdvisors, BooleanSupplier cancelled, BudgetLedger ledger) {
        CallContext ctx = new CallContext(observer, sessionId, forAgent,
                null, attachmentsFor(forAgent, assembly));
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelAndRecord(ctx, System.currentTimeMillis());
        }
        if (ledger != null && ledger.overBudget()) {
            throw new BudgetExceededException();
        }
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        ctx = ctx.withModel(model);
        // 流式重试约束：仅「首个 token 尚未发出」的失败才允许重试（一旦开始输出，
        // onToken 已回调、无法回滚，重试会造成重复输出）；已产生输出则立即抛出。
        // 取消异常永不重试（取消不是可重试错误，是断连语义）。
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            long start = System.currentTimeMillis();
            StringBuilder collected = new StringBuilder();
            // streamUsage 末帧真实 usage（无则估算兜底）
            java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 首 token 绝对时间戳（观测 TTFT，0=未触发）
            java.util.concurrent.atomic.AtomicLong firstTokenAt = new java.util.concurrent.atomic.AtomicLong();
            // roundtrip 增量记账游标（口径同 streamAttempt，见其注释）
            java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
            try {
                String out = streamCore(config, sessionId, forAgent, fallbackSystem, user, assembly,
                        extraAdvisors, collected, onToken, cancelled, usageRef, prevTotal, firstTokenAt, ledger);
                // streamUsage 回传真实 usage 时记真实值，无则按已收输出文本近似估算（原口径兜底）；
                // 账本兜底入账：无真实 usage 帧时按输出估算补记（有则增量已在流帧上记账，不重复）
                ctx.ok(ledger, start, out, usageRef.get(), firstTokenAt.get());
                return out;
            } catch (RuntimeException e) {
                boolean isCancel = e instanceof CancellationException
                        || (cancelled != null && cancelled.getAsBoolean());
                if (isCancel) {
                    throw cancelAndRecord(ctx, start);
                }
                ctx.error(start, e);
                // 账户级硬错误：与阻塞（call）路径同口径转换，不重试直接抛人话异常
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    throw translated;
                }
                boolean partialOutput = collected.length() > 0;
                boolean canRetry = !partialOutput && LlmRetry.isRetryable(e) && attempt < retry.maxAttempts();
                if (canRetry) {
                    retry.waitBeforeRetry(attempt);
                    continue;
                }
                throw e;
            }
        }
        // 理论不可达（maxAttempts>=1）
        throw new IllegalStateException("stream 重试循环异常退出");
    }

    /**
     * 组装请求（包级：路径 A executeStreamReactive 经此复用统一组装链）：委托
     * {@link AgentRequestSpecFactory#build}，装配差异由调用方传入的 {@code assembly} 声明。
     */
    ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String sessionId, String forAgent,
                                               String fallbackSystem, String user,
                                               AgentRequestSpecFactory.Assembly assembly,
                                               Advisor... extraAdvisors) {
        return specFactory.build(config, sessionId, forAgent, fallbackSystem, user, assembly, extraAdvisors);
    }

    /**
     * 角色策略装配（路径 B 三节点现状语义，与历史 buildSpec 内联版逐字段等价）：
     * 记忆按 {@link MemoryPolicy} 判定（仅 lead）、频率惩罚与工具硬预算启用、maxTokens 按角色档位。
     */
    private AgentRequestSpecFactory.Assembly assemblyForRole(String forAgent, String sessionId,
                                                             Consumer<String> toolEmitter, boolean disableTools) {
        return new AgentRequestSpecFactory.Assembly(toolEmitter, disableTools,
                memoryStore != null && memoryPolicy.shouldInject(forAgent, sessionId),
                true, true, maxTokensForRole(forAgent));
    }

    /** 幻觉工具名降级（去工具重试）的装配变体：去 emitter、disableTools=true，其余档位原样保留 */
    private static AgentRequestSpecFactory.Assembly noToolsVariant(AgentRequestSpecFactory.Assembly assembly) {
        return new AgentRequestSpecFactory.Assembly(null, true, assembly.injectMemory(),
                assembly.toolCallBudget(), assembly.frequencyPenalty(), assembly.maxTokens());
    }

    /** 查 agent 表配置（每次 LLM 调用仅查一次，观测记录与请求组装共用） */
    private AgentConfig configOf(String forAgent) {
        return agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
    }

    /** 观测名单计算（llm_call_log 装配名单列）：assembly.disableTools 时工具/子集置空、技能保留。
     *  包级可见：路径 A 响应式流的终结钩子（GeneralAssistantAgent.recordCall）同口径复用 */
    PromptAssembler.PromptAttachments attachmentsFor(String forAgent,
            AgentRequestSpecFactory.Assembly assembly) {
        PromptAssembler.PromptAttachments att = promptAssembler.attachmentsOf(forAgent);
        return assembly != null && assembly.disableTools() ? att.blankTools() : att;
    }

    /**
     * 输出封顶档位映射（消费侧 maxTokens，0 = 不限制）：
     * lead 拆解 → lead 档（JSON 中间产物本就该短）；aggregator → final 档
     * （与路径 A 直出对话同为直出用户的最终回答，共用一档）；其余（编排子任务专家
     * researcher/coder/analyst/writer/general）→ expert 档。
     * 角色名字面量与 {@link MemoryPolicy} / MultiAgentGraphAgent 的编排角色名同源。
     */
    private int maxTokensForRole(String forAgent) {
        if ("lead".equals(forAgent)) {
            return budgets.getMaxTokensLead();
        }
        if ("aggregator".equals(forAgent)) {
            return budgets.getMaxTokensFinal();
        }
        return budgets.getMaxTokensExpert();
    }

    /**
     * 观测上下文（llm_call_log 落库六元组的值对象）：call/stream 两通道共用，
     * 收敛 {@code observer.okStream/error} 的重复参数手拼（11 处）；start 每次尝试各异，
     * 作为方法参数传入而非上下文字段。方法均为历史内联口径的逐字面等价替换。
     */
    private record CallContext(LlmCallObserver observer, String sessionId, String forAgent,
                               String model, PromptAssembler.PromptAttachments attachments) {

        /** 成功观测对：落库 + 账本估算兜底入账（无真实 usage 帧；ledger null 直通）。
         *  firstTokenAt 为首个 token 到达的绝对时间戳（0=无/SYNC 通道），与 start 差值即 TTFT。 */
        void ok(BudgetLedger ledger, long start, String content, Usage usage, long firstTokenAt) {
            observer.okStream(sessionId, forAgent, model, start, content, usage, attachments, firstTokenAt);
            recordEstimatedIfNoUsage(ledger, content, usage);
        }

        /** 失败观测（stream=true：两通道底座均为流式信道） */
        void error(long start, Exception e) {
            observer.error(sessionId, forAgent, model, true, start, e, attachments);
        }

        /** 去工具重试变体：工具/MCP 名单置空、技能保留（llm_call_log blankTools 口径） */
        CallContext blankTools() {
            return new CallContext(observer, sessionId, forAgent, model, attachments.blankTools());
        }

        /** model 查表解析后派生（stream 入口取消早退发生在查表前，model 尚为 null） */
        CallContext withModel(String newModel) {
            return new CallContext(observer, sessionId, forAgent, newModel, attachments);
        }
    }

    /** 取消观测 + 构造上抛异常（stream 两处「落库 + 抛新取消异常」共用；call 通道复用捕获异常本体，不经此） */
    private static CancellationException cancelAndRecord(CallContext ctx, long start) {
        CancellationException ce = CallCancellation.cancelException();
        ctx.error(start, ce);
        return ce;
    }

    /** 模型空响应防御：从流式 chatResponse 捕获 usage（streamUsage 末帧回传真实值；取最后一个非空有效帧） */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse resp,
                                     java.util.concurrent.atomic.AtomicReference<Usage> ref) {
        if (ref == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            ref.set(usage);
        }
    }

}
