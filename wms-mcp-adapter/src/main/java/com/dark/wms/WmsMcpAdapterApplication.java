package com.dark.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WMS MCP 适配服务：独立 Spring Boot 应用（不进 server fat jar，进程外解耦部署）。
 *
 * <p>职责：把 WMS 仓储系统的只读 GET 端点包装为 10 个中文业务语义 MCP 工具
 * （MCP Streamable HTTP 端点 {@code /mcp}），供 harness 经 MCP client 接入，
 * 实现自然语言查库存/查单据。WMS 凭证与地址全部经环境变量注入（零硬编码）。
 */
@SpringBootApplication
public class WmsMcpAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsMcpAdapterApplication.class, args);
    }
}
