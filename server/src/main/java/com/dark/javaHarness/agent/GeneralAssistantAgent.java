package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.agent.orchestrate.BranchProgressListener;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.observe.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

/**
 * 通用 ChatModel 驱动 Agent（路径 A）：会话绑定 agent 的单轮问答执行者。
 *
 * <p>执行层已收敛到 {@link AgentChatCaller}（与路径 B 编排同一引擎）：本类只负责
 * 「查 agent 表配置 → 声明路径 A 装配差异 → 委托调用器」，与 MultiAgentGraphAgent
 * 的角色同构。重试/取消/预算/观测（llm_call_log）与流式管道核（{@code tokenStream}）
 * 均为单一来源，新增 agent 类型自动继承。
 *
 * <p>路径 A 装配差异（{@link #assemblyPathA}）：恒注入会话记忆（ContextAssemblingAdvisor
 * 对「历史 + 本轮」整体归一化与预算裁剪）、无频率惩罚、无工具次数预算、输出封顶走
 * final 档——不经 {@code MemoryPolicy} 角色策略（那是编排路径语义）。
 *
 * <p>模型与系统提示词通过 AgentService.getAgentConfig() 从 agent 表读取（按本实例的
 * agentName 匹配）；system 经 PromptAssembler 按段组装；工具/知识检索/预算由
 * AgentRequestSpecFactory 统一组装链注入。
 *
 * <p>多轮会话记忆基于 session + session_messages 两张表（一对一）；会话写回统一由
 * ChatService 在流结束后负责，本类只读注入。
 */
