package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.contract.spi.ChannelHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 长连接健康：四态判定只看「运行中 + 最近事件」，没事件是未知而不是故障 */
@ExtendWith(MockitoExtension.class)
class FeishuWsHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-10T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private FeishuWsClient client;

    @Mock
    private FeishuInboundService inbound;

    @Test
    void disabled_isDisabledEvenIfClientRunning() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setEnabled(false);

        ChannelHealth.Snapshot snapshot = health(props).snapshot();

        assertEquals(ChannelHealth.Status.DISABLED, snapshot.status());
    }

    @Test
    void notRunning_isDown() {
        when(client.isRunning()).thenReturn(false);

        ChannelHealth.Snapshot snapshot = health(enabledProps()).snapshot();

        assertEquals(ChannelHealth.Status.DOWN, snapshot.status());
        assertTrue(snapshot.detail().contains("未运行"), snapshot.detail());
    }

    @Test
    void runningWithoutEvents_isUnknown() {
        when(client.isRunning()).thenReturn(true);
        when(inbound.lastEventAtMillis()).thenReturn(0L);

        ChannelHealth.Snapshot snapshot = health(enabledProps()).snapshot();

        assertEquals(ChannelHealth.Status.UNKNOWN, snapshot.status());
        assertTrue(snapshot.detail().contains("未收到任何事件"), snapshot.detail());
    }

    @Test
    void recentEvent_isUp() {
        when(client.isRunning()).thenReturn(true);
        when(inbound.lastEventAtMillis()).thenReturn(NOW.minusSeconds(30).toEpochMilli());

        ChannelHealth.Snapshot snapshot = health(enabledProps()).snapshot();

        assertEquals(ChannelHealth.Status.UP, snapshot.status());
        assertTrue(snapshot.detail().contains("30 秒前"), snapshot.detail());
    }

    @Test
    void staleEvent_isUnknownNotDown() {
        when(client.isRunning()).thenReturn(true);
        when(inbound.lastEventAtMillis()).thenReturn(NOW.minusSeconds(8 * 86_400L).toEpochMilli());

        ChannelHealth.Snapshot snapshot = health(enabledProps()).snapshot();

        assertEquals(ChannelHealth.Status.UNKNOWN, snapshot.status(),
                "安静的机器人不该被判成故障，否则每周一条假警报");
        assertTrue(snapshot.detail().contains("8 天前"), snapshot.detail());
    }

    @Test
    void channelId_isSeparateFromWebhook() {
        FeishuWsHealth health = health(enabledProps());

        assertEquals("feishu-ws", health.channelId(), "与 webhook 共用 id 会让失败计数互相污染");
        assertEquals("飞书 / 长连接", health.channelName());
    }

    private FeishuProperties enabledProps() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setEnabled(true);
        return props;
    }

    private FeishuWsHealth health(FeishuProperties props) {
        return new FeishuWsHealth(props, client, inbound, CLOCK);
    }
}
