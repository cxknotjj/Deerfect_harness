package com.dark.javaHarness.prompt;

import com.dark.javaHarness.domain.dto.ContextComposition;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 会话上下文快照的内存存储：sessionId → 最近一份快照。
 * 容量上限 200 个会话、TTL 30 分钟；写入覆盖同会话旧快照并刷新其顺序，
 * 读取时惰性清理过期项，超容量时淘汰最久未写入的会话。
 * 进程重启即清空（设计取舍：快照仅用于实时观测，不落库）。
 */
@Component
public class ContextCompositionStore {

    static final int MAX_SESSIONS = 200;
    static final Duration TTL = Duration.ofMinutes(30);

    private final Clock clock;
    private final int maxSessions;
    private final Duration ttl;
    /** 插入顺序 = 最近写入顺序（重写时先删后放，保证淘汰的是最久未写入者） */
    private final LinkedHashMap<String, Entry> snapshots = new LinkedHashMap<>();

    public ContextCompositionStore() {
        this(Clock.systemDefaultZone(), MAX_SESSIONS, TTL);
    }

    /** 包内可见构造：单测注入固定/可推进时钟，避免 sleep。 */
    ContextCompositionStore(Clock clock, int maxSessions, Duration ttl) {
        this.clock = clock;
        this.maxSessions = maxSessions;
        this.ttl = ttl;
    }

    /** 写入或覆盖快照；超过容量时淘汰最久未写入的会话。 */
    public synchronized void put(ContextComposition snapshot) {
        String sessionId = snapshot.sessionId();
        snapshots.remove(sessionId);
        snapshots.put(sessionId, new Entry(snapshot, clock.millis() + ttl.toMillis()));
        Iterator<Map.Entry<String, Entry>> iterator = snapshots.entrySet().iterator();
        while (snapshots.size() > maxSessions && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /** 读取快照；过期项视为不存在并惰性移除。 */
    public synchronized Optional<ContextComposition> get(String sessionId) {
        Entry entry = snapshots.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() <= clock.millis()) {
            snapshots.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    private record Entry(ContextComposition snapshot, long expiresAt) {
    }
}
