package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.prompt.UserProfileSectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UserProfileSectionProvider 画像注入单测：
 * - 画像非空且角色匹配（general/lead）→ 输出【用户偏好画像】段
 * - 画像为空/文件不存在 → 空段（不注入）
 * - 子任务/聚合角色（researcher/aggregator）→ 空段（MemoryPolicy 口径，防画像污染子任务）
 */
class UserProfileSectionProviderTest {

    private UserProfileService profileService;
    private UserProfileSectionProvider provider;

    @BeforeEach
    void setUp() {
        profileService = mock(UserProfileService.class);
        provider = new UserProfileSectionProviderImpl(profileService);
    }

    @Test
    void profilePresent_andGeneralRole_rendersSection() {
        when(profileService.currentProfile()).thenReturn("- 偏好简洁回复\n- 主力 Java");

        String section = provider.provide("general");

        assertTrue(section.contains("【用户偏好画像】"), "应含段标题");
        assertTrue(section.contains("- 偏好简洁回复"), "应含画像正文");
    }

    @Test
    void profilePresent_andLeadRole_rendersSection() {
        when(profileService.currentProfile()).thenReturn("- 偏好简洁回复");

        String section = provider.provide("lead");

        assertTrue(section.contains("【用户偏好画像】"), "lead 角色应注入画像");
    }

    @Test
    void profileBlank_returnsNull() {
        when(profileService.currentProfile()).thenReturn("");

        assertNull(provider.provide("general"), "空画像不注入（空段跳过）");
    }

    @Test
    void subtaskRole_returnsNull_evenWithProfile() {
        when(profileService.currentProfile()).thenReturn("- 偏好简洁回复");

        assertNull(provider.provide("researcher"), "子任务角色不注入画像");
        assertNull(provider.provide("aggregator"), "聚合角色不注入画像");
    }

    @Test
    void nullAgentName_returnsNull() {
        when(profileService.currentProfile()).thenReturn("- 偏好简洁回复");

        assertNull(provider.provide(null), "无角色上下文不注入");
        assertFalse(provider.provide(null) != null && provider.provide(null).isBlank(), "一致性自检");
    }
}
