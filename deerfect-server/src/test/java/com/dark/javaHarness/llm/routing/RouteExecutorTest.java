package com.dark.javaHarness.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/** 路由推进：成功、换成员、4xx 立即失败、429 同成员重试、取消；流式首帧前切换 / 首帧后锁定。 */
class RouteExecutorTest {

    private final RouteExecutor executor = new RouteExecutor(2);

    @Test
    void firstMemberSucceeds_noSwitch() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(plan, () -> false, m -> {
            calls.incrementAndGet();
            return "ok-" + m.modelId();
        }, null);

        assertEquals("ok-1", result);
        assertEquals(1, calls.get());
    }

    @Test
    void serverError_switchesToNextMember() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));

        String result = executor.execute(plan, () -> false, m -> {
            if (m.modelId() == 1L) {
                throw http(503);
            }
            return "ok-" + m.modelId();
        }, null);

        assertEquals("ok-2", result);
    }

    @Test
    void clientError_failsWithoutSwitch() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger secondCalled = new AtomicInteger();

        WebClientResponseException error = http(400);
        WebClientResponseException thrown = assertThrows(WebClientResponseException.class,
                () -> executor.execute(plan, () -> false, m -> {
                    if (m.modelId() == 1L) {
                        throw error;
                    }
                    secondCalled.incrementAndGet();
                    return "ok";
                }, null));

        assertEquals(400, thrown.getStatusCode().value());
        assertEquals(0, secondCalled.get());
    }

    @Test
    void rateLimit_retriesSameMemberThenSwitches() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger firstCalls = new AtomicInteger();

        String result = executor.execute(plan, () -> false, m -> {
            if (m.modelId() == 1L) {
                firstCalls.incrementAndGet();
                throw http(429);
            }
            return "ok-" + m.modelId();
        }, null);

        assertEquals("ok-2", result);
        assertEquals(3, firstCalls.get(), "429 应重试 2 次后再切换（共 3 次）");
    }

    @Test
    void allMembersFail_throwsLastError() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> executor.execute(plan, () -> false, m -> {
                    throw http(503);
                }, null));

        assertTrue(thrown.getMessage() == null || thrown instanceof WebClientResponseException);
    }

    @Test
    void cancelled_beforeAttempt_throwsAndDoesNotCall() {
        RoutePlan plan = plan(member(1L, 10));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(CancellationException.class,
                () -> executor.execute(plan, () -> true, m -> {
                    calls.incrementAndGet();
                    return "ok";
                }, null));

        assertEquals(0, calls.get());
    }

    @Test
    void observer_recordsFallbackFrom() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        StringBuilder trace = new StringBuilder();

        executor.execute(plan, () -> false, m -> {
            if (m.modelId() == 1L) {
                throw http(503);
            }
            return "ok";
        }, new RouteExecutor.AttemptObserver() {
            @Override
            public void onFailure(RoutePlan.Member m, int attempt, Long fallbackFrom,
                                  RuntimeException error, FailurePolicy.Action action) {
                trace.append("fail:").append(m.modelId()).append(":").append(action).append(";");
            }

            @Override
            public void onSuccess(RoutePlan.Member m, int attempt, Long fallbackFrom) {
                trace.append("ok:").append(m.modelId()).append(":from=").append(fallbackFrom);
            }
        });

        assertEquals("fail:1:NEXT_MEMBER;ok:2:from=1", trace.toString());
    }

    // ---------- 流式：首帧前允许切换，首帧后锁定 ----------

    @Test
    void stream_firstMemberSucceeds_noSwitch() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger calls = new AtomicInteger();

        List<String> out = executor.stream(plan, () -> false, m -> {
            calls.incrementAndGet();
            return Flux.just("a-" + m.modelId(), "b-" + m.modelId());
        }, null).collectList().block();

        assertEquals(List.of("a-1", "b-1"), out);
        assertEquals(1, calls.get());
    }

    @Test
    void stream_serverErrorBeforeToken_switchesToNextMember() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));

        List<String> out = executor.stream(plan, () -> false, m -> {
            if (m.modelId() == 1L) {
                return Flux.error(http(503));
            }
            return Flux.just("ok-" + m.modelId());
        }, null).collectList().block();

        assertEquals(List.of("ok-2"), out);
    }

    @Test
    void stream_errorAfterFirstToken_locksMemberWithoutSwitch() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger secondCalled = new AtomicInteger();

        assertThrows(WebClientResponseException.class, () ->
                executor.stream(plan, () -> false, m -> {
                    if (m.modelId() == 1L) {
                        return Flux.concat(Flux.just("partial"), Flux.error(http(500)));
                    }
                    secondCalled.incrementAndGet();
                    return Flux.just("ok");
                }, null).collectList().block());

        assertEquals(0, secondCalled.get(), "首 token 后错误不得切换成员（避免两段模型输出拼接）");
    }

    @Test
    void stream_rateLimit_retriesSameMemberThenSucceeds() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger switches = new AtomicInteger();

        List<String> out = executor.stream(plan, () -> false, m -> {
            if (m.modelId() == 1L && firstCalls.incrementAndGet() <= 2) {
                return Flux.error(http(429));
            }
            return Flux.just("ok-" + m.modelId());
        }, switchCounter(switches)).collectList().block();

        assertEquals(List.of("ok-1"), out);
        assertEquals(3, firstCalls.get(), "429 应在同成员上重试 2 次后成功（共 3 次）");
        assertEquals(0, switches.get(), "同成员重试不应记为失败切换");
    }

    @Test
    void stream_rateLimitExhausted_switchesToNextMember() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger switches = new AtomicInteger();

        List<String> out = executor.stream(plan, () -> false, m -> {
            if (m.modelId() == 1L) {
                firstCalls.incrementAndGet();
                return Flux.error(http(429));
            }
            return Flux.just("ok-" + m.modelId());
        }, switchCounter(switches)).collectList().block();

        assertEquals(List.of("ok-2"), out);
        assertEquals(3, firstCalls.get(), "重试 2 次用尽后才切换（共 3 次）");
        assertEquals(1, switches.get(), "真正换成员时才计一次 route.fallbacks");
    }

    @Test
    void stream_clientError_failsWithoutRetryOrSwitch() {
        RoutePlan plan = plan(member(1L, 10), member(2L, 20));
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger switches = new AtomicInteger();

        assertThrows(WebClientResponseException.class, () ->
                executor.stream(plan, () -> false, m -> {
                    if (m.modelId() == 1L) {
                        firstCalls.incrementAndGet();
                        return Flux.error(http(400));
                    }
                    secondCalls.incrementAndGet();
                    return Flux.just("ok");
                }, switchCounter(switches)).collectList().block());

        assertEquals(1, firstCalls.get(), "4xx 不可重试，只尝试一次");
        assertEquals(0, secondCalls.get(), "4xx 不应切换成员");
        assertEquals(0, switches.get());
    }

    private static RouteExecutor.AttemptObserver switchCounter(AtomicInteger counter) {
        return new RouteExecutor.AttemptObserver() {
            @Override
            public void onSwitch(RoutePlan.Member from, RoutePlan.Member to) {
                counter.incrementAndGet();
            }
        };
    }

    private static RoutePlan plan(RoutePlan.Member... members) {
        return new RoutePlan(1L, "main", List.of(members));
    }

    private static RoutePlan.Member member(long modelId, int priority) {
        return new RoutePlan.Member(modelId, 100L + modelId, "p" + modelId,
                "https://example.com", "m" + modelId, "M" + modelId, priority, "{}", null);
    }

    private static WebClientResponseException http(int status) {
        return WebClientResponseException.create(status, "error", HttpHeaders.EMPTY,
                (byte[]) null, null);
    }
}
