package com.dark.javaHarness.contract.api;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 会话长期记忆摘要端口（P2-3）：上下文预算裁剪丢弃历史轮次时，把被丢弃内容压缩为
 * 一段滚动摘要，下一轮组装作为 system 段注入。
 *
 * <p>语义约束：
 * <ul>
 *   <li>{@link #summaryOf} 为同步只读，必须轻量（禁止 LLM 调用与阻塞 IO 重试）；失败返回 null。</li>
 *   <li>{@link #requestSummary} 为异步 best-effort：调用方（上下文组装）只投递不等待，
 *       实现内部串行合并、失败仅告警；任何异常不得传回主链路。</li>
 * </ul>
 */
public interface SessionSummaryPort {

    /** 读取会话当前滚动摘要（无摘要/读取失败返回 null） */
    String summaryOf(String sessionId);

    /**
     * 请求把本次被裁剪丢弃的消息并入会话摘要（异步、幂等、失败不抛）。
     *
     * @param droppedMessages 本次裁剪丢弃的消息（应含快照消息 ID metadata，用于增量游标）
     */
    void requestSummary(String sessionId, List<Message> droppedMessages);

    /**
     * 删除会话摘要（会话删除时级联清理，避免 summary 表留下找不到会话的孤儿行）。
     * 幂等、失败不抛——清理是旁路，不能阻断删除会话本身。
     */
    void deleteBySessionId(String sessionId);
}
