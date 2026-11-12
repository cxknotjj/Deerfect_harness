package com.dark.javaHarness.config.logging;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Reactor 上下文传播：把 {@link TraceIdFilter} 写入的 MDC 带进异步调度线程。
 *
 * <p>必要性：流式聊天的路径判定与 Agent 生成都经 {@code boundedElastic} 执行，MDC 是
 * ThreadLocal，不传播时异步线程日志只有业务标识、没有请求级 traceId。开启后订阅链在
 * 请求线程捕获 ThreadLocal 快照，后续调度线程按快照恢复。
 *
 * <p>注：全局 Hook 与 ContextRegistry 是 JVM 级状态，多 Spring 上下文（测试）重复初始化时
 * 只执行一次；模型 SDK 自建线程池不经营养器调度，不在传播范围。
 */
@Configuration
public class ContextPropagationConfig {

    /** JVM 级全局状态只初始化一次（多上下文测试场景） */
    private static final AtomicBoolean ENABLED = new AtomicBoolean();

    @PostConstruct
    public void enableMdcPropagation() {
        if (!ENABLED.compareAndSet(false, true)) {
            return;
        }
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }
}
