package com.dark.javaHarness.config.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录端点：口令换 HttpOnly Cookie（方案 B，凭据不进前端构建产物）。
 *
 * <p>链路：POST /api/auth/login 校验口令（常量时间比较）→ {@link LoginStateStore} 登记登录态 →
 * 下发 {@code harness_login} Cookie（HttpOnly + SameSite=Lax，浏览器自动携带，JS 不可读）；
 * 后续请求由 {@link ApiTokenFilter} 查登录态放行。本控制器全部端点在过滤器豁免清单内
 * （/api/auth/**），/me 供前端启动时探测「是否启用登录 + 是否已登录」，始终 200 不产生噪音。
 *
 * <p>防爆破：同 IP 连续失败达阈值锁定一段时间（内存计数，单用户场景无需验证码）；
 * 锁定期间直接 429，不比较口令（防时序预言）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 登录通道 Cookie 名（与 ApiTokenFilter.COOKIE 同源，独立常量避免反向依赖过滤器） */
    public static final String COOKIE_NAME = ApiTokenFilter.COOKIE;

    /**
     * 失败计数的保留时长：超过这段时间没有新失败的 IP 条目由清理任务移除。
     * 不清理的后果：只在登录成功时 remove，被扫描器刷过的 IP 会永久留一条——公网暴露下
     * map 只增不减（体检 2026-11-07）。
     */
    private static final long FAILURE_RETENTION_MS = 3600_000L;

    private final String password;
    private final long ttlHours;
    private final int maxFailures;
    private final long lockSeconds;
    private final LoginStateStore loginState;

    /** 同 IP 失败计数：count 累计失败次数，lockedUntil>0 表示锁定截止时刻（毫秒），lastFailureAt 供清理判定 */
    private record FailState(int count, long lockedUntil, long lastFailureAt) {
    }

    private final ConcurrentHashMap<String, FailState> failuresByIp = new ConcurrentHashMap<>();

    public AuthController(@Value("${app.security.password:}") String password,
                          @Value("${app.security.login-ttl-hours:168}") long ttlHours,
                          @Value("${app.security.login-max-failures:5}") int maxFailures,
                          @Value("${app.security.login-lock-seconds:60}") long lockSeconds,
                          LoginStateStore loginState) {
        this.password = password;
        this.ttlHours = ttlHours;
        this.maxFailures = maxFailures;
        this.lockSeconds = lockSeconds;
        this.loginState = loginState;
    }

    /** 登录请求体 */
    public record LoginRequest(String password) {
    }

    /** 登录：口令正确 → 200 + Set-Cookie（HttpOnly 登录态）；错误 → 401；锁定中 → 429；未启用 → 403 */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                     HttpServletRequest request) {
        if (notEnabled()) {
            return respond(HttpStatus.FORBIDDEN, 403, "登录未启用");
        }
        String ip = clientIp(request);
        long now = System.currentTimeMillis();
        FailState state = failuresByIp.get(ip);
        if (state != null && state.lockedUntil() > now) {
            return respond(HttpStatus.TOO_MANY_REQUESTS, 429, "失败次数过多，请稍后再试");
        }
        String provided = body == null || body.password() == null ? "" : body.password();
        if (MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            failuresByIp.remove(ip); // 登录成功重置失败计数
            String token = loginState.create();
            ResponseCookie cookie = cookieSpec(token, Duration.ofHours(ttlHours), request.isSecure());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(Map.of("code", 0, "message", "ok"));
        }
        registerFailure(ip, now);
        return respond(HttpStatus.UNAUTHORIZED, 401, "口令错误");
    }

    /** 登录态探测（前端启动时调用）：enabled=是否启用登录通道，authenticated=当前浏览器是否已登录 */
    @GetMapping("/me")
    public Map<String, Object> me(@CookieValue(name = COOKIE_NAME, required = false) String token) {
        boolean enabled = !notEnabled();
        boolean authenticated = enabled && token != null && loginState.isValid(token);
        return Map.of("enabled", enabled, "authenticated", authenticated);
    }

    /** 登出：吊销登录态 + 下发 Max-Age=0 清除 Cookie；未登录调用无害（幂等） */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(name = COOKIE_NAME, required = false) String token) {
        if (token != null && !token.isBlank()) {
            loginState.revoke(token);
        }
        ResponseCookie clear = cookieSpec("", Duration.ZERO, false);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .body(Map.of("code", 0, "message", "ok"));
    }

    /** 同 IP 连败登记：锁定中不累加；锁过期重新计数；达到阈值进入锁定并清零计数（下一轮窗口） */
    private void registerFailure(String ip, long now) {
        failuresByIp.compute(ip, (k, state) -> {
            if (state != null && state.lockedUntil() > now) {
                return state;
            }
            if (state != null && state.lockedUntil() > 0) {
                state = null; // 锁已过期，重新计数
            }
            int count = (state == null ? 0 : state.count()) + 1;
            if (count >= maxFailures) {
                return new FailState(0, now + lockSeconds * 1000, now);
            }
            return new FailState(count, 0, now);
        });
    }

    /**
     * 清理长时间没有新失败的 IP 条目（每小时，间隔可配）。
     *
     * <p>为什么需要：失败计数只在登录成功时 {@code remove}，被扫描器刷过的 IP 会永久留一条，
     * 公网暴露下 map 只增不减。这里按「最后一次失败时刻」清理，锁定中的条目只要还在窗口内就会被保留。
     */
    @Scheduled(fixedDelayString = "${app.security.failure-clean-interval-ms:3600000}")
    public void purgeStaleFailures() {
        int removed = purgeStaleFailures(System.currentTimeMillis() - FAILURE_RETENTION_MS);
        if (removed > 0) {
            log.info("[auth] 清理过期登录失败计数 {} 条，剩余 {} 条", removed, failuresByIp.size());
        }
    }

    /** 包可见：测试直接传 cutoff，不用等真实时间；返回清除条数 */
    int purgeStaleFailures(long cutoffMillis) {
        int before = failuresByIp.size();
        failuresByIp.entrySet().removeIf(entry -> entry.getValue().lastFailureAt() < cutoffMillis);
        return before - failuresByIp.size();
    }

    private boolean notEnabled() {
        return password == null || password.isBlank();
    }

    /** 客户端 IP：反代（nginx）场景取 X-Forwarded-For 首个，否则取直连地址 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Set-Cookie 统一口径：HttpOnly + SameSite=Lax + Path=/；Secure 仅 https 直连时附加 */
    private static ResponseCookie cookieSpec(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .secure(secure)
                .build();
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
