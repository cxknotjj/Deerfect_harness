package com.dark.javaHarness.agent;

import com.dark.javaHarness.agent.ProgressLine;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * 子任务结局进度播报：把每个子任务的最终 result（成功内容或占位常量）编码为
 * 「子任务 第 N 个子任务完成/失败/超时/预算超限」进度行，经旁路 Sink 串行化发射。
 *
 * <p>节点在返回前调用 {@link #recordOutcome} 登记结局；引擎在取到子任务结果后调用
 * {@link #report} 播报（旧 graph 实现由生命周期钩子 after 触发，已随 StateGraph 移除）。
 *
 * <p>同时提供 Sink 串行化发射/关闸工具：并行线程可能同时回调，
 * Reactor 单播 Sink 拒绝并发发射（FAIL_NON_SERIALIZED 会静默丢事件）。
 */
public final class BranchProgressListener {

    private static final Logger log = LoggerFactory.getLogger(BranchProgressListener.class);

    private final Sinks.Many<String> events;

    /**
     * 子任务结局登记：节点返回前把最终 result（成功内容或占位常量）登记到此处，
     * {@link #report} 优先读登记值，取不到时用调用方传入的 delta 结果兜底。
     */
    private final Map<Integer, String> outcomes = new ConcurrentHashMap<>();

    public BranchProgressListener(Sinks.Many<String> events, String nodePrefix, String statePrefix) {
        this.events = events;
    }

    /** 节点动作返回前登记该子任务的最终 result（成功内容或占位常量），供 {@link #report} 播报分类 */
    public void recordOutcome(int idx, String result) {
        outcomes.put(idx, result);
    }

    /** 播报子任务结局：优先读登记值，取不到时用节点返回的 result 兜底；文案四类见常量注释 */
    public void report(int idx, String fallbackResult) {
        String result = outcomes.remove(idx);
        String line = outcomeLine(idx, result != null ? result : fallbackResult);
        log.info("[multi-agent][hook] subtask-{} 结局播报：{}", idx, line);
        tryEmitSerialized(events, line);
    }

    /** 子任务结局 → 进度行（成功/失败/超时/预算超限四类文案，见占位常量注释） */
    private static String outcomeLine(int idx, String result) {
        if (SubtaskResults.FAILED.equals(result)) {
            return ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务执行失败，已跳过");
        }
        if (SubtaskResults.TIMEOUT_SKIPPED.equals(result)) {
            return ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务执行超时，已跳过");
        }
        if (SubtaskResults.SKIPPED.equals(result)) {
            return ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务因预算超限跳过");
        }
        return ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务完成");
    }

    /** 串行化向旁路 Sink 发射一条事件（并行线程可能同时回调，单播 Sink 拒绝并发发射）。 */
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
