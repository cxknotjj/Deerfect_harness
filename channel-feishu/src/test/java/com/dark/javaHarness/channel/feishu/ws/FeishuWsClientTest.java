package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 生命周期单测：启停委托、幂等、测试环境不 halt。
 *
 * <p>用假 transport 代替 SDK：真正建连需要网络与凭据，那条路径由真机验证覆盖，
 * 这里只验「什么时候调 start/stop」这一层策略。
 */
class FeishuWsClientTest {

    private FakeTransport transport;
    private FeishuWsClient client;

    @BeforeEach
    void setUp() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setEnabled(true);
        props.getWs().setAppId("cli_test");
        transport = new FakeTransport();
        client = new FeishuWsClient(props, transport);
    }

    @Test
    void start_delegatesAndMarksRunning() {
        assertFalse(client.isRunning());

        client.start();

        assertEquals(1, transport.starts);
        assertTrue(client.isRunning());
    }

    @Test
    void startTwice_startsOnce() {
        client.start();
        client.start();

        assertEquals(1, transport.starts, "重复 start 不能建两条连接");
    }

    @Test
    void stop_delegatesAndClearsRunning() {
        client.start();

        client.stop();

        assertEquals(1, transport.stops);
        assertFalse(client.isRunning());
    }

    @Test
    void stopBeforeStart_isNoop() {
        client.stop();

        assertEquals(0, transport.stops);
    }

    @Test
    void stopTwice_stopsOnce() {
        client.start();
        client.stop();
        client.stop();

        assertEquals(1, transport.stops);
    }

    @Test
    void phase_isMinValue_stopsLast() {
        assertEquals(Integer.MIN_VALUE, client.getPhase(),
                "phase 越小停得越晚：必须最后停，否则会在业务线程还在跑时强退");
    }

    private static final class FakeTransport implements FeishuWsTransport {

        private int starts;
        private int stops;

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
