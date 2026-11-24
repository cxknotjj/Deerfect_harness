package com.dark.javaHarness.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.plugin.PluginCapability;
import com.dark.javaHarness.plugin.PluginRegistry;
import com.dark.javaHarness.prompt.PromptSection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 长期记忆段：命中注入、空召回跳过、字符上限截断、插件缺失降级。
 * spec:001
 */
@ExtendWith(MockitoExtension.class)
class Mem0SectionTest {

    @Mock
    private PluginRegistry pluginRegistry;
    @Mock
    private MemoryPlugin plugin;
    @Mock
    private SessionService sessionService;

    private Mem0Properties properties;
    private Mem0Section section;

    @BeforeEach
    void setUp() {
        properties = new Mem0Properties();
        properties.setTopK(5);
        properties.setMaxChars(1200);
        section = new Mem0Section(properties, pluginRegistry, sessionService);
    }

    @Test
    void rendersHitsWithPriorityNotice() {
        stubActivePlugin();
        when(plugin.search(any())).thenReturn(List.of(
                new MemoryHit("m1", "用户叫小林", 0.9, Map.of())));
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", "cli", "q", 1, null));

        String text = section.render(context("我叫什么"));

        assertTrue(text.contains("【长期记忆】"));
        assertTrue(text.contains("用户叫小林"));
        assertTrue(text.contains("以显式记忆为准"));
    }

    @Test
    void emptyHitsProduceEmptySection() {
        stubActivePlugin();
        when(plugin.search(any())).thenReturn(List.of());
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", "cli", "q", 1, null));

        assertNull(section.render(context("我叫什么")));
    }

    @Test
    void blankUserMessageSkipsSearch() {
        assertNull(section.render(context(" ")));
    }

    @Test
    void missingPluginProducesEmptySection() {
        when(pluginRegistry.active(PluginCapability.MEMORY, MemoryPlugin.class))
                .thenReturn(Optional.empty());

        assertNull(section.render(context("我叫什么")));
    }

    @Test
    void missingCreatorProducesEmptySection() {
        stubActivePlugin();
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", " ", "q", 1, null));

        assertNull(section.render(context("我叫什么")));
    }

    @Test
    void truncatesByMaxChars() {
        stubActivePlugin();
        properties.setMaxChars(20);
        when(plugin.search(any())).thenReturn(List.of(
                new MemoryHit("m1", "这是一条非常长的记忆内容需要被截断", 0.9, Map.of())));
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", "cli", "q", 1, null));

        assertNull(section.render(context("我叫什么")));
    }

    private void stubActivePlugin() {
        when(pluginRegistry.active(PluginCapability.MEMORY, MemoryPlugin.class))
                .thenReturn(Optional.of(plugin));
    }

    private static PromptSection.Context context(String userMessage) {
        return new PromptSection.Context("general", "role", List.of(), null, "s1", userMessage);
    }
}
