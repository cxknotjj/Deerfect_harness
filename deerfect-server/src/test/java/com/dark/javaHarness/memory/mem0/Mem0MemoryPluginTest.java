package com.dark.javaHarness.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryScope;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * mem0 插件启停与配置校验：启用时缺配置启动失败且不泄露密钥；读写开关分别生效。
 * spec:001
 */
class Mem0MemoryPluginTest {

    @Test
    void missingApiKeyFailsAtStartupWithoutLeakingSecret() {
        Mem0Properties properties = validProperties();
        properties.setApiKey(" ");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Mem0MemoryPlugin(properties, mock(Mem0Client.class)));

        assertTrue(error.getMessage().contains("api-key"));
    }

    @Test
    void missingBaseUrlFailsAtStartup() {
        Mem0Properties properties = validProperties();
        properties.setBaseUrl(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Mem0MemoryPlugin(properties, mock(Mem0Client.class)));

        assertTrue(error.getMessage().contains("base-url"));
    }

    @Test
    void nonPositiveTopKFailsAtStartup() {
        Mem0Properties properties = validProperties();
        properties.setTopK(0);

        assertThrows(IllegalStateException.class,
                () -> new Mem0MemoryPlugin(properties, mock(Mem0Client.class)));
    }

    @Test
    void searchDisabledSkipsClient() {
        Mem0Properties properties = validProperties();
        properties.setSearchEnabled(false);
        Mem0Client client = mock(Mem0Client.class);
        Mem0MemoryPlugin plugin = new Mem0MemoryPlugin(properties, client);

        assertTrue(plugin.search(query()).isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void searchEnabledDelegates() {
        Mem0Properties properties = validProperties();
        Mem0Client client = mock(Mem0Client.class);
        when(client.search(query())).thenReturn(List.of(new MemoryHit("m1", "x", 1.0, java.util.Map.of())));
        Mem0MemoryPlugin plugin = new Mem0MemoryPlugin(properties, client);

        assertTrue(!plugin.search(query()).isEmpty());
    }

    @Test
    void writeDisabledSkipsClient() {
        Mem0Properties properties = validProperties();
        properties.setWriteEnabled(false);
        Mem0Client client = mock(Mem0Client.class);
        Mem0MemoryPlugin plugin = new Mem0MemoryPlugin(properties, client);

        plugin.remember(turn());

        verifyNoInteractions(client);
    }

    private static Mem0Properties validProperties() {
        Mem0Properties properties = new Mem0Properties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:8888");
        properties.setApiKey("m0sk_test");
        properties.setSearchEnabled(true);
        properties.setWriteEnabled(true);
        properties.setTopK(5);
        properties.setMaxChars(1200);
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(3000);
        return properties;
    }

    private static MemoryQuery query() {
        return new MemoryQuery("我叫什么", new MemoryScope("cli", "general", "s1"), 5);
    }

    private static MemoryTurn turn() {
        return new MemoryTurn(new MemoryScope("cli", "general", "s1"),
                "我叫小林", "记住了", "t1", "cli", System.currentTimeMillis());
    }
}
