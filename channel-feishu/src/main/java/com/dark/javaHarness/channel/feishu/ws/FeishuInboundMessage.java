package com.dark.javaHarness.channel.feishu.ws;

/**
 * 一条已通过白名单与去重的消息。
 *
 * @param openId    发送者 open_id（单聊的回复目标，也是会话绑定的用户维度）
 * @param chatId    会话 ID（群聊的回复目标）
 * @param messageId 飞书消息 ID（排障用）
 * @param eventId   事件 ID（去重键）
 * @param text      去掉 @ 占位符后的纯文本
 * @param group     是否来自群聊；决定回复用 chat_id 还是 open_id
 */
public record FeishuInboundMessage(String openId, String chatId, String messageId, String eventId,
                                   String text, boolean group) {

    /** 回复目标：群聊回 chat_id，单聊回 open_id */
    public String replyId() {
        return group ? chatId : openId;
    }

    /** 飞书消息接口的 receive_id_type */
    public String receiveIdType() {
        return group ? "chat_id" : "open_id";
    }
}
