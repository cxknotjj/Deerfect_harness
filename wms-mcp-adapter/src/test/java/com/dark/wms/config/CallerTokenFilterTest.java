package com.dark.wms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用方鉴权过滤器单测：对齐 ApiTokenFilter 口径——配置即启用、
 * {@code X-API-Token} 头匹配放行、缺失/错误拒绝（401）、未配置直通（本地调试）。
 */
class CallerTokenFilterTest {

    private CallerTokenFilter filterWith(String mcpToken) {
        WmsProperties props = new WmsProperties();
        props.setMcpToken(mcpToken);
        return new CallerTokenFilter(props);
    }

    private MockHttpServletRequest postMcp() {
        return new MockHttpServletRequest("POST", "/mcp");
    }

    @Test
    void 未配置token_不带鉴权头也直通() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filterWith("").doFilter(postMcp(), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 未配置token_带任意头也直通() throws Exception {
        MockHttpServletRequest request = postMcp();
        request.addHeader(CallerTokenFilter.HEADER, "whatever");
        MockFilterChain chain = new MockFilterChain();
        filterWith(null).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 已配置token_缺失被拒401且不触达端点() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filterWith("secret").doFilter(postMcp(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 已配置token_错误被拒401() throws Exception {
        MockHttpServletRequest request = postMcp();
        request.addHeader(CallerTokenFilter.HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filterWith("secret").doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 已配置token_匹配放行() throws Exception {
        MockHttpServletRequest request = postMcp();
        request.addHeader(CallerTokenFilter.HEADER, "secret");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filterWith("secret").doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 错误页转发豁免() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        MockFilterChain chain = new MockFilterChain();
        filterWith("secret").doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
