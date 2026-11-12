package com.dark.javaHarness.llm.routing;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;

/**
 * 失败分类：决定"同成员重试 / 换下一成员 / 直接失败"。
 *
 * <p>纯函数，不依赖网络与配置，便于单测覆盖完整矩阵。
 */
public final class FailurePolicy {

    public enum Action {
        /** 同一成员重试（典型：429 限流） */
        RETRY_SAME,
        /** 换下一成员（网络类错误、5xx、408） */
        NEXT_MEMBER,
        /** 直接失败（4xx 参数/鉴权错误、取消、未知异常） */
        FAIL
    }

    private FailurePolicy() {
    }

    public static Action classify(Throwable throwable) {
        Throwable t = Exceptions.unwrap(throwable);
        if (t instanceof CancellationException) {
            return Action.FAIL;
        }
        Integer status = httpStatus(t);
        if (status != null) {
            if (status == 429) {
                return Action.RETRY_SAME;
            }
            if (status == 408) {
                return Action.NEXT_MEMBER;
            }
            if (status >= 500) {
                return Action.NEXT_MEMBER;
            }
            if (status >= 400) {
                return Action.FAIL;
            }
        }
        if (t instanceof TimeoutException || t instanceof IOException) {
            return Action.NEXT_MEMBER;
        }
        return Action.FAIL;
    }

    private static Integer httpStatus(Throwable t) {
        if (t instanceof WebClientResponseException ex) {
            return ex.getStatusCode().value();
        }
        return null;
    }
}
