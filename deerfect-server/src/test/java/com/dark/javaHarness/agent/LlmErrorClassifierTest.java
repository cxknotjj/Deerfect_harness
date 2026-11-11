package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 错误分类纯函数单测：客户端请求错误（4xx 除 408）判定覆盖两种载体形态
 * （WebClientResponseException / OpenAIServiceException）与 cause 链包装，
 * 供管道丢池决策（doOnError）区分「请求被拒」与「连接失活」。
 */
class LlmErrorClassifierTest {

    /** OpenAI SDK 服务异常桩：statusCode + message */
    private static OpenAIServiceException serviceError(int status, String message) {
        OpenAIServiceException e = mock(OpenAIServiceException.class);
        when(e.statusCode()).thenReturn(status);
        when(e.getMessage()).thenReturn(message);
        return e;
    }

    private static WebClientResponseException webError(int status) {
        return WebClientResponseException.create(status, "status-" + status,
                HttpHeaders.EMPTY, (byte[]) null, null);
    }

    @Test
    void isClientRequestError_webClient4xx_true_5xx_false() {
        assertTrue(LlmErrorClassifier.isClientRequestError(webError(400)));
        assertTrue(LlmErrorClassifier.isClientRequestError(webError(401)));
        assertTrue(LlmErrorClassifier.isClientRequestError(webError(429)));
        assertFalse(LlmErrorClassifier.isClientRequestError(webError(500)));
        assertFalse(LlmErrorClassifier.isClientRequestError(webError(503)));
    }

    @Test
    void isClientRequestError_408Excluded_staleConnectionSignal() {
        // 408 请求超时常为池内陈旧连接表征：不按客户端错误对待（应丢池）
        assertFalse(LlmErrorClassifier.isClientRequestError(webError(408)));
        assertFalse(LlmErrorClassifier.isClientRequestError(serviceError(408, "408: Request Timeout")));
    }

    @Test
    void isClientRequestError_serviceExceptionStatusCodeShape() {
        assertTrue(LlmErrorClassifier.isClientRequestError(serviceError(400, "400: Bad Request")));
        assertTrue(LlmErrorClassifier.isClientRequestError(serviceError(402, "402: usage limit")));
        assertFalse(LlmErrorClassifier.isClientRequestError(serviceError(502, "502: Bad Gateway")));
    }

    @Test
    void isClientRequestError_walksCauseChain_andHandlesPlain() {
        // WebClientResponseException 被包装在 cause 链里（Reactor 块收集等场景）仍应命中
        RuntimeException wrapped = new RuntimeException("outer", webError(400));
        assertTrue(LlmErrorClassifier.isClientRequestError(wrapped));
        // OpenAI SDK 服务异常被包装在 cause 链里同样命中
        OpenAIServiceException svc = serviceError(401, "401: invalid api key");
        assertTrue(LlmErrorClassifier.isClientRequestError(new RuntimeException("outer", svc)));
        // 普通运行时异常（网络类/未知）不命中
        assertFalse(LlmErrorClassifier.isClientRequestError(new RuntimeException("boom")));
        assertFalse(LlmErrorClassifier.isClientRequestError(null));
    }
}