public class GeneralAssistantAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(GeneralAssistantAgent.class);

    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final String agentName;
    private final AgentService agentService;
    /** 输出封顶（final 档，与编排聚合同为直出用户的最终回答；0 = 不限制），来自 app.context.max-tokens-final */
    private final int maxTokensFinal;
    /** 统一执行器（路径 A/B 收敛）：重试/取消/预算/观测/组装链的单一来源 */
    private final AgentChatCaller chatCaller;
    /** 流式重试策略：响应式直答路径与调用器内部阻塞路径共享同一 LlmRetry 实例（口径/上限单一来源） */
    private final LlmRetry streamRetry;

    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments, recorder, null);
    }

    /** budgets：上下文预算配置（路径 A 会话历史裁剪预算；null 时用内置默认值，单测场景） */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments, recorder, budgets, null);
    }

    /**
     * 全参构造：lazyTools 为 null 时构造禁用态实例（旧构造链/单测场景，工具面全量注入现状）。
     * 正式装配由 ChatAgentConfig 注入共享实例（app.prompt.lazy-tools.enabled 开关）。
     */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                 ToolLazyManager lazyTools) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments,
                recorder, budgets, lazyTools, null, null);
    }

    /**
     * 全参构造（含 skill 装配）：promptAssembler/skillManager 为 null 时内部裸构建
     * （旧构造链/单测场景，无 skill 段、不注册 load_skill）；正式装配由 ChatAgentConfig/
     * AgentRegistry 注入共享实例（skill 段提供者与 toolLazyManager 开关对齐）。
     */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                 ToolLazyManager lazyTools,
                                 PromptAssembler promptAssembler,
                                 SkillManager skillManager) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments,
                recorder, budgets, lazyTools, promptAssembler, skillManager, null, null);
    }

    /**
     * 全参构造（含 skill 装配与 RAG 知识检索）：knowledgeRetriever 仅知识库启用时非 null
     * （AgentRegistry 经 ObjectProvider 注入），null 时无知识段注入、行为退化现状。
     * 内部构建 {@link AgentChatCaller} 共享执行引擎（组装链/重试/观测同源）。timeouts 透传
     * 给调用器作流式空闲超时（null 时走调用器默认）。
     */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                 ToolLazyManager lazyTools,
                                 PromptAssembler promptAssembler,
                                 SkillManager skillManager,
                                 com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                                 com.dark.javaHarness.config.ChatTimeoutProperties timeouts) {
        this.agentName = agentName;
        this.agentService = agentService;
        ToolLazyManager effectiveLazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        // 工具索引段与延迟加载同源：开启时索引段追加 expand_tool 使用引导（与轻量态工具面对齐）
        PromptAssembler effectiveAssembler = promptAssembler != null ? promptAssembler
                : new PromptAssembler(agentService, toolAssignments, List.of(), List.of(), effectiveLazy.isEnabled());
        // budgets 缺省时取配置类默认（与 application.yaml 生产默认一致的单一数值源），
        // 不在此处硬编码兜底数字，避免代码/yaml 双口径漂移
        com.dark.javaHarness.config.ContextBudgetProperties effectiveBudgets =
                budgets != null ? budgets : new com.dark.javaHarness.config.ContextBudgetProperties();
        this.maxTokensFinal = effectiveBudgets.getMaxTokensFinal();
        this.streamRetry = new LlmRetry();
        this.chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder,
                streamRetry, effectiveBudgets, effectiveAssembler, memoryStore, effectiveLazy,
                skillManager, knowledgeRetriever, timeouts);
    }

    /** 返回 Agent 名称（用于注册与路由） */
    @Override
    public String name() {
        return agentName;
    }

    /**
     * 同步执行目标：委托统一执行器返回完整回复（底层流式信道，唯一可中止通道；
     * streamUsage 回传真实 usage 时记真实 token）。
     * 会话记忆写回不在此处做，统一由 ChatService 负责（与流式路径保持一致）。
     */
    @Override
    public String execute(Goal goal) {
        log.info("AI agent '{}' 开始处理目标: {}", name(), goal.objective());
        String reply = chatCaller.callWithAssembly(goal.sessionId(), agentName, DEFAULT_SYSTEM_PROMPT,
                goal.objective(), assemblyPathA(null), new Advisor[0], null, null, CallTrace.fromGoal(goal),
                null);
        log.info("AI agent '{}' 得到回复: {}", name(), reply);
        return reply;
    }

    /**
     * 流式执行：委托统一执行器，逐 token 回调 onToken（真正边收边发），阻塞至流结束。
     * 完整结果由调用方在 onToken 回调中拼接（如 AgentService 收集后写入 Goal.summary）；
     * 记忆持久化不在此处做，交给 ChatService 在流结束后统一写回。
     */
    @Override
    public void executeStream(Goal goal, Consumer<String> onToken) {
        log.info("AI agent '{}' 开始流式处理目标: {}", name(), goal.objective());
        chatCaller.streamWithAssembly(goal.sessionId(), agentName, DEFAULT_SYSTEM_PROMPT, goal.objective(),
                onToken, assemblyPathA(null), new Advisor[0], null, null, CallTrace.fromGoal(goal), null);
        log.info("AI agent '{}' 流式输出完成", name());
    }

    /**
     * 响应式流式执行：真·逐 token 发射。管道核复用 {@link AgentChatCaller#tokenStream}
     * （usage 捕获/空帧跳过与路径 B 同源），本类仅接续路径 A 专属段：
     * 工具调用起止经旁路 sink 合并进同一 Flux（ProgressLine 进度行，CLI 据此展示工具调用行）、
     * 终结钩子落观测。会话记忆写入统一由 ChatService 在流结束后负责，此处不做。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        log.info("AI agent '{}' 开始响应式流式处理目标: {}", name(), goal.objective());
        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        // spanId 在调用发起前生成（本线程，纯内存）；turnId/traceId 取自 Goal，直答为根调用 parentSpan=null
        String spanId = CallTrace.newSpanId();
        StringBuilder collected = new StringBuilder();
        // 流式异常的真实原因（供应商 4xx/5xx 报错、读超时等）在 Flux 错误通道里，
        // doFinally 只有信号没有异常体——用 doOnError 抓住真实 Throwable 供观测落库
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        // streamUsage 开启后末帧回传真实 usage（无则维持估算兜底）；tokenStream 内捕获
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        // 首 token 绝对时间戳（观测 TTFT，0=未触发）
        AtomicLong firstTokenAt = new AtomicLong();
        // 当前尝试序号与起始时刻（观测行 attempt 口径：重试轮由尝试工厂回写，doFinally 终局行取实时值）
        AtomicInteger attemptRef = new AtomicInteger();
        AtomicLong attemptStartRef = new AtomicLong();
        AgentConfig config = agentService.getAgentConfig(agentName)
                .orElse(new AgentConfig(null, null, null, null));
        // 思考透传旁路（agent 表 thinking 列显示口径：仅控制是否显示思考内容，并不是控制模型是否思考）：
        // thinking=1 时把 reasoningContent delta 编码为「思考 · agent名」进度行经工具旁路 sink 合入 SSE
        //（stage 携带归属 agent，前端折叠块标题据此标注，消除「这是谁在思考」歧义）
        Consumer<String> reasoningTap = Boolean.TRUE.equals(config.thinking())
                ? delta -> BranchProgressListener.tryEmitSerialized(toolEvents,
                        ProgressLine.encode("思考 · " + agentName, delta))
                : null;
        // 关闸挂 merge 之前的主干段（多 Agent 侧同款死锁教训：关闸在 merge 后会循环等待）
        AgentRequestSpecFactory.Assembly assembly =
                assemblyPathA(row -> BranchProgressListener.tryEmitSerialized(toolEvents, row));
        // 观测名单装配期计算一次（llm_call_log 三名单列，与 AgentChatCaller 内聚口径一致）
        PromptAssembler.PromptAttachments attachments = chatCaller.attachmentsFor(agentName, assembly);
        // 观测上下文与阻塞路径同源（LlmCallObserver/CallContext 单一口径）；model 取装配期值
        //（即请求实际使用的模型，比落库时重查 agent 表更真实）
        CallContext ctx = chatCaller.ctxFor(goal.sessionId(), agentName, config.model(), attachments,
                CallTrace.fromGoal(goal).withSpan(spanId));
        // 端点无响应兜底（空闲超时 + 失败丢池）+ 响应式重试自愈（chatCaller.streamWithRetry）：
        // 浏览器 SSE 主回答与阻塞路径共用同一层保护。trace 带 spanId 下传 spec 组装：
        // 工具侧经 ToolContext 关联本调用（parent_span=spanId）
        Flux<String> content = chatCaller.streamWithRetry(
                        attempt -> {
                            attemptRef.set(attempt);
                            attemptStartRef.set(System.currentTimeMillis());
                            return chatCaller.tokenStreamWithWatchdog(
                                            chatCaller.buildSpec(config, goal.sessionId(), agentName,
                                                    DEFAULT_SYSTEM_PROMPT, goal.objective(), assembly,
                                                    CallTrace.fromGoal(goal).withSpan(spanId)),
                                            usageRef, null, null, firstTokenAt, config.model(), reasoningTap)
                                    .doOnNext(collected::append)
                                    .doOnError(streamError::set);
                        },
                        () -> collected.length() > 0,
                        (attempt, e) -> {
                            // 被重试丢弃的失败尝试：按尝试粒度落观测行（ctx.error 与阻塞路径同构）
                            ctx.error(attemptStartRef.get(), asException(e), attempt,
                                    streamRetry.maxAttempts());
                            if (attempt == 1) {
                                // 首次失败带全量堆栈记一次（根因链是排错现场）；后续重试保持紧凑
                                log.warn("[agent-{}] 流式调用失败，将重试（attempt {}/{}）：{}", agentName,
                                        attempt + 1, streamRetry.maxAttempts(),
                                        LlmCallRecorder.describeError(e), e);
                            } else {
                                log.warn("[agent-{}] 流式调用重试失败（attempt {}/{}）：{}", agentName,
                                        attempt + 1, streamRetry.maxAttempts(),
                                        LlmCallRecorder.describeError(e));
                            }
                            // 重试前重置每尝试观测状态：失败尝试的 usage/TTFT/错误不得泄漏进后续尝试
                            usageRef.set(null);
                            firstTokenAt.set(0);
                            streamError.set(null);
                        })
                .doFinally(sig -> {
                    // 终局行（成功/最终失败/取消）统一在此落库，时长/startedAt 取终局尝试口径
                    //（与阻塞路径 attempt 粒度同构；单尝试场景即整轮）。真实错误优先于信号类型：
                    // mergeWith(toolEvents) 下 Reactor 对出错源头执行 cancel——doFinally 收到
                    // CANCEL 而非 ON_ERROR（实测信号语义），先看信号会把模型/网络真实报错
                    // 误记成「客户端断开」
                    Throwable err;
                    if (streamError.get() != null) {
                        err = streamError.get();
                    } else if (sig == SignalType.CANCEL) {
                        err = new IllegalStateException("客户端断开（stream cancel）");
                    } else if (sig == SignalType.ON_ERROR) {
                        err = new IllegalStateException("流式异常终止");
                    } else {
                        err = null;
                    }
                    if (err == null) {
                        ctx.ok(null, attemptStartRef.get(), collected.toString(), usageRef.get(),
                                firstTokenAt.get(), attemptRef.get(), streamRetry.maxAttempts());
                    } else {
                        ctx.error(attemptStartRef.get(), asException(err), attemptRef.get(),
                                streamRetry.maxAttempts());
                    }
                    BranchProgressListener.tryCompleteSerialized(toolEvents);
                });
        return content.mergeWith(toolEvents.asFlux());
    }

    /** ctx.error/ok 只收 Exception：Error 形态（OOM 等）包装为 IllegalStateException 保语义不炸钩子 */
    private static Exception asException(Throwable t) {
        return t instanceof Exception ex ? ex : new IllegalStateException(String.valueOf(t), t);
    }

    /** 路径 A 装配差异声明：恒注入记忆、无频率惩罚、输出封顶 final 档（工具次数/结果预算两路径统一生效；路径 A 不挂工具包，extraToolNames=null） */
    private AgentRequestSpecFactory.Assembly assemblyPathA(Consumer<String> toolEmitter) {
        return new AgentRequestSpecFactory.Assembly(toolEmitter, false, true, false, maxTokensFinal, null);
    }
}
