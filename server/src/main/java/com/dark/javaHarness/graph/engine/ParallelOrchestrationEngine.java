package com.dark.javaHarness.graph.engine;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.agent.BranchProgressListener;
import com.dark.javaHarness.graph.MultiAgentGraphAgent;
import com.dark.javaHarness.graph.OrchestrationBudget;
import com.dark.javaHarness.graph.OrchestrationNodes;
import com.dark.javaHarness.state.CheckpointSelector;
import com.dark.javaHarness.state.OrchestrationCheckpoint;
import com.dark.javaHarness.state.OrchestrationStage;
import com.dark.javaHarness.state.persistence.OrchestrationCheckpointStore;
import com.dark.javaHarness.domain.Goal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 自研多 Agent 编排引擎（替代 SAA StateGraph）：lead 拆解 → 并行子任务 → 聚合。
 *
 * <p>与旧图实现的行为契约保持一致：
 * <ul>
 *   <li>进度行阶段/文案逐字沿用（编排/拆解/子任务/聚合），子任务结局播报走
 *       {@link BranchProgressListener}；
 *   <li>子任务并行完成后按完成顺序播报、结果按槽位索引合并；异常写失败占位而非上抛
 *       （客户端断连除外），聚合据此注入降级说明；
 *   <li>每个阶段结束落一条检查点（{@link OrchestrationStage}），续跑按阶段跳过已完成部分。
 * </ul>
 *
 * <p>检查点存储可 null（单测/未启用）：为 null 时执行照常，仅不落库；
 * 续跑此时快速失败，与旧「未启用检查点存储，无法续跑」同口径。
 */
