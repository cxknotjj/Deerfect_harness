package com.dark.javaHarness.prompt;

/**
 * 活跃目标段提供者（扩展点）：把「当前会话声明的跨轮长期目标」注入 system prompt。
 *
 * <p>与 {@link UserProfileSectionProvider} 同构：实现并注入 {@link PromptAssembler} 即可，
 * 不改组装管线。区别是画像按 agent 过滤，本段按会话过滤——目标属于某个会话，不属于角色。
 *
 * <p>返回 null / 空白表示该会话没有活跃目标，段落整体跳过（不留空标题）。
 */
public interface ActiveGoalSectionProvider {

    /** 返回该会话的活跃目标段文本；无目标返回 null */
    String provide(String sessionId);
}
