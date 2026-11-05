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
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 一次 TTS 合成：成功/失败计数、合成耗时与朗读字数（Edge TTS 子进程）。 */
    public static void ttsCall(boolean ok, long durationMs, int chars) {
        String status = ok ? "ok" : "error";
        Counter.builder("harness.tts.calls")
                .description("TTS 合成次数（错误率 = status=error 占比）")
                .tag("status", status)
                .register(REGISTRY)
                .increment();
        Timer.builder("harness.tts.duration")
                .description("TTS 合成耗时")
                .tag("status", status)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofSeconds(120))
                .register(REGISTRY)
                .record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("harness.tts.chars")
                .description("TTS 朗读字数")
                .register(REGISTRY)
                .increment(chars);
    }

    /** Watchdog 判定为停滞并标记失败的 Goal 数（按扫描批次累加）。 */
    public static void goalWatchdogStale(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("harness.goal.watchdog.stale")
                .description("Watchdog 标记为停滞失败的 Goal 数")
                .register(REGISTRY)
                .increment(count);
    }

    /** 观测队列打满丢弃的记录数：kind=llm / tool；非零即排障数据出现缺口。 */
    public static void observabilityDropped(String kind) {
        Counter.builder("harness.observability.dropped")
                .description("观测记录因队列打满被丢弃的条数")
                .tag("kind", label(kind))
                .register(REGISTRY)
                .increment();
    }

    private static final AtomicInteger SANDBOX_TOOLS = new AtomicInteger();
    private static final AtomicInteger SANDBOX_BROWSER_TOOLS = new AtomicInteger();
    private static final AtomicBoolean SANDBOX_GAUGE_BOUND = new AtomicBoolean();

    /**
     * 沙箱已注册工具面 gauge：0 表示沙箱降级（Docker 不可达 / 初始化超时 / 镜像缺失）。
     * 降级本身不阻断主链路，但必须能从指标看出「Agent 当前没有工具可用」。
     */
    public static void sandboxTools(int baseTools, int browserTools) {
        SANDBOX_TOOLS.set(baseTools);
        SANDBOX_BROWSER_TOOLS.set(browserTools);
        if (!SANDBOX_GAUGE_BOUND.compareAndSet(false, true)) {
            return;
        }
        Gauge.builder("harness.sandbox.tools", SANDBOX_TOOLS, (AtomicInteger v) -> v.get())
                .description("沙箱已注册工具数（0=降级，沙箱类能力不可用）")
                .strongReference(true)
                .register(REGISTRY);
        Gauge.builder("harness.sandbox.browser.tools", SANDBOX_BROWSER_TOOLS, (AtomicInteger v) -> v.get())
                .description("沙箱已注册浏览器工具数（0=浏览器组降级）")
                .strongReference(true)
                .register(REGISTRY);
    }

    /**
     * tools 声明中无法注入的 token 数（每个 agent+token 只记一次）：
     * kind=unavailable 表示已登记但当前环境不可用（沙箱未启动 / MCP 未连接），
     * kind=unregistered 表示声明笔误或工具名未登记——后者需要改配置。
     */
    public static void toolTokenUnavailable(String agent, String token, boolean registered) {
        Counter.builder("harness.tool.unavailable_tokens")
                .description("tools 声明中当前不可用或未登记的 token 数")
                .tags("agent", label(agent), "token", label(token),
                        "kind", registered ? "unavailable" : "unregistered")
                .register(REGISTRY)
                .increment();
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
