package com.dark.javaHarness.tool.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.music.MusicLibrary;
import com.dark.javaHarness.music.MusicProperties;
import com.dark.javaHarness.music.MusicTools;
import com.dark.javaHarness.prompt.SkillRepository;
import com.dark.javaHarness.tool.builtin.SandboxToolProvider;
import com.dark.javaHarness.tool.builtin.WebTools;
import com.dark.javaHarness.tool.builtin.history.RecallHistoryTools;
import com.dark.javaHarness.tool.builtin.memory.MemoryTools;
import com.dark.javaHarness.tool.builtin.tts.EdgeTtsClient;
import com.dark.javaHarness.tool.builtin.tts.TextToSpeechTools;
import com.dark.javaHarness.tool.mcp.McpToolProvider;
import com.dark.javaHarness.voice.InMemoryVoiceArtifactStore;
import com.dark.javaHarness.voice.TtsProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

class CapabilityReportTest {

    @Test
    void report_aggregatesAllSources() {
        HttpToolLoader http = mock(HttpToolLoader.class);
        when(http.manifests()).thenReturn(List.of(manifest()));
        when(http.problems()).thenReturn(List.of());

        SandboxToolProvider sandbox = mock(SandboxToolProvider.class);
        ToolCallback shell = callback("run_shell_command");
        ToolCallback readFile = callback("fs_read_file");
        ToolCallback sendFile = callback("send_file");
        when(sandbox.baseTools()).thenReturn(List.of(shell));
        when(sandbox.readOnlyFileTools()).thenReturn(List.of(readFile, sendFile));
        when(sandbox.writeTools()).thenReturn(List.of());
        when(sandbox.browserTools()).thenReturn(List.of());

        McpToolProvider mcp = mock(McpToolProvider.class);
        when(mcp.statuses()).thenReturn(List.of(
                new McpToolProvider.McpServerStatus("tavily", "http", "connected", 2)));

        SkillRepository skills = mock(SkillRepository.class);
        // agents 为 null 是「全部 agent 可见」的合法形态，曾经在这里 NPE
        when(skills.inspect()).thenReturn(List.of(
                new SkillRepository.SkillInfo("wms", "查询 WMS", null, "skills/wms/SKILL.md", 10, null)));

        CapabilityReport.Report result = new CapabilityReport(http, mcp, sandbox, skills,
                new WebTools(), tts(), music(), recall(), memory()).report();

        assertTrue(result.entries().stream().anyMatch(e -> e.pack().equals("wms.query")
                && e.tools().equals(List.of("wms_query"))), "外部 HTTP 工具应在清单里");
        assertTrue(result.entries().stream().anyMatch(e -> e.pack().equals("skill:wms")
                && e.state().contains("全部 agent")), "agents=null 不应报错");
        assertTrue(result.entries().stream().anyMatch(e -> e.pack().equals("sandbox.read")
                && e.tools().contains("send_file")), "沙箱组按组聚合");
        assertTrue(result.entries().stream().anyMatch(e -> e.source().startsWith("mcp:")
                && e.state().contains("2 个工具")), "MCP 服务器带工具数");
        assertTrue(result.entries().stream().allMatch(e -> !e.location().contains("\\")),
                "路径统一用 /");
        assertEquals(0, result.problems().size());
        assertTrue(result.totalTools() >= 3);
    }

    @Test
    void report_exposesLoaderProblems() {
        HttpToolLoader http = mock(HttpToolLoader.class);
        when(http.manifests()).thenReturn(List.of());
        when(http.problems()).thenReturn(List.of("wms.yaml：缺少 pack"));

        CapabilityReport.Report result = new CapabilityReport(http, mock(McpToolProvider.class),
                mock(SandboxToolProvider.class), mock(SkillRepository.class), new WebTools(), tts(), music(),
                recall(), memory()).report();

        assertEquals(List.of("wms.yaml：缺少 pack"), result.problems());
    }

    private static HttpToolManifest manifest() {
        return new HttpToolManifest("wms.query", Path.of("tools/http/wms.yaml"),
                "http://wms", "${WMS_API_BASE:}", new HttpToolManifest.Auth("X-Access-Token", "WMS_API_TOKEN"),
                15000, new HttpToolManifest.ResponseShape("jeecg", List.of(), 6000),
                new HttpToolManifest.ToolSpec("wms_query", "查询", "resource", "params"),
                Map.of("stock", new HttpToolManifest.Resource("/inventory", List.of(), List.of(), Map.of(), "")));
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name).description("").inputSchema("{}").build());
        return callback;
    }

    private static TextToSpeechTools tts() {
        TtsProperties props = new TtsProperties();
        props.setEnabled(false);
        return new TextToSpeechTools(props, mock(EdgeTtsClient.class), new InMemoryVoiceArtifactStore(props));
    }

    private static MusicTools music() {
        MusicProperties props = new MusicProperties();
        props.setEnabled(false);
        return new MusicTools(props, new MusicLibrary(props), new InMemoryVoiceArtifactStore(new TtsProperties()));
    }

    private static RecallHistoryTools recall() {
        return new RecallHistoryTools(mock(com.dark.javaHarness.service.impl.SessionHistoryArchiveService.class));
    }

    private static MemoryTools memory() {
        return new MemoryTools(mock(com.dark.javaHarness.service.MemoryService.class));
    }
}
