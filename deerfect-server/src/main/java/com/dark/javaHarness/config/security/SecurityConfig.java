package com.dark.javaHarness.config.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 鉴权装配：注册 {@link ApiTokenFilter} 到全部请求（豁免路径见过滤器内 shouldNotFilter）。
 *
 * <p>双通道「配置即启用」，默认全空 = 不启用（本地单机零影响）：
 * <ul>
 *   <li>机器通道：{@code app.security.api-token} 非空 → 校验 X-API-Token 头</li>
 *   <li>登录通道：{@code app.security.password} 非空 → 浏览器口令登录换 HttpOnly Cookie，
 *       过滤器查 {@link LoginStateStore} 放行；null 传入即关闭该通道</li>
 * </ul>
 * 用 FilterRegistrationBean 显式注册保证顺序与可控性。
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * 鉴权配置自检。
     *
     * <p>默认（{@code require-configured=false}）保持历史行为：双通道全空 = 不启用，
     * 本地单机零影响；仅在启动日志打一条 warn，提示管理面当前是敞开的。
     * 生产部署可把 {@code app.security.require-configured} 置 true，让「忘记配口令」
     * 变成启动失败，而不是静默裸奔——是否默认拒绝需产品/运维拍板，这里只提供开关。
     */
    static void validateAuthConfig(boolean requireConfigured, String apiToken, String password) {
        boolean configured = (apiToken != null && !apiToken.isBlank())
                || (password != null && !password.isBlank());
        if (configured) {
            return;
        }
        if (requireConfigured) {
            throw new IllegalStateException(
                    "app.security.require-configured=true，但 api-token 与 password 均为空："
                            + "管理面 /api/** 将无鉴权，拒绝启动。请配置 app.security.api-token"
                            + "（机器通道）或 app.security.password（登录通道）");
        }
        log.warn("[api-auth] 未配置 api-token / password：/api/** 全部接口（含 /api/admin/**）"
                + "当前无鉴权。仅建议本机使用；对外部署请设置口令或开启 require-configured");
    }

    @Bean
    public LoginStateStore loginStateStore(
            @Value("${app.security.login-ttl-hours:168}") long ttlHours) {
        return new LoginStateStore(Duration.ofHours(ttlHours));
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(
            @Value("${app.security.api-token:}") String apiToken,
            @Value("${app.security.password:}") String password,
            @Value("${app.security.require-configured:false}") boolean requireConfigured,
            LoginStateStore loginStateStore) {
        validateAuthConfig(requireConfigured, apiToken, password);
        boolean loginEnabled = password != null && !password.isBlank();
        ApiTokenFilter filter = new ApiTokenFilter(apiToken, loginEnabled ? loginStateStore : null);
        FilterRegistrationBean<ApiTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
