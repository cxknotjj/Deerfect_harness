package com.dark.javaHarness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 插件注册表行为：启用过滤、按扩展点取实现、同扩展点多实现启动失败。
 * spec:001
 */
class PluginRegistryTest {

    @Test
    void disabledPluginIsNotActive() {
        PluginRegistry registry = new PluginRegistry(List.of(new DummyPlugin(false)));

        assertTrue(registry.active(PluginCapability.MEMORY, MemoryPlugin.class).isEmpty());
    }

    @Test
    void enabledMemoryPluginIsActive() {
        PluginRegistry registry = new PluginRegistry(List.of(new DummyPlugin(true)));

        assertTrue(registry.active(PluginCapability.MEMORY, MemoryPlugin.class).isPresent());
    }

    @Test
    void duplicateEnabledCapabilityFailsAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PluginRegistry(List.of(new DummyPlugin(true), new DummyPlugin(true))));

        assertTrue(error.getMessage().contains("多个启用插件"));
    }

    @Test
    void nonMemoryImplementationDoesNotLeakIntoTypedLookup() {
        Plugin other = new Plugin() {
            @Override
            public String name() {
                return "other";
            }

            @Override
            public PluginCapability capability() {
                return PluginCapability.MEMORY;
            }

            @Override
            public boolean enabled() {
                return true;
            }
        };

        PluginRegistry registry = new PluginRegistry(List.of(other));

        assertTrue(registry.active(PluginCapability.MEMORY, MemoryPlugin.class).isEmpty());
    }

    /** 测试替身：只实现记忆扩展点，不触碰任何外部服务 */
    private static final class DummyPlugin implements MemoryPlugin {

        private final boolean enabled;

        private DummyPlugin(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String name() {
            return "dummy";
        }

        @Override
        public PluginCapability capability() {
            return PluginCapability.MEMORY;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<MemoryHit> search(MemoryQuery query) {
            return List.of();
        }

        @Override
        public void remember(MemoryTurn turn) {
            // no-op
        }
    }
}
