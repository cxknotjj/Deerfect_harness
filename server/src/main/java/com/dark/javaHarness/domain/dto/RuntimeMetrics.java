package com.dark.javaHarness.domain.dto;

/**
 * 进程内 Micrometer 指标快照，与 /actuator/prometheus 同源。
 *
 * <p>计数类指标为进程启动以来累计（重启清零，历史趋势由 Prometheus 保存）；
 * heap/CPU/uptime/线程数来自 Spring Boot 默认的 JVM 与进程 binder。
 */
public record RuntimeMetrics(
        long uptimeSeconds,
        double processCpuUsage,
        long heapUsedBytes,
        long heapMaxBytes,
        int liveThreads,
        int sseActive,
        long llmCalls,
        long llmErrors,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long routeAgentic,
        long routeGraph,
        long graphFallbacks,
        TimerStats llmDuration,
        TimerStats firstToken) {

    /** 计时器累计：观测次数、均值与最大值（P95 由 Prometheus 直方图查询时计算）。 */
    public record TimerStats(long count, long avgMs, long maxMs) {
    }
}
