package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.service.MessageSender;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 推送注册表单测：地址解析、未注册渠道、实现违约兜底、同渠道去重。
 *
 * <p>这层是主动外发的唯一入口，出错方式是「静默发错人」或「异常冒到任务链路」，
 * 所以每个失败分支都断言返回 false 而不是抛。
 */
class MessageSenderRegistryTest {

    /**
     * 构造渠道替身。
     *
     * <p>用 {@code CALLS_REAL_METHODS} 而不是裸 mock：{@code sendForReceipt} 是 default 方法，
     * 裸 mock 会让它返回 null（Mockito 不执行 default 实现），注册表就会把它当成「空回执」。
     * 走真实方法才能验证「default 折 send」这条契约本身。
     */
    private static MessageSender sender(String channel, boolean result) {
        MessageSender sender = mock(MessageSender.class, org.mockito.Answers.CALLS_REAL_METHODS);
        when(sender.channel()).thenReturn(channel);
        when(sender.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
        return sender;
    }

    @Test
    void send_routesByChannelPrefix() {
        MessageSender qq = sender("qq", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(qq));

        assertTrue(registry.send("qq:10001", "hello"));
        verify(qq).send("10001", "hello");
    }

    /** target 里允许出现冒号（如将来的 group:123），只按第一个冒号切分 */
    @Test
    void send_targetMayContainColon() {
        MessageSender qq = sender("qq", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(qq));

        registry.send("qq:group:42", "hi");

        verify(qq).send("group:42", "hi");
    }

    @Test
    void send_unknownChannel_returnsFalse() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        assertFalse(registry.send("wecom:zhangsan", "hi"), "未注册渠道应返回 false 而非抛异常");
    }

    @Test
    void send_malformedAddress_returnsFalse() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        assertFalse(registry.send("qq", "hi"), "缺冒号");
        assertFalse(registry.send(":10001", "hi"), "缺渠道");
        assertFalse(registry.send("qq:", "hi"), "缺目标");
        assertFalse(registry.send(null, "hi"));
        assertFalse(registry.send("qq:10001", " "), "空正文不发送");
    }

    @Test
    void send_senderThrows_swallowedAsFalse() {
        MessageSender broken = mock(MessageSender.class);
        when(broken.channel()).thenReturn("qq");
        when(broken.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("network"));
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(broken));

        assertFalse(registry.send("qq:1", "hi"), "实现违约不应把异常冒到任务链路");
    }

    /** 同渠道两个实现：先注册者优先，行为确定而不是随机挑一个 */
    @Test
    void duplicateChannel_firstWins() {
        MessageSender first = sender("qq", true);
        MessageSender second = sender("qq", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(first, second));

        registry.send("qq:1", "hi");

        verify(first).send("1", "hi");
        assertEquals(1, registry.channels().size());
    }

    @Test
    void emptySenders_sendReturnsFalse() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of());

        assertFalse(registry.send("qq:1", "hi"));
        assertEquals(0, registry.channels().size());
    }

    // ---------- 降级链：解析与校验 ----------

    @Test
    void chainOf_singleValue_isLegacyCompatible() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        assertEquals(List.of("qq:10001"), registry.chainOf("qq:10001"),
                "存量单值必须是长度 1 的链，行为与改造前一致");
    }

    @Test
    void chainOf_twoHops_keepsOrder() {
        MessageSenderRegistry registry = new MessageSenderRegistry(
                List.of(sender("qq", true), sender("email", true)));

        assertEquals(List.of("feishu:main".replace("feishu", "qq"), "email:me@x.com"),
                registry.chainOf("qq:main|email:me@x.com"));
    }

    @Test
    void chainOf_emptySegment_rejected() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        assertThrows(IllegalArgumentException.class, () -> registry.chainOf("qq:1||email:a@b.com"),
                "空段会在运行时变成一次无意义的发送尝试，必须入队前拒绝");
    }

    @Test
    void chainOf_unknownChannel_rejected() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.chainOf("q:1"));
        assertTrue(e.getMessage().contains("渠道未注册"), e.getMessage());
    }

    @Test
    void chainOf_missingTarget_rejected() {
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(sender("qq", true)));

        assertThrows(IllegalArgumentException.class, () -> registry.chainOf("qq:"));
    }

    // ---------- 降级链：逐跳尝试 ----------

    @Test
    void sendWithFallback_firstSuccess_stopsAtFirst() {
        MessageSender qq = sender("qq", true);
        MessageSender email = sender("email", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(qq, email));

        List<MessageSenderRegistry.Attempt> attempts = registry.sendWithFallback("qq:1|email:a@b.com", "hi", null);

        assertEquals(1, attempts.size(), "第一跳成功就不该再打扰第二跳");
        assertTrue(attempts.get(0).sent());
        verify(email, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sendWithFallback_firstFails_goesSecond() {
        MessageSender qq = sender("qq", false);
        MessageSender email = sender("email", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(qq, email));

        List<MessageSenderRegistry.Attempt> attempts = registry.sendWithFallback("qq:1|email:a@b.com", "hi", null);

        assertEquals(2, attempts.size());
        assertFalse(attempts.get(0).sent());
        assertTrue(attempts.get(1).sent());
        verify(email).send("a@b.com", "hi");
    }

    @Test
    void sendWithFallback_excludeChannel_skipsIt() {
        MessageSender qq = sender("qq", false);
        MessageSender email = sender("email", true);
        MessageSenderRegistry registry = new MessageSenderRegistry(List.of(qq, email));

        List<MessageSenderRegistry.Attempt> attempts =
                registry.sendWithFallback("qq:1|email:a@b.com", "渠道挂了", "qq");

        assertEquals(2, attempts.size());
        assertTrue(attempts.get(0).reason().contains("excludeChannel"));
        verify(qq, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertTrue(attempts.get(1).sent());
    }

    @Test
    void sendWithFallback_allFail_returnsEveryHop() {
        MessageSenderRegistry registry = new MessageSenderRegistry(
                List.of(sender("qq", false), sender("email", false)));

        List<MessageSenderRegistry.Attempt> attempts = registry.sendWithFallback("qq:1|email:a@b.com", "hi", null);

        assertEquals(2, attempts.size());
        assertTrue(attempts.stream().noneMatch(MessageSenderRegistry.Attempt::sent));
    }

    @Test
    void sendFirstSuccess_allFail_returnsLastReason() {
        MessageSenderRegistry registry = new MessageSenderRegistry(
                List.of(sender("qq", false), sender("email", false)));

        MessageSenderRegistry.Attempt last = registry.sendFirstSuccess("qq:1|missing:2", "hi", null);

        assertFalse(last.sent());
        assertTrue(last.reason().contains("渠道未注册"), "最后一跳的错误最接近真实原因：" + last.reason());
    }

    @Test
    void sendForReceipt_defaultContract_noErrorCode() {
        MessageSender qq = sender("qq", false);

        MessageSender.DeliveryReceipt receipt = qq.sendForReceipt("1", "hi");

        assertFalse(receipt.sent());
        assertTrue(receipt.reason().contains("未提供失败原因"),
                "没有结构化错误码的渠道也要给出可读原因，否则 outbox 的 last_error 是空的");
    }
}
