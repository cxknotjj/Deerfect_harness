package com.dark.javaHarness.agent;

import com.dark.javaHarness.agent.BudgetLedger.BudgetExceededException;
import com.dark.javaHarness.agent.BudgetLedger;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.contract.model.AgentConfig;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.FailurePolicy;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RouteAttempt;
import com.dark.javaHarness.llm.routing.RouteExecutor;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.observability.LlmCallObserver;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.prompt.ContextCompositionRecorder;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.tool.DefaultToolDecorators;
import com.dark.javaHarness.tool.ToolAssignments;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 路径 A/B 统一 LLM 调用器（执行层单一来源）：查 agent 表配置 → 取注册客户端 → 组装请求 → 调用。
 * 本类收窄为「调用生命周期编排」——call/stream 门面、重试循环（callWithAssembly 全尝试重试 +
 * streamWithAssembly 首 token 前重试）、取消拦截、观测记录；独立关注点已提取为同包组件：
 * 流管道核 {@link AgentChatPipeline}（streamAttempt/streamCore/tokenStream/watchdog/记账/空响应防御）、
 * 角色装配策略 {@link CallSpecAssembler}、观测值对象 {@link CallContext}、账本契约 {@link BudgetLedger}
 * （含熔断异常与估算兜底静态）、取消词汇表 {@link CallCancellation}、错误分类纯函数
 * {@link LlmErrorClassifier}、观测封装 {@link LlmCallObserver}（llm_call_log 落库口径）。
 *
 * <p>供 {@link MultiAgentGraphAgent} 各环节（lead 拆解 / 专家子任务 / 聚合）与
 * {@link GeneralAssistantAgent}（路径 A，薄适配）复用；每次调用按传入的 agent 名独立查表，
 * 同一编排内不同环节可各用各的模型与提示词。装配差异两入口：角色策略入口
 * （{@link CallSpecAssembler#assemblyForRole}，仅 lead 注入记忆等编排语义）与 Assembly 直传入口
 * （路径 A 声明恒记忆/final 档等差异）。
 */
public final class AgentChatCaller {

    private static final Logger log =
            LoggerFactory.getLogger(AgentChatCaller.class);

    /** 流式空闲超时兜底默认（秒）：app.chat.timeouts.stream-idle-timeout-seconds 未配置时生效 */
    static final int DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS = 120;
    /** 流式首帧超时兜底默认（秒）：黑洞连接快速判定；app.chat.timeouts.first-frame-timeout-seconds 未配置时生效 */
    static final int DEFAULT_FIRST_FRAME_TIMEOUT_SECONDS = 20;

    private final AgentService agentService;
    /** LLM 调用观测封装（llm_call_log 落库口径见 {@link LlmCallObserver}；recorder null 直通） */
    private final LlmCallObserver observer;
    /** 模型调用重试策略（指数退避，最多 3 次） */
    private final LlmRetry retry;
    /** 上下文预算配置（工具次数/结果预算等；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 请求规格组装工厂：system/记忆/选项/工具注入的统一组装链（与路径 A 共用） */
    private final AgentRequestSpecFactory specFactory;
    /** 流管道核（超长类拆分 2026-09-25 拆出） */
    private final AgentChatPipeline pipeline;
    /** 角色装配策略（超长类拆分 2026-09-25 拆出） */
    private final CallSpecAssembler assembler;
    /** 新解析链的客户端注册表（按 model_id）：失败切换时按成员 id 丢池 */
    private final LlmClientRegistry llmClientRegistry;
    /** 路由推进策略：阻塞/流式两条链路的失败切换与重试唯一实现（null 时按默认上限自建） */
    private final RouteExecutor routeExecutor;

    /**
     * 全参构造（含 skill 装配与 RAG 知识检索，正式装配由 MultiAgentGraphAgent/GeneralAssistantAgent
     * 统一构建传入）：knowledgeRetriever 仅知识库启用时非 null（MultiAgentGraphAgent 经
     * ObjectProvider 传入），null 时无知识段注入。
     * timeouts 为 null（单测场景）时流式空闲超时走默认
     * {@link #DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS}。
     * 路由解析三件套（modelResolver / llmAgentCatalog / llmClientRegistry）必填：
     * 调用链只按 Agent → 路由 → 模型 id 取客户端。
     */
    public AgentChatCaller(AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore,
                    ToolLazyManager lazyTools,
                    SkillManager skillManager,
                    KnowledgeRetriever knowledgeRetriever,
                    ChatTimeoutProperties timeouts,
                    ContextCompositionRecorder compositionRecorder,
                    ModelResolver modelResolver,
                    AgentCatalog llmAgentCatalog,
                    LlmClientRegistry llmClientRegistry,
                    RouteExecutor routeExecutor) {
        this.agentService = agentService;
        this.llmClientRegistry = llmClientRegistry;
        this.routeExecutor = routeExecutor != null ? routeExecutor
                : new RouteExecutor(RouteExecutor.DEFAULT_MAX_SAME_MEMBER_RETRIES);
        this.observer = new LlmCallObserver(recorder);
        this.retry = retry;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        ToolLazyManager lazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.specFactory = new AgentRequestSpecFactory(promptAssembler,
                toolAssignments, lazy, skillManager, memoryStore, this.budgets,
                knowledgeRetriever, recorder,
                DefaultToolDecorators.defaults(lazy, skillManager,
                        this.budgets, recorder), compositionRecorder,
                modelResolver, llmAgentCatalog, llmClientRegistry);
        Duration streamIdleTimeout = ChatClientFactory.resolve(
                timeouts != null ? timeouts.getStreamIdleTimeoutSeconds() : null,
                DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS);
        Duration firstFrameTimeout = ChatClientFactory.resolve(
                timeouts != null ? timeouts.getFirstFrameTimeoutSeconds() : null,
                DEFAULT_FIRST_FRAME_TIMEOUT_SECONDS);
        this.pipeline = new AgentChatPipeline(llmClientRegistry, this.specFactory, streamIdleTimeout,
                firstFrameTimeout, this.routeExecutor);
        this.assembler = new CallSpecAssembler(promptAssembler, memoryStore, this.budgets);
    }

    /**
     * 调用可选项（call / stream 共用）：把原先「逐位追加参数」的重载链收敛为一个参数对象，
     * 调用方显式构造，不再靠 null 占位对位（优化审查 2026-11-03）。
     *
     * @param toolEmitter    工具进度行出口（null=无）
     * @param advisors       请求级 advisor（如 PromptBudgetAdvisor；空=不挂载）
     * @param cancelled      取消令牌（编排节点共享断连标志；null=不取消）
     * @param ledger         编排预算账本（null=不记账）
     * @param trace          轨迹标识（null 按 {@link CallTrace#NONE}）
     * @param extraToolNames 附加工具包名单（子任务挂载领域工具包；null=未声明）
     * @param reasoningTap   思考透传旁路（null=不透传）
     */
    public record CallOptions(
            Consumer<String> toolEmitter,
            List<Advisor> advisors,
            BooleanSupplier cancelled,
            BudgetLedger ledger,
            CallTrace trace,
            List<String> extraToolNames,
            Consumer<String> reasoningTap) {

        /** 全默认：无进度行、无请求级 advisor、不取消、无账本、无轨迹、无附加工具、无思考透传 */
        public static CallOptions none() {
            return new CallOptions(null, List.of(), null, null, CallTrace.NONE, null, null);
        }

        public CallOptions {
            advisors = advisors == null ? List.of() : List.copyOf(advisors);
            extraToolNames = extraToolNames == null ? null : List.copyOf(extraToolNames);
        }
    }

    /** 带会话观测的单次调用（推荐入口：sessionId 用于 llm_call_log 归因） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user) {
        return call(sessionId, forAgent, fallbackSystem, user, CallOptions.none());
    }

    /**
     * 角色策略入口：装配经 {@link CallSpecAssembler#assemblyForRole} 按角色生成，可选参数见
     * {@link CallOptions}。
     *
     * <p>实现说明：底层统一走流式通道收集完整内容返回——RestClient 阻塞调用不可中断
     * （JDK HttpClient 不响应线程中断），流式是 Spring AI 1.1.4 + JDK 连接器下唯一
     * 能中止在途 HTTP 请求的通道；代价是 token 用量从响应 usage 真实值变为估算
     * （streamUsage 回传真实值时仍记真实 token）。
     *
     * <p>取消语义：cancelled 已置位时直接抛 {@link CancellationException}（零 HTTP 请求）；
     * 执行中置位时在下一个 token 边界中止并抛出——不重试、部分输出不按成功返回。
     */
    public String call(String sessionId, String forAgent, String fallbackSystem, String user,
                       CallOptions opts) {
        return callWithAssembly(sessionId, forAgent, fallbackSystem, user,
                assembler.assemblyForRole(forAgent, sessionId, opts.toolEmitter(), false,
                        opts.extraToolNames()),
                opts.advisors().toArray(new Advisor[0]), opts.cancelled(), opts.ledger(),
                opts.trace(), opts.reasoningTap());
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
     *
     * <p>轨迹标识：spanId 在本方法发起前生成；turnId/traceId/parentSpan 由调用方经
     * {@link CallTrace} 传入（路径 A 从 Goal 取值，无上下文场景传 {@link CallTrace#NONE}）。
     */
    String callWithAssembly(String sessionId, String forAgent, String fallbackSystem, String user,
                            AgentRequestSpecFactory.Assembly assembly, Advisor[] extraAdvisors,
                            BooleanSupplier cancelled, BudgetLedger ledger, CallTrace trace,
                            Consumer<String> reasoningTap) {
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        CallTrace t = trace == null ? CallTrace.NONE : trace;
        // spanId 原则上由本发起点生成；编排树 lead 场景由编排器预生成（t.spanId 非 null）沿用
        String spanId = t.spanId() != null ? t.spanId() : CallTrace.newSpanId();
        // 带 spanId 的完整标识沿调用链下传至 spec 组装（工具侧经 ToolContext 关联）
        CallTrace eff = t.withSpan(spanId);
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly), eff.turnId(), eff.traceId(), spanId,
                eff.parentSpan(), null);
        // 重试可见性：计数器必须在重试循环外创建（放进 lambda 每次尝试都会重置,
        // 重试成功的行会错报 attempt=1）；每次尝试执行 lambda 时自增即第几次尝试
        AtomicInteger attemptCounter =
                new AtomicInteger();
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            int maxAttempts = retry.maxAttempts();
            // 观测口径：本次尝试实际命中的路由成员（重试/切换时由 pipeline 回写）
            AtomicReference<RouteAttempt> routeRef = new AtomicReference<>();
            try {
                AtomicReference<Usage> usageRef =
                        new AtomicReference<>();
                AtomicLong firstTokenAt =
                        new AtomicLong();
                int attempt = attemptCounter.incrementAndGet();
                String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                        assembly, extraAdvisors, eff, null, cancelled, usageRef, firstTokenAt, ledger,
                        reasoningTap, routeRef);
                ctx.withRoute(routeRef.get()).ok(ledger, start, content, usageRef.get(),
                        firstTokenAt.get(), attempt, maxAttempts);
                return content;
            } catch (RuntimeException e) {
                int attempt = Math.max(attemptCounter.get(), 1);
                CallContext attemptCtx = ctx.withRoute(routeRef.get());
                // 客户端断连中止：记录后立即上抛（CancellationException 不可重试，直接放行）
                if (e instanceof CancellationException) {
                    attemptCtx.error(start, e, attempt, maxAttempts);
                    throw e;
                }
                // 编排预算熔断：政策性中止（非模型错误），记录后立即上抛（不可重试，
                // 编排节点捕获后写「预算超限跳过」占位）
                if (e instanceof BudgetExceededException) {
                    attemptCtx.error(start, e, attempt, maxAttempts);
                    throw e;
                }
                // 账户级硬错误（余额不足/配额耗尽 402/403、鉴权失败 401）：重试无意义，
                // 立即转人话异常向上传播（分类口径收敛于 LlmErrorClassifier）
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    attemptCtx.error(start, e, attempt, maxAttempts);
                    throw translated;
                }
                // 模型可能把提示词里的专家名（researcher 等）误当工具发起调用——
                // 工具列表里没有该名字，Spring AI 执行时抛「No ToolCallback found」。
                // 此时去掉工具列表重试一次：模型纯文本作答仍可产出结果，不炸整个编排。
                if (LlmErrorClassifier.isUnknownToolCall(e)) {
                    log.warn("[caller] {} 发起未知名工具调用，去工具重试一次：{}", forAgent, LlmCallRecorder.describeError(e));
                    CallContext noToolsCtx = ctx.blankTools();
                    long start2 = System.currentTimeMillis();
                    AtomicReference<RouteAttempt> routeRef2 = new AtomicReference<>();
                    try {
                        AtomicReference<Usage> usageRef2 =
                                new AtomicReference<>();
                        AtomicLong firstTokenAt2 =
                                new AtomicLong();
                        int attempt2 = attemptCounter.incrementAndGet();
                        String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                                assembly.withoutTools(), extraAdvisors, eff, null, cancelled, usageRef2,
                                firstTokenAt2, ledger, reasoningTap, routeRef2);
                        noToolsCtx.withRoute(routeRef2.get()).ok(ledger, start2, content, usageRef2.get(),
                                firstTokenAt2.get(), attempt2, maxAttempts);
                        return content;
                    } catch (RuntimeException e2) {
                        noToolsCtx.withRoute(routeRef2.get())
                                .error(start2, e2, attemptCounter.get(), maxAttempts);
                        throw e2;
                    }
                }
                attemptCtx.error(start, e, attempt, maxAttempts);
                throw e;
            }
        });
    }

    /** 单次调用 + 成功观测记录（失败由调用方记录）；disableTools=true 时不注入任何工具（幻觉工具调用的降级路径）。
     *  暂无调用方携带 Goal 上下文：轨迹标识落 NONE（turn/trace/parent NULL，spanId 照常生成） */
    String invokeAndRecord(AgentConfig config, String sessionId, String forAgent,
                           String fallbackSystem, String user, Consumer<String> toolEmitter,
                           boolean disableTools, String model, long start, Advisor... extraAdvisors) {
        AgentRequestSpecFactory.Assembly assembly = assembler.assemblyForRole(forAgent, sessionId, toolEmitter, disableTools);
        String spanId = CallTrace.newSpanId();
        CallTrace eff = new CallTrace(null, null, null, spanId, null);
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly), null, null, spanId, null, null);
        AtomicReference<Usage> usageRef =
                new AtomicReference<>();
        AtomicLong firstTokenAt = new AtomicLong();
        AtomicReference<RouteAttempt> routeRef = new AtomicReference<>();
        String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                assembly, extraAdvisors, eff, null, null, usageRef, firstTokenAt, null, null, routeRef);
        ctx.withRoute(routeRef.get()).ok(null, start, content, usageRef.get(), firstTokenAt.get(), 1, 1);
        return content;
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
     * @param modelId 失败时用于重建客户端的部署模型 id（null 则跳过丢池）
     */
    Flux<String> tokenStreamWithWatchdog(ChatClient.ChatClientRequestSpec spec,
                                         AtomicReference<Usage> usageRef,
                                         BudgetLedger ledger,
                                         AtomicLong prevTotal,
                                         AtomicLong firstTokenAt,
                                         Long modelId,
                                         Consumer<String> reasoningTap) {
        return pipeline.tokenStreamWithWatchdog(spec, usageRef, ledger, prevTotal, firstTokenAt, modelId,
                reasoningTap);
    }

    /**
     * 路由感知的响应式流：按成员优先级尝试，只有"尚未输出任何 token"时才切换下一个成员；
     * 同成员重试仍由调用方外层的 {@link #streamWithRetry} 负责。
     */
    Flux<String> streamWithRoute(AgentConfig config, String sessionId, String forAgent,
                                 String fallbackSystem, String user,
                                 AgentRequestSpecFactory.Assembly assembly, CallTrace trace,
                                 BooleanSupplier cancelled,
                                 AtomicReference<Usage> usageRef,
                                 AtomicLong firstTokenAt,
                                 Consumer<String> reasoningTap,
                                 AtomicReference<RouteAttempt> routeRef) {
        RoutePlan plan = specFactory.routePlan(config, forAgent);
        return routeExecutor.stream(plan, cancelled,
                member -> {
                    // 观测口径：真正发起请求前记录本次命中的成员
                    if (routeRef != null) {
                        routeRef.set(RouteAttempt.at(plan, plan.members().indexOf(member)));
                    }
                    return tokenStreamWithWatchdog(
                            specFactory.build(config, sessionId, forAgent, fallbackSystem, user, assembly,
                                    trace, member),
                            usageRef, null, null, firstTokenAt, member.modelId(), reasoningTap);
                },
                new RouteExecutor.AttemptObserver() {
                    @Override
                    public void onFailure(RoutePlan.Member member, int attempt, Long fallbackFrom,
                                          RuntimeException error, FailurePolicy.Action action) {
                        // 与阻塞路径同口径：失败按成员 id 丢池（4xx 由判定矩阵放行前已在此丢池）
                        if (llmClientRegistry != null) {
                            llmClientRegistry.invalidate(member.modelId());
                        }
                    }

                    @Override
                    public void onSwitch(RoutePlan.Member from, RoutePlan.Member to) {
                        HarnessMetrics.routeFallback(plan.routeCode(), from.modelId(), to.modelId());
                    }
                });
    }

    /**
     * 直答路径观测上下文：与阻塞路径同源（{@link LlmCallObserver}/{@link CallContext} 单一口径），
     * 供路径 A 的成功/失败/取消行落库（attempt/max_attempts 重试口径与阻塞路径同构）。
     */
    CallContext ctxFor(String sessionId, String forAgent, String model,
                       PromptAssembler.PromptAttachments attachments, CallTrace trace) {
        return new CallContext(observer, sessionId, forAgent, model, attachments,
                trace.turnId(), trace.traceId(), trace.spanId(), trace.parentSpan(), null);
    }

    /**
     * 响应式流式重试通道（路径 A 直答；与阻塞 stream 重试循环同口径）：
     * 决策（{@link LlmRetry#shouldRetry}）/退避/上限/递归统一在此——非阻塞等待用
     * {@code Mono.delay}（阻塞循环用 {@code Thread.sleep}，reactor 线程不可阻塞）；
     * 取消走 Reactor cancel 信号（非 error）天然不进重试。
     *
     * @param attemptFlux        单次尝试工厂：入参尝试序号（1 起），返回该尝试的流；调用方在工厂内
     *                           新建 spec 并回写尝试序号/起始时刻（观测行 attempt 口径）——路径 A 的
     *                           reasoningTap/旁路 sink 生命周期绑定整轮，无法下沉到本引擎
     * @param hasPartialOutput   部分输出探测：已有 token 下发不可回滚 → 不重试
     * @param onRetryableFailure 可重试失败回调（决策通过、即将重试才触发）：调用方在此落被丢弃
     *                           尝试的观测行、告警日志并重置每尝试观测状态
     */
    Flux<String> streamWithRetry(IntFunction<Flux<String>> attemptFlux,
                                 BooleanSupplier hasPartialOutput,
                                 BiConsumer<Integer, Throwable> onRetryableFailure) {
        return streamWithRetry(attemptFlux, hasPartialOutput, onRetryableFailure, 1);
    }

    private Flux<String> streamWithRetry(IntFunction<Flux<String>> attemptFlux,
                                         BooleanSupplier hasPartialOutput,
                                         BiConsumer<Integer, Throwable> onRetryableFailure,
                                         int attempt) {
        return attemptFlux.apply(attempt)
                .onErrorResume(e -> {
                    if (!retry.shouldRetry(e, attempt, hasPartialOutput.getAsBoolean())) {
                        return Flux.error(e);
                    }
                    onRetryableFailure.accept(attempt, e);
                    return Mono.delay(Duration.ofMillis(retry.backoffDelayMillis(attempt)))
                            .thenMany(streamWithRetry(attemptFlux, hasPartialOutput, onRetryableFailure,
                                    attempt + 1));
                });
    }

    /**
     * 重试告知：向流内插一条进度行（前端进度轨迹可见），避免重试期间长时间无输出被误认为卡死。
     * 无 emitter（纯阻塞收集路径）时静默；detail 不含底层错误原文，网络细节只留在服务端日志。
     */
    private static void emitRetryNotice(Consumer<String> toolEmitter, int failedAttempt, int maxAttempts) {
        if (toolEmitter == null) {
            return;
        }
        toolEmitter.accept(ProgressLine.encode(
                "重试", "模型调用失败，正在重试（第 " + (failedAttempt + 1) + "/" + maxAttempts + " 次）"));
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
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, CallOptions.none());
    }

    /**
     * 角色策略流式入口：装配经 {@link CallSpecAssembler#assemblyForRole} 按角色生成，
     * 可选参数见 {@link CallOptions}（取消令牌 / 预算账本 / 轨迹 / 思考透传）。
     */
    public String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, CallOptions opts) {
        return streamWithAssembly(sessionId, forAgent, fallbackSystem, user, onToken,
                assembler.assemblyForRole(forAgent, sessionId, opts.toolEmitter(), false),
                opts.advisors().toArray(new Advisor[0]), opts.cancelled(), opts.ledger(),
                opts.trace(), opts.reasoningTap());
    }

    /**
     * Assembly 直传入口（路径 A 薄适配用：装配差异由调用方声明，不经角色策略）。
     * 带取消令牌与预算账本的流式调用（发起前熔断判定 + 成功结束后估算兜底入账/真实增量入账）。
     * 轨迹标识同 {@link #callWithAssembly}：spanId 发起前生成，turnId/traceId/parentSpan 经
     * {@link CallTrace} 传入。
     */
    String streamWithAssembly(String sessionId, String forAgent, String fallbackSystem, String user,
                              Consumer<String> onToken, AgentRequestSpecFactory.Assembly assembly,
                              Advisor[] extraAdvisors, BooleanSupplier cancelled, BudgetLedger ledger,
                              CallTrace trace, Consumer<String> reasoningTap) {
        CallTrace t = trace == null ? CallTrace.NONE : trace;
        // spanId 原则上由本发起点生成；编排树 lead 场景由编排器预生成（t.spanId 非 null）沿用
        String spanId = t.spanId() != null ? t.spanId() : CallTrace.newSpanId();
        CallTrace eff = t.withSpan(spanId);
        CallContext ctx = new CallContext(observer, sessionId, forAgent,
                null, attachmentsFor(forAgent, assembly), eff.turnId(), eff.traceId(),
                spanId, eff.parentSpan(), null);
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelAndRecord(ctx, System.currentTimeMillis(), 1, retry.maxAttempts());
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
            AtomicReference<Usage> usageRef =
                    new AtomicReference<>();
            // 首 token 绝对时间戳（观测 TTFT，0=未触发）
            AtomicLong firstTokenAt = new AtomicLong();
            // roundtrip 增量记账游标（口径同 streamAttempt，见其注释）
            AtomicLong prevTotal = new AtomicLong();
            // 观测口径：本次尝试实际命中的路由成员（失败切换时由 pipeline 回写）
            AtomicReference<RouteAttempt> routeRef = new AtomicReference<>();
            try {
                String out = pipeline.streamCore(config, sessionId, forAgent, fallbackSystem, user, assembly,
                        extraAdvisors, eff, collected, onToken, cancelled, usageRef, prevTotal,
                        firstTokenAt, ledger, reasoningTap, routeRef);
                // streamUsage 回传真实 usage 时记真实值，无则按已收输出文本近似估算（原口径兜底）；
                // 账本兜底入账：无真实 usage 帧时按输出估算补记（有则增量已在流帧上记账，不重复）
                ctx.withRoute(routeRef.get()).ok(ledger, start, out, usageRef.get(),
                        firstTokenAt.get(), attempt, retry.maxAttempts());
                return out;
            } catch (RuntimeException e) {
                boolean isCancel = e instanceof CancellationException
                        || (cancelled != null && cancelled.getAsBoolean());
                CallContext attemptCtx = ctx.withRoute(routeRef.get());
                if (isCancel) {
                    throw cancelAndRecord(attemptCtx, start, attempt, retry.maxAttempts());
                }
                attemptCtx.error(start, e, attempt, retry.maxAttempts());
                // 账户级硬错误：与阻塞（call）路径同口径转换，不重试直接抛人话异常
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    throw translated;
                }
                boolean canRetry = retry.shouldRetry(e, attempt, collected.length() > 0);
                if (canRetry) {
                    // 前端告知：重试期间流内无输出，插一条进度行避免被误认为卡死
                    emitRetryNotice(assembly.toolEmitter(), attempt, retry.maxAttempts());
                    // 首次失败带全量堆栈（排错现场），后续重试紧凑——口径与 LlmRetry 一致
                    if (attempt == 1) {
                        log.warn("[caller] {} 流式调用失败，将重试（attempt {}/{}）：{}", forAgent,
                                attempt + 1, retry.maxAttempts(),
                                LlmCallRecorder.describeError(e), e);
                    } else {
                        log.warn("[caller] {} 流式调用重试失败（attempt {}/{}）：{}", forAgent,
                                attempt, retry.maxAttempts(), LlmCallRecorder.describeError(e));
                    }
                    retry.waitBeforeRetry(attempt);
                    continue;
                }
                log.warn("[caller] {} 流式调用最终失败：{}", forAgent, LlmCallRecorder.describeError(e), e);
                throw e;
            }
        }
        // 理论不可达（maxAttempts>=1）
        throw new IllegalStateException("stream 重试循环异常退出");
    }

    /**
     * 组装请求（包级：路径 A executeStreamReactive 经此复用统一组装链）：委托
     * {@link AgentRequestSpecFactory#build}，装配差异由调用方传入的 {@code assembly} 声明；
     * trace 为轨迹标识（经 ToolContext 关联工具行，可 null）。
     */
    ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String sessionId, String forAgent,
                                               String fallbackSystem, String user,
                                               AgentRequestSpecFactory.Assembly assembly, CallTrace trace,
                                               Advisor... extraAdvisors) {
        return specFactory.build(config, sessionId, forAgent, fallbackSystem, user, assembly, trace, extraAdvisors);
    }

    /** 查 agent 表配置（每次 LLM 调用仅查一次，观测记录与请求组装共用）；命中在此打一条 INFO——
     *  本方法是全部真实 LLM 调用（路径 A 直答 + 编排 lead/子任务/聚合）的唯一配置读取点，
     *  其余旁路读取（RAG 预取 knowledge、思考透传判定）经 AgentConfigProvider 已降 debug */
    private AgentConfig configOf(String forAgent) {
        AgentConfig config = agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
        if (config != null) {
            // 每条 LLM 调用都会走到这里（含每个子任务），命中明细只在排错时需要，压到 debug；
            // 「未命中/回退」由 AgentConfigProvider 的 warn 覆盖，不会丢信号
            log.debug("[agent配置] agentName='{}' -> modelId={}, model={}, prompt={}, knowledge={}, thinking={}",
                    forAgent, config.modelId(), config.model(), config.prompt(),
                    config.knowledge(), config.thinking());
        }
        return config;
    }

    /** 观测名单计算（llm_call_log 装配名单列）：assembly.disableTools 时工具/子集置空、技能保留。
     *  包级可见：路径 A 响应式流的终结钩子（GeneralAssistantAgent.recordCall）同口径复用 */
    PromptAssembler.PromptAttachments attachmentsFor(String forAgent,
            AgentRequestSpecFactory.Assembly assembly) {
        return assembler.attachmentsFor(forAgent, assembly);
    }

    /** 取消观测 + 构造上抛异常（stream 两处「落库 + 抛新取消异常」共用；call 通道复用捕获异常本体，不经此） */
    private static CancellationException cancelAndRecord(CallContext ctx, long start, int attempt, int maxAttempts) {
        CancellationException ce = CallCancellation.cancelException();
        ctx.error(start, ce, attempt, maxAttempts);
        return ce;
    }

}
