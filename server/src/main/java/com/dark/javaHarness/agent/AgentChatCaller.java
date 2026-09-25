package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.agent.BudgetLedger.BudgetExceededException;
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
final class AgentChatCaller {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentChatCaller.class);

    /** 流式空闲超时兜底默认（秒）：app.chat.timeouts.stream-idle-timeout-seconds 未配置时生效 */
    static final int DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS = 120;

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
        this.agentService = agentService;
        this.observer = new LlmCallObserver(recorder);
        this.retry = retry;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        ToolLazyManager lazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.specFactory = new AgentRequestSpecFactory(clientRegistry, promptAssembler,
                toolAssignments, lazy, skillManager, memoryStore, this.budgets,
                knowledgeRetriever, recorder);
        java.time.Duration streamIdleTimeout = ChatClientFactory.resolve(
                timeouts != null ? timeouts.getStreamIdleTimeoutSeconds() : null,
                DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS);
        this.pipeline = new AgentChatPipeline(clientRegistry, this.specFactory, streamIdleTimeout);
        this.assembler = new CallSpecAssembler(promptAssembler, memoryStore, this.budgets);
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
        // 编排节点经 trace 重载携带轨迹标识；本重载为无 Goal 上下文场景兜底
        return call(sessionId, forAgent, fallbackSystem, user, toolEmitter, extraAdvisors, cancelled,
                ledger, CallTrace.NONE);
    }

    /** 同上，可携带轨迹标识（编排节点传入：turn/trace 取 Goal，parentSpan 为父调用 span） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor[] extraAdvisors, BooleanSupplier cancelled,
                BudgetLedger ledger, CallTrace trace) {
        return callWithAssembly(sessionId, forAgent, fallbackSystem, user,
                assembler.assemblyForRole(forAgent, sessionId, toolEmitter, false), extraAdvisors, cancelled, ledger,
                trace);
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
                            BooleanSupplier cancelled, BudgetLedger ledger, CallTrace trace) {
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        CallTrace t = trace == null ? CallTrace.NONE : trace;
        // spanId 原则上由本发起点生成；编排树 lead 场景由编排器预生成（t.spanId 非 null）沿用
        String spanId = t.spanId() != null ? t.spanId() : CallTrace.newSpanId();
        // 带 spanId 的完整标识沿调用链下传至 spec 组装（工具侧经 ToolContext 关联）
        CallTrace eff = t.withSpan(spanId);
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly), eff.turnId(), eff.traceId(), spanId,
                eff.parentSpan());
        // 重试可见性：计数器必须在重试循环外创建（放进 lambda 每次尝试都会重置,
        // 重试成功的行会错报 attempt=1）；每次尝试执行 lambda 时自增即第几次尝试
        java.util.concurrent.atomic.AtomicInteger attemptCounter =
                new java.util.concurrent.atomic.AtomicInteger();
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            int maxAttempts = retry.maxAttempts();
            try {
                java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicLong firstTokenAt =
                        new java.util.concurrent.atomic.AtomicLong();
                int attempt = attemptCounter.incrementAndGet();
                String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                        assembly, extraAdvisors, eff, null, cancelled, usageRef, firstTokenAt, ledger);
                ctx.ok(ledger, start, content, usageRef.get(), firstTokenAt.get(), attempt, maxAttempts);
                return content;
            } catch (RuntimeException e) {
                int attempt = Math.max(attemptCounter.get(), 1);
                // 客户端断连中止：记录后立即上抛（CancellationException 不可重试，直接放行）
                if (e instanceof CancellationException) {
                    ctx.error(start, e, attempt, maxAttempts);
                    throw e;
                }
                // 编排预算熔断：政策性中止（非模型错误），记录后立即上抛（不可重试，
                // 编排节点捕获后写「预算超限跳过」占位）
                if (e instanceof BudgetExceededException) {
                    ctx.error(start, e, attempt, maxAttempts);
                    throw e;
                }
                // 账户级硬错误（余额不足/配额耗尽 402/403、鉴权失败 401）：重试无意义，
                // 立即转人话异常向上传播（分类口径收敛于 LlmErrorClassifier）
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    ctx.error(start, e, attempt, maxAttempts);
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
                        int attempt2 = attemptCounter.incrementAndGet();
                        String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                                assembly.withoutTools(), extraAdvisors, eff, null, cancelled, usageRef2,
                                firstTokenAt2, ledger);
                        noToolsCtx.ok(ledger, start2, content, usageRef2.get(), firstTokenAt2.get(), attempt2, maxAttempts);
                        return content;
                    } catch (RuntimeException e2) {
                        noToolsCtx.error(start2, e2, attemptCounter.get(), maxAttempts);
                        throw e2;
                    }
                }
                ctx.error(start, e, attempt, maxAttempts);
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
        CallTrace eff = new CallTrace(null, null, null, spanId);
        CallContext ctx = new CallContext(observer, sessionId, forAgent, model,
                attachmentsFor(forAgent, assembly), null, null, spanId, null);
        java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong firstTokenAt = new java.util.concurrent.atomic.AtomicLong();
        String content = pipeline.streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                assembly, extraAdvisors, eff, null, null, usageRef, firstTokenAt, null);
        ctx.ok(null, start, content, usageRef.get(), firstTokenAt.get(), 1, 1);
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
     * @param model 失败时用于重建客户端的模型名（null/未命中注册表则跳过丢池）
     */
    Flux<String> tokenStreamWithWatchdog(ChatClient.ChatClientRequestSpec spec,
                                         java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                         BudgetLedger ledger,
                                         java.util.concurrent.atomic.AtomicLong prevTotal,
                                         java.util.concurrent.atomic.AtomicLong firstTokenAt,
                                         String model) {
        return pipeline.tokenStreamWithWatchdog(spec, usageRef, ledger, prevTotal, firstTokenAt, model);
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
        // 编排节点经 trace 重载携带轨迹标识；本重载为无 Goal 上下文场景兜底
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, toolEmitter, extraAdvisors,
                cancelled, ledger, CallTrace.NONE);
    }

    /** 同上，可携带轨迹标识（编排聚合节点传入：turn/trace 取 Goal，parentSpan 为 lead 的 span） */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor[] extraAdvisors,
                  BooleanSupplier cancelled, BudgetLedger ledger, CallTrace trace) {
        return streamWithAssembly(sessionId, forAgent, fallbackSystem, user, onToken,
                assembler.assemblyForRole(forAgent, sessionId, toolEmitter, false), extraAdvisors, cancelled, ledger,
                trace);
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
                              CallTrace trace) {
        CallTrace t = trace == null ? CallTrace.NONE : trace;
        // spanId 原则上由本发起点生成；编排树 lead 场景由编排器预生成（t.spanId 非 null）沿用
        String spanId = t.spanId() != null ? t.spanId() : CallTrace.newSpanId();
        CallTrace eff = t.withSpan(spanId);
        CallContext ctx = new CallContext(observer, sessionId, forAgent,
                null, attachmentsFor(forAgent, assembly), eff.turnId(), eff.traceId(),
                spanId, eff.parentSpan());
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
            java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 首 token 绝对时间戳（观测 TTFT，0=未触发）
            java.util.concurrent.atomic.AtomicLong firstTokenAt = new java.util.concurrent.atomic.AtomicLong();
            // roundtrip 增量记账游标（口径同 streamAttempt，见其注释）
            java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
            try {
                String out = pipeline.streamCore(config, sessionId, forAgent, fallbackSystem, user, assembly,
                        extraAdvisors, eff, collected, onToken, cancelled, usageRef, prevTotal,
                        firstTokenAt, ledger);
                // streamUsage 回传真实 usage 时记真实值，无则按已收输出文本近似估算（原口径兜底）；
                // 账本兜底入账：无真实 usage 帧时按输出估算补记（有则增量已在流帧上记账，不重复）
                ctx.ok(ledger, start, out, usageRef.get(), firstTokenAt.get(), attempt, retry.maxAttempts());
                return out;
            } catch (RuntimeException e) {
                boolean isCancel = e instanceof CancellationException
                        || (cancelled != null && cancelled.getAsBoolean());
                if (isCancel) {
                    throw cancelAndRecord(ctx, start, attempt, retry.maxAttempts());
                }
                ctx.error(start, e, attempt, retry.maxAttempts());
                // 账户级硬错误：与阻塞（call）路径同口径转换，不重试直接抛人话异常
                RuntimeException translated = LlmErrorClassifier.translate(e, model);
                if (translated != null) {
                    throw translated;
                }
                boolean partialOutput = collected.length() > 0;
                boolean canRetry = !partialOutput && LlmRetry.isRetryable(e) && attempt < retry.maxAttempts();
                if (canRetry) {
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

    /** 查 agent 表配置（每次 LLM 调用仅查一次，观测记录与请求组装共用） */
    private AgentConfig configOf(String forAgent) {
        return agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
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
