package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 显式记忆注入段单测：角色过滤（general/lead 注入）、空记忆跳过、
 * 条目渲染（标签/日期）、字符上限截断与「优先于画像」的口径提示。
 */
@ExtendWith(MockitoExtension.class)
class ExplicitMemorySectionProviderTest {

    @Mock
    private MemoryService memoryService;

    private ExplicitMemorySectionProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new ExplicitMemorySectionProviderImpl(memoryService);
    }

    @Test
    void general_rendersSectionWithPriorityHint() {
        when(memoryService.activeForInjection()).thenReturn(List.of(
                memory(3L, "车牌", "沪A12345"),
                memory(4L, null, "每周一上午开周会")));

        String section = provider.provide("general");

        assertTrue(section.startsWith("【用户明确记住的事】"));
        assertTrue(section.contains("- [车牌] 沪A12345（2026-11-07 记）"));
        assertTrue(section.contains("- 每周一上午开周会"), "无 key 的条目不输出空方括号");
        assertTrue(section.contains("优先于画像推断"), "必须声明与画像冲突时的优先级");
        assertTrue(section.contains("用 forget 工具删除"));
    }

    @Test
    void lead_alsoInjected() {
        when(memoryService.activeForInjection()).thenReturn(List.of(memory(1L, null, "项目代号是凤凰")));
        assertTrue(provider.provide("lead").contains("项目代号是凤凰"));
    }

    @Test
    void expertRoles_notInjected() {
        assertNull(provider.provide("coder"));
        assertNull(provider.provide("researcher"));
        assertNull(provider.provide("aggregator"));
        verifyNoInteractions(memoryService);
    }

    @Test
    void emptyMemories_returnsNull() {
        when(memoryService.activeForInjection()).thenReturn(List.of());
        assertNull(provider.provide("general"), "无记忆时应跳过整段，不留空标题");
    }

    @Test
    void overCharLimit_truncatesAndReportsRemaining() {
        when(memoryService.activeForInjection()).thenReturn(List.of(
                memory(1L, null, "甲".repeat(700)),
                memory(2L, null, "乙".repeat(700)),
                memory(3L, null, "丙".repeat(700))));

        String section = provider.provide("general");

        assertTrue(section.contains("另有 1 条记忆未展示"), "超字符上限应截断并注明剩余条数");
        assertTrue(section.contains("甲"));
        assertFalse(section.contains("丙"), "第三条应被截掉");
    }

    @Test
    void singleHugeMemory_beyondLimit_skipsSection() {
        when(memoryService.activeForInjection()).thenReturn(List.of(memory(1L, null, "超".repeat(2000))));
        assertNull(provider.provide("general"), "一条都放不下时不留空标题");
    }

    private static Memory memory(long id, String key, String content) {
        return new Memory(id, key, content, "user", "12",
                LocalDateTime.of(2026, 11, 7, 9, 0), null, Memory.Status.ACTIVE);
    }
}
