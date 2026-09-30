package com.dark.javaHarness.agent.orchestrate;

import com.dark.javaHarness.agent.ProgressLine;
import java.util.function.Consumer;

/**
 * 思考增量合帧节流器（子任务思考透传专用，包私有零 Spring 装配——仿 AggregateStreamGuard 先例）：
 * 思考模型逐 delta 帧（单子任务可达数千帧，并行子任务交织），直接逐帧透传会让前端
 * 行数量爆炸。本类把增量积攒进缓冲，按「字数阈值 OR 时间窗」合帧发射整行思考进度行，
 * 使行数量与 delta 帧数解耦（数千帧 → 数百行以内）。
 *
 * <p>线程口径：单个子任务的 tap 回调来自同一条流信号线程，天然串行；并行子任务
 * 各持独立实例互不共享。异常中止（超时/断连）不调用 {@link #flush()}，缓冲直接丢弃
 * ——思考是瞬时展示信息，允许丢。
 *
 * <p>参数内置常量不进 yaml（YAGNI）：出现调优诉求再配置化。
 */
final class ThinkingThrottle implements Consumer<String> {

    /** 合帧时间窗（毫秒）：距上次发射超过该值即发射缓冲（慢速滴流下保可见性） */
    private static final long FLUSH_INTERVAL_MS = 300;

    /** 合帧字数阈值：缓冲达到该字符数即发射（正常速率下限制行数） */
    private static final int FLUSH_THRESHOLD_CHARS = 120;

    private final String stage;
    /** 编码后整行的发射出口（经 BranchProgressListener.tryEmitSerialized 串行化入旁路 sink） */
    private final Consumer<String> rowSink;
    private final StringBuilder buffer = new StringBuilder();
    private long lastFlushAt = System.currentTimeMillis();

    /** @param stage 思考进度行 stage（携带归属：主回答/聚合为「思考 · agent名/聚合」，子任务为「思考N · 专家名」1 基序号） */
    ThinkingThrottle(String stage, Consumer<String> rowSink) {
        this.stage = stage;
        this.rowSink = rowSink;
    }

    @Override
    public void accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        buffer.append(delta);
        maybeFlush(false);
    }

    /** 流正常结束的兜底发射：把残余缓冲全部发出（异常中止场景不调用，缓冲随作用域丢弃） */
    void flush() {
        maybeFlush(true);
    }

    /** 发射判定：force（流结束兜底）恒发；否则缓冲达字数阈值或距上次发射超时间窗才发 */
    private void maybeFlush(boolean force) {
        if (buffer.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && buffer.length() < FLUSH_THRESHOLD_CHARS && now - lastFlushAt < FLUSH_INTERVAL_MS) {
            return;
        }
        rowSink.accept(ProgressLine.encode(stage, buffer.toString()));
        buffer.setLength(0);
        lastFlushAt = now;
    }
}
