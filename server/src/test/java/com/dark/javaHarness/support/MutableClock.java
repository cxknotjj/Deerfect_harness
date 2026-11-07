package com.dark.javaHarness.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可手动推进的时钟：给依赖时间的组件做测试替身，避免用 {@code Thread.sleep} 等真实时间流逝。
 *
 * <p>典型用法（登录态过期、滑动续期、缓存 TTL）：
 * <pre>{@code
 * MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
 * LoginStateStore store = new LoginStateStore(Duration.ofMinutes(10), clock);
 * clock.advance(Duration.ofMinutes(11));
 * assertFalse(store.isValid(token));
 * }</pre>
 *
 * <p>只实现 {@link Clock} 的必要方法；{@link #withZone} 返回自身（本工具只服务 UTC 场景）。
 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant start) {
        this.instant = start;
    }

    /** 固定起点的便捷构造（2026-01-01T00:00:00Z） */
    public MutableClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

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

    /** 把当前时刻往前推进（不改变已有登记项的绝对过期时间戳） */
    public void advance(Duration delta) {
        instant = instant.plus(delta);
    }
}
