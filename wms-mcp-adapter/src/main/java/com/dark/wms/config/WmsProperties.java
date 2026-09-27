package com.dark.wms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WMS 适配服务配置项：全部经环境变量注入，代码与配置文件零硬编码凭据。
 *
 * <ul>
 *   <li>{@code WMS_API_BASE}：WMS 服务根地址（必填，留空则工具调用返回引导性错误）</li>
 *   <li>{@code WMS_API_TOKEN}：WMS 只读账号 token（必填）</li>
 *   <li>{@code WMS_TOKEN_HEADER}：WMS 鉴权头名称，默认 {@code X-Access-Token}（jeecg 惯例，
 *       OpenAPI 未声明鉴权方案，以可配置 header 应对）</li>
 *   <li>{@code WMS_MCP_TOKEN}：调用方（harness）鉴权 token；留空 = 不启用调用方鉴权（本地调试）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms")
public class WmsProperties {

    /** WMS 服务根地址（环境变量 WMS_API_BASE，空默认） */
    private String apiBase = "";

    /** WMS 只读账号 token（环境变量 WMS_API_TOKEN，空默认） */
    private String apiToken = "";

    /** WMS 鉴权头名称（环境变量 WMS_TOKEN_HEADER，默认 jeecg 惯例） */
    private String tokenHeader = "X-Access-Token";

    /** 调用方鉴权 token（环境变量 WMS_MCP_TOKEN，空 = 不启用） */
    private String mcpToken = "";
}
