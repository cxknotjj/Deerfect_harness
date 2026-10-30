package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.RouteInfo;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.enums.SseProtocol;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.ReplyPostProcessor;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.route.RagPrefetcher;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天用例服务实现：承载聊天完整业务编排。
 * - 无 sessionId 时自动建档
 * - 同步聊天走 Agent 单次调用（executeSync）
 * - 流式聊天走响应式 ({@link #streamReactive})
 */
@Service
public class ChatServiceImpl implements ChatService {

    /** SSE 事件名/结束标记等协议常量统一在 {@link SseProtocol}（与 CLI 端共用） */

    private final AgentService agentService;
    private final SessionService sessionService;
    private final RouteJudge routeJudge;
    private final GoalService goalService;
    /** 知识检索器（RAG 出处透出源）；null 时 meta.sources 恒空（知识库禁用场景，Demo 规模 pragmatic 方案） */
    private final com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever;

    /** 流式连接限流器（stream/resume 入口过载保护，超限 429） */
    private final com.dark.javaHarness.config.StreamConnectionLimiter streamConnectionLimiter;

    /** COMPLEX 编排失败降级开关（app.chat.complex-fallback.enabled）：开启时编排失败降级为会话 Agent 单模型重答一次 */
    private final boolean complexFallbackEnabled;

    /** RAG 入口预取器（进程级共享池 bean，KnowledgeConfig 随 knowledgeRetriever 同条件装配）；知识库禁用时缺位 null */
    private final RagPrefetcher ragPrefetcher;

    /** 回复后处理器扩展点（渠道无关的通用 SPI，按注册顺序依次调用）；无实现时缺位，行为与改造前一致 */
    private final org.springframework.beans.factory.ObjectProvider<ReplyPostProcessor> replyPostProcessors;

    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService) {
        this(agentService, sessionService, routeJudge, goalService, null, null,
                new com.dark.javaHarness.config.StreamConnectionLimiter(0), true, null);
    }

    /**
     * Spring 装配入口（多构造需显式标注）：knowledgeRetriever 仅知识库启用时非 null；
     * ragPrefetcher 为进程级共享池 bean（KnowledgeConfig 同条件装配），经 ObjectProvider
     * null 安全解析——bean 缺位（知识库禁用）即为 null，消费点零行为；
     * replyPostProcessors 收集所有 ReplyPostProcessor 实现（无实现时缺位，后处理零行为）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService,
                           com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                           org.springframework.beans.factory.ObjectProvider<RagPrefetcher> ragPrefetcherProvider,
                           com.dark.javaHarness.config.StreamConnectionLimiter streamConnectionLimiter,
                           @org.springframework.beans.factory.annotation.Value("${app.chat.complex-fallback.enabled:true}")
                           boolean complexFallbackEnabled,
                           org.springframework.beans.factory.ObjectProvider<ReplyPostProcessor> replyPostProcessors) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.routeJudge = routeJudge;
        this.goalService = goalService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.streamConnectionLimiter = streamConnectionLimiter;
        this.complexFallbackEnabled = complexFallbackEnabled;
        // 预取器不再随本类构造（会话 agent 名解析留守宿主），改为注入共享池 bean（缺位 null）
        this.ragPrefetcher = ragPrefetcherProvider == null ? null : ragPrefetcherProvider.getIfAvailable();
        this.replyPostProcessors = replyPostProcessors;
    }

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 同步聊天：无 sessionId 时自动建档，调 general Agent 同步执行并写回会话记忆 */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 发送时刻在入口捕获（写回 user 消息快照用，历史回显真实发送时间）
        long userSentAt = System.currentTimeMillis();
        // 轮次标识：一条用户消息触发的完整处理（route-judge + 直答/编排 + 可能的降级重答）共用
        String turnId = newTurnId();
        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession("anonymous", request.message());
            newSession = true;
        }

        // agentId 仅作会话绑定副作用（失败告警不中断）；绑定成功即视为「用户显式指定 Agent」，
        // 本轮直接走该 Agent 的 agentic 单 Agent 链路，不再做路由判定
        boolean explicitAgent = false;
        if (request.agentId() != null) {
            explicitAgent = bindAgentQuietly(sessionId, request.agentId());
        }
        // 路由策略（2026-09-27 用户决策）：绑定专属 Agent（≠general）只走简单链路——跳过
        // routeJudge 与 RAG 预取汇合，由该 Agent 携带自身工具直答（如 wms 仓储专员：编排会把
        // 子任务派给无 wms 工具的通用专家，直答才能查到真实数据）；general 会话保持统一判定
        // （SIMPLE 由 general 直答 / COMPLEX 进 multi-agent 编排）；请求显式携带 agentId 且绑定成功
        // 时（含通用 general），同样跳过判定，由指定 Agent 走 agentic 链路
        String boundAgent = sessionAgentName(sessionId);
        RoutingOutcome routing = resolveRouting(boundAgent, request, sessionId, turnId, explicitAgent);
        String resolvedAgent = routing.agent();

        Goal goal = agentService.executeSync(resolvedAgent, request.message(), sessionId, turnId);
        // COMPLEX 编排失败降级（同步路径无客户端断开，FAILED 即编排自身失败）：
        // 降级为会话 Agent 单模型重答一次，与流式 withComplexFallback 同语义，一层兜底不递归
        if (complexFallbackEnabled && GoalStatus.FAILED == goal.status()
                && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
            return chatWithComplexFallback(goal, request, sessionId, newSession, userSentAt, turnId);
        }
        ReplyProcessing pr = applyReplyPostProcessors(goal.summary());
        writeBackContext(sessionId, request.message(), goal, pr.text(), userSentAt);

        if (goal.status() == GoalStatus.FAILED) {
            // 失败文案不做后处理（保持原始错误信息），flags 走 failure 的空集
            return ChatResponse.failure(sessionId, newSession, goal.id(), goal.summary(), resolvedAgent,
                    routing.plan().toInfo());
        }
        return ChatResponse.success(sessionId, newSession, goal.id(), pr.text(),
                recentKnowledgeSources(sessionId), resolvedAgent, pr.flags(), routing.plan().toInfo());
    }

    /**
     * 同步路径 COMPLEX 编排失败降级重答：编排 FAILED 时用会话绑定 Agent 单模型重答一次。
     * 重答成功按正常响应返回；重答也失败才返回失败响应，错误信息保留编排失败原因 +
     * 重答失败原因两段（与流式 {@link #withComplexFallback} 错误拼接口径一致）。
     */
    private ChatResponse chatWithComplexFallback(Goal orchestration, ChatRequest request,
                                                 String sessionId, boolean newSession, long userSentAt,
                                                 String turnId) {
        log.warn("[route] 编排失败（goal={}），降级单模型重答：{}", orchestration.id(), orchestration.summary());
        HarnessMetrics.graphFallback("to_agentic");
        String fallbackAgent = sessionAgentName(sessionId);
        RouteInfo fallbackRoute = RoutePlan.agentic(1.0, "graph path failed; fallback to single agent",
                RoutePlan.Source.FALLBACK).toInfo();
        // 降级重答仍属同一轮同一执行链：turnId 沿用本轮值，traceId 复用编排 Goal 的（轨迹同树）
        Goal retry = agentService.executeSync(fallbackAgent, request.message(), sessionId, turnId,
                orchestration.traceId());
        ReplyProcessing pr = applyReplyPostProcessors(retry.summary());
        writeBackContext(sessionId, request.message(), retry, pr.text(), userSentAt);
        if (retry.status() == GoalStatus.FAILED) {
            // 失败文案不做后处理（保持原始双段错误信息）
            return ChatResponse.failure(sessionId, newSession, retry.id(),
                    "编排失败: " + orchestration.summary() + "；重答失败: " + retry.summary(),
                    fallbackAgent, fallbackRoute);
        }
        return ChatResponse.success(sessionId, newSession, retry.id(), pr.text(),
                recentKnowledgeSources(sessionId), fallbackAgent, pr.flags(), fallbackRoute);
    }

    /**
     * 会话内切换 Agent（副作用绑定）：失败只告警不中断，随后统一判定按会话绑定继续路由。
     * sync/stream 两入口共用（原两份重复 try-catch 收编，超长类拆分 2026-09-25）。
     */
    private boolean bindAgentQuietly(String sessionId, Long agentId) {
        try {
            sessionService.switchAgent(sessionId, agentId);
            return true;
        } catch (Exception e) {
            log.warn("[chat] 会话 Agent 同步失败（不影响本次路由）sid={} agentId={}: {}",
                    sessionId, agentId, safeMessage(e));
            return false;
        }
    }

    /**
     * 同步路径写回（user 消息记录真实发送时刻，历史回显时间不失真）：仅成功轮写（FAILED/取消不落上下文）；
     * assistant 文本为回复后处理清洗后的文本（无处理器时与 goal.summary() 一致）。
     */
    private void writeBackContext(String sessionId, String message, Goal goal, String assistantReply, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank() && goal.status() == GoalStatus.SUCCEEDED) {
            writeBackBoth(sessionId, message, assistantReply, userSentAt);
        }
    }

    /** 应用所有回复后处理器（无实现时原样返回、flags 为空，行为与改造前一致） */
    private ReplyProcessing applyReplyPostProcessors(String raw) {
        String text = raw == null ? "" : raw;
        java.util.Set<String> flags = new java.util.LinkedHashSet<>();
        if (replyPostProcessors != null) {
            java.util.stream.Stream<ReplyPostProcessor> stream = replyPostProcessors.orderedStream();
            if (stream != null) {
                for (ReplyPostProcessor p : stream.toList()) {
                    try {
                        ReplyPostProcessor.Processed r = p.process(text);
                        if (r != null) {
                            text = r.text() == null ? "" : r.text();
                            if (r.flags() != null) {
                                flags.addAll(r.flags());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[chat] 回复后处理器异常，跳过：{}", e.getMessage());
                    }
                }
            }
        }
        return new ReplyProcessing(text, java.util.Set.copyOf(flags));
    }

    /** 后处理结果载体 */
    private record ReplyProcessing(String text, java.util.Set<String> flags) {
    }

    /** 写回公共体（sync/stream 两份重复块收编）：user+assistant 双消息落快照 + touch 会话 */
    private void writeBackBoth(String sessionId, String message, String assistantReply, long userSentAt) {
        sessionService.saveContext(sessionId, new UserMessage(message), userSentAt);
        sessionService.saveContext(sessionId, new AssistantMessage(assistantReply), System.currentTimeMillis());
        sessionService.touchSession(sessionId, message);
    }

    /** 建会话所需的会话标识（sid + 是否新建） */
    private record SessionCtx(String sid, boolean newSession) {
    }

    /**
     * 响应式流式聊天：返回 text/event-stream 格式的 SSE 行文本。
     * - 无 sessionId 时在 boundedElastic 上自动建档
     * - 逐 token 产出 {@code event: token} + {@code data: <token>}，结束后产出 [DONE]，末尾产出 meta 事件
     * - agent 流出错时产出 error 事件 + 错误信息，并以 meta(FAILED) 收尾，避免调用方悬挂
     */
    @Override
    public Flux<String> streamReactive(ChatRequest request) {
        // 过载保护：同步占名额（超限立即 429，不排队不占线程池）；流终结（complete/error/cancel）统一释放
        streamConnectionLimiter.tryAcquire();
        Flux<String> flux;
        try {
            flux = streamReactiveInternal(request);
        } catch (RuntimeException e) {
            streamConnectionLimiter.release();
            throw e;
        }
        return flux.doFinally(sig -> streamConnectionLimiter.release());
    }

    /** 实际流式编排（限流包裹层内）：无 sessionId 时在 boundedElastic 上自动建档 */
    private Flux<String> streamReactiveInternal(ChatRequest request) {
        // 轮次/调用链标识：与同步 chat 同口径，一轮（含降级重答）共用；纯内存生成，订阅前即确定
        String turnId = newTurnId();
        String traceId = newTurnId();
        String existing = request.sessionId();
        boolean needNew = existing == null || existing.isBlank();
        Mono<SessionCtx> sessionMono = needNew
                ? Mono.fromCallable(() -> sessionService.createSession("anonymous", request.message()))
                        .map(sid -> new SessionCtx(sid, true))
                        .subscribeOn(Schedulers.boundedElastic())
                : Mono.just(new SessionCtx(existing, false));

        return sessionMono.flatMapMany(ctx -> {
            // 请求携带 agentId 即视为「会话内切换 Agent」：同步更新 session 表 agent_id，
            // 会话档案与实际路由保持一致（新建会话则从默认 1 修正为请求指定的 Agent）。
            // 失败只告警不中断：随后统一判定按会话绑定（无绑定/失效回退 general）继续路由；
            // 绑定成功则本轮跳过判定，由指定 Agent 走 agentic 链路
            final boolean explicitAgent = request.agentId() != null
                    && bindAgentQuietly(ctx.sid(), request.agentId());
            // 路由策略（2026-09-27 用户决策）同 chat()：general 会话统一判定（SIMPLE/COMPLEX 分流）；
            // 绑定专属 Agent（≠general）跳过判定与预取，由该 Agent 携带自身工具直答；
            // 请求显式指定 agentId（绑定成功，含 general）同样跳过判定
            String boundAgent = sessionAgentName(ctx.sid());
            boolean useJudge = AgentConstants.DEFAULT_AGENT.equals(boundAgent);
            java.util.concurrent.atomic.AtomicReference<RouteInfo> routeRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 首帧先行：进度行在订阅时立即发出（judge 是同步阻塞调用，挪入 boundedElastic 防止
            // 「连接已建立却零字节」窗口被外部掐断；专属/显式 Agent 无判定环节，首帧直出行「直答」）
            boolean judgeThisTurn = useJudge && !explicitAgent;
            return toSseBody(Flux.concat(
                    Flux.just(ProgressLine.encode("路由", judgeThisTurn ? "判定中…" : "直答")),
                    Mono.fromCallable(() -> {
                        RoutingOutcome routing = resolveRouting(boundAgent, request, ctx.sid(), turnId, explicitAgent);
                        routeRef.set(routing.plan().toInfo());
                        String resolvedAgent = routing.agent();
                        Flux<String> agentStream = agentService.executeStreamReactive(resolvedAgent, request.message(), ctx.sid(), turnId, traceId);
                        // COMPLEX 编排失败降级：编排自身异常（客户端断开走 cancel，不触发 onErrorResume）时
                        // 降级为会话 Agent 单模型重答一次，一层兜底不递归；Agentic 链路不做 Graph 升级
                        if (complexFallbackEnabled && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
                            agentStream = withComplexFallback(agentStream, request.message(), ctx.sid(), turnId, traceId);
                        }
                        // 路径去向可见化：Agentic / Graph 编排（带置信度与决策来源）
                        String path = routing.plan().graph()
                                ? "Graph 编排路径（置信度 " + formatConfidence(routing.plan().confidence()) + "）"
                                : "Agentic 路径（置信度 " + formatConfidence(routing.plan().confidence()) + "）";
                        return Flux.concat(
                                Flux.just(ProgressLine.encode("路由", "判定完成：" + path)),
                                SseEncoder.withAgentProgress(resolvedAgent, agentStream));
                    }).subscribeOn(Schedulers.boundedElastic())
                      .flatMapMany(flux -> flux)),
                    ctx.sid(), ctx.newSession(), request.message(), null, routeRef);
        });
    }

    /**
     * COMPLEX 编排失败降级重答：编排流异常时先发降级进度行，再用会话绑定 Agent 单模型重答一次。
     * 重答失败保留双段错误（编排原因 + 重答原因），随 error 事件与 meta(FAILED) 透出；
     * 重答走显式单 Agent 通道（executeStreamReactive 具名 agent），不经 RouteJudge/编排二次入口。
     */
    private Flux<String> withComplexFallback(Flux<String> orchestration, String message, String sessionId,
                                             String turnId, String traceId) {
        return orchestration.onErrorResume(e -> {
            log.warn("[route] 编排失败，降级单模型重答：{}", safeMessage(e));
            HarnessMetrics.graphFallback("to_agentic");
            return Flux.concat(
                    Flux.just(ProgressLine.encode("编排", "失败，降级为单模型重答")),
                    // 降级重答仍属同一轮同一执行链：turnId/traceId 均沿用入口生成值（轨迹同树）
                    agentService.executeStreamReactive(sessionAgentName(sessionId), message, sessionId, turnId, traceId)
                            .onErrorMap(e2 -> new IllegalStateException(
                                    "编排失败: " + safeMessage(e) + "；重答失败: " + safeMessage(e2), e2)));
        });
    }

    /** 复杂编排断点续跑：校验 goal 状态后走 multi-agent 检查点续跑（SSE 输出同 streamReactive）。 */
    @Override
    public Flux<String> resume(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            throw new IllegalArgumentException("goalId 不能为空");
        }
        Goal goal = goalService.get(goalId)
                .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
        if (goal.status() == GoalStatus.RUNNING) {
            throw new ResumeConflictException("该目标仍在执行中，无法续跑: " + goalId);
        }
        if (goal.status() == GoalStatus.SUCCEEDED) {
            // 已成功的 goal 再 resume 会把状态拉回 RUNNING（kill 进程后卡死），且检查点回放
            // 只是跳过所有节点重复吐最终结果——直接拒绝，避免误以为有新工作发生
            throw new ResumeConflictException("该任务已完成，无需续跑: " + goalId);
        }
        log.info("[resume] goal '{}' 续跑请求（原状态={}）", goal.id(), goal.status());
        // 过载保护与 streamReactive 同口径：校验通过后才占名额，流终结统一释放
        streamConnectionLimiter.tryAcquire();
        Flux<String> flux;
        try {
            RouteInfo resumeRoute = RoutePlan.graph(1.0, List.of(), List.of(),
                    "resume from orchestration checkpoint", RoutePlan.Source.RESUME).toInfo();
            flux = toSseBody(SseEncoder.withAgentProgress(AgentConstants.MULTI_AGENT, agentService.resumeStreamReactive(goal)),
                goal.sessionId(), false, goal.objective(), goal.id(),
                new java.util.concurrent.atomic.AtomicReference<>(resumeRoute));
        } catch (RuntimeException e) {
            streamConnectionLimiter.release();
            throw e;
        }
        return flux.doFinally(sig -> streamConnectionLimiter.release());
    }

    /**
     * SSE 包装公共体（stream 与 resume 共用）：进度/内容行转 SSE 事件 + [DONE] + meta 收尾，
     * 成功后写回会话记忆（user=本次消息 objective，assistant=完整回复），出错发 error 事件。
     *
     * @param goalId     meta 事件携带的目标 ID（resume 场景传 goal.id()；全新 stream 时 null）
     */
    private Flux<String> toSseBody(Flux<String> agentTokens, String sessionId, boolean newSession,
                                   String userMessage, String goalId,
                                   java.util.concurrent.atomic.AtomicReference<RouteInfo> routeRef) {
        // 发送时刻在组装期捕获（请求到达即记录，写回 user 消息快照用——历史回显真实发送时间）
        long userSentAt = System.currentTimeMillis();
        // doOnNext 收集完整回复，流正常结束后由 doOnComplete 统一写回会话记忆（保持多轮记忆语义）
        // 其中「进度行」（以 ProgressLine.MARK 开头，多 Agent 编排的阶段反馈）不计入会话摘要
        StringBuilder full = new StringBuilder();
        // meta.sources 延迟到 meta 事件发射期求值（Flux.defer）：编排路径的知识检索发生在执行期
        // （lead 出口预取 remember 在组装之后），组装期求值会恒取到空值；直答路径求值结果不变
        return agentTokens
                .doOnNext(row -> { if (!ProgressLine.isProgress(row)) { full.append(row); } })
                .flatMap(SseEncoder::toSseRows)
                .concatWithValues("event: " + SseProtocol.EVENT_TOKEN
                        + "\ndata: " + SseProtocol.DONE_MARKER)
                .concatWith(Flux.defer(() -> SseEncoder.metaEvent(sessionId, newSession, goalId,
                        GoalStatus.SUCCEEDED.name(), null, recentKnowledgeSources(sessionId),
                        routeRef == null ? null : routeRef.get())))
                .doOnComplete(() -> writeBackContext(sessionId, userMessage, full.toString(), userSentAt))
                // 客户端断开（Tomcat 报 AsyncRequestNotUsableException/Connection reset）：
                // 框架层 ERROR 堆栈由 ClientAbortLogFilter 降噪，此处统一记可观测 warn 单行
                .doOnCancel(() -> log.warn("[stream] 客户端断开，取消推送与编排：sid={}", sessionId))
                .onErrorResume(ex -> {
                    String err = safeMessage(ex);
                    return Flux.concat(
                            Flux.just("event: " + SseProtocol.EVENT_ERROR + "\ndata: " + err),
                            SseEncoder.metaEvent(sessionId, newSession, goalId,
                                    GoalStatus.FAILED.name(), err, recentKnowledgeSources(sessionId),
                                    routeRef == null ? null : routeRef.get()));
                });
    }

    /** 流式成功后写回会话记忆（响应式路径：assistant 完整回复已由 doOnNext 收集；user 记录发送时刻） */
    private void writeBackContext(String sessionId, String message, String assistantReply, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank()) {
            writeBackBoth(sessionId, message, assistantReply, userSentAt);
        }
    }

    /** 本次会话最近一次知识命中的出处（RAG 出处透出；禁用/无命中返回 null，旧客户端兼容） */
    private List<KnowledgeSource> recentKnowledgeSources(String sessionId) {
        if (knowledgeRetriever == null) {
            return null;
        }
        List<KnowledgeSource> sources = knowledgeRetriever.recentSources(sessionId);
        return sources.isEmpty() ? null : sources;
    }

    /**
     * 预取提交 + 路径判定并行 + 汇合的收敛入口：sync/stream 共用。
     * 会话 agent 名由入口解析后显式传入（kb 绑定解析内聚预取器，null/空白不入池）。
     */
    private RoutingOutcome resolveRouteWithPrefetch(String agentName, String message, String sessionId,
                                                    String turnId, String routing) {
        return resolveRouteWithPrefetch(agentName, message, sessionId, turnId, routing, null);
    }

    /** forcedPlan 非空时跳过判定（显式指定 Agent 的 agentic 链路），入口 RAG 预取照常提交/汇合。 */
    private RoutingOutcome resolveRouteWithPrefetch(String agentName, String message, String sessionId,
                                                    String turnId, String routing, RoutePlan forcedPlan) {
        Future<?> prefetch = ragPrefetcher == null ? null
                : ragPrefetcher.submit(agentName, sessionId, message, KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        try {
            RoutePlan plan = forcedPlan != null ? forcedPlan : decidePlan(message, sessionId, turnId, routing);
            // 路径分布是 Graph 降级率的分母（fallbacks / graph decisions），与判定同点记账
            HarnessMetrics.routeDecision(plan.graph());
            String resolvedAgent = plan.graph() ? AgentConstants.MULTI_AGENT : sessionAgentName(sessionId);
            if (ragPrefetcher != null) { // 知识库禁用时预取器缺位，判定完成即返回
                ragPrefetcher.await(prefetch);
            }
            log.info("[route] message '{}' -> path={} confidence={} source={} reason='{}' -> agent={}",
                    trimForLog(message), plan.path(), formatConfidence(plan.confidence()),
                    plan.source(), plan.reason(), resolvedAgent);
            return new RoutingOutcome(plan, resolvedAgent);
        } finally {
            if (prefetch != null) {
                prefetch.cancel(true); // 汇合窗口外放弃：中断预取任务，占用即归还共享池
            }
        }
    }

    /** 绑定专属 Agent 的会话跳过判定（Graph 专家没有该 Agent 的领域工具），显式 graph 仅告警。 */
    private RoutingOutcome resolveRouting(String boundAgent, ChatRequest request, String sessionId,
                                          String turnId, boolean explicitAgent) {
        if (explicitAgent) {
            // 用户显式指定 Agent（绑定成功）：本轮固定走该 Agent 的 agentic 链路，不做路由判定；
            // 显式 routing=graph 与「指定 Agent」语义冲突，忽略并告警
            if ("graph".equalsIgnoreCase(request.routing())) {
                log.warn("[route] 请求显式指定 Agent {}，忽略 routing=graph，保持单 Agent 执行", boundAgent);
            }
            RoutePlan plan = RoutePlan.agentic(1.0, "explicit agent: " + boundAgent,
                    RoutePlan.Source.OVERRIDE);
            if (AgentConstants.DEFAULT_AGENT.equals(boundAgent)) {
                // 显式选 general 仍属 agentic 链路：跳过判定，但保留入口 RAG 预取（与自动判定同待遇）
                return resolveRouteWithPrefetch(boundAgent, request.message(), sessionId, turnId, null, plan);
            }
            return new RoutingOutcome(plan, boundAgent);
        }
        if (!AgentConstants.DEFAULT_AGENT.equals(boundAgent)) {
            if ("graph".equalsIgnoreCase(request.routing())) {
                log.warn("[route] 会话绑定专属 Agent {}，忽略 routing=graph，保持单 Agent 执行", boundAgent);
            }
            RoutePlan plan = RoutePlan.agentic(1.0, "session bound agent: " + boundAgent,
                    RoutePlan.Source.BOUND_AGENT);
            return new RoutingOutcome(plan, boundAgent);
        }
        return resolveRouteWithPrefetch(boundAgent, request.message(), sessionId, turnId, request.routing());
    }

    /**
     * 路径决策：显式 routing 优先，否则交给判定器（规则短路 + 灰区 LLM + 置信度策略；
     * 判定器内部已兜底 Agentic，此处不再重复防御）。
     */
    private RoutePlan decidePlan(String message, String sessionId, String turnId, String routing) {
        String mode = routing == null ? "auto" : routing.trim().toLowerCase(java.util.Locale.ROOT);
        if ("agentic".equals(mode)) {
            return RoutePlan.agentic(1.0, "routing override: agentic", RoutePlan.Source.OVERRIDE);
        }
        if ("graph".equals(mode)) {
            return RoutePlan.graph(1.0, List.of(), List.of(), "routing override: graph",
                    RoutePlan.Source.OVERRIDE);
        }
        if (!"auto".equals(mode) && !mode.isBlank()) {
            log.warn("[route] 未知 routing='{}'，按 auto 处理", routing);
        }
        return routeJudge.plan(message, sessionId, turnId);
    }

    /** 置信度展示（两位小数，便于进度行/日志阅读）。 */
    private static String formatConfidence(double confidence) {
        return String.format(java.util.Locale.ROOT, "%.2f", confidence);
    }

    /** 路径决策 + 实际执行 Agent 的绑定结果。 */
    private record RoutingOutcome(RoutePlan plan, String agent) {
    }

    /**
     * 解析会话绑定的 Agent 名（session.agent_id → agent 表 agent_name）。
     * 会话不存在/未绑定/查询失败/agent 行已删时回退默认 general。
     */
    private String sessionAgentName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return AgentConstants.DEFAULT_AGENT;
        }
        try {
            SessionEntity session = sessionService.getSession(sessionId);
            if (session == null || session.getAgentId() == null) {
                return AgentConstants.DEFAULT_AGENT;
            }
            return agentService.findAgentNameById(session.getAgentId().longValue())
                    .orElse(AgentConstants.DEFAULT_AGENT);
        } catch (Exception e) {
            log.warn("[route] 会话 Agent 解析失败，回退默认 sid={}: {}", sessionId, safeMessage(e));
            return AgentConstants.DEFAULT_AGENT;
        }
    }

    /** 异常信息取安全短文案（message 为空时退回类名，避免日志出现 "null"） */
    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** 截断过长的 message 用于日志，避免刷屏 */
    private static String trimForLog(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("[\\r\\n]+", " ");
        return single.length() > 80 ? single.substring(0, 80) + "..." : single;
    }

    /**
     * 生成轮次标识 turn_id（32 位 hex UUID）：一条用户消息触发的完整处理一个，
     * 入口处在调用发起线程生成（纯内存操作，无锁无 IO，观测零主链路影响）。
     */
    private static String newTurnId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}