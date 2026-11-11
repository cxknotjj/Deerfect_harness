package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.config.StreamConnectionLimiter;
import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.RouteInfo;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.contract.model.GoalStatus;
import com.dark.javaHarness.view.SseProtocol;
import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.contract.api.AgentService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 流式聊天执行段（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 响应式路由/执行、COMPLEX 降级重答、SSE 组装（进度/token/meta/[DONE]）、断连取消、
 * 过载限流与续跑入口。
 */
@Component
class ChatStreamPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamPipeline.class);

    private final AgentService agentService;
    private final StreamConnectionLimiter streamConnectionLimiter;
    private final RouteDecider routeDecider;
    private final TurnPersistence persistence;
    private final ReplyDecorator decorator;

    /** COMPLEX 编排失败降级开关（app.chat.complex-fallback.enabled）：开启时编排失败降级为会话 Agent 单模型重答一次 */
    private final boolean complexFallbackEnabled;

    ChatStreamPipeline(AgentService agentService, StreamConnectionLimiter streamConnectionLimiter,
                       RouteDecider routeDecider, TurnPersistence persistence, ReplyDecorator decorator,
                       @Value("${app.chat.complex-fallback.enabled:true}") boolean complexFallbackEnabled) {
        this.agentService = agentService;
        this.streamConnectionLimiter = streamConnectionLimiter;
        this.routeDecider = routeDecider;
        this.persistence = persistence;
        this.decorator = decorator;
        this.complexFallbackEnabled = complexFallbackEnabled;
    }

    /**
     * 响应式流式聊天：返回 text/event-stream 格式的 SSE 行文本。
     * - 无 sessionId 时在 boundedElastic 上自动建档
     * - 逐 token 产出 {@code event: token} + {@code data: <token>}，结束后产出 [DONE]，末尾产出 meta 事件
     * - agent 流出错时产出 error 事件 + 错误信息，并以 meta(FAILED) 收尾，避免调用方悬挂
     */
    Flux<String> stream(ChatRequest request) {
        // 过载保护：同步占名额（超限立即 429，不排队不占线程池）；流终结（complete/error/cancel）统一释放
        streamConnectionLimiter.tryAcquire();
        Flux<String> flux;
        try {
            flux = streamInternal(request);
        } catch (RuntimeException e) {
            streamConnectionLimiter.release();
            throw e;
        }
        return flux
                // 释放点放在终止信号传播之前（doOnTerminate/doOnCancel 互斥，不会重复释放）：
                // doFinally 在 onComplete 之后才执行，客户端拿到终止信号立刻重连会撞上尚未
                // 归还的名额（实测稳定复现 429）
                .doOnTerminate(streamConnectionLimiter::release)
                .doOnCancel(streamConnectionLimiter::release);
    }

    /** 实际流式编排（限流包裹层内）：无 sessionId 时在 boundedElastic 上自动建档 */
    private Flux<String> streamInternal(ChatRequest request) {
        // 轮次/调用链标识：与同步 chat 同口径，一轮（含降级重答）共用；纯内存生成，订阅前即确定
        String turnId = TurnPersistence.newTurnId();
        String traceId = TurnPersistence.newTurnId();
        Mono<TurnPersistence.SessionCtx> sessionMono =
                persistence.ensureSessionReactive(request.sessionId(), request.message());

        return sessionMono.flatMapMany(ctx -> {
            // 请求携带 agentId 即视为「会话内切换 Agent」：同步更新 session 表 agent_id，
            // 会话档案与实际路由保持一致（新建会话则从默认 1 修正为请求指定的 Agent）。
            // 失败只告警不中断：随后统一判定按会话绑定（无绑定/失效回退 general）继续路由；
            // 绑定成功则本轮跳过判定，由指定 Agent 走 agentic 链路
            final boolean explicitAgent = request.agentId() != null
                    && persistence.bindAgentQuietly(ctx.sid(), request.agentId());
            // 路由策略（2026-09-27 用户决策）同 chat()：general 会话统一判定（SIMPLE/COMPLEX 分流）；
            // 绑定专属 Agent（≠general）跳过判定与预取，由该 Agent 携带自身工具直答；
            // 请求显式指定 agentId（绑定成功，含 general）同样跳过判定
            String boundAgent = persistence.sessionAgentName(ctx.sid());
            boolean useJudge = AgentConstants.DEFAULT_AGENT.equals(boundAgent);
            AtomicReference<RouteInfo> routeRef = new AtomicReference<>();
            // 首帧先行：进度行在订阅时立即发出（judge 是同步阻塞调用，挪入 boundedElastic 防止
            // 「连接已建立却零字节」窗口被外部掐断；专属/显式 Agent 无判定环节，首帧直出行「直答」）
            boolean judgeThisTurn = useJudge && !explicitAgent;
            return toSseBody(Flux.concat(
                    Flux.just(ProgressLine.encode("路由", judgeThisTurn ? "判定中…" : "直答")),
                    Mono.fromCallable(() -> {
                        RouteDecider.RoutingOutcome routing =
                                routeDecider.resolve(boundAgent, request, ctx.sid(), turnId, explicitAgent);
                        routeRef.set(routing.plan().toInfo());
                        String resolvedAgent = routing.agent();
                        Flux<String> agentStream = agentService.executeStreamReactive(resolvedAgent,
                                request.message(), ctx.sid(), turnId, traceId, request.channel());
                        // COMPLEX 编排失败降级：编排自身异常（客户端断开走 cancel，不触发 onErrorResume）时
                        // 降级为会话 Agent 单模型重答一次，一层兜底不递归；Agentic 链路不做 Graph 升级
                        if (complexFallbackEnabled && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
                            agentStream = withComplexFallback(agentStream, request.message(), ctx.sid(),
                                    turnId, traceId, request.channel());
                        }
                        // 路径去向可见化：Agentic / Graph 编排（带置信度与决策来源）
                        String path = routing.plan().graph()
                                ? "Graph 编排路径（置信度 " + RouteDecider.formatConfidence(routing.plan().confidence()) + "）"
                                : "Agentic 路径（置信度 " + RouteDecider.formatConfidence(routing.plan().confidence()) + "）";
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
                                             String turnId, String traceId, String channel) {
        return orchestration.onErrorResume(e -> {
            log.warn("[route] 编排失败，降级单模型重答：{}", safeMessage(e));
            HarnessMetrics.graphFallback("to_agentic");
            return Flux.concat(
                    Flux.just(ProgressLine.encode("编排", "失败，降级为单模型重答")),
                    // 降级重答仍属同一轮同一执行链：turnId/traceId 均沿用入口生成值（轨迹同树）
                    agentService.executeStreamReactive(persistence.sessionAgentName(sessionId), message, sessionId,
                                    turnId, traceId, channel)
                            .onErrorMap(e2 -> new IllegalStateException(
                                    "编排失败: " + safeMessage(e) + "；重答失败: " + safeMessage(e2), e2)));
        });
    }

    /** 复杂编排断点续跑：校验 goal 状态后走 multi-agent 检查点续跑（SSE 输出同 streamReactive）。 */
    Flux<String> resume(String goalId) {
        Goal goal = persistence.requireResumable(goalId);
        // 过载保护与 streamReactive 同口径：校验通过后才占名额，流终结统一释放
        streamConnectionLimiter.tryAcquire();
        Flux<String> flux;
        try {
            RouteInfo resumeRoute = RoutePlan.graph(1.0, List.of(), List.of(),
                    "resume from orchestration checkpoint", RoutePlan.Source.RESUME).toInfo();
            flux = toSseBody(SseEncoder.withAgentProgress(AgentConstants.MULTI_AGENT,
                    agentService.resumeStreamReactive(goal)),
                    goal.sessionId(), false, goal.objective(), goal.id(),
                    new AtomicReference<>(resumeRoute));
        } catch (RuntimeException e) {
            streamConnectionLimiter.release();
            throw e;
        }
        return flux
                // 释放点放在终止信号传播之前（doOnTerminate/doOnCancel 互斥，不会重复释放）：
                // doFinally 在 onComplete 之后才执行，客户端拿到终止信号立刻重连会撞上尚未
                // 归还的名额（实测稳定复现 429）
                .doOnTerminate(streamConnectionLimiter::release)
                .doOnCancel(streamConnectionLimiter::release);
    }

    /**
     * SSE 包装公共体（stream 与 resume 共用）：进度/内容行转 SSE 事件 + [DONE] + meta 收尾，
     * 成功后写回会话记忆（user=本次消息 objective，assistant=完整回复），出错发 error 事件。
     *
     * @param goalId     meta 事件携带的目标 ID（resume 场景传 goal.id()；全新 stream 时 null）
     */
    private Flux<String> toSseBody(Flux<String> agentTokens, String sessionId, boolean newSession,
                                   String userMessage, String goalId,
                                   AtomicReference<RouteInfo> routeRef) {
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
                        GoalStatus.SUCCEEDED.name(), null, decorator.recentSources(sessionId),
                        routeRef == null ? null : routeRef.get())))
                .doOnComplete(() -> persistence.writeBackReply(sessionId, userMessage, full.toString(), userSentAt))
                // 客户端断开（Tomcat 报 AsyncRequestNotUsableException/Connection reset）：
                // 框架层 ERROR 堆栈由 ClientAbortLogFilter 降噪，此处统一记可观测 warn 单行
                .doOnCancel(() -> log.warn("[stream] 客户端断开，取消推送与编排：sid={}", sessionId))
                .onErrorResume(ex -> {
                    String err = safeMessage(ex);
                    return Flux.concat(
                            Flux.just("event: " + SseProtocol.EVENT_ERROR + "\ndata: " + err),
                            SseEncoder.metaEvent(sessionId, newSession, goalId,
                                    GoalStatus.FAILED.name(), err, decorator.recentSources(sessionId),
                                    routeRef == null ? null : routeRef.get()));
                });
    }

    /** 异常信息取安全短文案（message 为空时退回类名，避免日志出现 "null"） */
    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
