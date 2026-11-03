package com.dark.javaHarness.domain;

/**
 * 聊天消息 metadata 的共享键：会话快照读取时把消息 ID 放进 Spring AI Message，
 * 上下文裁剪据此生成归档引用，recall_history 工具据此按会话召回。
 */
public final class ChatMessageMetadata {

    /** 会话快照 item 的稳定消息 ID（快照 JSON 的 id 字段与 Message metadata 同键） */
    public static final String MESSAGE_ID = "messageId";

    private ChatMessageMetadata() {
    }
}
