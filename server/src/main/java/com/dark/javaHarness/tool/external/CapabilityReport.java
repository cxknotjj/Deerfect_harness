package com.dark.javaHarness.tool.external;

import com.dark.javaHarness.music.MusicTools;
import com.dark.javaHarness.prompt.SkillRepository;
import com.dark.javaHarness.tool.builtin.SandboxToolProvider;
import com.dark.javaHarness.tool.builtin.WebTools;
import com.dark.javaHarness.tool.builtin.history.RecallHistoryTools;
import com.dark.javaHarness.tool.builtin.memory.MemoryTools;
import com.dark.javaHarness.tool.builtin.tts.TextToSpeechTools;
import com.dark.javaHarness.tool.mcp.McpToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 能力清单：把「谁提供、放在哪、现在能不能用」摊平成一张表。
 *
 * <p>存在的理由是排查成本——工具名散落在 {@code agent.tools}、注解、MCP 配置、外部 yaml 四处，
 * 出问题时只能靠猜。这里按「来源」聚合，每条都带定位符（类名/文件路径/URL），
 * 让「这个工具从哪来、删哪个文件能去掉它」有一个确定答案。
 *
 * <p>只读、不触发模型调用、不建沙箱；沙箱未启动时对应条目显示 unavailable 而不是报错。
 */
@Component
public class CapabilityReport {

    /**
     * @param pack     配置里的 token（agent.tools 引用的名字）
     * @param source   core / sandbox / mcp / http / skill
     * @param location 定位符：源文件、模块坐标、URL 或目录
     * @param state    ready / unavailable / disabled / missing-config
     */
    public record Entry(String pack, String source, String location, List<String> tools, String state) {
    }

    public record Report(int totalTools, List<Entry> entries, List<String> problems) {
    }

    private final HttpToolLoader httpTools;
    private final McpToolProvider mcp;
    private final SandboxToolProvider sandbox;
    private final SkillRepository skills;
    private final List<CorePack> core;

    private record CorePack(String token, Object bean) {
    }

    public CapabilityReport(HttpToolLoader httpTools, McpToolProvider mcp, SandboxToolProvider sandbox,
                            SkillRepository skills, WebTools webTools, TextToSpeechTools textToSpeechTools,
                            MusicTools musicTools, RecallHistoryTools recallHistoryTools,
                            MemoryTools memoryTools) {
        this.httpTools = httpTools;
        this.mcp = mcp;
        this.sandbox = sandbox;
        this.skills = skills;
        this.core = List.of(
                new CorePack("web", webTools),
                new CorePack("history", recallHistoryTools),
                new CorePack("memory", memoryTools),
                new CorePack("text_to_speech", textToSpeechTools),
                new CorePack("play_music", musicTools));
    }

    public Report report() {
        List<Entry> entries = new ArrayList<>();
        entries.addAll(coreEntries());
        entries.addAll(sandboxEntries());
        entries.addAll(httpEntries());
        entries.addAll(mcpEntries());
        entries.addAll(skillEntries());
        entries.sort(Comparator.comparing(Entry::source).thenComparing(Entry::pack));

        Set<String> names = new LinkedHashSet<>();
        entries.forEach(entry -> names.addAll(entry.tools()));
        return new Report(names.size(), entries, httpTools.problems());
    }

    private List<Entry> coreEntries() {
        List<Entry> entries = new ArrayList<>();
        for (CorePack pack : core) {
            List<String> tools = names(ToolCallbacks.from(pack.bean()));
            String state = tools.isEmpty() ? "disabled" : "ready";
            if (pack.bean() instanceof MusicTools music && !music.enabled()) {
                state = "disabled";
            }
            entries.add(new Entry(pack.token(), "core", locate(pack.bean().getClass()), tools, state));
        }
        return entries;
    }

    private List<Entry> sandboxEntries() {
        return List.of(
                sandboxEntry("sandbox.base", sandbox.baseTools(), "Shell 执行"),
                sandboxEntry("sandbox.read", sandbox.readOnlyFileTools(), "只读文件 + send_file"),
                sandboxEntry("sandbox.write", sandbox.writeTools(), "写文件"),
                sandboxEntry("sandbox.browser", sandbox.browserTools(), "浏览器"));
    }

    private Entry sandboxEntry(String pack, List<ToolCallback> callbacks, String hint) {
        List<String> tools = names(callbacks.toArray(new ToolCallback[0]));
        String state = tools.isEmpty() ? "unavailable（沙箱未启动）" : "ready";
        return new Entry(pack, "sandbox", "agentscope-runtime-sandbox-core｜" + hint, tools, state);
    }

    private List<Entry> httpEntries() {
        List<Entry> entries = new ArrayList<>();
        for (HttpToolManifest manifest : httpTools.manifests()) {
            String state = manifest.configured() ? "ready" : "missing-config（缺 " + manifest.requiredEnvHint() + "）";
            entries.add(new Entry(manifest.pack(), "http",
                    manifest.file().toString().replace('\\', '/'), List.of(manifest.tool().name()), state));
        }
        return entries;
    }

    private List<Entry> mcpEntries() {
        List<Entry> entries = new ArrayList<>();
        for (McpToolProvider.McpServerStatus status : mcp.statuses()) {
            entries.add(new Entry(status.name(), "mcp:" + status.transport(),
                    status.transport(), List.of(),
                    status.state() + "（" + status.tools() + " 个工具）"));
        }
        return entries;
    }

    private List<Entry> skillEntries() {
        List<Entry> entries = new ArrayList<>();
        for (SkillRepository.SkillInfo skill : skills.inspect()) {
            // agents 未声明时是 null（全部可见），不能直接 isEmpty
            boolean allAgents = skill.agents() == null || skill.agents().isEmpty();
            String state = skill.error() == null
                    ? (allAgents ? "ready（全部 agent）" : "ready（" + skill.agents() + "）")
                    : "error：" + skill.error();
            entries.add(new Entry("skill:" + skill.name(), "skill",
                    skill.entryFile() == null ? "" : skill.entryFile().replace('\\', '/'),
                    List.of(), state));
        }
        return entries;
    }

    private static List<String> names(ToolCallback[] callbacks) {
        List<String> names = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    /** 开发期解析到源文件；打包后只有类名（jar 里没有源码，不编造路径） */
    private static String locate(Class<?> type) {
        String relative = type.getName().replace('.', '/') + ".java";
        for (String prefix : List.of("server/src/main/java/", "src/main/java/")) {
            Path candidate = Path.of(prefix + relative);
            if (Files.exists(candidate)) {
                return candidate.toString().replace('\\', '/');
            }
        }
        return type.getSimpleName();
    }
}
