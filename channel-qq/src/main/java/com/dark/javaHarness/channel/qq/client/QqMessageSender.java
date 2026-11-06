package com.dark.javaHarness.channel.qq.client;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.service.MessageSender;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ 渠道的主动推送实现：私聊送达（定时任务结果、失败告警）。
 *
 * <p>为什么只做私聊：主动消息是「任务找你」，群聊广播会把内部任务通知推给无关成员；
 * 需要群通知时由 agent 在自己的任务里显式发，不走这条通道。
 *
 * <p>失败语义与 {@link NapCatApiClient} 一致：客户端本身不抛异常，返回 null 即未送达。
 */
public class QqMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(QqMessageSender.class);

    private final NapCatApiClient apiClient;

    public QqMessageSender(NapCatApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String channel() {
        return "qq";
    }

    @Override
    public boolean send(String target, String text) {
        long userId;
        try {
            userId = Long.parseLong(target.trim());
        } catch (NumberFormatException e) {
            log.warn("[qq-sender] 目标不是合法 QQ 号: {}", target);
            return false;
        }
        try {
            Long messageId = apiClient.sendPrivateMsg(userId,
                    List.of(new MessageSegment("text", Map.of("text", text))));
            return messageId != null;
        } catch (Exception e) {
            log.warn("[qq-sender] 私聊推送失败 userId={}: {}", userId, e.toString());
            return false;
        }
    }
}
