package com.dark.javaHarness.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 鉴权装配：注册 {@link ApiTokenFilter} 到全部请求（豁免路径见过滤器内 shouldNotFilter）。
 *
 * <p>token 取自 {@code app.security.api-token}（默认空 = 不启用，过滤器直通）；
 * 公网部署时配置非空值即得防护。用 FilterRegistrationBean 显式注册保证顺序与可控性。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(
            @Value("${app.security.api-token:}") String apiToken) {
        FilterRegistrationBean<ApiTokenFilter> registration =
                new FilterRegistrationBean<>(new ApiTokenFilter(apiToken));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
