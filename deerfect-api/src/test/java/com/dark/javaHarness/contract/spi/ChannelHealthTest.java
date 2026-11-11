package com.dark.javaHarness.contract.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 渠道状态快照的单测：四态映射与两个布尔投影。
 *
 * <p>这层映射是「探活告警会不会误报」的总开关：`UNKNOWN` 若被折成 DOWN，邮件渠道
 * 7 天没投递就会变成每周一条假警报。所以三种旧式二元组组合都单独断言。
 */
class ChannelHealthTest {

    @Test
    void legacyDisabled_mapsToDisabled() {
        ChannelHealth.Snapshot snapshot = new ChannelHealth.Snapshot(false, true, "关掉了");

        assertEquals(ChannelHealth.Status.DISABLED, snapshot.status(), "enabled=false 优先于 available");
        assertFalse(snapshot.enabled());
        assertFalse(snapshot.available());
    }

    @Test
    void legacyEnabledButUnavailable_mapsToDown() {
        ChannelHealth.Snapshot snapshot = new ChannelHealth.Snapshot(true, false, "不可达");

        assertEquals(ChannelHealth.Status.DOWN, snapshot.status());
        assertTrue(snapshot.enabled());
        assertFalse(snapshot.available());
    }

    @Test
    void legacyEnabledAndAvailable_mapsToUp() {
        ChannelHealth.Snapshot snapshot = new ChannelHealth.Snapshot(true, true, "可达");

        assertEquals(ChannelHealth.Status.UP, snapshot.status());
        assertTrue(snapshot.enabled());
        assertTrue(snapshot.available());
    }

    @Test
    void unknown_isNeitherEnabledNorAvailable() {
        ChannelHealth.Snapshot snapshot = new ChannelHealth.Snapshot(ChannelHealth.Status.UNKNOWN, "未知");

        assertTrue(snapshot.enabled(), "UNKNOWN 是「已启用但不知道」，不是「关掉了」");
        assertFalse(snapshot.available(), "不知道不能报可用——那是假装健康");
    }

    @Test
    void nulls_areNormalized() {
        ChannelHealth.Snapshot snapshot = new ChannelHealth.Snapshot(null, null);

        assertEquals(ChannelHealth.Status.UNKNOWN, snapshot.status());
        assertEquals("", snapshot.detail(), "detail 为 null 会让日志与前端出现 null 字样");
    }

    @Test
    void defaultChannelId_isBlank() {
        ChannelHealth health = new ChannelHealth() {
            @Override
            public String channelName() {
                return "测试渠道";
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(true, true, "可达");
            }
        };

        assertEquals("", health.channelId(), "未覆盖时退化为空串；探活告警据此跳过「排除本渠道」");
    }
}
