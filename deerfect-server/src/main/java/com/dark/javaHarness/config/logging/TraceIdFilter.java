package com.dark.javaHarness.config.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级 traceId 装配（结构化日志关联键）。
 *
 * <p>取值优先级：调用方传入的 {@code X-Trace-Id}（网关/前端链路透传）→ 本服务生成 32 位 hex。
 * 写入 MDC 的 {@code traceId} 随 logstash JSON 输出（见 application.yaml 的
 * logging.structured）；同时回写响应头，便于前端/网关对齐同一条请求。
 *
 * <p>入口超长或含控制字符的值直接丢弃重建：header 是外部输入，不能原样进日志与响应头。
 * 异步线程的 MDC 传递由 Micrometer context-propagation（pom 依赖）承接。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 请求/响应头名（网关与前端同名字段） */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 键名（logstash JSON 的字段名） */
    public static final String MDC_KEY = "traceId";

    /** 外部传入 traceId 的长度上限：32 位 hex 足够，超长视为噪音 */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被复用：必须清除，否则下一个请求继承上一个的 traceId
            MDC.remove(MDC_KEY);
        }
    }

    /** 透传值可用则沿用（跨服务关联），不可用则生成新值 */
    private static String resolve(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_TRACE_ID_LENGTH
                || !incoming.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return incoming.trim();
    }
}
