package com.dark.javaHarness.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 语音工件内存库：turnId 覆盖、一次性取走、TTL 过期与容量淘汰。 */
class InMemoryVoiceArtifactStoreTest {

    private static final byte[] AUDIO = {1, 2, 3};

    @Test
    void put_findByTurn_thenTake_removesArtifact() {
        InMemoryVoiceArtifactStore store = new InMemoryVoiceArtifactStore(60_000L, () -> 1_000L);

        String id = store.put("turn-1", "你好", AUDIO, "mp3");

        assertEquals(id, store.findByTurn("turn-1").orElseThrow().id(), "findByTurn 只读命中");
        assertTrue(store.findByTurn("turn-1").isPresent(), "findByTurn 不删除");
        assertTrue(store.take(id).isPresent(), "take 取走工件");
        assertFalse(store.findByTurn("turn-1").isPresent(), "take 后 turn 索引一并清理");
        assertFalse(store.take(id).isPresent(), "同一 id 不能重复取走");
    }

    @Test
    void put_sameTurn_overwritesOldArtifact() {
        InMemoryVoiceArtifactStore store = new InMemoryVoiceArtifactStore(60_000L, () -> 1_000L);

        String first = store.put("turn-1", "第一段", AUDIO, "mp3");
        String second = store.put("turn-1", "第二段", AUDIO, "mp3");

        assertTrue(store.take(first).isEmpty(), "同一轮重复合成时旧工件立即失效");
        assertEquals(second, store.findByTurn("turn-1").orElseThrow().id());
        assertEquals("第二段", store.findByTurn("turn-1").orElseThrow().text());
    }

    @Test
    void findByTurn_expiredByTtl_returnsEmpty() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryVoiceArtifactStore store = new InMemoryVoiceArtifactStore(10_000L, now::get);

        String id = store.put("turn-1", "你好", AUDIO, "mp3");
        now.set(11_000L);

        assertTrue(store.findByTurn("turn-1").isEmpty(), "超过 TTL 后按 turn 查不到");
        assertTrue(store.take(id).isEmpty(), "过期工件不能再被渠道取走");
    }

    @Test
    void put_beyondCapacity_evictsOldest() {
        InMemoryVoiceArtifactStore store = new InMemoryVoiceArtifactStore(60_000L, () -> 1_000L);

        String oldest = store.put("turn-0", "最早", AUDIO, "mp3");
        for (int i = 1; i <= 20; i++) {
            store.put("turn-" + i, "第" + i + "段", AUDIO, "mp3");
        }

        assertTrue(store.take(oldest).isEmpty(), "超过容量上限时按插入顺序淘汰最旧工件");
        assertTrue(store.findByTurn("turn-20").isPresent(), "最新工件保留");
    }
}
