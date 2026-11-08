package com.dark.javaHarness.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 装配期校验单测：缺 URL / 缺 secret / 空别名都必须让启动失败。
 *
 * <p>「缺 secret 就按无签名发」是最危险的默认值——它不会报错，只会让群机器人对
 * 任何拿到 URL 的人开放。所以这条校验必须有自己的测试，而不是靠 review 记住。
 */
class FeishuChannelConfigTest {

    private final FeishuChannelConfig config = new FeishuChannelConfig();

    @Test
    void missingUrl_failsFast() {
        FeishuProperties props = props();
        props.setWebhookUrl(" ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.feishuWebhookSender(props));

        assertTrue(e.getMessage().contains("FEISHU_WEBHOOK_URL"), e.getMessage());
    }

    @Test
    void missingSecret_failsFastInsteadOfSendingUnsigned() {
        FeishuProperties props = props();
        props.setWebhookSecret(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.feishuWebhookSender(props));

        assertTrue(e.getMessage().contains("FEISHU_WEBHOOK_SECRET"), e.getMessage());
        assertTrue(e.getMessage().contains("拒绝启动"), "要说明为什么不能静默放行");
    }

    @Test
    void blankAlias_failsFast() {
        FeishuProperties props = props();
        props.setAlias("  ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.feishuWebhookSender(props));

        assertTrue(e.getMessage().contains("alias"), e.getMessage());
    }

    @Test
    void validConfig_buildsSenderAndHealth() {
        FeishuProperties props = props();
        FeishuWebhookSender sender = config.feishuWebhookSender(props);

        assertEquals("feishu", sender.channel());
        assertEquals("feishu", config.feishuChannelHealth(props, sender).channelId());
    }

    private static FeishuProperties props() {
        FeishuProperties props = new FeishuProperties();
        props.setEnabled(true);
        props.setAlias("main");
        props.setWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/test-token");
        props.setWebhookSecret("test-secret");
        return props;
    }
}
