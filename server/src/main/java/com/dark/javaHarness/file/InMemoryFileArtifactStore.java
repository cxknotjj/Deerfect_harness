package com.dark.javaHarness.file;

import com.dark.javaHarness.contract.api.FileArtifactStore;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 文件工件内存实现：短 TTL + 容量上限，进程重启即清空。
 *
 * <p>为什么不落盘：工件是「这一轮即将发出」的临时数据，落盘要处理清理、并发与磁盘配额，
 * 收益为零。代价是进程重启会丢——但重启时那一轮回复本来也发不出去了。
 *
 * <p>容量按插入顺序淘汰最旧条目：异常场景（渠道没消费）下不能让内存无界增长。
 */
@Component
public class InMemoryFileArtifactStore implements FileArtifactStore {

    /** 同时待发的文件数上限：一轮只有一个，10 足以覆盖并发会话 */
    private static final int MAX_ARTIFACTS = 10;

    /** 5 分钟没人取就丢：渠道发送在回复的同一轮里完成，超过这个时间说明链路已经断了 */
    private static final long TTL_MS = 300_000L;

    private final long ttlMs;
    private final java.util.function.LongSupplier clock;
    private final Map<String, FileArtifact> byId = new LinkedHashMap<>();
    private final Map<String, String> byTurn = new HashMap<>();

    public InMemoryFileArtifactStore() {
        this(TTL_MS, System::currentTimeMillis);
    }

    /** 测试用：注入固定时钟，避免 sleep 验证 TTL */
    InMemoryFileArtifactStore(long ttlMs, java.util.function.LongSupplier clock) {
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    @Override
    public synchronized String put(String turnId, String fileName, byte[] content) {
        cleanup();
        String oldId = byTurn.remove(turnId);
        if (oldId != null) {
            byId.remove(oldId);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        byId.put(id, new FileArtifact(id, turnId, fileName, content, clock.getAsLong()));
        byTurn.put(turnId, id);
        while (byId.size() > MAX_ARTIFACTS) {
            Iterator<Map.Entry<String, FileArtifact>> iterator = byId.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            String evictedId = iterator.next().getKey();
            iterator.remove();
            byTurn.values().removeIf(evictedId::equals);
        }
        return id;
    }

    @Override
    public synchronized Optional<FileArtifact> findByTurn(String turnId) {
        cleanup();
        String id = byTurn.get(turnId);
        if (id == null) {
            return Optional.empty();
        }
        FileArtifact artifact = byId.get(id);
        if (artifact == null) {
            byTurn.remove(turnId);
            return Optional.empty();
        }
        return Optional.of(artifact);
    }

    @Override
    public synchronized Optional<FileArtifact> take(String id) {
        cleanup();
        FileArtifact artifact = byId.remove(id);
        if (artifact == null) {
            return Optional.empty();
        }
        byTurn.remove(artifact.turnId(), id);
        return Optional.of(artifact);
    }

    /** 惰性清理过期工件（put/find/take 前调用，无需后台线程） */
    private void cleanup() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, FileArtifact>> iterator = byId.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, FileArtifact> entry = iterator.next();
            if (now - entry.getValue().createdAt() >= ttlMs) {
                iterator.remove();
                byTurn.remove(entry.getValue().turnId(), entry.getKey());
            }
        }
    }
}
