package com.dark.javaHarness.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCP 配置解析：日志 URL 脱敏（query 值打码、参数名保留），解析结果仍保留原始 URL。
 */
class McpConfigParserTest {

    @Test
    void redactUrlMasksQueryValues() {
        assertEquals("https://mcp.tavily.com/mcp/?tavilyApiKey=***",
                McpConfigParser.redactUrl("https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-dev-secret"));
    }

    @Test
    void redactUrlMasksAllParamsAndKeepsFragment() {
        assertEquals("https://host/path?a=***&b=***&flag#sec",
                McpConfigParser.redactUrl("https://host/path?a=1&b=2&flag#sec"));
    }

    @Test
    void redactUrlLeavesUrlsWithoutQueryUntouched() {
        assertEquals("http://localhost:8090/mcp", McpConfigParser.redactUrl("http://localhost:8090/mcp"));
        assertEquals("", McpConfigParser.redactUrl(""));
        assertNull(McpConfigParser.redactUrl(null));
    }

    @Test
    void loadServerSpecsKeepsRawUrl(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("mcp-config.json");
        Files.writeString(cfg, """
                {"mcpServers":{"tavily":{"url":"https://mcp.tavily.com/mcp/?tavilyApiKey=secret"}}}
                """);
        List<McpToolProvider.ServerSpec> specs = McpConfigParser.loadServerSpecs(cfg);
        assertEquals(1, specs.size());
        assertEquals("https://mcp.tavily.com/mcp/?tavilyApiKey=secret", specs.get(0).url());
    }
}
