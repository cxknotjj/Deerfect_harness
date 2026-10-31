package com.dark.javaHarness.observability;

import com.dark.javaHarness.domain.dto.RuntimeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * 运行时指标快照：直接读 Spring 管理的 MeterRegistry（与 /actuator/prometheus 同一数据源）。
 *
 * <p>六项自埋指标按累计口径汇总：SSE 活跃、LLM 调用/错误、token、耗时与首 token计时器、
 * 路径决策与 Graph 降级。标准 JVM/进程指标取 Spring Boot 默认 binder（日志堆、CPU、运行时长、
 * 线程数）。应用重启后计数从零开始，历史趋势以 Prometheus 侧为准。
 */
@Service
public class RuntimeMetricsService {

    /** 注入应用 registry：globalRegistry 只持有经它注册的米表，JVM/进程 binder 的指标在其子 registry。 */
    private final MeterRegistry registry;

    public RuntimeMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 当前进程内的指标快照；未注册的米表按 0 返回（如刚重启尚无 LLM 调用）。 */
    public RuntimeMetrics snapshot() {
        return new RuntimeMetrics(
                uptimeSeconds(),
                gauge("process.cpu.usage"),
                sumGauges("jvm.memory.used", "area", "heap", false),
                sumGauges("jvm.memory.max", "area", "heap", true),
                (int) gauge("jvm.threads.live"),
                (int) gauge("harness.sse.active"),
                sumCounters("harness.llm.calls", null, null),
                sumCounters("harness.llm.calls", "status", "error"),
                sumCounters("harness.llm.tokens", "kind", "prompt"),
                sumCounters("harness.llm.tokens", "kind", "completion"),
                sumCounters("harness.llm.tokens", "kind", "total"),
                sumCounters("harness.route.decisions", "path", "agentic"),
                sumCounters("harness.route.decisions", "path", "graph"),
                sumCounters("harness.graph.fallbacks", null, null),
                timerStats("harness.llm.duration"),
                timerStats("harness.llm.first_token"));
    }

    /** process.uptime 由 Boot 注册为 TimeGauge，缺省回退普通 Gauge。 */
    private long uptimeSeconds() {
        Search search = registry.find("process.uptime");
        TimeGauge timeGauge = search.timeGauge();
        if (timeGauge != null) {
            return (long) timeGauge.value(TimeUnit.SECONDS);
        }
        return (long) gauge("process.uptime");
    }

    private double gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    /** 同一指标按 tag 过滤后求和；positiveOnly 用于剔除堆上限里的 -1（无上限的内存池）。 */
    private long sumGauges(String name, String tagKey, String tagValue, boolean positiveOnly) {
        double sum = 0;
        for (Gauge gauge : registry.find(name).tag(tagKey, tagValue).gauges()) {
            double value = gauge.value();
            if (!positiveOnly || value > 0) {
                sum += value;
            }
        }
        return (long) sum;
    }

    private long sumCounters(String name, String tagKey, String tagValue) {
        Search search = registry.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        double sum = 0;
        for (Counter counter : search.counters()) {
            sum += counter.count();
        }
        return (long) sum;
    }

    private RuntimeMetrics.TimerStats timerStats(String name) {
        long count = 0;
        double totalMs = 0;
        double maxMs = 0;
        for (Timer timer : registry.find(name).timers()) {
            count += timer.count();
            totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, timer.max(TimeUnit.MILLISECONDS));
        }
        return new RuntimeMetrics.TimerStats(
                count,
                count == 0 ? 0 : (long) (totalMs / count),
                (long) maxMs);
    }
}
