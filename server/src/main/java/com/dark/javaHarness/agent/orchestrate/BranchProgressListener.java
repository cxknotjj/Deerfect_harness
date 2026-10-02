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

    BranchProgressListener(Sinks.Many<String> events, String nodePrefix, String statePrefix) {
        this.events = events;
        this.nodePrefix = nodePrefix;
        this.statePrefix = statePrefix;
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
        // 占位结果区分播报：超时/预算/失败占位不是「完成」，按实际结局播报（state 取不到
        // result 键时按「完成」播报兜底，与旧版行为一致）
        Object result = state == null ? null : state.get(MultiAgentGraphAgent.K_RESULT_PREFIX + idx);
        String line;
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
