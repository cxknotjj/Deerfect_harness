package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.enums.SseProtocol;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Jackson 序列化（SseMeta 为 record，默认序列化即可） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    /** 预取汇合窗口（秒）：预取内部已被 app.knowledge.search-timeout-seconds 限时，本值仅为快速 judge 场景的等待上限 */
    private static final long PREFETCH_GRACE_SECONDS = 2;

    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService) {
        this(agentService, sessionService, routeJudge, goalService, null,
                new com.dark.javaHarness.config.StreamConnectionLimiter(0), true);
    }

    /** Spring 装配入口（多构造需显式标注）：knowledgeRetriever 仅知识库启用时非 null */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService,
                           com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                           com.dark.javaHarness.config.StreamConnectionLimiter streamConnectionLimiter,
                           @org.springframework.beans.factory.annotation.Value("${app.chat.complex-fallback.enabled:true}")
                           boolean complexFallbackEnabled) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.routeJudge = routeJudge;
        this.goalService = goalService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.streamConnectionLimiter = streamConnectionLimiter;
        this.complexFallbackEnabled = complexFallbackEnabled;
    }

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 同步聊天：无 sessionId 时自动建档，调 general Agent 同步执行并写回会话记忆 */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 发送时刻在入口捕获（写回 user 消息快照用，历史回显真实发送时间）
        long userSentAt = System.currentTimeMillis();
        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession("anonymous", request.message());
            newSession = true;
        }

        // agentId 仅作会话绑定副作用（失败告警不中断，口径不变）；路由一律统一判定：
        // 指定 Agent 不再跳过 RouteJudge——SIMPLE 由会话绑定 Agent（即刚切换的）直答，
        // COMPLEX 照常进 multi-agent 编排（指定 Agent 仅是编排失败降级重答的落点）
        if (request.agentId() != null) {
            try {
                sessionService.switchAgent(sessionId, request.agentId());
            } catch (Exception e) {
                log.warn("[chat] 会话 Agent 同步失败（不影响本次路由）sid={} agentId={}: {}",
                        sessionId, request.agentId(), safeMessage(e));
            }
        }
        // judge 与 RAG 预取无数据依赖（都只吃用户原始消息）：预取先行提交、与 judge 并行，
        // judge 完成后小幅汇合——预取结果进 KnowledgeRetriever 请求级缓存，组装期短路命中
        ExecutorService prefetchPool = newPrefetchPool();
        // sessionId 上方可能被重新赋值（新建会话），lambda 捕获需 effectively final 快照
        final String prefetchSid = sessionId;
        Future<?> prefetch = prefetchPool == null ? null
                : prefetchPool.submit(() -> doPrefetch(request.message(), prefetchSid));
        String resolvedAgent;
        try {
            resolvedAgent = resolveAgent(request.message(), sessionId);
            awaitPrefetch(prefetch);
        } finally {
            if (prefetchPool != null) {
                prefetchPool.shutdown();
            }
        }

        Goal goal = agentService.executeSync(resolvedAgent, request.message(), sessionId);
        // COMPLEX 编排失败降级（同步路径无客户端断开，FAILED 即编排自身失败）：
        // 降级为会话 Agent 单模型重答一次，与流式 withComplexFallback 同语义，一层兜底不递归
        if (complexFallbackEnabled && GoalStatus.FAILED == goal.status()
                && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
            return chatWithComplexFallback(goal, request, sessionId, newSession, userSentAt);
        }
        writeBackContext(sessionId, request.message(), goal, userSentAt);

        if (goal.status() == GoalStatus.FAILED) {
            return ChatResponse.failure(sessionId, newSession, goal.id(), goal.summary(), resolvedAgent);
        }
        return ChatResponse.success(sessionId, newSession, goal.id(), goal.summary(),
                recentKnowledgeSources(sessionId), resolvedAgent);
    }

    /**
     * 同步路径 COMPLEX 编排失败降级重答：编排 FAILED 时用会话绑定 Agent 单模型重答一次。
     * 重答成功按正常响应返回；重答也失败才返回失败响应，错误信息保留编排失败原因 +
     * 重答失败原因两段（与流式 {@link #withComplexFallback} 错误拼接口径一致）。
     */
    private ChatResponse chatWithComplexFallback(Goal orchestration, ChatRequest request,
                                                 String sessionId, boolean newSession, long userSentAt) {
        log.warn("[route] 编排失败（goal={}），降级单模型重答：{}", orchestration.id(), orchestration.summary());
        String fallbackAgent = sessionAgentName(sessionId);
        Goal retry = agentService.executeSync(fallbackAgent, request.message(), sessionId);
        writeBackContext(sessionId, request.message(), retry, userSentAt);
        if (retry.status() == GoalStatus.FAILED) {
            return ChatResponse.failure(sessionId, newSession, retry.id(),
                    "编排失败: " + orchestration.summary() + "；重答失败: " + retry.summary(), fallbackAgent);
        }
        return ChatResponse.success(sessionId, newSession, retry.id(), retry.summary(),
                recentKnowledgeSources(sessionId), fallbackAgent);
    }

    /** 同步执行成功后写回会话记忆（user 消息记录真实发送时刻，历史回显时间不失真） */
    private void writeBackContext(String sessionId, String message, Goal goal, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank() && goal.status() == GoalStatus.SUCCEEDED) {
            sessionService.saveContext(sessionId, new UserMessage(message), userSentAt);
            sessionService.saveContext(sessionId, new AssistantMessage(goal.summary()), System.currentTimeMillis());
            sessionService.touchSession(sessionId, message);
        }
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
            // 失败只告警不中断：随后统一判定按会话绑定（无绑定/失效回退 general）继续路由
            if (request.agentId() != null) {
                try {
                    sessionService.switchAgent(ctx.sid(), request.agentId());
                } catch (Exception e) {
                    log.warn("[chat] 会话 Agent 同步失败（不影响本次路由）sid={} agentId={}: {}",
                            ctx.sid(), request.agentId(), safeMessage(e));
                }
            }
            // 路由统一判定（指定 Agent 不再跳过）：SIMPLE → 会话绑定 Agent 直答，
            // COMPLEX → multi-agent 编排（指定 Agent 仅是编排失败降级重答的落点）
            // judge 与 RAG 预取同样并行汇合（与同步 chat 同口径）。
            // 首帧先行：「路由判定中」进度行在订阅时立即发出，HTTP 响应头随首个 SSE 字节提交——
            // judge 是同步阻塞调用（实测复杂问题 5-6 秒），若等它完成才有首字节，连接会经历
            // 「已建立却零字节」窗口，期间被外部掐断（实测 lead 在启动瞬间即收 client-cancelled，
            // SIMPLE 首字节快故幸免）。judge 挪入 boundedElastic，不再阻塞订阅线程。
            return toSseBody(Flux.concat(
                    Flux.just(ProgressLine.encode("路由", "判定中…")),
                    Mono.fromCallable(() -> {
                        ExecutorService prefetchPool = newPrefetchPool();
                        Future<?> prefetch = prefetchPool == null ? null
                                : prefetchPool.submit(() -> doPrefetch(request.message(), ctx.sid()));
                        String resolvedAgent;
                        try {
                            resolvedAgent = resolveAgent(request.message(), ctx.sid());
                            awaitPrefetch(prefetch);
                        } finally {
                            if (prefetchPool != null) {
                                prefetchPool.shutdown();
                            }
                        }
                        Flux<String> agentStream = agentService.executeStreamReactive(resolvedAgent, request.message(), ctx.sid());
                        // COMPLEX 编排失败降级：编排自身异常（客户端断开走 cancel，不触发 onErrorResume）时
                        // 降级为会话 Agent 单模型重答一次，一层兜底不递归
                        if (complexFallbackEnabled && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
                            agentStream = withComplexFallback(agentStream, request.message(), ctx.sid());
                        }
                        // 判定结果可见化：路由去向作为进度行透出（SIMPLE → 谁直答 / COMPLEX → 编排）
                        String path = AgentConstants.MULTI_AGENT.equals(resolvedAgent)
                                ? "COMPLEX，走 multi-agent 编排"
                                : "SIMPLE，走 " + resolvedAgent + " 直答";
                        return Flux.concat(
                                Flux.just(ProgressLine.encode("路由", "判定完成：" + path)),
                                withAgentProgress(resolvedAgent, agentStream));
                    }).subscribeOn(Schedulers.boundedElastic())
                      .flatMapMany(flux -> flux)),
                    ctx.sid(), ctx.newSession(), request.message(), null);
        });
    }

    /**
     * COMPLEX 编排失败降级重答：编排流异常时先发降级进度行，再用会话绑定 Agent 单模型重答一次。
     * 重答失败保留双段错误（编排原因 + 重答原因），随 error 事件与 meta(FAILED) 透出；
     * 重答走显式单 Agent 通道（executeStreamReactive 具名 agent），不经 RouteJudge/编排二次入口。
     */
    private Flux<String> withComplexFallback(Flux<String> orchestration, String message, String sessionId) {
        return orchestration.onErrorResume(e -> {
            log.warn("[route] 编排失败，降级单模型重答：{}", safeMessage(e));
            return Flux.concat(
                    Flux.just(ProgressLine.encode("编排", "失败，降级为单模型重答")),
                    agentService.executeStreamReactive(sessionAgentName(sessionId), message, sessionId)
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
            flux = toSseBody(withAgentProgress(AgentConstants.MULTI_AGENT, agentService.resumeStreamReactive(goal)),
                    goal.sessionId(), false, goal.objective(), goal.id());
        } catch (RuntimeException e) {
            streamConnectionLimiter.release();
            throw e;
        }
        return flux.doFinally(sig -> streamConnectionLimiter.release());
    }

    /**
     * 流首插入 agent 归属进度行（stage=agent, detail=agentName）：CLI 在首个回答 token 前
     * 渲染「agentName&gt; 」前缀，与用户侧「你&gt; 」提示符对称——智能分流下实际路由的 Agent
     * 只有服务端知道。进度行走旁路协议，不计入会话摘要与 goal.summary。
     */
    private static Flux<String> withAgentProgress(String agentName, Flux<String> agentTokens) {
        return Flux.concat(Flux.just(ProgressLine.encode("agent", agentName)), agentTokens);
    }

    /**
     * SSE 包装公共体（stream 与 resume 共用）：进度/内容行转 SSE 事件 + [DONE] + meta 收尾，
     * 成功后写回会话记忆（user=本次消息 objective，assistant=完整回复），出错发 error 事件。
     *
     * @param goalId     meta 事件携带的目标 ID（resume 场景传 goal.id()；全新 stream 时 null）
     */
    private Flux<String> toSseBody(Flux<String> agentTokens, String sessionId, boolean newSession,
                                   String userMessage, String goalId) {
        // 发送时刻在组装期捕获（请求到达即记录，写回 user 消息快照用——历史回显真实发送时间）
        long userSentAt = System.currentTimeMillis();
        // doOnNext 收集完整回复，流正常结束后由 doOnComplete 统一写回会话记忆（保持多轮记忆语义）
        // 其中「进度行」（以 ProgressLine.MARK 开头，多 Agent 编排的阶段反馈）不计入会话摘要
        StringBuilder full = new StringBuilder();
        return agentTokens
                .doOnNext(row -> { if (!ProgressLine.isProgress(row)) { full.append(row); } })
                .flatMap(ChatServiceImpl::toSseRows)
                .concatWithValues("event: " + SseProtocol.EVENT_TOKEN
                        + "\ndata: " + SseProtocol.DONE_MARKER)
                .concatWith(metaEvent(sessionId, newSession, goalId, GoalStatus.SUCCEEDED.name(), null))
                .doOnComplete(() -> writeBackContext(sessionId, userMessage, full.toString(), userSentAt))
                // 客户端断开（Tomcat 报 AsyncRequestNotUsableException/Connection reset）：
                // 框架层 ERROR 堆栈由 ClientAbortLogFilter 降噪，此处统一记可观测 warn 单行
                .doOnCancel(() -> log.warn("[stream] 客户端断开，取消推送与编排：sid={}", sessionId))
                .onErrorResume(ex -> {
                    String err = safeMessage(ex);
                    return Flux.concat(
                            Flux.just("event: " + SseProtocol.EVENT_ERROR + "\ndata: " + err),
                            metaEvent(sessionId, newSession, goalId, GoalStatus.FAILED.name(), err));
                });
    }

    /**
     * 把 Agent 流出的一行转成 SSE 行序列：
     * - 进度行 {@code \u0000stage\u0001detail} → {@code event: progress} + {@code data: {"stage":..,"detail":..}}
     * - 其它（内容 token）→ {@code event: token} + {@code data: <token>}
     *
     * <p>progress 的 data JSON 直接用 Jackson 序列化 record，转义交给它，不再手写。
     */
    private static Flux<String> toSseRows(String row) {
        ProgressLine.StageRow p = ProgressLine.decode(row);
        if (p == null) {
            // 内容行：裸换行会把一条 data 断成多个物理行，CLI 只认前缀行会丢内容——必须行内转义（可逆）。
            // event: token 必须显式声明：SSE 的 event 字段粘滞，progress 块之后不带 event: 的
            // data 行会被客户端误归入 progress（token 被吞、CLI 显示 0 字）。
            return Flux.just("event: " + SseProtocol.EVENT_TOKEN
                    + "\ndata: " + SseProtocol.escapeLineBreaks(row));
        }
        try {
            // event 与 data 必须在同一元素内：MVC 逐元素 flush，拆成两个元素会被其它事件的行交叉插入
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: " + OBJECT_MAPPER.writeValueAsString(p));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: {\"stage\":\"?\",\"detail\":\"?\"}");
        }
    }

    /** 流式成功后写回会话记忆（响应式路径：assistant 完整回复已由 doOnNext 收集；user 记录发送时刻） */
    private void writeBackContext(String sessionId, String message, String assistantReply, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionService.saveContext(sessionId, new UserMessage(message), userSentAt);
            sessionService.saveContext(sessionId, new AssistantMessage(assistantReply), System.currentTimeMillis());
            sessionService.touchSession(sessionId, message);
        }
    }

    /** 组装 SSE meta 事件单元素块（event+data 同元素，保证成对不被交叉）：{@code event: meta\n data: {json}} */
    private Flux<String> metaEvent(String sessionId, boolean newSession, String goalId, String status, String error) {
        SseMeta meta = new SseMeta(sessionId, newSession, goalId, status, error, recentKnowledgeSources(sessionId));
        try {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: " + OBJECT_MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: {\"error\":\"meta serialization failed\"}");
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

    /** RAG 预取专用单线程池（守护线程）：每次请求独立创建用完即弃，生命周期自洽零泄漏 */
    private ExecutorService newPrefetchPool() {
        if (knowledgeRetriever == null) {
            return null;
        }
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rag-prefetch");
            t.setDaemon(true);
            return t;
        });
    }

    /** 入口预取：解析会话绑定 agent 的知识库绑定并发起预取（任何失败静默，不影响主流程） */
    private void doPrefetch(String message, String sessionId) {
        try {
            String agentName = sessionAgentName(sessionId);
            com.dark.javaHarness.domain.AgentConfig config =
                    agentName == null ? null : agentService.getAgentConfig(agentName).orElse(null);
            List<String> kbs = com.dark.javaHarness.knowledge.KnowledgeRetriever
                    .parseBinding(config == null ? null : config.knowledge());
            if (kbs == null) {
                return; // 未绑定知识库，无事发生
            }
            knowledgeRetriever.prefetch(agentName, sessionId, message, kbs);
        } catch (Exception e) {
            log.debug("[chat] RAG 预取跳过（静默）：{}", safeMessage(e));
        }
    }

    /** 汇合预取：judge 完成后小幅等待；超时取消放弃（预取是纯加速，失败退化为组装期现查） */
    private void awaitPrefetch(Future<?> prefetch) {
        if (prefetch == null) {
            return;
        }
        try {
            prefetch.get(PREFETCH_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            prefetch.cancel(true);
            log.debug("[chat] RAG 预取未在汇合窗口内完成，放弃");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            prefetch.cancel(true);
        } catch (Exception e) {
            log.debug("[chat] RAG 预取失败（静默）：{}", safeMessage(e));
        }
    }

    /** 安全取异常信息，避免 getMessage 为空导致行文本不规范；换行替换为空格避免破坏逐行解析 */
    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 主 Agent 前置判断：调用 {@link RouteJudge} 决定走哪条路径。
     * 复杂(COMPLEX) → multi-agent 多 Agent 编排；简单/未知 → 会话绑定的 Agent
     * （session.agent_id，如曾用 /agent 切换；未绑定或失效回退默认 general），
     * 不再一律压回 general——会话切过 Agent 后简单问题也应由该 Agent 回答。
     * 判断异常/失败时兜底简单路径（宁可简单，不阻塞请求）。
     *
     * @return 选中的 agent 名（会话 Agent/general 或 "multi-agent"）
     */
    private String resolveAgent(String message, String sessionId) {
        try {
            RouteDecision route = routeJudge.judge(message, sessionId);
            String resolved = route == RouteDecision.COMPLEX
                    ? AgentConstants.MULTI_AGENT
                    : sessionAgentName(sessionId);
            log.info("[route] message '{}' -> {} -> agent={}", trimForLog(message), route, resolved);
            return resolved;
        } catch (Exception e) {
            // 判断异常不得影响请求主流程：兜底默认（简单）路径
            log.warn("[route] 主 Agent 判断异常，回退会话 Agent：{}", safeMessage(e));
            return sessionAgentName(sessionId);
        }
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

    /** 截断过长的 message 用于日志，避免刷屏 */
    private static String trimForLog(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("[\\r\\n]+", " ");
        return single.length() > 80 ? single.substring(0, 80) + "..." : single;
    }
}