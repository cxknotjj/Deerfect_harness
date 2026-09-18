package com.dark.javaHarness.exception;

/**
 * 流式连接数已达上限（过载保护触发）。
 *
 * <p>由 {@code StreamConnectionLimiter} 在流式入口（streamReactive/resume）并发超限时同步抛出，
 * {@link GlobalExceptionHandler} 统一映射为 429 Too Many Requests——客户端应稍后重试，
 * 而非被兜底处理器吞成 500「服务器内部错误」。
 */
public class ConcurrentRequestException extends RuntimeException {

    public ConcurrentRequestException(String message) {
        super(message);
    }
}
