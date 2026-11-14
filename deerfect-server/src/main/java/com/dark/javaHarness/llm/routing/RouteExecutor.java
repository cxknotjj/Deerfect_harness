package com.dark.javaHarness.llm.routing;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 路由执行器：按成员优先级推进，集中处理重试与失败切换——阻塞路径（{@link #execute}）
 * 与流式路径（{@link #stream}）共用同一套成员推进策略，调用方只提供「单个成员怎么调」。
 *
 * <p>流式调用只在「尚未输出任何内容」时允许切换；首个 token 到达后锁定当前成员，
 * 错误直接上抛，绝不拼接两个模型的输出。
 *
 * <p>副作用（丢池、指标、归因日志）由调用方经 {@link AttemptObserver} 注入：本类只做
 * 策略判定，不依赖客户端缓存与观测设施，保持 llm.routing 的纯策略边界。
 */
@Component
public class RouteExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouteExecutor.class);

    /** 同成员重试上限缺省值：429 退避后仍失败才切换（设计 §6.1） */
    public static final int DEFAULT_MAX_SAME_MEMBER_RETRIES = 2;

    private final int maxSameMemberRetries;

    public RouteExecutor(
            @Value("${app.llm.route.max-same-member-retries:2}") int maxSameMemberRetries) {
        this.maxSameMemberRetries = Math.max(0, maxSameMemberRetries);
    }

    /**
     * 依次尝试路由成员（阻塞）。
     *
     * @param plan       路由解析结果
     * @param cancelled  取消信号，可为 null
     * @param attempt    单成员调用
     * @param observer   每次尝试的观测回调，可为 null
     * @return 首个成功结果
     */
    public <T> T execute(RoutePlan plan,
                         BooleanSupplier cancelled,
                         Attempt<T> attempt,
                         AttemptObserver observer) {
        RuntimeException last = null;
        Long fallbackFrom = null;
        int attemptNo = 0;
        List<RoutePlan.Member> members = plan.members();
        for (int i = 0; i < members.size(); i++) {
            RoutePlan.Member member = members.get(i);
            int sameMemberRetried = 0;
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancellationException("请求已取消");
                }
                attemptNo++;
                try {
                    T result = attempt.call(member);
                    if (observer != null) {
                        observer.onSuccess(member, attemptNo, fallbackFrom);
                    }
                    return result;
                } catch (CancellationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    FailurePolicy.Action action = FailurePolicy.classify(e);
                    if (observer != null) {
                        observer.onFailure(member, attemptNo, fallbackFrom, e, action);
                    }
                    // 调用方终端条件（预算熔断 / 已产生输出等）：原样上抛，不重试不切换
                    if (observer != null && observer.shouldStop(e)) {
                        throw e;
                    }
                    last = e;
                    if (action == FailurePolicy.Action.FAIL) {
                        throw e;
                    }
                    if (action == FailurePolicy.Action.RETRY_SAME
                            && sameMemberRetried < maxSameMemberRetries) {
                        sameMemberRetried++;
                        log.warn("[llm] 路由 {} 成员 {} 第 {} 次重试（{}）",
                                plan.routeCode(), member.modelId(), sameMemberRetried,
                                e.getClass().getSimpleName());
                        continue;
                    }
                    fallbackFrom = member.modelId();
                    if (i + 1 < members.size()) {
                        if (observer != null) {
                            observer.onSwitch(member, members.get(i + 1));
                        }
                        log.warn("[llm] 路由 {} 成员 {} 失败，切换下一成员: {}",
                                plan.routeCode(), member.modelId(), e.getMessage());
                    } else {
                        log.warn("[llm] 路由 {} 成员 {} 失败，已无下一成员: {}",
                                plan.routeCode(), member.modelId(), e.getMessage());
                    }
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("路由无可用成员: " + plan.routeCode());
    }

    /**
     * 依次尝试路由成员（流式）。首帧到达即锁定当前成员——切换只发生在一帧未输出之前。
     *
     * @param plan      路由解析结果
     * @param cancelled 取消信号，可为 null
     * @param attempt   单成员流式调用
     * @param observer  每次尝试的观测回调，可为 null
     * @return 首个成功成员的 token 流
     */
    public <T> Flux<T> stream(RoutePlan plan,
                              BooleanSupplier cancelled,
                              StreamAttempt<T> attempt,
                              AttemptObserver observer) {
        return streamMember(plan, 0, 0, cancelled, attempt, observer, new AtomicInteger(), null);
    }

    private <T> Flux<T> streamMember(RoutePlan plan, int index, int sameMemberRetried,
                                     BooleanSupplier cancelled,
                                     StreamAttempt<T> attempt,
                                     AttemptObserver observer,
                                     AtomicInteger attemptNo,
                                     Long fallbackFrom) {
        List<RoutePlan.Member> members = plan.members();
        if (index >= members.size()) {
            return Flux.error(new IllegalStateException("路由无可用成员: " + plan.routeCode()));
        }
        RoutePlan.Member member = members.get(index);
        AtomicBoolean emitted = new AtomicBoolean();
        return Flux.defer(() -> {
                    int no = attemptNo.incrementAndGet();
                    return attempt.call(member).doOnNext(__ -> emitted.set(true));
                })
                .onErrorResume(e -> {
                    // 取消 / 已输出内容 / 取消竞态：一律不切换，原样上抛
                    if ((cancelled != null && cancelled.getAsBoolean())
                            || emitted.get()
                            || e instanceof CancellationException) {
                        return Flux.error(e);
                    }
                    RuntimeException failure =
                            e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
                    FailurePolicy.Action action = FailurePolicy.classify(e);
                    if (observer != null) {
                        observer.onFailure(member, attemptNo.get(), fallbackFrom, failure, action);
                    }
                    if (observer != null && observer.shouldStop(failure)) {
                        return Flux.error(e);
                    }
                    if (action == FailurePolicy.Action.FAIL) {
                        return Flux.error(e);
                    }
                    // 429 限流：先在当前成员上退避重试，重试次数用尽才切换——
                    // 与阻塞路径 execute 的推进策略保持一致（此前会直接切换，
                    // 既少了一次恢复机会，又多记一次 route.fallbacks）
                    if (action == FailurePolicy.Action.RETRY_SAME
                            && sameMemberRetried < maxSameMemberRetries) {
                        log.warn("[llm] 路由 {} 成员 {} 第 {} 次重试（{}）",
                                plan.routeCode(), member.modelId(), sameMemberRetried + 1,
                                failure.getClass().getSimpleName());
                        return streamMember(plan, index, sameMemberRetried + 1, cancelled,
                                attempt, observer, attemptNo, fallbackFrom);
                    }
                    if (index + 1 >= members.size()) {
                        // 已是最后一个成员：原样上抛本次错误，交给外层重试策略分类
                        // （此前会换成「路由无可用成员」，把可重试的超时/5xx 误判为不可重试）
                        return Flux.error(e);
                    }
                    if (observer != null) {
                        observer.onSwitch(member, members.get(index + 1));
                    }
                    log.warn("[llm] 路由 {} 成员 {} 失败，切换下一成员: {}",
                            plan.routeCode(), member.modelId(), e.getMessage());
                    return streamMember(plan, index + 1, 0, cancelled, attempt, observer,
                            attemptNo, member.modelId());
                });
    }

    /** 单成员阻塞调用。 */
    @FunctionalInterface
    public interface Attempt<T> {
        T call(RoutePlan.Member member);
    }

    /** 单成员流式调用。 */
    @FunctionalInterface
    public interface StreamAttempt<T> {
        Flux<T> call(RoutePlan.Member member);
    }

    /** 尝试观测回调：成功/失败/切换/终端条件，全部可选。 */
    public interface AttemptObserver {

        default void onSuccess(RoutePlan.Member member, int attempt, Long fallbackFrom) {
        }

        default void onFailure(RoutePlan.Member member, int attempt, Long fallbackFrom,
                               RuntimeException error, FailurePolicy.Action action) {
        }

        /** 真正切换到下一成员时回调（指标 / 归因挂钩）。 */
        default void onSwitch(RoutePlan.Member from, RoutePlan.Member to) {
        }

        /** 调用方终端条件：返回 true 时立即上抛，不再重试或切换。 */
        default boolean shouldStop(RuntimeException error) {
            return false;
        }
    }
}
