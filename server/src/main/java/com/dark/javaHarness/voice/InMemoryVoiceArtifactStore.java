package com.dark.javaHarness.voice;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 语音工件内存实现：短 TTL + 容量上限，进程重启即清空。
 *
 * <p>只保存本轮待发送的音频；渠道 take 后立即删除。容量按插入顺序淘汰最旧条目，
 * 防止异常场景下无人消费导致内存堆积。
 */
@Component
public class InMemoryVoiceArtifactStore implements VoiceArtifactStore {

    private static final int MAX_ARTIFACTS = 20;

    private final long ttlMs;
    private final LongSupplier clock;
    private final Map<String, VoiceArtifact> byId = new LinkedHashMap<>();
    private final Map<String, String> byTurn = new HashMap<>();

    /** Spring 装配入口（多构造需显式标注）：TTL 取自配置。 */
    @Autowired
    public InMemoryVoiceArtifactStore(TtsProperties props) {
        this(Math.max(1, props.getArtifactTtlSeconds()) * 1000L, System::currentTimeMillis);
    }

    /** 测试用：注入固定时钟，避免 sleep 验证 TTL。 */
    InMemoryVoiceArtifactStore(long ttlMs, LongSupplier clock) {
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    @Override
    public synchronized String put(String turnId, String text, byte[] audio, String format) {
        cleanup();
        String oldId = byTurn.remove(turnId);
        if (oldId != null) {
            byId.remove(oldId);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        VoiceArtifact artifact = new VoiceArtifact(
                id, turnId, text, audio, format, clock.getAsLong());
        byId.put(id, artifact);
        byTurn.put(turnId, id);
        while (byId.size() > MAX_ARTIFACTS) {
            Iterator<Map.Entry<String, VoiceArtifact>> iterator = byId.entrySet().iterator();
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
    public synchronized Optional<VoiceArtifact> findByTurn(String turnId) {
        cleanup();
        String id = byTurn.get(turnId);
        if (id == null) {
            return Optional.empty();
        }
        VoiceArtifact artifact = byId.get(id);
        if (artifact == null) {
            byTurn.remove(turnId);
            return Optional.empty();
        }
        return Optional.of(artifact);
    }

    @Override
    public synchronized Optional<VoiceArtifact> take(String id) {
        cleanup();
        VoiceArtifact artifact = byId.remove(id);
        if (artifact == null) {
            return Optional.empty();
        }
        byTurn.remove(artifact.turnId(), id);
        return Optional.of(artifact);
    }

    /** 惰性清理过期工件（put/find/take 前调用，无需后台线程）。 */
    private void cleanup() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, VoiceArtifact>> iterator = byId.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VoiceArtifact> entry = iterator.next();
            VoiceArtifact artifact = entry.getValue();
            if (now - artifact.createdAt() >= ttlMs) {
                iterator.remove();
                byTurn.remove(artifact.turnId(), entry.getKey());
            }
        }
    }
}
