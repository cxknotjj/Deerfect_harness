package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.domain.dto.ContextComposition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 上下文快照存储单测：同会话覆盖、TTL 过期、容量淘汰、缺失返回空。
 * 时钟注入避免 sleep。
 */
class ContextCompositionStoreTest {

    @Test
    void put_overwritesSameSession() {
        ContextCompositionStore store = new ContextCompositionStore(
                new MutableClock(0, ZoneId.systemDefault()), 200, Duration.ofMinutes(30));
        store.put(snapshot("s1", 10));
        store.put(snapshot("s1", 20));

        assertEquals(20, store.get("s1").orElseThrow().estimatedTotalTokens());
    }

    @Test
    void get_expiresAfterTtl() {
        MutableClock clock = new MutableClock(0, ZoneId.systemDefault());
        ContextCompositionStore store = new ContextCompositionStore(
                clock, 200, Duration.ofMinutes(30));
        store.put(snapshot("s1", 10));

        clock.advance(Duration.ofMinutes(29));
        assertTrue(store.get("s1").isPresent(), "TTL 内仍可读取");

        clock.advance(Duration.ofMinutes(2));
        assertTrue(store.get("s1").isEmpty(), "超过 TTL 惰性清理");
    }

    @Test
    void put_evictsOldestSessionWhenOverCapacity() {
        MutableClock clock = new MutableClock(0, ZoneId.systemDefault());
        ContextCompositionStore store = new ContextCompositionStore(
                clock, 2, Duration.ofMinutes(30));
        store.put(snapshot("s1", 10));
        clock.advance(Duration.ofSeconds(1));
        store.put(snapshot("s2", 20));
        clock.advance(Duration.ofSeconds(1));
        store.put(snapshot("s3", 30));

        assertTrue(store.get("s1").isEmpty(), "超容量淘汰最久未写入的会话");
        assertEquals(20, store.get("s2").orElseThrow().estimatedTotalTokens());
        assertEquals(30, store.get("s3").orElseThrow().estimatedTotalTokens());
    }

    @Test
    void get_missingSessionReturnsEmpty() {
        ContextCompositionStore store = new ContextCompositionStore(
                new MutableClock(0, ZoneId.systemDefault()), 200, Duration.ofMinutes(30));

        assertTrue(store.get("missing").isEmpty());
    }

    private static ContextComposition snapshot(String sessionId, int tokens) {
        return new ContextComposition(sessionId, "general", "m1", "t1", 0, tokens,
                List.of(new ContextComposition.Part("system.role", "角色提示词", "system",
                        tokens, 100.0)));
    }

    /** 可推进的测试时钟（固定时区） */
    private static final class MutableClock extends Clock {

        private final ZoneId zone;
        private long millis;

        private MutableClock(long millis, ZoneId zone) {
            this.millis = millis;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(millis, zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
