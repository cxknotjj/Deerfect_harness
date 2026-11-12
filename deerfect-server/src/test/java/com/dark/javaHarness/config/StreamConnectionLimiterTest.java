package com.dark.javaHarness.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.exception.ConcurrentRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 流式连接数限制器单测：
 * - 上限内计数放行，超限抛 {@link ConcurrentRequestException} 且计数回退（名额未泄漏）
 * - release 带下限保护（多释放不越 0、无副作用）
 * - 0 = 不限制（直通放行，与项目预算口径一致）
 * - 并发抢占终态归零（无泄漏）：超限拒绝不破坏计数一致性
 */
class StreamConnectionLimiterTest {

    @Test
    void tryAcquire_withinLimit_passesAndCounts() {
        StreamConnectionLimiter limiter = new StreamConnectionLimiter(2);
        limiter.tryAcquire();
        limiter.tryAcquire();
        ConcurrentRequestException e = assertThrows(ConcurrentRequestException.class, limiter::tryAcquire,
                "第 3 次占用应超限拒绝");
        assertTrue(e.getMessage().contains("上限 2"), "异常消息应含上限值: " + e.getMessage());
    }

    @Test
    void release_freesSlotAndNeverGoesNegative() {
        StreamConnectionLimiter limiter = new StreamConnectionLimiter(1);
        limiter.tryAcquire();
        limiter.release();
        assertDoesNotThrow(limiter::tryAcquire, "释放后应可再次占用");
        // 多释放无副作用（下限保护：计数不低于 0）
        limiter.release();
        limiter.release();
        assertDoesNotThrow(limiter::tryAcquire, "多释放不得把计数打成负数导致永久拒绝");
    }

    @Test
    void zeroLimit_meansUnlimited() {
        StreamConnectionLimiter limiter = new StreamConnectionLimiter(0);
        for (int i = 0; i < 100; i++) {
            assertDoesNotThrow(limiter::tryAcquire, "0 = 不限制，直通放行");
        }
    }

    /** 并发 10 抢上限 4：终态计数必须归零（无泄漏），且至少 4 个任务成功占用 */
    @Test
    void concurrentAcquireRelease_noLeak() throws Exception {
        int threads = 10;
        StreamConnectionLimiter limiter = new StreamConnectionLimiter(4);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        limiter.tryAcquire();
                        accepted.incrementAndGet();
                        limiter.release();
                    } catch (ConcurrentRequestException expected) {
                        // 超限拒绝：tryAcquire 内部已回退计数，不影响终态归零
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(accepted.get() >= 4, "至少 4 个任务应成功占用（上限 4），实际 " + accepted.get());
        assertDoesNotThrow(limiter::tryAcquire, "全部任务结束后计数必须归零（无泄漏）");
    }
}
