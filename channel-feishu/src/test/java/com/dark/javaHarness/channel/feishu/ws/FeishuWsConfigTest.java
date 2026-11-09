package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 长连接装配校验：缺 App ID / Secret 必须拒绝启动。
 *
 * <p>静默放行的后果是「连上了但永远收不到消息」，比启动失败难查得多。
 */
class FeishuWsConfigTest {

    private final FeishuWsConfig config = new FeishuWsConfig();

    @Test
    void missingAppId_failsFast() {
        FeishuProperties props = props();
        props.getWs().setAppId(" ");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.feishuApiClient(props));

        assertTrue(e.getMessage().contains("FEISHU_APP_ID"), e.getMessage());
    }

    @Test
    void missingAppSecret_failsFast() {
        FeishuProperties props = props();
        props.getWs().setAppSecret(null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.feishuApiClient(props));

        assertTrue(e.getMessage().contains("FEISHU_APP_SECRET"), e.getMessage());
    }

    @Test
    void validConfig_buildsWholeChain() {
        FeishuProperties props = props();
        FeishuEventDedup dedup = config.feishuEventDedup();
        FeishuApiClient api = config.feishuApiClient(props);
        FeishuInboundService inbound = config.feishuInboundService(props, dedup, message -> { });
        FeishuWsTransport transport = config.feishuWsTransport(props, inbound);
        FeishuWsClient client = config.feishuWsClient(props, transport);

        assertNotNull(api);
        assertNotNull(transport);
        assertNotNull(client);
    }

    private static FeishuProperties props() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setEnabled(true);
        props.getWs().setAppId("cli_test");
        props.getWs().setAppSecret("secret");
        props.getWs().setAllowedUsers(List.of());
        return props;
    }
}
