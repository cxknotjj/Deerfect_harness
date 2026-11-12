package com.dark.javaHarness.observability;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.service.GoalService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Goal 停滞看门狗单测：阈值换算 cutoff、调用条件更新、非法阈值跳过。
 */
class StaleGoalWatchdogTest {

    @Test
    void scan_marksStagnantGoalsWithCutoff() {
        GoalService goalService = mock(GoalService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
        when(goalService.failStagnant(eq(LocalDateTime.of(2026, 11, 2, 23, 30)), anyString()))
                .thenReturn(2);

        new StaleGoalWatchdog(goalService, 30, clock).scan();

        verify(goalService).failStagnant(
                eq(LocalDateTime.of(2026, 11, 2, 23, 30)),
                eq("执行停滞：超过 30 分钟无进展"));
    }

    @Test
    void scan_skipsWhenThresholdIsNotPositive() {
        GoalService goalService = mock(GoalService.class);

        new StaleGoalWatchdog(goalService, 0, Clock.systemUTC()).scan();

        verifyNoInteractions(goalService);
    }

    @Test
    void scan_isIdempotentWhenNoGoalIsStagnant() {
        GoalService goalService = mock(GoalService.class);
        when(goalService.failStagnant(eq(LocalDateTime.of(2026, 11, 2, 23, 30)), anyString()))
                .thenReturn(0);

        StaleGoalWatchdog watchdog = new StaleGoalWatchdog(goalService, 30,
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC));
        watchdog.scan();
        watchdog.scan();

        verify(goalService, times(2)).failStagnant(
                eq(LocalDateTime.of(2026, 11, 2, 23, 30)), anyString());
    }
}
