package com.dark.javaHarness.memory;

import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.prompt.UserProfileSectionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 用户偏好画像段提供者：把全局单份画像（user-profile/user-profile.md）作为
 * system 的【用户偏好画像】段注入。
 *
 * <p>注入口径与 {@link MemoryPolicy} 会话记忆一致（general/lead）：画像对简单路径
 * 与编排拆解可见；子任务专家/聚合角色不注入——子任务上下文由 lead 在子任务描述中
 * 传递，画像全局信息无须也不应重复下发到每个专家。
 *
 * <p>画像与提取服务同开关（app.memory.profile-enabled），关闭时本组件不装配、段为空。
 */
@Component
@ConditionalOnProperty(name = "app.memory.profile-enabled", havingValue = "true")
public class UserProfileSectionProviderImpl implements UserProfileSectionProvider {

    private static final String SECTION_TITLE = "【用户偏好画像】";

    private final UserProfileService profileService;
    private final MemoryPolicy memoryPolicy = new MemoryPolicy();

    public UserProfileSectionProviderImpl(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @Override
    public String provide(String agentName) {
        if (!memoryPolicy.shouldInject(agentName)) {
            return null;
        }
        String profile = profileService.currentProfile();
        if (profile == null || profile.isBlank()) {
            return null;
        }
        return SECTION_TITLE + "\n" + profile;
    }
}
