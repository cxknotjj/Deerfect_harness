package com.dark.javaHarness.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * API 鉴权过滤器单测：双通道（X-API-Token 机器头 / harness_login 登录 Cookie）、
 * 禁用直通、启用校验、放行范围（仅 /api/** 且豁免 /api/auth/**）、401 响应体。
 */
class ApiTokenFilterTest {

    private static final String TOKEN = "s3cret-token";

    private MockHttpServletResponse run(ApiTokenFilter filter, String method, String path, String tokenHeader,
            String loginCookie) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (tokenHeader != null) {
            request.addHeader(ApiTokenFilter.HEADER, tokenHeader);
        }
        if (loginCookie != null) {
            request.setCookies(new jakarta.servlet.http.Cookie(ApiTokenFilter.COOKIE, loginCookie));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // chain 到达 = 放行（请求对象出现在链上）
        if (response.getStatus() != 401 && chain.getRequest() != null) {
            assertTrue(chain.getRequest() == request, "放行时应继续过滤器链");
        }
        return response;
    }

    /** 双通道都未配置（本地单机默认形态）：任何 /api 请求直通 */
    @Test
    void disabled_bothChannelsOff_passesAll() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter("", null);
        assertEquals(200, run(filter, "GET", "/api/harness/agents", null, null).getStatus());
        assertEquals(200, run(filter, "GET", "/api/chat", "wrong", "any-cookie").getStatus());
    }

    /** 机器通道：正确 token 放行 */
    @Test
    void headerChannel_correctToken_passes() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter(TOKEN, null);
        assertEquals(200, run(filter, "POST", "/api/chat", TOKEN, null).getStatus());
    }

    /** 机器通道启用后：缺头或错 token 均 401，响应体含 unauthorized */
    @Test
    void headerChannel_missingOrWrongToken_returns401() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter(TOKEN, null);
        MockHttpServletResponse missing = run(filter, "GET", "/api/harness/agents", null, null);
        assertEquals(401, missing.getStatus());
        assertTrue(missing.getContentAsString().contains("unauthorized"));

        MockHttpServletResponse wrong = run(filter, "GET", "/api/harness/agents", "bad-token", null);
        assertEquals(401, wrong.getStatus());
    }

    /** 登录通道：有效登录 Cookie 放行（无需 header） */
    @Test
    void cookieChannel_validLoginCookie_passes() throws Exception {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        String login = store.create();
        ApiTokenFilter filter = new ApiTokenFilter("", store);
        assertEquals(200, run(filter, "GET", "/api/harness/sessions", null, login).getStatus());
    }

    /** 登录通道：无 Cookie / 失效 Cookie 均 401 */
    @Test
    void cookieChannel_missingOrInvalidCookie_returns401() throws Exception {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        ApiTokenFilter filter = new ApiTokenFilter("", store);
        assertEquals(401, run(filter, "GET", "/api/harness/sessions", null, null).getStatus());
        assertEquals(401, run(filter, "GET", "/api/harness/sessions", null, "stale-token").getStatus());
    }

    /** 登录通道：过期 Cookie 401 */
    @Test
    void cookieChannel_expiredCookie_returns401() throws Exception {
        LoginStateStore store = new LoginStateStore(Duration.ofMillis(80));
        String login = store.create();
        Thread.sleep(120);
        ApiTokenFilter filter = new ApiTokenFilter("", store);
        assertEquals(401, run(filter, "GET", "/api/harness/sessions", null, login).getStatus());
    }

    /** 双通道并存：任一通过即放行 */
    @Test
    void bothChannels_eitherPasses() throws Exception {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        String login = store.create();
        ApiTokenFilter filter = new ApiTokenFilter(TOKEN, store);
        assertEquals(200, run(filter, "GET", "/api/harness/agents", TOKEN, null).getStatus());
        assertEquals(200, run(filter, "GET", "/api/harness/agents", null, login).getStatus());
        assertEquals(401, run(filter, "GET", "/api/harness/agents", "bad", "bad").getStatus());
    }

    /** 放行范围：仅拦 /api/**；/api/auth/**（登录/登出/探测）、/onebot/**、/error、静态资源一律直行 */
    @Test
    void scope_onlyApiPathsFiltered() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter(TOKEN, null);
        assertEquals(200, run(filter, "POST", "/api/auth/login", null, null).getStatus());
        assertEquals(200, run(filter, "GET", "/api/auth/me", null, null).getStatus());
        assertEquals(200, run(filter, "POST", "/api/auth/logout", null, null).getStatus());
        assertEquals(200, run(filter, "POST", "/onebot/event", null, null).getStatus());
        assertEquals(200, run(filter, "GET", "/error", null, null).getStatus());
        // 静态资源与根路径不拦（登录页本身要能加载）
        assertEquals(200, run(filter, "GET", "/", null, null).getStatus());
        assertEquals(200, run(filter, "GET", "/index.html", null, null).getStatus());
        assertEquals(200, run(filter, "GET", "/assets/app.js", null, null).getStatus());
        // 非 /api/ 前缀的相似路径不在拦截范围（非真实端点，放行后由 404 兜底）
        assertEquals(200, run(filter, "GET", "/apix/agents", null, null).getStatus());
    }
}
