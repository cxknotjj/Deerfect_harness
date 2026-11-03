package com.dark.javaHarness.service;

import org.springframework.ai.chat.messages.Message;

/**
 * 会话快照写入条目：一条待追加的消息 + 其真实时刻（epoch 毫秒）。
 *
 * <p>批量写回（{@link SessionService#saveContextBatch}）需要保留逐条时间戳——
 * 一轮对话里 user 是发送时刻、assistant 是完成时刻，两者不同；用同一个 ts 会让
 * 历史回显的时间失真，因此时间戳随条目携带而不是由方法统一注入。
 *
 * @param message 待追加的消息（user/assistant）
 * @param ts      消息真实时刻（epoch 毫秒）；null 表示按写入时刻处理
 */
public record SessionMessageEntry(Message message, Long ts) {
}
