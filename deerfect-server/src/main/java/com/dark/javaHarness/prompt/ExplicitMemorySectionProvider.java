package com.dark.javaHarness.prompt;

/**
 * 显式记忆段提供者（扩展点）：把用户明说「记住」的事实注入 system prompt。
 *
 * <p>与 {@link UserProfileSectionProvider} 同构，区别是语义：画像是模型从对话里推断的
 * 软偏好（下一次提炼可能整段改写），显式记忆是用户声明的事实（只有用户能删）。
 * 段落次序在画像之前，冲突时以显式记忆为准。
 *
 * <p>角色过滤由实现侧按 {@link MemoryPolicy} 判定（general/lead 注入，子任务/聚合不注入），
 * 返回 null 或空白表示无记忆，段落整体跳过。
 */
public interface ExplicitMemorySectionProvider {

    /** 返回该 agent 的显式记忆段文本；无记忆返回 null */
    String provide(String agentName);
}
