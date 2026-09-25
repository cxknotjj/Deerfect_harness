package com.dark.javaHarness.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 最小 API 鉴权过滤器：单 token 模式（优化审查 2026-09-25 高危项收敛）。
 *
 * <p>背景：全部 /api/** 接口无鉴权（烧 LLM token、热改模型映射、读全量调用日志均裸奔）。
 * 本过滤器提供「配置即启用」的最小防护：
 * <ul>
 *   <li>配置 {@code app.security.api-token} 后启用：请求须携带 {@code X-API-Token} 头且值匹配，
 *       否则 401。比较用 {@link MessageDigest#isEqual} 常量时间比较，防时序侧信道。</li>
 *   <li>配置为空（默认）不启用：本地单机零影响，公网部署时配置即得。</li>
 *   <li>豁免：{@code /onebot/**}（NapCat 上报自带 HMAC-SHA1 验签，见
 *       OneBotEventController.signatureValid）与 {@code /error}（错误页转发）。</li>
 * </ul>
 *
 * <p>web 前端经 {@code VITE_API_TOKEN} 构建注入同值，client.ts 统一加头；
 * CLI / QQ 渠道为进程内调用不经 HTTP，不受影响。
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    /** 鉴权头名（web client.ts 同名常量） */
    public static final String HEADER = "X-API-Token";

    /** 豁免前缀：NapCat 上报（自有验签） */
    static final String ONEBOT_PREFIX = "/onebot/";

    private final byte[] expectedToken;

    public ApiTokenFilter(String apiToken) {
        this.expectedToken = apiToken == null ? new byte[0] : apiToken.getBytes(StandardCharsets.UTF_8);
    }

    /** token 是否已配置（空 = 过滤器直通，不启用鉴权） */
    boolean enabled() {
        return expectedToken.length > 0;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || path.startsWith(ONEBOT_PREFIX) || "/error".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 未配置 token：不启用鉴权，直通（本地单机默认形态）
        if (!enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(
                expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("[api-auth] 鉴权失败：{} {}（缺头或 token 不匹配）", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\"}");
    }
}
