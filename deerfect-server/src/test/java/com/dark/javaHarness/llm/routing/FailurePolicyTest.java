package com.dark.javaHarness.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 失败矩阵：同成员重试 / 换成员 / 直接失败。 */
class FailurePolicyTest {

    @Test
    void timeout_switchesMember() {
        assertEquals(FailurePolicy.Action.NEXT_MEMBER,
                FailurePolicy.classify(new TimeoutException("timeout")));
    }

    @Test
    void ioError_switchesMember() {
        assertEquals(FailurePolicy.Action.NEXT_MEMBER,
                FailurePolicy.classify(new IOException("connection reset")));
    }

    @Test
    void http5xx_switchesMember() {
        assertEquals(FailurePolicy.Action.NEXT_MEMBER,
                FailurePolicy.classify(http(503)));
    }

    @Test
    void http408_switchesMember() {
        assertEquals(FailurePolicy.Action.NEXT_MEMBER,
                FailurePolicy.classify(http(408)));
    }

    @Test
    void http429_retriesSameMember() {
        assertEquals(FailurePolicy.Action.RETRY_SAME,
                FailurePolicy.classify(http(429)));
    }

    @Test
    void http400_failsFast() {
        assertEquals(FailurePolicy.Action.FAIL,
                FailurePolicy.classify(http(400)));
    }

    @Test
    void cancellation_failsFast() {
        assertEquals(FailurePolicy.Action.FAIL,
                FailurePolicy.classify(new java.util.concurrent.CancellationException("cancel")));
    }

    @Test
    void unknownRuntime_failsFast() {
        assertEquals(FailurePolicy.Action.FAIL,
                FailurePolicy.classify(new IllegalStateException("boom")));
    }

    /* ---------------- error_class 观测口径 ---------------- */

    @Test
    void errorClass_classifiesKnownFailures() {
        assertEquals("timeout", FailurePolicy.errorClass(new TimeoutException("timeout")));
        assertEquals("timeout", FailurePolicy.errorClass(new java.net.SocketTimeoutException("read")));
        assertEquals("connection", FailurePolicy.errorClass(new IOException("reset")));
        assertEquals("4xx", FailurePolicy.errorClass(http(401)));
        assertEquals("5xx", FailurePolicy.errorClass(http(503)));
        assertEquals("cancel", FailurePolicy.errorClass(new java.util.concurrent.CancellationException("x")));
        assertEquals("other", FailurePolicy.errorClass(new IllegalStateException("boom")));
    }

    @Test
    void routeAttempt_primaryAndFallback() {
        RoutePlan plan = new RoutePlan(1L, "main", java.util.List.of(
                new RoutePlan.Member(10L, 1L, "dashscope", "https://a", "m-a", "A", 10, "{}", null),
                new RoutePlan.Member(11L, 2L, "deepseek", "https://b", "m-b", "B", 20, "{}", null)));

        RouteAttempt first = RouteAttempt.at(plan, 0);
        assertEquals(10L, first.modelId());
        assertEquals(null, first.fallbackFromModelId());

        RouteAttempt second = RouteAttempt.at(plan, 1);
        assertEquals(11L, second.modelId());
        assertEquals(10L, second.fallbackFromModelId(), "切换后应记录上一成员为来源");
        assertEquals("main", second.routeCode());
        assertEquals("deepseek", second.providerCode());
        assertEquals("m-b", second.upstreamName());
    }

    private static WebClientResponseException http(int status) {
        return WebClientResponseException.create(status, "error", HttpHeaders.EMPTY,
                (byte[]) null, null);
    }
}
