package com.dark.javaHarness.llm.routing;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 路由执行器：按成员优先级推进，集中处理重试与失败切换。
 *
 * <p>流式调用只在"尚未输出任何内容"时允许切换；首个 token 到达后的锁定由上层调用方
 * 通过取消/终止信号表达，本执行器不拼接两个模型的输出。
 */
@Component
public class RouteExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouteExecutor.class);

    private final int maxSameMemberRetries;

    public RouteExecutor(@Value("${app.llm.route.max-same-member-retries:2}") int maxSameMemberRetries) {
        this.maxSameMemberRetries = Math.max(0, maxSameMemberRetries);
    }

    /**
     * 依次尝试路由成员。
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
        for (RoutePlan.Member member : plan.members()) {
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
                    log.warn("[llm] 路由 {} 成员 {} 失败，切换下一成员: {}",
                            plan.routeCode(), member.modelId(), e.getMessage());
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("路由无可用成员: " + plan.routeCode());
    }

    /** 单成员调用。 */
    @FunctionalInterface
    public interface Attempt<T> {
        T call(RoutePlan.Member member);
    }

    /** 尝试观测回调。 */
    public interface AttemptObserver {

        default void onSuccess(RoutePlan.Member member, int attempt, Long fallbackFrom) {
        }

        default void onFailure(RoutePlan.Member member, int attempt, Long fallbackFrom,
                               RuntimeException error, FailurePolicy.Action action) {
        }
    }
}
