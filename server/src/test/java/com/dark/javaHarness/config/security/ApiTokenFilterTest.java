package com.dark.javaHarness.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * API 鉴权过滤器单测：禁用直通、启用校验（对/错/缺 token）、豁免路径、401 响应体。
 */
class ApiTokenFilterTest {

    private static final String TOKEN = "s3cret-token";

    private MockHttpServletResponse run(ApiTokenFilter filter, String path, String tokenHeader)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (tokenHeader != null) {
            request.addHeader(ApiTokenFilter.HEADER, tokenHeader);
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

    /** 默认空配置：不启用鉴权，任何请求直通 */
    @Test
    void disabled_blankToken_passesAll() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter("");
        assertEquals(200, run(filter, "/api/harness/agents", null).getStatus());
        assertEquals(200, run(filter, "/api/chat", "wrong").getStatus());
    }

    /** 启用后：正确 token 放行 */
    @Test
    void enabled_correctToken_passes() throws Exception {
        assertEquals(200, run(new ApiTokenFilter(TOKEN), "/api/chat", TOKEN).getStatus());
    }

    /** 启用后：缺头或错 token 均 401，响应体含 unauthorized */
    @Test
    void enabled_missingOrWrongToken_returns401() throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter(TOKEN);
        MockHttpServletResponse missing = run(filter, "/api/harness/agents", null);
        assertEquals(401, missing.getStatus());
        assertTrue(missing.getContentAsString().contains("unauthorized"));

        MockHttpServletResponse wrong = run(filter, "/api/harness/agents", "bad-token");
        assertEquals(401, wrong.getStatus());
    }

    /** 启用后：/onebot/** 豁免（NapCat 上报自有 HMAC 验签），无 token 也放行 */
    @Test
    void enabled_onebotPath_exempt() throws Exception {
        assertEquals(200, run(new ApiTokenFilter(TOKEN), "/onebot/event", null).getStatus());
    }

    /** 启用后：/error 豁免（错误页转发不被过滤器拦截二次报错） */
    @Test
    void enabled_errorPath_exempt() throws Exception {
        assertEquals(200, run(new ApiTokenFilter(TOKEN), "/error", null).getStatus());
    }
}
