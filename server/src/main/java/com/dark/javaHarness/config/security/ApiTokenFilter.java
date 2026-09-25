package com.dark.javaHarness.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 最小 API 鉴权过滤器（优化审查 2026-09-25 最高危项收敛）——双通道「配置即启用」：
 * <ul>
 *   <li>机器通道：{@code app.security.api-token} 非空启用，请求须携带 {@code X-API-Token} 头
 *       且值匹配（{@link MessageDigest#isEqual} 常量时间比较防时序侧信道）——供 curl/脚本直连</li>
 *   <li>登录通道：{@code app.security.password} 非空启用，浏览器经 /api/auth/login 以口令换
 *       HttpOnly Cookie（{@link #COOKIE}，凭据不进前端构建产物），本过滤器查
 *       {@link LoginStateStore} 验证登录态</li>
 *   <li>两通道均未配置（默认）不启用：本地单机零影响，公网部署按需配置即得防护</li>
 * </ul>
 * 拦截范围仅 {@code /api/**}；豁免：{@code /api/auth/**}（登录/登出/探测本身）、
 * {@code /onebot/**}（NapCat 上报自带 HMAC-SHA1 验签）、{@code /error}（错误页转发）、
 * 静态资源与根路径（登录页本身要能加载，非真实端点放行后由 404 兜底）。
 *
 * <p>CLI / QQ 渠道为进程内调用不经 HTTP，不受影响。
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    /** 机器通道鉴权头名（web client.ts / CLI 同名常量） */
    public static final String HEADER = "X-API-Token";

    /** 登录通道 Cookie 名（AuthController 下发同名 HttpOnly Cookie） */
    public static final String COOKIE = "harness_login";

    /** 豁免前缀：NapCat 上报（自有验签） */
    static final String ONEBOT_PREFIX = "/onebot/";

    /** 豁免前缀：登录端点自身 */
    static final String AUTH_PREFIX = "/api/auth/";

    private final byte[] expectedToken;
    private final LoginStateStore loginState;

    /**
     * @param apiToken   机器通道 token；空 = 该通道不启用
     * @param loginState 登录态登记簿；null = 登录通道不启用（SecurityConfig 按 password 是否配置决定）
     */
    public ApiTokenFilter(String apiToken, LoginStateStore loginState) {
        this.expectedToken = apiToken == null ? new byte[0] : apiToken.getBytes(StandardCharsets.UTF_8);
        this.loginState = loginState;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.startsWith(ONEBOT_PREFIX) || "/error".equals(path)) {
            return true;
        }
        // 只拦 /api/**；登录端点与静态资源直行
        if (!path.startsWith("/api/") || path.startsWith(AUTH_PREFIX)) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 双通道均未配置：不启用鉴权，直通（本地单机默认形态）
        if (!headerEnabled() && !loginEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        // 机器通道：X-API-Token 头常量时间匹配
        String provided = request.getHeader(HEADER);
        if (headerEnabled() && provided != null && MessageDigest.isEqual(
                expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        // 登录通道：harness_login Cookie 有效（含滑动续期）
        if (loginEnabled()) {
            String cookieToken = firstCookie(request, COOKIE);
            if (cookieToken != null && loginState.isValid(cookieToken)) {
                chain.doFilter(request, response);
                return;
            }
        }
        log.warn("[api-auth] 鉴权失败：{} {}（双通道均未通过）", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\"}");
    }

    private boolean headerEnabled() {
        return expectedToken.length > 0;
    }

    private boolean loginEnabled() {
        return loginState != null;
    }

    private static String firstCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
