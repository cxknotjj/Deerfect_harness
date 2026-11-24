package com.dark.javaHarness.memory.mem0;

import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import com.dark.javaHarness.plugin.PluginCapability;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * mem0 记忆插件：把 mem0 OSS REST 接入长期记忆扩展点。
 *
 * <p>只在 {@code app.memory.mem0.enabled=true} 时装配；启用时在构造期校验必要配置，
 * 非法配置直接让启动失败（避免运行期静默无记忆）。检索与写入开关分别由
 * {@code search-enabled} / {@code write-enabled} 控制。
 */
@Component
@ConditionalOnProperty(prefix = "app.memory.mem0", name = "enabled", havingValue = "true")
public class Mem0MemoryPlugin implements MemoryPlugin {

    private final Mem0Properties properties;
    private final Mem0Client client;

    public Mem0MemoryPlugin(Mem0Properties properties, Mem0Client client) {
        this.properties = properties;
        this.client = client;
        requireText(properties.getBaseUrl(), "app.memory.mem0.base-url");
        requireText(properties.getApiKey(), "app.memory.mem0.api-key");
        requirePositive(properties.getTopK(), "app.memory.mem0.top-k");
        requirePositive(properties.getMaxChars(), "app.memory.mem0.max-chars");
        requirePositive(properties.getConnectTimeoutMs(), "app.memory.mem0.connect-timeout-ms");
        requirePositive(properties.getReadTimeoutMs(), "app.memory.mem0.read-timeout-ms");
    }

    @Override
    public String name() {
        return "mem0";
    }

    @Override
    public PluginCapability capability() {
        return PluginCapability.MEMORY;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<MemoryHit> search(MemoryQuery query) {
        if (!properties.isSearchEnabled() || query.query() == null || query.query().isBlank()) {
            return List.of();
        }
        return client.search(query);
    }

    @Override
    public void remember(MemoryTurn turn) {
        if (!properties.isWriteEnabled()) {
            return;
        }
        client.add(turn);
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("mem0 插件已启用但缺少配置: " + key);
        }
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalStateException("mem0 插件配置必须为正数: " + key + "=" + value);
        }
    }
}
