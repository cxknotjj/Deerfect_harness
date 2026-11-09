package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 去重单测：窗口内重复、过期后可再次处理、容量上限。
 *
 * <p>用可变时钟推进，不 sleep——去重窗口是 5 分钟，真实等待没有意义。
 */
class FeishuEventDedupTest {

    @Test
    void sameEventWithinWindow_isDuplicate() {
        FeishuEventDedup dedup = new FeishuEventDedup(new MutableClock());

        assertTrue(dedup.firstSeen("evt-1"));
        assertFalse(dedup.firstSeen("evt-1"), "第二次必须被拦下，否则会重复调模型");
        assertEquals(1, dedup.size());
    }

    @Test
    void differentEvents_areIndependent() {
        FeishuEventDedup dedup = new FeishuEventDedup(new MutableClock());

        assertTrue(dedup.firstSeen("evt-1"));
        assertTrue(dedup.firstSeen("evt-2"));
        assertEquals(2, dedup.size());
    }

    @Test
    void afterTtl_sameEventProcessedAgain() {
        MutableClock clock = new MutableClock();
        FeishuEventDedup dedup = new FeishuEventDedup(clock);
        assertTrue(dedup.firstSeen("evt-1"));

        clock.advance(Duration.ofMinutes(6));

        assertTrue(dedup.firstSeen("evt-1"), "超过 5 分钟窗口后应重新处理（飞书不会等这么久才重推）");
    }

    @Test
    void blankEventId_isTreatedAsFirstSeen() {
        FeishuEventDedup dedup = new FeishuEventDedup(new MutableClock());

        assertTrue(dedup.firstSeen(null));
        assertTrue(dedup.firstSeen(" "), "没有 event_id 时无法去重，只能放行");
    }

    @Test
    void capacity_isBounded() {
        FeishuEventDedup dedup = new FeishuEventDedup(new MutableClock());

        for (int i = 0; i < 2100; i++) {
            dedup.firstSeen("evt-" + i);
        }

        assertTrue(dedup.size() <= 2048, "容量必须有上限，异常流量不能把内存吃光，实际=" + dedup.size());
    }

    /** 可推进的 UTC 时钟：只服务本测试 */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration delta) {
            instant = instant.plus(delta);
        }
    }
}
