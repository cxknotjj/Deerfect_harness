package com.dark.javaHarness.controller;

import com.dark.javaHarness.tool.external.CapabilityReport;
import com.dark.javaHarness.tool.external.HttpToolLoader;
import com.dark.javaHarness.tool.mcp.McpToolProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力清单与外部工具重载。
 *
 * <p>重载覆盖两类外部来源：声明式 HTTP 清单（读文件即重建）与 MCP server 配置
 * （重新解析 mcp-config.json，旧连接宽限关闭）。core 与 sandbox 是编译进进程的，不参与。
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityReport report;
    private final HttpToolLoader httpTools;
    private final McpToolProvider mcpTools;

    public CapabilityController(CapabilityReport report, HttpToolLoader httpTools, McpToolProvider mcpTools) {
        this.report = report;
        this.httpTools = httpTools;
        this.mcpTools = mcpTools;
    }

    @GetMapping
    public CapabilityReport.Report list() {
        return report.report();
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("problems", httpTools.reload());
        result.put("tools", httpTools.manifests().size());
        result.put("directory", httpTools.directory().toAbsolutePath().normalize().toString());

        McpToolProvider.ReloadResult mcp = mcpTools.reload();
        Map<String, Object> mcpView = new LinkedHashMap<>();
        mcpView.put("servers", mcp.servers());
        mcpView.put("previousServers", mcp.previousServers());
        mcpView.put("names", mcp.names());
        result.put("mcp", mcpView);
        return result;
    }
}
