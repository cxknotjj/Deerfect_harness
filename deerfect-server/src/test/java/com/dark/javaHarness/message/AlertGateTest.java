package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 告警抑制单测：连续 N 次才发、窗口内不重复、恢复补发。
 *
 * <p>时间用 {@link MutableClock} 推进，不 sleep——抑制窗口是 24 小时，真实等待没有意义。
 */
class AlertGateTest {

    private MutableClock clock;
    private AlertGate gate;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        gate = new AlertGate(3, 24, clock);
    }

    @Test
    void belowThreshold_silent() {
        assertFalse(gate.onFailure("qq"), "第 1 次失败不该打扰");
        assertFalse(gate.onFailure("qq"), "第 2 次仍不该打扰");
        assertEquals(2, gate.consecutiveFailures("qq"));
    }

    @Test
    void reachThreshold_alertsOnce() {
        gate.onFailure("qq");
        gate.onFailure("qq");

        assertTrue(gate.onFailure("qq"), "第 3 次放行第一条告警");
        assertTrue(gate.alerted("qq"));
    }

    @Test
    void withinThrottle_suppressedButStillCounting() {
        for (int i = 0; i < 3; i++) {
            gate.onFailure("qq");
        }
        clock.advance(Duration.ofHours(1));

        assertFalse(gate.onFailure("qq"), "24 小时窗口内同类不重复发");
        assertEquals(4, gate.consecutiveFailures("qq"), "抑制不影响计数——恢复判定要看到真实次数");
    }

    @Test
    void afterThrottle_alertsAgain() {
        for (int i = 0; i < 3; i++) {
            gate.onFailure("qq");
        }
        clock.advance(Duration.ofHours(25));

        assertTrue(gate.onFailure("qq"), "过了窗口仍故障，应该再提醒一次");
    }

    @Test
    void successAfterAlert_requestsRecovery() {
        for (int i = 0; i < 3; i++) {
            gate.onFailure("qq");
        }

        assertTrue(gate.onSuccess("qq"), "此前处于告警态，恢复要补一条");
        assertFalse(gate.alerted("qq"));
        assertEquals(0, gate.consecutiveFailures("qq"), "恢复后计数清零");
    }

    @Test
    void successWithoutAlert_noRecoveryMessage() {
        gate.onFailure("qq");

        assertFalse(gate.onSuccess("qq"), "没告过警就不该发「已恢复」——那会让告警失去可信度");
    }

    @Test
    void differentKeys_isolated() {
        for (int i = 0; i < 3; i++) {
            gate.onFailure("qq");
        }

        assertFalse(gate.onFailure("feishu"), "不同渠道各自计数");
        assertTrue(gate.alerted("qq"));
    }
}
