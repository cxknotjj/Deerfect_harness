package com.dark.javaHarness.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 登录态登记簿单测：签发/校验/滑动续期/过期惰性清理/吊销。
 * 登录态 = 「登录凭证 → 过期时间」映射，与聊天会话（session_messages，MySQL）无关。
 *
 * <p>时间一律用 {@link MutableClock} 推进，不 sleep：过期与续期都是「时间轴上的位置」，
 * 直接跳到目标时刻即可。固定睡眠在慢机 / CI 高负载下会 flaky，且一条用例白等上百毫秒。
 */
class LoginStateStoreTest {

    private static MutableClock clock() {
        return new MutableClock();
    }

    /** 签发即可用；两次签发不重复 */
    @Test
    void create_thenValid_andUnique() {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        String a = store.create();
        String b = store.create();
        assertTrue(store.isValid(a));
        assertTrue(store.isValid(b));
        assertNotEquals(a, b);
    }

    /** 未知/空白凭证一律无效 */
    @Test
    void unknownOrBlankToken_invalid() {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        assertFalse(store.isValid(null));
        assertFalse(store.isValid(""));
        assertFalse(store.isValid("no-such-token"));
    }

    /** 滑动续期：临近过期的有效访问把过期时间推后，活跃用户不被登出 */
    @Test
    void validAccess_slidesExpiry() {
        MutableClock clock = clock();
        LoginStateStore store = new LoginStateStore(Duration.ofMillis(200), clock);
        String token = store.create();

        clock.advance(Duration.ofMillis(150)); // 逼近过期但未过期
        assertTrue(store.isValid(token), "未过期应通过，并续期至 now+200ms");

        clock.advance(Duration.ofMillis(150)); // 距签发已 300ms > 原始 200ms
        assertTrue(store.isValid(token), "续期后仍未过期，活跃访问不登出");
    }

    /** 过期后无效；失效项被惰性清理（size 收敛） */
    @Test
    void expired_invalid_andPurged() {
        MutableClock clock = clock();
        LoginStateStore store = new LoginStateStore(Duration.ofMillis(80), clock);
        String token = store.create();
        assertTrue(store.isValid(token));

        clock.advance(Duration.ofMillis(120));
        assertFalse(store.isValid(token), "超过 TTL 后失效");
        assertEquals(0, store.size(), "校验失败路径已惰性移除");
    }

    /** revoke 单独吊销（登出），revokeAll 全量吊销（全局踢下线） */
    @Test
    void revoke_works() {
        LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10));
        String a = store.create();
        String b = store.create();
        store.revoke(a);
        assertFalse(store.isValid(a));
        assertTrue(store.isValid(b));
        assertEquals(1, store.revokeAll());
        assertFalse(store.isValid(b));
        assertEquals(0, store.size());
    }

    /** 签发时顺带全量清扫过期项，避免长期运行下死亡凭证堆积 */
    @Test
    void create_purgesExpired() {
        MutableClock clock = clock();
        LoginStateStore store = new LoginStateStore(Duration.ofMillis(80), clock);
        String stale = store.create();

        clock.advance(Duration.ofMillis(120));
        store.create(); // 新签发触发清扫
        assertEquals(1, store.size(), "过期项被清掉，只剩新签发的这条");
        assertFalse(store.isValid(stale));
    }
}
