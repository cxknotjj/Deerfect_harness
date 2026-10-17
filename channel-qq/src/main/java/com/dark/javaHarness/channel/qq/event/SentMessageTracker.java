package com.dark.javaHarness.channel.qq.event;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人已发消息记录（内存 TTL）：messageId → 发送时刻，外加「目标 → 最后一条 id」。
 * 供两处消费：回复触发判定（入站 reply 段命中的 messageId 是否为机器人所发）、
 * /recall 撤回本会话/本群最近一条。容量超阈值按 TTL 清扫一次，无后台线程、零外部依赖。
 */
public class SentMessageTracker {

    private static final long TTL_MS = Duration.ofMinutes(30).toMillis();

    /** 容量软上限：达到即触发一次过期清扫 */
    private static final int SWEEP_THRESHOLD = 10_000;

    /** messageId → 发送时刻 */
    private final ConcurrentHashMap<Long, Long> sent = new ConcurrentHashMap<>();

    /** 目标键（private:<uid> / group:<gid>）→ 最后一条 messageId */
    private final ConcurrentHashMap<String, Long> lastByTarget = new ConcurrentHashMap<>();

    /** 记录一条已发消息（messageId 为空=发送失败，忽略） */
    public void record(String targetKey, Long messageId) {
        if (messageId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (sent.size() >= SWEEP_THRESHOLD) {
            sent.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        }
        sent.put(messageId, now);
        lastByTarget.put(targetKey, messageId);
    }

    /** 该 messageId 是否为我方近期所发（TTL 内） */
    public boolean contains(Long messageId) {
        if (messageId == null) {
            return false;
        }
        Long ts = sent.get(messageId);
        return ts != null && System.currentTimeMillis() - ts <= TTL_MS;
    }

    /** 目标最近一条已发 messageId（TTL 外或不存在返回 null） */
    public Long lastId(String targetKey) {
        Long id = lastByTarget.get(targetKey);
        return id != null && contains(id) ? id : null;
    }

    /** /recall 撤回后移除该 id（lastId 用 contains 兜底，移除后不再命中同一 id，避免重复 delete） */
    public void forget(Long messageId) {
        if (messageId != null) {
            sent.remove(messageId);
        }
    }
}
