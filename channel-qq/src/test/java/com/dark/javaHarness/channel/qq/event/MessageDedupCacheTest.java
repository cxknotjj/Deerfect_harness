package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * message_id 幂等去重单测：重复投递只放行一次，无 id 事件不参与去重。
 */
class MessageDedupCacheTest {

    @Test
    void sameMessageId_onlyFirstAcquirePasses() {
        MessageDedupCache cache = new MessageDedupCache();

        assertTrue(cache.tryAcquire("10001"), "首次出现应放行");
        assertFalse(cache.tryAcquire("10001"), "TTL 内重复应丢弃");
        assertFalse(cache.tryAcquire("10001"), "持续重复仍应丢弃");
    }

    @Test
    void differentMessageIds_allPass() {
        MessageDedupCache cache = new MessageDedupCache();

        assertTrue(cache.tryAcquire("a"));
        assertTrue(cache.tryAcquire("b"));
    }

    @Test
    void nullMessageId_alwaysPasses() {
        MessageDedupCache cache = new MessageDedupCache();

        assertTrue(cache.tryAcquire(null), "无 id 事件无法去重，直接放行");
        assertTrue(cache.tryAcquire(null));
    }
}
