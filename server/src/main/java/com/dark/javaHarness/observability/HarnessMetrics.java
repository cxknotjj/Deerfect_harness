package com.dark.javaHarness.observability;

import com.dark.javaHarness.domain.LlmCallLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * 运行基线指标（Micrometer，经 /actuator/prometheus 抓取）。
 *
 * <p>覆盖六项基线：SSE 活跃连接（gauge）、首 token 延迟与单次耗时（timer，Prometheus 直方图
 * 直接算 P95，route-judge 的 P95 取 agent=route-judge 的那条）、LLM 错误率（calls 计数按
 * status 分组）、Graph 降级（fallbacks 计数）、路径决策分布（decisions，降级率分母）、
 * token 计数（tokens 按 kind/model 分组，按小时 increase 即用量与成本换算基数）。
 *
 * <p>用静态口径而非逐层构造注入：埋点分散在观测/路由/编排/流控四层，注入 MeterRegistry
 * 会把这些与观测无关的类全部拖进依赖；Spring Boot 启动时会把应用 registry 注册进
 * {@link Metrics#globalRegistry}，此处创建的米表随之桥接（单测无 registry 时为 no-op）。
 */
public final class HarnessMetrics {

    private static final MeterRegistry REGISTRY = Metrics.globalRegistry;

    /** 连接数 gauge 只允许绑定一次（首个 StreamConnectionLimiter 实例即生产单例） */
    private static final AtomicBoolean SSE_GAUGE_BOUND = new AtomicBoolean();

    /** 标签缺值占位：Micrometer 不接受 null 标签值 */
    private static final String UNKNOWN = "unknown";

    private HarnessMetrics() {
    }

    /** 一次 LLM 调用（成功/失败、流式/阻塞都走这里）：错误率、耗时/首 token P95 与 token 计数。 */
    public static void llmCall(LlmCallLog call) {
        String agent = label(call.agentName());
        String model = label(call.model());
        String kind = call.stream() ? "stream" : "sync";
        String status = call.ok() ? "ok" : "error";

        Counter.builder("harness.llm.calls")
                .description("LLM 调用次数（错误率 = status=error 占比）")
                .tags("agent", agent, "model", model, "kind", kind, "status", status)
                .register(REGISTRY)
                .increment();

        Timer.builder("harness.llm.duration")
                .description("LLM 单次尝试耗时（P95 取直方图分位）")
                .tags("agent", agent, "model", model, "kind", kind, "status", status)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(300))
                .register(REGISTRY)
                .record(call.durationMs(), TimeUnit.MILLISECONDS);

        Long firstTokenMs = call.firstTokenMs();
        if (firstTokenMs != null && firstTokenMs >= 0) {
            Timer.builder("harness.llm.first_token")
                    .description("首 token 延迟（TTFT）")
                    .tags("agent", agent, "model", model)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(10))
                    .maximumExpectedValue(Duration.ofSeconds(60))
                    .register(REGISTRY)
                    .record(firstTokenMs, TimeUnit.MILLISECONDS);
        }

        tokens(agent, model, "prompt", call.promptTokens());
        tokens(agent, model, "completion", call.completionTokens());
        tokens(agent, model, "total", call.totalTokens());
    }

    /** 路径判定结果（Graph 降级率的分母：Graph 决策数 vs 降级次数）。 */
    public static void routeDecision(boolean graph) {
        Counter.builder("harness.route.decisions")
                .description("路径判定分布")
                .tag("path", graph ? "graph" : "agentic")
                .register(REGISTRY)
                .increment();
    }

    /**
     * Graph 编排失效降级计数。
     * stage=to_agentic（编排失败降级单 Agent 重答，sync/stream 共用）、stage=digest（聚合失败
     * 降级为子任务结果汇总）。
     */
    public static void graphFallback(String stage) {
        Counter.builder("harness.graph.fallbacks")
                .description("Graph 编排失效降级次数")
                .tag("stage", stage)
                .register(REGISTRY)
                .increment();
    }

    /** 绑定 SSE 活跃连接 gauge（StreamConnectionLimiter 为唯一计数源）。 */
    public static void bindSseActive(IntSupplier active) {
        if (!SSE_GAUGE_BOUND.compareAndSet(false, true)) {
            return;
        }
        // Gauge 默认弱引用目标对象：这里的 lambda 除 gauge 外无人持有，GC 后读数会变 NaN，
        // 因此显式强引用（绑定的是生产单例的连接计数方法）
        Gauge.builder("harness.sse.active", active, IntSupplier::getAsInt)
                .description("当前活跃 SSE 连接数")
                .strongReference(true)
                .register(REGISTRY);
    }

    /** token 计数：null（流式未回传 usage 的输入侧）不记，避免用估算值污染成本口径。 */
    private static void tokens(String agent, String model, String kind, Integer value) {
        if (value == null) {
            return;
        }
        Counter.builder("harness.llm.tokens")
                .description("LLM token 用量（按小时 increase 即用量）")
                .tags("agent", agent, "model", model, "kind", kind)
                .register(REGISTRY)
                .increment(value);
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
