package com.dark.javaHarness.agent.orchestrate;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dark.javaHarness.agent.ProgressLine;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * 并行分支进度监听器（流式旁路）：补齐 graph-core stream 合并掉的「子任务完成」事件。
 *
 * <p>before/after 配对过滤 lead 未布置的短路槽位——进入节点的输入态必含已布置的
 * subtask_i（空槽位执行即短路返回空 map），仅对真实执行的子任务经旁路 Sink 播报「完成」，
 * 避免向 CLI 推虚假进度。
 *
 * <p>同时提供 Sink 串行化发射/关闸工具：并行钩子线程可能同时回调，
 * Reactor 单播 Sink 拒绝并发发射（FAIL_NON_SERIALIZED 会静默丢事件）。
 */
public final class BranchProgressListener implements GraphLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(BranchProgressListener.class);

    private final Sinks.Many<String> events;
    /** 子任务节点名前缀（如 "subtask-"），用于识别子任务帧 */
    private final String nodePrefix;
    /** 子任务状态键前缀（如 "subtask_"），用于判定槽位是否被 lead 布置 */
    private final String statePrefix;

    /** before 登记已布置槽位，after 消费：不在集合中即静默 */
    private final Set<Integer> scheduled = ConcurrentHashMap.newKeySet();

    /**
     * 子任务结局登记：graph-core 并行分支的 after 钩子拿到的是节点输入态快照
     * （ParallelNode 在 action.apply 的 whenComplete 里传 state.data()，本节点写入的
     * result 键要等所有分支结束后才由父节点合并进全局状态）——钩子里读 state 恒取不到
     * 本轮写入的结果，占位区分播报全数落入「完成」兜底。节点动作返回前把最终 result
     * （成功内容或占位常量）登记到此处，after 优先读登记值；state 读取保留作兜底
     * （防未来库版本把合并后状态传进钩子）。
     */
    private final Map<Integer, String> outcomes = new ConcurrentHashMap<>();

    BranchProgressListener(Sinks.Many<String> events, String nodePrefix, String statePrefix) {
        this.events = events;
        this.nodePrefix = nodePrefix;
        this.statePrefix = statePrefix;
    }

    /** 节点动作返回前登记该子任务的最终 result（成功内容或占位常量），供 after 播报分类 */
    public void recordOutcome(int idx, String result) {
        outcomes.put(idx, result);
    }

    @Override
    public void before(String node, Map<String, Object> state,
                       RunnableConfig config, Long costMillis) {
        Integer idx = subtaskIndexIfAny(node);
        if (idx != null && state != null) {
            Object task = state.get(statePrefix + idx);
            if (task instanceof String s && !s.isBlank()) {
                scheduled.add(idx);
            }
        }
    }

    @Override
    public void after(String node, Map<String, Object> state,
                      RunnableConfig config, Long costMillis) {
        Integer idx = subtaskIndexIfAny(node);
        if (idx == null || !scheduled.remove(idx)) {
            return; // 非子任务帧或短路槽位：静默
        }
        // 结局分类：优先读节点登记值（输入态快照取不到本轮 result，见 outcomes 注释），
        // 占位结果区分播报：超时/预算/失败占位不是「完成」，按实际结局播报（登记与 state
        // 都取不到时按「完成」播报兜底，与旧版行为一致）
        String line;
        Object result = outcomes.remove(idx);
        if (result == null && state != null) {
            result = state.get(MultiAgentGraphAgent.K_RESULT_PREFIX + idx);
        }
        if (MultiAgentGraphAgent.FAILED_RESULT.equals(result)) {
            line = ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务执行失败，已跳过");
        } else if (MultiAgentGraphAgent.TIMEOUT_SKIPPED_RESULT.equals(result)) {
            line = ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务执行超时，已跳过");
        } else if (MultiAgentGraphAgent.SKIPPED_RESULT.equals(result)) {
            line = ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务因预算超限跳过");
        } else {
            line = ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务完成");
        }
        log.info("[multi-agent][hook] subtask-{} 结局播报：{}", idx, line);
        tryEmitSerialized(events, line);
    }

    /** 节点名为 "{prefix}{i}" 时返回索引 i，否则返回 null（供生命周期钩子判定是否子任务帧）。 */
    private Integer subtaskIndexIfAny(String nodeName) {
        if (nodeName == null || !nodeName.startsWith(nodePrefix)) {
            return null;
        }
        try {
            return Integer.parseInt(nodeName.substring(nodePrefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 串行化向旁路 Sink 发射一条事件（并行钩子线程可能同时回调，单播 Sink 拒绝并发发射）。 */
    public static void tryEmitSerialized(Sinks.Many<String> sink, String line) {
        synchronized (sink) {
            Sinks.EmitResult result = sink.tryEmitNext(line);
            // 发射失败原本静默（FAIL_NON_SERIALIZED 等）——事件无声丢失极难排查，失败必须留痕
            if (result != Sinks.EmitResult.OK) {
                log.warn("[multi-agent][bypass] 旁路 sink 发射失败 result={} rowChars={}", result, line.length());
            }
        }
    }

    /** 串行化关闸：与 {@link #tryEmitSerialized} 共用同一把锁，防止迟到发射与 complete 竞争。 */
    public static void tryCompleteSerialized(Sinks.Many<String> sink) {
        synchronized (sink) {
            sink.tryEmitComplete();
        }
    }
}
