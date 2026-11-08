package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.channel.ChannelHealth;
import com.dark.javaHarness.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 渠道探活告警单测：阈值、抑制、恢复、非本渠道、以及三种不该告警的状态。
 *
 * <p>重点不在「能发出去」，在「不该发的时候一条都不发」——误报比漏报更快毁掉告警的可信度。
 */
@ExtendWith(MockitoExtension.class)
class ChannelWatchdogTest {

    private static final String EMAIL_CHAIN = "email:me@example.com";

    @Mock
    private ObjectProvider<ChannelHealth> healths;

    @Mock
    private DeliveryOutboxService outbox;

    private MutableClock clock;
    private AlertGate gate;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        gate = new AlertGate(3, 24, clock);
    }

    @Test
    void belowThreshold_silent() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(qq);

        watchdog.scan();
        watchdog.scan();

        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
        assertEquals(2, gate.consecutiveFailures("channel:qq"), "计数要累计，只是还没到阈值");
    }

    @Test
    void thirdFailure_enqueuesAlert() {
        FakeHealth qq = new FakeHealth("qq", down("不可达（retcode=1404）"));
        ChannelWatchdog watchdog = watchdog(qq);

        watchdog.scan();
        watchdog.scan();
        List<ChannelWatchdog.Notice> notices = watchdog.scan();

        assertEquals(1, notices.size());
        assertEquals(ChannelWatchdog.NoticeKind.ALERT, notices.get(0).kind());
        verify(outbox).enqueue(eq("alert"), eq("qq/ALERT"), eq(EMAIL_CHAIN),
                contains("连续失败：3 次"));
        verify(outbox).enqueue(eq("alert"), eq("qq/ALERT"), eq(EMAIL_CHAIN),
                contains("不可达（retcode=1404）"));
    }

    @Test
    void alertChain_excludesFailingChannel() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(true, "qq:123|email:me@example.com", qq);

        List<ChannelWatchdog.Notice> notices = afterThreeScans(watchdog);

        assertEquals("email:me@example.com", notices.get(0).chain(),
                "不能用坏渠道报坏渠道：qq 段必须被剔除");
    }

    @Test
    void alertChainOfOnlyFailingChannel_isNotSent() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(true, "qq:123", qq);

        List<ChannelWatchdog.Notice> notices = afterThreeScans(watchdog);

        assertTrue(notices.isEmpty(), "链上只剩故障渠道，此时无路可发");
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recovery_afterAlert_sendsRecovered() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(qq);
        afterThreeScans(watchdog);

        qq.set(up("可达（http://napcat:3000）"));
        List<ChannelWatchdog.Notice> notices = watchdog.scan();

        assertEquals(1, notices.size());
        assertEquals(ChannelWatchdog.NoticeKind.RECOVERED, notices.get(0).kind());
        verify(outbox).enqueue(eq("alert"), eq("qq/RECOVERED"), eq(EMAIL_CHAIN), contains("已恢复"));
        assertFalse(gate.alerted("channel:qq"));
    }

    @Test
    void recovery_withoutAlert_isSilent() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(qq);
        watchdog.scan();

        qq.set(up("可达"));
        assertTrue(watchdog.scan().isEmpty(), "没告过警就不该发「已恢复」——那会让告警失去可信度");
    }

    @Test
    void unknown_isNotTreatedAsDown() {
        FakeHealth email = new FakeHealth("email",
                new ChannelHealth.Snapshot(ChannelHealth.Status.UNKNOWN, "未知（尚无投递记录）"));
        ChannelWatchdog watchdog = watchdog(email);

        for (int i = 0; i < 5; i++) {
            assertTrue(watchdog.scan().isEmpty(), "「不知道」不是「坏了」");
        }
        assertEquals(0, gate.consecutiveFailures("channel:email"));
    }

    @Test
    void disabled_isIgnored() {
        FakeHealth qq = new FakeHealth("qq",
                new ChannelHealth.Snapshot(ChannelHealth.Status.DISABLED, "enabled=false"));
        ChannelWatchdog watchdog = watchdog(qq);

        for (int i = 0; i < 5; i++) {
            watchdog.scan();
        }

        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());
        assertEquals(0, gate.consecutiveFailures("channel:qq"), "关掉的渠道不参与计数");
    }

    @Test
    void persistentFailure_suppressedWithinThrottle() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(qq);
        afterThreeScans(watchdog);

        clock.advance(Duration.ofHours(1));
        assertTrue(watchdog.scan().isEmpty(), "故障持续期间不重复提醒");

        clock.advance(Duration.ofHours(25));
        assertEquals(1, watchdog.scan().size(), "过了抑制窗口仍故障，再提醒一次");
        verify(outbox, times(2)).enqueue(eq("alert"), eq("qq/ALERT"), anyString(), anyString());
    }

    @Test
    void noHealthBeans_noError() {
        ChannelWatchdog watchdog = watchdog();

        assertTrue(watchdog.scan().isEmpty(), "渠道模块未装配时应该是空转，不是报错");
    }

    @Test
    void watchdogDisabled_doesNotProbe() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(false, EMAIL_CHAIN, qq);

        assertTrue(watchdog.scan().isEmpty());
        assertEquals(0, qq.probes(), "看门狗关闭时不该去探活");
    }

    @Test
    void blankAlertChain_doesNotProbe() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        ChannelWatchdog watchdog = watchdog(true, "  ", qq);

        assertTrue(watchdog.scan().isEmpty(), "没有告警落点时探活没有意义");
        assertEquals(0, qq.probes());
    }

    @Test
    void enqueueFailure_doesNotStopOtherChannels() {
        FakeHealth qq = new FakeHealth("qq", down("不可达"));
        FakeHealth feishu = new FakeHealth("feishu", down("token 失效"));
        ChannelWatchdog watchdog = watchdog(qq, feishu);
        when(outbox.enqueue(anyString(), eq("qq/ALERT"), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        List<ChannelWatchdog.Notice> notices = afterThreeScans(watchdog);

        assertEquals(1, notices.size(), "一个渠道入队失败不该拖住另一个");
        assertEquals("feishu", notices.get(0).channelId());
    }

    @Test
    void withoutChannel_keepsOtherSegmentsInOrder() {
        assertEquals("feishu:main|email:me@example.com",
                ChannelWatchdog.withoutChannel("qq:123|feishu:main|email:me@example.com", "qq"));
        assertEquals("qq:123|email:me@example.com",
                ChannelWatchdog.withoutChannel("qq:123| feishu:main |email:me@example.com", "feishu"));
        assertEquals("", ChannelWatchdog.withoutChannel("qq:123", "qq"));
        assertEquals("qq:123", ChannelWatchdog.withoutChannel("qq:123", ""), "未知渠道标识时原样保留");
    }

    private List<ChannelWatchdog.Notice> afterThreeScans(ChannelWatchdog watchdog) {
        watchdog.scan();
        watchdog.scan();
        return watchdog.scan();
    }

    private ChannelWatchdog watchdog(ChannelHealth... list) {
        return watchdog(true, EMAIL_CHAIN, list);
    }

    private ChannelWatchdog watchdog(boolean enabled, String chain, ChannelHealth... list) {
        lenient().when(healths.orderedStream()).thenAnswer(invocation -> Arrays.stream(list));
        return new ChannelWatchdog(healths, outbox, gate, enabled, chain, clock);
    }

    private static ChannelHealth.Snapshot down(String detail) {
        return new ChannelHealth.Snapshot(ChannelHealth.Status.DOWN, detail);
    }

    private static ChannelHealth.Snapshot up(String detail) {
        return new ChannelHealth.Snapshot(ChannelHealth.Status.UP, detail);
    }

    /** 可变状态的渠道替身：同一个实例在多次 scan 之间切换状态 */
    private static final class FakeHealth implements ChannelHealth {

        private final String id;
        private ChannelHealth.Snapshot snapshot;
        private int probes;

        private FakeHealth(String id, ChannelHealth.Snapshot snapshot) {
            this.id = id;
            this.snapshot = snapshot;
        }

        private void set(ChannelHealth.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public String channelId() {
            return id;
        }

        @Override
        public String channelName() {
            return "测试渠道 " + id;
        }

        @Override
        public ChannelHealth.Snapshot snapshot() {
            probes++;
            return snapshot;
        }

        private int probes() {
            return probes;
        }
    }
}
