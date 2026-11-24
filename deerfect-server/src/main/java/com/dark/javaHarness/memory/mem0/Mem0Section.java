package com.dark.javaHarness.memory.mem0;

import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryScope;
import com.dark.javaHarness.plugin.PluginCapability;
import com.dark.javaHarness.plugin.PluginRegistry;
import com.dark.javaHarness.prompt.PromptSection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 长期记忆段（order 145，位于显式记忆 140 与画像 150 之间）：
 * 以当前用户消息为 query 检索长期记忆，命中后渲染为 system prompt 的一段。
 *
 * <p>空召回、未配置插件、会话无 creator 时返回空段（组装自动跳过）；
 * 冲突优先级写进段文案：显式记忆 &gt; 长期记忆 &gt; 画像。
 */
@Component
@ConditionalOnProperty(prefix = "app.memory.mem0", name = "enabled", havingValue = "true")
public class Mem0Section implements PromptSection {

    private static final String TITLE = "【长期记忆】";

    private static final String FOOTER =
            "以上是从历史对话中自动提取的长期记忆，仅作背景参考；"
                    + "与用户明确记住的事冲突时，以显式记忆为准。";

    private final Mem0Properties properties;
    private final PluginRegistry pluginRegistry;
    private final SessionService sessionService;

    public Mem0Section(Mem0Properties properties, PluginRegistry pluginRegistry, SessionService sessionService) {
        this.properties = properties;
        this.pluginRegistry = pluginRegistry;
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return "长期记忆";
    }

    @Override
    public int order() {
        return 145;
    }

    @Override
    public String render(Context context) {
        if (context.userMessage() == null || context.userMessage().isBlank()
                || context.sessionId() == null || context.sessionId().isBlank()) {
            return null;
        }
        MemoryPlugin plugin = pluginRegistry.active(PluginCapability.MEMORY, MemoryPlugin.class).orElse(null);
        if (plugin == null) {
            return null;
        }
        SessionInfo session = sessionService.getSession(context.sessionId());
        if (session == null || session.creator() == null || session.creator().isBlank()) {
            return null;
        }
        MemoryScope scope = new MemoryScope(session.creator(), context.agentName(), context.sessionId());
        List<MemoryHit> hits = plugin.search(
                new MemoryQuery(context.userMessage(), scope, properties.getTopK()));
        if (hits.isEmpty()) {
            return null;
        }
        String block = renderBlock(hits);
        return block.isBlank() ? null : block;
    }

    /** 逐条拼装并按 max-chars 截断；超出时注明剩余条数，避免无声丢记忆 */
    private String renderBlock(List<MemoryHit> hits) {
        StringBuilder sb = new StringBuilder(TITLE).append('\n');
        int used = TITLE.length() + 1;
        int included = 0;
        for (MemoryHit hit : hits) {
            String line = "- " + hit.text() + "\n";
            if (used + line.length() > properties.getMaxChars()) {
                break;
            }
            sb.append(line);
            used += line.length();
            included++;
        }
        if (included == 0) {
            return "";
        }
        if (included < hits.size()) {
            sb.append("（另有 ").append(hits.size() - included).append(" 条长期记忆未展示）\n");
        }
        sb.append(FOOTER);
        return sb.toString();
    }
}
