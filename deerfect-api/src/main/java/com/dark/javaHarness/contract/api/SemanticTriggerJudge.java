package com.dark.javaHarness.contract.api;

/**
 * 渠道事件语义层判断端口（Slow Path）：对程序层标记为「候选」的事件，
 * 判断是否值得 Agent 主动介入。实现由 core 提供，渠道只依赖本端口；
 * 无实现或判断失败时渠道按「不响应」处理（保持被动模式行为）。
 */
public interface SemanticTriggerJudge {

    /**
     * @param channel        渠道标识（如 qq）
     * @param conversationKey 会话键（渠道内定位，如 qq:group:gid:uid）
     * @param message        入站正文
     * @return true = 值得响应；false = 保持安静
     */
    boolean shouldRespond(String channel, String conversationKey, String message);
}
