package com.dark.javaHarness.service;

/**
 * 会话上下文快照中的一条消息（读侧视图）。
 *
 * <p>与 {@link org.springframework.ai.chat.messages.Message} 的区别：额外携带快照条目的
 * 稳定 id。增量消费方（画像提炼）需要「上次处理到哪一条」的游标，而 Message 只有角色与正文，
 * 无法在裁剪后重新定位。
 *
 * @param id      快照条目 id（写回时生成；存量条目可能为 null，下次写回会补齐）
 * @param role    user / assistant
 * @param content 正文
 * @param ts      真实时刻（epoch 毫秒；旧快照为 null）
 */
public record SessionSnapshotItem(String id, String role, String content, Long ts) {
}
