package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SentMessageTracker：记录后 contains 命中；未知/null 不命中；lastId 返回目标最近一条。 */
class SentMessageTrackerTest {

    private final SentMessageTracker tracker = new SentMessageTracker();

    @Test
    void recordThenContains() {
        tracker.record("group:88", 500L);
        assertTrue(tracker.contains(500L));
    }

    @Test
    void unknownOrNullNotContained() {
        assertFalse(tracker.contains(999L));
        assertFalse(tracker.contains(null));
    }

    @Test
    void lastIdPerTarget() {
        tracker.record("group:88", 500L);
        tracker.record("group:88", 501L);
        tracker.record("private:10001", 601L);
        assertEquals(501L, tracker.lastId("group:88"));
        assertEquals(601L, tracker.lastId("private:10001"));
        assertNull(tracker.lastId("group:999"));
    }

    @Test
    void nullMessageIdIgnored() {
        tracker.record("group:88", null);
        assertNull(tracker.lastId("group:88"));
    }
}
