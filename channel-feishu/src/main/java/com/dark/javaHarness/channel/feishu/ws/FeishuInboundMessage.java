package com.dark.javaHarness.channel.feishu.ws;

import java.util.List;

/**
 * 一条已通过白名单与去重的消息。
 *
 * @param openId      发送者 open_id（单聊的回复目标，也是会话绑定的用户维度）
 * @param chatId      会话 ID（群聊的回复目标）
 * @param messageId   飞书消息 ID（排障与下载资源用）
 * @param eventId     事件 ID（去重键）
 * @param text        纯文本：text 原文 / post 解析结果 / 图片占位「[图片]」；@ 占位符已剥离
 * @param group       是否来自群聊；决定回复用 chat_id 还是 open_id
 * @param attachments 随消息携带的资源（当前只有图片），字节在处理线程里下载
 * @param mentioned   群聊里是否 @ 了机器人；reply-mode=mention 时据此决定要不要回答
 */
public record FeishuInboundMessage(String openId, String chatId, String messageId, String eventId,
                                   String text, boolean group, List<FeishuAttachment> attachments,
                                   boolean mentioned) {

    /** 空附件归一为空列表；调用方不必判空 */
    public FeishuInboundMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 无附件的常用形态（纯文本消息） */
    public FeishuInboundMessage(String openId, String chatId, String messageId, String eventId,
                                String text, boolean group) {
        this(openId, chatId, messageId, eventId, text, group, List.of(), false);
    }

    /** 带附件、未 @ 机器人的形态 */
    public FeishuInboundMessage(String openId, String chatId, String messageId, String eventId,
                                String text, boolean group, List<FeishuAttachment> attachments) {
        this(openId, chatId, messageId, eventId, text, group, attachments, false);
    }

    /** 回复目标：群聊回 chat_id，单聊回 open_id */
    public String replyId() {
        return group ? chatId : openId;
    }

    /** 飞书消息接口的 receive_id_type */
    public String receiveIdType() {
        return group ? "chat_id" : "open_id";
    }
}
