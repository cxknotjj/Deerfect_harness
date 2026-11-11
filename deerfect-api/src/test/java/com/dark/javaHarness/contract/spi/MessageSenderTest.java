package com.dark.javaHarness.contract.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt;
import org.junit.jupiter.api.Test;

/**
 * 主动推送端口的契约单测：default 回执方法 + 回执工厂。
 *
 * <p>这里的代码在 {@code harness-api} 里，但真正的调用方都在 {@code server}——如果没有这组
 * 用例，端口自身的逻辑（default 折 send、失败原因兜底）就没有任何测试，模块覆盖率会被稀释。
 */
class MessageSenderTest {

    /** 只实现抽象方法的最小渠道：验证 default 方法不依赖具体实现 */
    private static MessageSender channel(boolean result) {
        return new MessageSender() {
            @Override
            public String channel() {
                return "stub";
            }

            @Override
            public boolean send(String target, String text) {
                return result;
            }
        };
    }

    @Test
    void sendForReceipt_default_successHasNoMessageId() {
        DeliveryReceipt receipt = channel(true).sendForReceipt("t", "body");

        assertTrue(receipt.sent());
        assertNull(receipt.messageId(), "没有渠道回执 ID 时不该编一个出来");
        assertNull(receipt.reason());
        assertFalse(receipt.retryable(), "已送达时重试标记无意义，保持 false 避免误读");
    }

    @Test
    void sendForReceipt_default_failureGivesReadableReason() {
        DeliveryReceipt receipt = channel(false).sendForReceipt("t", "body");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable(), "没有结构化错误码时按可重试处理——宁可多试一次也别丢消息");
        assertTrue(receipt.reason().contains("未提供失败原因"), receipt.reason());
    }

    @Test
    void failed_keepsReasonAndIsRetryable() {
        DeliveryReceipt receipt = DeliveryReceipt.failed("超时");

        assertFalse(receipt.sent());
        assertEquals("超时", receipt.reason());
        assertTrue(receipt.retryable());
    }

    @Test
    void fatal_isNotRetryable() {
        DeliveryReceipt receipt = DeliveryReceipt.fatal("签名错");

        assertFalse(receipt.sent());
        assertEquals("签名错", receipt.reason());
        assertFalse(receipt.retryable(), "配置错的失败重试只会一直失败");
    }

    @Test
    void failed_blankReason_normalized() {
        assertEquals("未提供原因", DeliveryReceipt.failed("  ").reason());
        assertEquals("未提供原因", DeliveryReceipt.failed(null).reason());
        assertEquals("未提供原因", DeliveryReceipt.fatal(null).reason());
    }

    @Test
    void sent_carriesMessageId() {
        DeliveryReceipt receipt = DeliveryReceipt.sent("om_xxx");

        assertTrue(receipt.sent());
        assertEquals("om_xxx", receipt.messageId());
    }
}
