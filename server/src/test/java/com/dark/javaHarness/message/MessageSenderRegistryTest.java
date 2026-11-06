package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static MessageSender sender(String channel, boolean result) {
        MessageSender sender = mock(MessageSender.class);
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
}
