package com.dark.javaHarness.config;

import com.dark.javaHarness.exception.ConcurrentRequestException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 流式连接数限制器（过载保护）：限制同时在途的流式请求（streamReactive/resume）数量，
 * 防止编排类请求长时间占用把服务拖垮。
 *
 * <p>语义：{@code maxConnections <= 0} 表示不限制（直通放行，行为等同无限流），
 * 与项目「全键统一 0 = 不限制」的预算口径一致；上限内计数放行，超限立即抛出
 * {@link ConcurrentRequestException}（由全局异常处理映射 429，客户端稍后重试）。
 * 释放经 {@link #release()}，带下限保护——计数永不为负，多释放/重复释放无副作用。
 *
 * <p>构造注入上限便于单测直接 {@code new StreamConnectionLimiter(2)} 构造小上限实例。
 */
@Component
public class StreamConnectionLimiter {

    private final int maxConnections;
    private final AtomicInteger active = new AtomicInteger();

    public StreamConnectionLimiter(@Value("${app.chat.max-stream-connections:50}") int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * 尝试占有一个流式连接名额：上限内计数放行；超限回退计数并抛出
     * {@link ConcurrentRequestException}（message 含当前活跃数与上限）。
     * {@code maxConnections <= 0} 时不限制，直通返回。
     */
    public void tryAcquire() {
        if (maxConnections <= 0) {
            return;
        }
        int current = active.incrementAndGet();
        if (current > maxConnections) {
            active.decrementAndGet();
            throw new ConcurrentRequestException("当前流式连接数已达上限（活跃 " + (current - 1)
                    + "，上限 " + maxConnections + "），请稍后重试");
        }
    }

    /** 释放一个流式连接名额（下限保护：计数不低于 0，多释放无副作用） */
    public void release() {
        active.updateAndGet(cur -> Math.max(0, cur - 1));
    }
}
