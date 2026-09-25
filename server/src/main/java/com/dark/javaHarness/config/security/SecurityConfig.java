package com.dark.javaHarness.config.security;

import java.time.Duration;
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

    @Bean
    public LoginStateStore loginStateStore(
            @Value("${app.security.login-ttl-hours:168}") long ttlHours) {
        return new LoginStateStore(Duration.ofHours(ttlHours));
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(
            @Value("${app.security.api-token:}") String apiToken,
            @Value("${app.security.password:}") String password,
            LoginStateStore loginStateStore) {
        boolean loginEnabled = password != null && !password.isBlank();
        ApiTokenFilter filter = new ApiTokenFilter(apiToken, loginEnabled ? loginStateStore : null);
        FilterRegistrationBean<ApiTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
