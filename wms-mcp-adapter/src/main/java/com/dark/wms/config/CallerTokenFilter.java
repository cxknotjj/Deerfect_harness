package com.dark.wms.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 调用方鉴权过滤器：校验 MCP 请求携带的 {@code X-API-Token} 头（对齐 harness server
 * 模块 ApiTokenFilter 的「配置即启用」与常量时间比较口径）。
 *
 * <ul>
 *   <li>{@code wms.mcp-token}（环境变量 WMS_MCP_TOKEN）非空启用：请求须携带匹配的
 *       {@code X-API-Token} 头，{@link MessageDigest#isEqual} 常量时间比较防时序侧信道</li>
 *   <li>留空（默认）不启用：本地调试零影响，公网部署按需配置即得防护</li>
 * </ul>
 * 本服务仅暴露 MCP 端点，故除错误页转发（{@code /error}）外全路径纳入校验范围。
 */
@Component
public class CallerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallerTokenFilter.class);

    /** 调用方鉴权头名（harness 侧 mcp-config.json headers 配同名 header） */
    public static final String HEADER = "X-API-Token";

    /** 期望 token 的 UTF-8 字节；空数组 = 未配置，不启用鉴权 */
    private final byte[] expectedToken;

    public CallerTokenFilter(WmsProperties props) {
        String token = props.getMcpToken();
        this.expectedToken = token == null || token.isBlank()
                ? new byte[0]
                : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 豁免错误页转发；MCP 端点（/mcp）全部纳入鉴权
        return "/error".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 未配置 token：不启用鉴权，直通（本地调试默认形态）
        if (expectedToken.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("[mcp-auth] 鉴权失败：{} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\"}");
    }
}
