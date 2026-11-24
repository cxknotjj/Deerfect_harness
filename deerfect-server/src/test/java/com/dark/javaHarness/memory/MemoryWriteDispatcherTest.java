package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryScope;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import com.dark.javaHarness.plugin.PluginCapability;
import com.dark.javaHarness.plugin.PluginRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 写入调度：无插件空操作、同 turnId 去重、插件异常不冒泡。
 * spec:001
 */
class MemoryWriteDispatcherTest {

    @Test
    void inactiveWithoutPlugin() {
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.active(PluginCapability.MEMORY, MemoryPlugin.class)).thenReturn(Optional.empty());
        MemoryWriteDispatcher dispatcher = new MemoryWriteDispatcher(registry, 10, 10);

        assertFalse(dispatcher.active());
        assertDoesNotThrow(() -> dispatcher.submit(turn("t1")));
    }

    @Test
    void sameTurnIdIsWrittenOnce() {
        MemoryPlugin plugin = mock(MemoryPlugin.class);
        PluginRegistry registry = registryWith(plugin);
        MemoryWriteDispatcher dispatcher = new MemoryWriteDispatcher(registry, 10, 10);

        dispatcher.submit(turn("t1"));
        dispatcher.submit(turn("t1"));

        verify(plugin, timeout(2000).times(1)).remember(any(MemoryTurn.class));
    }

    @Test
    void pluginFailureDoesNotEscapeSubmit() {
        MemoryPlugin plugin = mock(MemoryPlugin.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(plugin).remember(any());
        PluginRegistry registry = registryWith(plugin);
        MemoryWriteDispatcher dispatcher = new MemoryWriteDispatcher(registry, 10, 10);

        assertDoesNotThrow(() -> dispatcher.submit(turn("t2")));
        verify(plugin, timeout(2000).times(1)).remember(any(MemoryTurn.class));
    }

    @Test
    void blankTurnIdIsIgnored() {
        MemoryPlugin plugin = mock(MemoryPlugin.class);
        MemoryWriteDispatcher dispatcher = new MemoryWriteDispatcher(registryWith(plugin), 10, 10);

        dispatcher.submit(turn(" "));

        verifyNoInteractions(plugin);
    }

    private static PluginRegistry registryWith(MemoryPlugin plugin) {
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.active(PluginCapability.MEMORY, MemoryPlugin.class)).thenReturn(Optional.of(plugin));
        return registry;
    }

    private static MemoryTurn turn(String turnId) {
        return new MemoryTurn(new MemoryScope("cli", "general", "s1"),
                "我叫小林", "记住了", turnId, "cli", System.currentTimeMillis());
    }
}