public final class ParallelOrchestrationEngine implements OrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(ParallelOrchestrationEngine.class);

    private final OrchestrationNodes nodes;
    private final OrchestrationBudget budget;
    /** 检查点存储（可 null = 不落库，单测场景） */
    private final OrchestrationCheckpointStore checkpoints;
    /** 子任务并发上限（<=0 表示不限制，按子任务数开线程） */
    private final int concurrency;

    public ParallelOrchestrationEngine(OrchestrationNodes nodes,
                                       OrchestrationBudget budget,
                                       OrchestrationCheckpointStore checkpoints,
                                       int concurrency) {
        this.nodes = nodes;
        this.budget = budget;
        this.checkpoints = checkpoints;
        this.concurrency = Math.max(0, concurrency);
    }

    /* ---------------- 同步执行 ---------------- */

    @Override
    public String execute(Goal goal) {
        OrchestrationState state = initialState(goal);
        merge(state, nodes.lead(state, null, null));
        save(goal, OrchestrationStage.LEAD_DONE, state);
        runSubtasks(state, null, null, null);
        save(goal, OrchestrationStage.SUBTASKS_DONE, state);
        merge(state, nodes.aggregate(state, null, null, null));
        save(goal, OrchestrationStage.DONE, state);
        return state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse(goal.objective());
    }

    /* ---------------- 流式执行 ---------------- */

    @Override
    public Flux<EngineEvent> stream(Goal goal) {
        Channels channels = new Channels();
        return channels.flux()
                .doOnSubscribe(sub -> Schedulers.boundedElastic()
                        .schedule(() -> runStreaming(goal, channels)))
                .doOnCancel(() -> onCancel(channels));
    }

    @Override
    public Flux<EngineEvent> resume(Goal goal) {
        // 快速失败：检查点选择在订阅前同步完成（与旧 resumeStreamReactive 同口径）
        CheckpointSelector.ResumePlan plan = selectResumePlan(goal);
        Channels channels = new Channels();
        return channels.flux()
                .doOnSubscribe(sub -> Schedulers.boundedElastic()
                        .schedule(() -> runResume(goal, plan, channels)))
                .doOnCancel(() -> onCancel(channels));
    }

    private void runStreaming(Goal goal, Channels channels) {
        BranchProgressListener listener = progressListener(channels);
        try {
            OrchestrationState state = initialState(goal);
            emit(channels.progress, ProgressLine.encode("编排", "开始拆解复杂目标…"));
            merge(state, nodes.lead(state, channels.cancelled,
                    line -> BranchProgressListener.tryEmitSerialized(channels.progress, line)));
            emit(channels.progress, ProgressLine.encode("拆解",
                    state.value(MultiAgentGraphAgent.K_SUBTASK_COUNT, Integer.class).orElse(0)
                            + " 个子任务已就绪"));
            save(goal, OrchestrationStage.LEAD_DONE, state);
            runSubtasks(state,
                    line -> BranchProgressListener.tryEmitSerialized(channels.progress, line),
                    channels.cancelled, listener);
            save(goal, OrchestrationStage.SUBTASKS_DONE, state);
            aggregateStage(goal, state, channels);
        } catch (Throwable e) {
            fail(channels, e);
        } finally {
            channels.complete();
        }
    }

    private void runResume(Goal goal, CheckpointSelector.ResumePlan plan, Channels channels) {
        BranchProgressListener listener = progressListener(channels);
        try {
            Map<String, Object> data = new HashMap<>(plan.checkpoint().state());
            // 续跑重建预算账本（旧图路径由 input 注入新账本覆盖旧值，同口径）
            budget.putLedger(data);
            OrchestrationState state = new OrchestrationState(goal.id(), data);
            switch (plan.stage()) {
                case DONE -> replayFinal(goal, state, channels);
                case SUBTASKS_DONE -> aggregateStage(goal, state, channels);
                case LEAD_DONE -> {
                    runSubtasks(state,
                            line -> BranchProgressListener.tryEmitSerialized(channels.progress, line),
                            channels.cancelled, listener);
                    save(goal, OrchestrationStage.SUBTASKS_DONE, state);
                    aggregateStage(goal, state, channels);
                }
            }
        } catch (Throwable e) {
            fail(channels, e);
        } finally {
            channels.complete();
        }
    }

    /** 已完成编排的续跑：零 LLM 调用，直接回放最终回答（旧 END 帧兜底语义）。 */
    private void replayFinal(Goal goal, OrchestrationState state, Channels channels) {
        String finalAnswer = state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse(null);
        emit(channels.tokens, finalAnswer == null || finalAnswer.isBlank()
                ? goal.objective() : finalAnswer);
    }

    /**
     * 聚合阶段：调用聚合节点（流式 token 经 tokens sink 旁路），落 DONE 检查点；
     * 聚合未推出任何 token 时按旧主干帧语义补发进度行与最终内容。
     */
    private void aggregateStage(Goal goal, OrchestrationState state, Channels channels) {
        merge(state, nodes.aggregate(state, channels.tokens, channels.contentSent, channels.cancelled));
        save(goal, OrchestrationStage.DONE, state);
        if (channels.contentSent.get()) {
            return;
        }
        String finalAnswer = state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse(null);
        if (finalAnswer != null && !finalAnswer.isBlank()) {
            emit(channels.progress, ProgressLine.encode("聚合", "汇总子任务结果，生成最终回答"));
            emit(channels.progress, finalAnswer);
        } else {
            emit(channels.progress, ProgressLine.encode("聚合", "正在生成最终回答…"));
        }
    }

    private CheckpointSelector.ResumePlan selectResumePlan(Goal goal) {
        if (checkpoints == null) {
            throw new IllegalStateException("未启用检查点存储，无法续跑");
        }
        List<OrchestrationCheckpoint> list;
        try {
            list = checkpoints.list(goal.id());
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取检查点失败: " + goal.id(), e);
        }
        if (list.isEmpty()) {
            throw new IllegalStateException(
                    "无可续跑的编排：goal " + goal.id() + " 没有检查点（可能未走过复杂路径）");
        }
        return CheckpointSelector.select(list);
    }

    /* ---------------- 子任务并行执行 ---------------- */

    /**
     * 并行执行子任务：提交全部槽位后按完成顺序取结果并播报结局；
     * 超时/预算由节点内部写占位，未捕获异常在此转失败占位（客户端断连除外）。
     */
    private void runSubtasks(OrchestrationState state,
                             Consumer<String> toolEmitter,
                             AtomicBoolean cancelled,
                             BranchProgressListener progress) {
        int count = state.value(MultiAgentGraphAgent.K_SUBTASK_COUNT, Integer.class).orElse(0);
        if (count <= 0) {
            return;
        }
        int threads = concurrency > 0 ? Math.min(concurrency, count) : count;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Map<String, Object>> deltas = new ArrayList<>(Collections.nCopies(count, null));
        try {
            CompletionService<IndexedDelta> completion = new ExecutorCompletionService<>(pool);
            for (int i = 0; i < count; i++) {
                final int idx = i;
                completion.submit(() -> {
                    try {
                        return new IndexedDelta(idx,
                                nodes.subtask(state, idx, toolEmitter, cancelled, progress), null);
                    } catch (Exception e) {
                        return new IndexedDelta(idx, null, e);
                    }
                });
            }
            for (int done = 0; done < count; done++) {
                Future<IndexedDelta> future;
                try {
                    future = completion.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待子任务结果时被中断", e);
                }
                IndexedDelta result = await(future);
                Map<String, Object> delta;
                if (result.error() != null) {
                    if (cancelled != null && cancelled.get()) {
                        throw new java.util.concurrent.CancellationException("客户端已断开，中止编排");
                    }
                    log.warn("[multi-agent][subtask-{}] 子任务执行异常，写占位结果：{}",
                            result.idx(), safe(result.error()), result.error());
                    delta = failedDelta(result.idx(), progress);
                } else {
                    delta = result.delta();
                }
                deltas.set(result.idx(), delta);
                if (progress != null) {
                    progress.report(result.idx(), resultOf(delta, result.idx()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (Map<String, Object> delta : deltas) {
            merge(state, delta);
        }
    }

    /** 取子任务结果；任务内已捕获异常，此处只兜底 Error 级中断。 */
    private static IndexedDelta await(Future<IndexedDelta> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待子任务结果时被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("子任务执行线程异常退出", e.getCause());
        }
    }

    private static Map<String, Object> failedDelta(int idx, BranchProgressListener progress) {
        if (progress != null) {
            progress.recordOutcome(idx, MultiAgentGraphAgent.FAILED_RESULT);
        }
        return Map.of(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, MultiAgentGraphAgent.FAILED_RESULT);
    }

    private static String resultOf(Map<String, Object> delta, int idx) {
        Object value = delta == null ? null : delta.get(MultiAgentGraphAgent.K_RESULT_PREFIX + idx);
        return value instanceof String s ? s : null;
    }

    /* ---------------- 状态与检查点 ---------------- */

    /** 编排 input 组装：objective/sessionId + 轨迹标识（取 Goal，null 不放键）+ 预算账本。 */
    private OrchestrationState initialState(Goal goal) {
        Map<String, Object> input = new HashMap<>();
        input.put(MultiAgentGraphAgent.K_OBJECTIVE, goal.objective());
        input.put(MultiAgentGraphAgent.K_SESSION_ID, goal.sessionId());
        if (goal.turnId() != null) {
            input.put(MultiAgentGraphAgent.K_TURN_ID, goal.turnId());
        }
        if (goal.traceId() != null) {
            input.put(MultiAgentGraphAgent.K_TRACE_ID, goal.traceId());
        }
        budget.putLedger(input);
        return new OrchestrationState(goal.id(), input);
    }

    /** 落检查点：剔除运行时账本对象（不可序列化，续跑时重建）。 */
    private void save(Goal goal, OrchestrationStage stage, OrchestrationState state) {
        if (checkpoints == null) {
            return;
        }
        Map<String, Object> snapshot = new HashMap<>(state.snapshot());
        snapshot.remove(OrchestrationBudget.K_TOKEN_LEDGER);
        snapshot.remove(OrchestrationBudget.K_TOKEN_ESTIMATED);
        checkpoints.save(goal.id(), stage, snapshot);
    }

    /* ---------------- 流/事件工具 ---------------- */

    private static BranchProgressListener progressListener(Channels channels) {
        return new BranchProgressListener(channels.progress,
                MultiAgentGraphAgent.SUBTASK_NODE_PREFIX, MultiAgentGraphAgent.K_SUBTASK_PREFIX);
    }

    private static void emit(Sinks.Many<String> sink, String row) {
        BranchProgressListener.tryEmitSerialized(sink, row);
    }

    /** 异常收尾：先补「异常，已回退」进度行，再以包装异常关闭两条旁路（与旧管道同语义）。 */
    private static void fail(Channels channels, Throwable e) {
        log.warn("[multi-agent] 流式执行异常：{}", safe(e));
        IllegalStateException wrapped = new IllegalStateException("编排失败：" + safe(e), e);
        emit(channels.progress, ProgressLine.encode("编排", "异常，已回退：" + safe(e)));
        channels.progress.tryEmitError(wrapped);
        channels.tokens.tryEmitError(wrapped);
    }

    private static void onCancel(Channels channels) {
        channels.cancelled.set(true);
        log.warn("[multi-agent] 客户端已断开，终止编排：不再发起新的 LLM 调用；在途流式调用在下一个 token 边界中止");
    }

    private static void merge(OrchestrationState state, Map<String, Object> delta) {
        if (delta != null && !delta.isEmpty()) {
            state.putAll(delta);
        }
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 单次执行的两条旁路与共享标志（进度行 / 聚合 token）。 */
    private static final class Channels {

        final Sinks.Many<String> progress = Sinks.many().unicast().onBackpressureBuffer();
        final Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean contentSent = new AtomicBoolean(false);

        Flux<EngineEvent> flux() {
            Flux<EngineEvent> progressRows = progress.asFlux()
                    .map(line -> (EngineEvent) new EngineEvent.Progress(line));
            // 聚合旁路同一 sink 混发进度行（思考/聚合进度）与内容 token，按线协议分流
            Flux<EngineEvent> tokenRows = tokens.asFlux()
                    .map(line -> ProgressLine.isProgress(line)
                            ? (EngineEvent) new EngineEvent.Progress(line)
                            : new EngineEvent.Token(line));
            return Flux.merge(progressRows, tokenRows);
        }

        void complete() {
            BranchProgressListener.tryCompleteSerialized(progress);
            BranchProgressListener.tryCompleteSerialized(tokens);
        }
    }

    /** 子任务结果及其槽位（完成顺序回传时保持索引关联）。 */
    private record IndexedDelta(int idx, Map<String, Object> delta, Throwable error) {
    }
}
