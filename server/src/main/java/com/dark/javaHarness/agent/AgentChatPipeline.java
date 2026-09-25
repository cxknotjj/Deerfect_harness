package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

/**
 * 流式管道核（自 {@link AgentChatCaller} 拆出，超长类拆分 2026-09-25）：
 * 单次流式调用尝试（streamAttempt：取消预检/预算预检/取消竞态归因）、
 * 管道构建（tokenStream + 空闲超时 watchdog + 失败丢池）、流帧 usage 捕获与
 * 预算增量记账、空响应防御纯函数（contentOf/usageOf）。
 * 重试循环、角色装配策略、观测值对象仍属 {@link AgentChatCaller}（调用生命周期编排）。
 */
final class AgentChatPipeline {

    private final ChatClientRegistry clientRegistry;
    private final AgentRequestSpecFactory specFactory;
    /**
     * 流式调用空闲超时：相邻信号间隔超过该时长即判定端点挂起，超时失败（不可重试——
     * 实测厂商端对该类请求为稳定挂死，重试同请求只会成倍放大等待）。
     */
    private final Duration streamIdleTimeout;

    AgentChatPipeline(ChatClientRegistry clientRegistry,
                      AgentRequestSpecFactory specFactory,
                      Duration streamIdleTimeout) {
        this.clientRegistry = clientRegistry;
        this.specFactory = specFactory;
        this.streamIdleTimeout = streamIdleTimeout;
    }

