package com.dark.javaHarness.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 登录失败计数的清理：只清「长时间没有新失败」的 IP 条目，锁定窗口内的条目保留。
 *
 * <p>背景：失败计数只在登录成功时移除，被扫描器刷过的 IP 会永久留一条，公网暴露下 map
 * 只增不减。清理按「最后一次失败时刻」判定，因此测试用包可见的
 * {@link AuthController#purgeStaleFailures(long)} 直接传 cutoff，不依赖真实时间流逝。
 */
class AuthControllerFailureCleanupTest {

    private static AuthController controller() {
        return new AuthController("test-password", 1, 5, 60, mock(LoginStateStore.class));
    }

    private static MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Forwarded-For", ip);
        return request;
    }

    private static void failOnce(AuthController controller, String ip) {
        controller.login(new AuthController.LoginRequest("wrong"), requestFrom(ip));
    }

    @Test
    void purge_removesEntriesOutsideRetentionWindow() {
        AuthController controller = controller();
        failOnce(controller, "10.0.0.1");
        failOnce(controller, "10.0.0.2");

        // 刚失败：仍在一小时保留窗口内，不清
        assertEquals(0, controller.purgeStaleFailures(System.currentTimeMillis() - 3600_000L));

        // cutoff 挪到未来：两条都算过期
        assertEquals(2, controller.purgeStaleFailures(System.currentTimeMillis() + 1));

        // 清理后重复调用不再有可清条目
        assertEquals(0, controller.purgeStaleFailures(System.currentTimeMillis() + 1));
    }

    @Test
    void purge_keepsLockedEntryWithinWindow() {
        AuthController controller = controller();
        for (int i = 0; i < 5; i++) {
            failOnce(controller, "10.0.0.3"); // 达到阈值 → 进入锁定
        }

        // 锁定中的条目刚失败过：窗口内保留
        assertEquals(0, controller.purgeStaleFailures(System.currentTimeMillis() - 3600_000L));

        // 超过保留窗口后可以清掉（锁本身早已过期，条目已无意义）
        assertEquals(1, controller.purgeStaleFailures(System.currentTimeMillis() + 1));
    }

    @Test
    void scheduledPurge_keepsFreshEntries() {
        AuthController controller = controller();
        failOnce(controller, "10.0.0.4");

        // 调度入口用当前时间减保留时长作 cutoff：刚失败的条目不会被清
        controller.purgeStaleFailures();
        assertEquals(0, controller.purgeStaleFailures(System.currentTimeMillis() - 3600_000L));
    }
}
