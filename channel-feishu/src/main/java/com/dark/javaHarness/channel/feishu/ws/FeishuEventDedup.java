package com.dark.javaHarness.channel.feishu.ws;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * event_id 去重：飞书在弱网或重连后会重推同一事件。
 *
 * <p>为什么用 {@link LinkedHashMap} 而不是 Caffeine：只需要「5 分钟内不重复」这一个语义，
 * 引入缓存库会顺带带来淘汰策略、统计、线程模型三件要理解的东西。这里显式控制容量与 TTL，
 * 行为一眼可见。
 *
 * <p>去重发生在**入队前**：重推的事件若进了队列，会消耗一次模型调用并重复回复。
 */
public class FeishuEventDedup {

    /** 同一 event_id 的去重窗口；飞书重推通常发生在秒级，5 分钟足够 */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 容量上限：异常流量下宁可提前忘掉旧事件，也不能让 map 无界增长 */
    private static final int MAX_ENTRIES = 2048;

    private final Map<String, Long> seen = new LinkedHashMap<>(64, 0.75f, false);
    private final Clock clock;

    public FeishuEventDedup() {
        this(Clock.systemUTC());
    }

    /** 测试用：注入可控时钟 */
    public FeishuEventDedup(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记录并判断是否首次出现。
     *
     * @return true = 首次（应继续处理）；false = 窗口内重复（应丢弃）
     */
    public synchronized boolean firstSeen(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // 没有 event_id 就无法去重；按「首次」处理，但调用方会记 debug 日志
            return true;
        }
        long now = clock.millis();
        evictExpired(now);
        Long previous = seen.get(eventId);
        if (previous != null && now - previous < TTL.toMillis()) {
            return false;
        }
        if (seen.size() >= MAX_ENTRIES) {
            Iterator<String> eldest = seen.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        seen.put(eventId, now);
        return true;
    }

    /** 当前缓存条数（排障与测试用） */
    public synchronized int size() {
        return seen.size();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        long ttl = TTL.toMillis();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= ttl) {
                it.remove();
            } else {
                // 插入顺序即时间顺序，遇到第一个未过期即可停
                return;
            }
        }
    }
}
