package com.dark.javaHarness.contract.spi;

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

    /** 渠道标识，与 notify_channel 的前缀对应（qq / email / feishu / ...） */
    String channel();

    /**
     * 向渠道内目标发送纯文本。
     *
     * <p>失败不抛异常：推送是尽力而为，调用方只记日志，不让一次发送失败拖垮任务链路。
     *
     * @param target 渠道内目标（QQ 为 userId 字符串，邮件为收件地址，飞书为别名或 open_id）
     * @return 是否送达
     */
    boolean send(String target, String text);

    /**
     * 带回执的发送：需要 message_id 的场景（已读追查、撤回）与需要「失败原因可分类」的
     * 场景（outbox 判断可重试 / 终态）用它。
     *
     * <p>默认实现把 {@link #send} 折成「成功但无 ID」/「失败但无原因」，现有渠道无需改动。
     * 有结构化错误码的渠道（飞书）应覆盖它，把原始错误码写进 {@code reason}——
     * 那是 outbox 唯一能依据的补救线索。
     */
    default DeliveryReceipt sendForReceipt(String target, String text) {
        return send(target, text)
                ? DeliveryReceipt.sent(null)
                : DeliveryReceipt.failed("渠道返回未送达，且未提供失败原因");
    }

    /**
     * 投递回执。
     *
     * @param sent      是否送达
     * @param messageId 渠道侧消息 ID（无则为 null）
     * @param reason    失败原因，需可读且尽量含渠道原始错误码；{@code sent=true} 时为 null
     * @param retryable 失败是否值得重试。默认 true（未知失败按可重试处理，宁可多试一次也别丢消息）；
     *                  配置错 / 内容问题用 {@link #fatal} 明确标为非重试，避免 outbox 白白重试三轮
     */
    record DeliveryReceipt(boolean sent, String messageId, String reason, boolean retryable) {

        public static DeliveryReceipt sent(String messageId) {
            return new DeliveryReceipt(true, messageId, null, false);
        }

        /** 可重试失败（网络抖动、限流、服务端 5xx） */
        public static DeliveryReceipt failed(String reason) {
            return new DeliveryReceipt(false, null, normalize(reason), true);
        }

        /** 不可重试失败（签名错、请求体非法、收件人不存在）——重试只会一直失败 */
        public static DeliveryReceipt fatal(String reason) {
            return new DeliveryReceipt(false, null, normalize(reason), false);
        }

        private static String normalize(String reason) {
            return reason == null || reason.isBlank() ? "未提供原因" : reason;
        }
    }
}
