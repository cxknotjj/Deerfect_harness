package com.dark.javaHarness.service;

/**
 * 主动消息发送端口（渠道无关）：任务在**没有人开口**的时候找到用户。
 *
 * <p>与 {@link ReplyPostProcessor} 的区别：那个是「清洗模型回复文本」的幂等纯函数 SPI，
 * 只参与同步回复链路、不得有副作用；本端口有真实副作用（投递到 IM）、可能失败、需要回执，
 * 用于定时任务完成通知、失败告警这类主动外发。两者语义相反，不要互相复用。
 *
 * <p>实现由各渠道模块提供（{@code channel-qq} 等），core 只依赖本接口——渠道模块在
 * `server/pom.xml` 是 runtime 依赖，compile 期不可见，这层倒置是 enforcer 规则的前提。
 */
public interface MessageSender {

    /** 渠道标识，与 notify_channel 的前缀对应（qq / wecom / ...） */
    String channel();

    /**
     * 向渠道内目标发送纯文本。
     *
     * <p>失败不抛异常：推送是尽力而为，调用方只记日志，不让一次发送失败拖垮任务链路。
     *
     * @param target 渠道内目标（QQ 为 userId 字符串）
     * @return 是否送达
     */
    boolean send(String target, String text);
}