    /**
     * 单次流式调用尝试（不做重试——重试由 call 的 {@link LlmRetry} / stream 的循环自行处理）：
     * 收集全部 token 阻塞至流结束，返回完整内容；onToken 可 null（无需实时回调）。
     *
     * <p>取消语义：cancelled 已置位时直接抛取消异常（零 HTTP 请求）；执行中置位时
     * takeUntil 在下一个 token 边界中止订阅——取消向上传播关闭 HTTP 连接（厂商端
     * 停止生成），部分输出不返回。
     *
     * <p>usageRef 非 null 时捕获 streamUsage 末帧真实 usage，供记录真实 token（null 则纯收集）。
     *
     * <p>预算门控（ledger 非 null）：发起前超限直接抛 {@link BudgetLedger.BudgetExceededException}
     * （零 HTTP 请求）；每轮 LLM roundtrip 的 usage 帧到达时按增量记账并复检——
     * 单次 call 内部工具循环的下一轮在超限后不再发起（断流阻止后续消耗）。
     */
    String streamAttempt(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                         String user, AgentRequestSpecFactory.Assembly assembly,
                         org.springframework.ai.chat.client.advisor.api.Advisor[] extraAdvisors, CallTrace trace,
                         Consumer<String> onToken,
                         BooleanSupplier cancelled,
                         java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                         java.util.concurrent.atomic.AtomicLong firstTokenAt, BudgetLedger ledger) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw CallCancellation.cancelException();
        }
        if (ledger != null && ledger.overBudget()) {
            throw new BudgetLedger.BudgetExceededException();
        }
        StringBuilder collected = new StringBuilder();
        // roundtrip 增量记账游标：usage 帧的 total 为该轮完整 prompt+completion，
        // 与上一轮差值即本轮新增消耗（各轮 prompt 单调递增，差值非负、求和=末轮 total，不重复计数）
        java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
        try {
            return streamCore(config, sessionId, forAgent, fallbackSystem, user, assembly,
                    extraAdvisors, trace, collected, onToken, cancelled, usageRef, prevTotal, firstTokenAt, ledger);
        } catch (RuntimeException e) {
            // 取消置位时一律按取消归因（流取消竞态下 blockLast 可能抛出其他形态异常）
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw CallCancellation.cancelException();
            }
            throw e;
        }
    }

    /**
     * 流式管道（含端点无响应兜底）：{@link #tokenStream} + 空闲超时 + 失败丢池。
     *
     * <p>路径 A（浏览器 SSE 主回答）与路径 B（阻塞收集）共用同一层兜底：此前超时只挂在
     * 路径 B，路径 A 在端点在途无响应时会永久挂起（实测主回答挂 200s+ 无任何超时信号，
     * 请求最终只能靠客户端断连收场）。
     *
     * <p>失败即丢池：连接可能已成网络黑洞（对端静默失活、不发 RST），丢池使下次调用
     * 重建连接池，不再复用同一根死连接。
     *
     * @param model 失败时用于重建客户端的模型名（null/未命中注册表则跳过丢池）
     */
    Flux<String> tokenStreamWithWatchdog(ChatClient.ChatClientRequestSpec spec,
                                         java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                         BudgetLedger ledger,
                                         java.util.concurrent.atomic.AtomicLong prevTotal,
                                         java.util.concurrent.atomic.AtomicLong firstTokenAt,
                                         String model) {
        return tokenStream(spec, usageRef, ledger, prevTotal, firstTokenAt)
                .timeout(streamIdleTimeout)
                .doOnError(e -> clientRegistry.invalidateByModel(model));
    }

    /**
     * 流式管道共用核（tokenStream 之上的路径 B 段：空闲超时/取消拦截/阻塞收集）。
     * 取消语义：takeUntil 在下一个 token 边界中止订阅（取消向上传播关闭 HTTP 连接），
     * doOnNext 拦截 takeUntil 放行的终止前元素；流结束后取消竞态复检（不按成功返回）。
     */
    String streamCore(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                      String user, AgentRequestSpecFactory.Assembly assembly,
                      org.springframework.ai.chat.client.advisor.api.Advisor[] extraAdvisors, CallTrace trace,
                      StringBuilder collected,
                      Consumer<String> onToken,
                      BooleanSupplier cancelled,
                      java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                      java.util.concurrent.atomic.AtomicLong prevTotal,
                      java.util.concurrent.atomic.AtomicLong firstTokenAt, BudgetLedger ledger) {
        tokenStreamWithWatchdog(
                        specFactory.build(config, sessionId, forAgent, fallbackSystem, user, assembly, trace,
                                extraAdvisors),
                        usageRef, ledger, prevTotal, firstTokenAt, config != null ? config.model() : null)
                .takeUntil(__ -> cancelled != null && cancelled.getAsBoolean())
                .doOnNext(token -> {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        // takeUntil 放行的终止前元素在此拦截；异常致流以错误终止，
                        // Reactor cancel 向上游传播关闭 HTTP 连接
                        throw CallCancellation.cancelException();
                    }
                    collected.append(token);
                    if (onToken != null) {
                        onToken.accept(token);
                    }
                })
                .blockLast();
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw CallCancellation.cancelException();
        }
        return collected.toString();
    }

    /**
     * 流式管道共用核（路径 A {@link GeneralAssistantAgent#executeStreamReactive} 与路径 B 同源）：
     * 请求 spec → chatResponse 流 → usage 捕获/预算增量记账（ledger null 直通）→ 空帧跳过。
     * 后续算子（超时/取消/收集 or 合并工具进度行）由调用方接续——错误通道保持原样向上传播，
     * 观测归因由各调用方的终结钩子负责。
     */
    private static Flux<String> tokenStream(ChatClient.ChatClientRequestSpec spec,
                                            java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                            BudgetLedger ledger,
                                            java.util.concurrent.atomic.AtomicLong prevTotal,
                                            java.util.concurrent.atomic.AtomicLong firstTokenAt) {
        return spec
                .stream()
                .chatResponse()
                .doOnNext(resp -> captureUsageAndAccount(resp, usageRef, ledger, prevTotal))
                // streamUsage 末帧是只含 usage 的空帧（contentOf 为 null）：Reactor 的 map
                // 不允许 null 返回（直接抛「The mapper returned a null value」），后面的
                // filter 根本不会执行——必须用 handle 跳过空帧
                .handle((org.springframework.ai.chat.model.ChatResponse resp,
                         reactor.core.publisher.SynchronousSink<String> sink) -> {
                    String token = contentOf(resp);
                    if (token != null) {
                        sink.next(token);
                    }
                })
                // 首 token 打点（观测 TTFT）：首个 token 帧到达时记录绝对时间戳，
                // 纯内存赋值（firstTokenAt null 直通=无观测场景）
                .doOnNext(token -> {
                    if (firstTokenAt != null) {
                        firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                    }
                });
    }

    /** 模型空响应防御：逐层取 assistant 文本，任一层缺失返回 null（call/stream 记录与展示共用） */
    static String contentOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                ? resp.getResult().getOutput().getText() : null;
    }

    /** 模型空响应防御：逐层取 usage，任一层缺失返回 null */
    private static Usage usageOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
    }

    /**
     * 流帧处理：捕获 usage（llm_call_log 末帧口径不变）+ 预算账本按 roundtrip 增量记账与熔断复检
     * （ledger 为 null 时仅捕获）。超限即抛 {@link BudgetLedger.BudgetExceededException} 断流——Reactor
     * 取消向上传播关闭 HTTP 连接，工具循环的下一轮不再发起。
     */
    private static void captureUsageAndAccount(org.springframework.ai.chat.model.ChatResponse resp,
                                               java.util.concurrent.atomic.AtomicReference<Usage> ref,
                                               BudgetLedger ledger,
                                               java.util.concurrent.atomic.AtomicLong prevTotal) {
        captureUsage(resp, ref);
        if (ledger == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
            return;
        }
        int total = usage.getTotalTokens();
        int delta = (int) Math.max(0, total - prevTotal.getAndSet(total));
        if (delta > 0) {
            ledger.recordUsage(delta, false);
        }
        if (ledger.overBudget()) {
            throw new BudgetLedger.BudgetExceededException();
        }
    }

    /** 模型空响应防御：从流式 chatResponse 捕获 usage（streamUsage 末帧回传真实值；取最后一个非空有效帧） */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse resp,
                                     java.util.concurrent.atomic.AtomicReference<Usage> ref) {
        if (ref == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            ref.set(usage);
        }
    }
}
