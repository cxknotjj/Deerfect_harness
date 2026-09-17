package com.dark.javaHarness.prompt;

/**
 * 用户偏好画像段内容提供者（扩展点）：为指定 agent 追加【用户偏好画像】段文本。
 *
 * <p>与 {@link SkillSectionProvider} 同构的扩展模式：实现本接口并注入
 * {@link PromptAssembler} 即可，无需改动组装管线。注入侧按 {@code MemoryPolicy}
 * 口径（general/lead）判定——子任务/聚合角色不注入，防画像污染。
 */
public interface UserProfileSectionProvider {

    /** 返回该 agent 的画像段文本；返回 null 或空白表示无内容（空段跳过） */
    String provide(String agentName);
}
