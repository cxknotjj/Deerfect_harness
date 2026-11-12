package com.dark.javaHarness.view;

import com.dark.javaHarness.contract.model.ChatAttachment;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 聊天请求体（POST /api/chat、/api/chat/stream）。
 * message 必填，sessionId 可选（为空时自动创建新会话并在响应中返回）。
 * agentId 可选：指定要调用的 Agent（对应 agent 表主键），为空时走默认 Agent。
 * routing 可选：{@code auto}（默认）/ {@code agentic} / {@code graph}，显式指定执行路径。
 * channel 可选：消息来源渠道（{@code qq} / {@code cli} / {@code web} / {@code api}），
 * 供 system prompt 的信道规范段按渠道定制输出；空 = 不注入信道段。
 * attachments 可选：随本轮提交的图片，直接交给支持视觉的模型；空表示纯文本。
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        String sessionId,
        Long agentId,
        String routing,
        String channel,
        List<ChatAttachment> attachments) {

    /** 缺省不带附件；null 归一为空列表，下游不必判空 */
    public ChatRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 不指定执行路径（auto，由服务端判定）的常用形态。 */
    public ChatRequest(String message, String sessionId, Long agentId) {
        this(message, sessionId, agentId, null, null, List.of());
    }

    /** 指定执行路径、不指定渠道的常用形态。 */
    public ChatRequest(String message, String sessionId, Long agentId, String routing) {
        this(message, sessionId, agentId, routing, null, List.of());
    }

    /** 不带附件的完整形态（渠道 / CLI 的绝大多数调用走这里）。 */
    public ChatRequest(String message, String sessionId, Long agentId, String routing, String channel) {
        this(message, sessionId, agentId, routing, channel, List.of());
    }
}
