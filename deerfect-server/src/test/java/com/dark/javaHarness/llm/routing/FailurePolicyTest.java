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

    private static WebClientResponseException http(int status) {
        return WebClientResponseException.create(status, "error", HttpHeaders.EMPTY,
                (byte[]) null, null);
    }
}
