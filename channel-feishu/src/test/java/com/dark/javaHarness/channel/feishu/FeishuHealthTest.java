package com.dark.javaHarness.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.spi.ChannelHealth;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 飞书健康单测：用「最近一次投递」当探针，不发测试消息。
 *
 * <p>关键断言是「不知道就说不知道」：7 天没投递报 UNKNOWN。若报 UP 是假装健康，
 * 若报 DOWN 会让探活告警每周发一条假警报。
 */
class FeishuHealthTest {

    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    private StubWebhookServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubWebhookServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void disabled_reportsDisabled() {
        FeishuProperties props = props();
        props.setEnabled(false);

        ChannelHealth.Snapshot snapshot = healthAt(props, senderAt(NOW, props)).snapshot();

        assertEquals(ChannelHealth.Status.DISABLED, snapshot.status());
        assertFalse(snapshot.enabled());
        assertTrue(snapshot.detail().contains("enabled=false"), snapshot.detail());
    }

    @Test
    void neverSent_reportsUnknownNotHealthy() {
        FeishuProperties props = props();

        ChannelHealth.Snapshot snapshot = healthAt(props, senderAt(NOW, props)).snapshot();

        assertEquals(ChannelHealth.Status.UNKNOWN, snapshot.status(), "没投递过就不能报「可用」");
        assertFalse(snapshot.available());
        assertTrue(snapshot.detail().contains("尚无投递记录"), snapshot.detail());
    }

    @Test
    void recentSuccess_reportsUp() {
        FeishuProperties props = props();
        FeishuWebhookSender sender = senderAt(NOW.minusSeconds(60), props);
        sender.sendForReceipt("main", "内容");

        ChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertEquals(ChannelHealth.Status.UP, snapshot.status());
        assertTrue(snapshot.detail().contains("成功"), snapshot.detail());
    }

    @Test
    void recentFailure_reportsDownWithReason() {
        FeishuProperties props = props();
        server.respond(503, "boom");
        FeishuWebhookSender sender = senderAt(NOW.minusSeconds(60), props);
        sender.sendForReceipt("main", "内容");

        ChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertEquals(ChannelHealth.Status.DOWN, snapshot.status());
        assertTrue(snapshot.detail().contains("失败"), snapshot.detail());
    }

    @Test
    void staleSuccess_reportsUnknownAfterSevenDays() {
        FeishuProperties props = props();
        FeishuWebhookSender sender = senderAt(NOW.minusSeconds(8 * 24 * 3600L), props);
        sender.sendForReceipt("main", "内容");

        ChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertEquals(ChannelHealth.Status.UNKNOWN, snapshot.status(), "8 天没投递，状态已经不代表当前可用性");
        assertTrue(snapshot.detail().contains("未知"), snapshot.detail());
    }

    @Test
    void detail_neverLeaksWebhookUrlOrSecret() {
        FeishuProperties props = props();
        server.respond(500, "boom");
        FeishuWebhookSender sender = senderAt(NOW, props);
        sender.sendForReceipt("main", "内容");

        ChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertFalse(snapshot.detail().contains("secret"), snapshot.detail());
        assertFalse(snapshot.detail().contains("127.0.0.1"), snapshot.detail());
    }

    @Test
    void channelIdentity_isReadable() {
        FeishuProperties props = props();
        FeishuHealth health = healthAt(props, senderAt(NOW, props));

        assertTrue(health.channelName().contains("飞书"));
        assertEquals("feishu", health.channelId(), "探活告警靠它排除「本渠道」");
    }

    private FeishuProperties props() {
        FeishuProperties props = new FeishuProperties();
        props.setEnabled(true);
        props.setAlias("main");
        props.setWebhookUrl(server.url());
        props.setWebhookSecret("test-secret");
        return props;
    }

    private static FeishuWebhookSender senderAt(Instant at, FeishuProperties props) {
        return new FeishuWebhookSender(props, StubWebhookServer.httpClient(), Clock.fixed(at, ZoneOffset.UTC));
    }

    private static FeishuHealth healthAt(FeishuProperties props, FeishuWebhookSender sender) {
        return new FeishuHealth(props, sender, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
