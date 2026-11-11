package com.dark.javaHarness.controller;

import com.dark.javaHarness.tool.external.CapabilityReport;
import com.dark.javaHarness.tool.external.HttpToolLoader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力清单与外部工具重载。
 *
 * <p>重载只覆盖声明式 HTTP 工具（读文件即可重建）。MCP 的 client 生命周期绑在连接上，
 * 重建需要先关连接池，属于单独一步，不在这里假装能做到。
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityReport report;
    private final HttpToolLoader httpTools;

    public CapabilityController(CapabilityReport report, HttpToolLoader httpTools) {
        this.report = report;
        this.httpTools = httpTools;
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
        return result;
    }
}
