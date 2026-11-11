package com.dark.javaHarness.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.api.FileArtifactStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 文件工件库单测：按 turn 覆盖、take 取走即删、TTL 过期、容量淘汰。
 *
 * <p>时钟注入而不是 sleep：TTL 用真实时间验会让测试变慢且不稳定。
 */
class InMemoryFileArtifactStoreTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void put_thenFindByTurn_returnsArtifact() {
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(60_000, System::currentTimeMillis);

        String id = store.put("turn-1", "a.md", bytes("hello"));

        FileArtifactStore.FileArtifact artifact = store.findByTurn("turn-1").orElseThrow();
        assertEquals(id, artifact.id());
        assertEquals("a.md", artifact.fileName());
        assertArrayEquals(bytes("hello"), artifact.content());
    }

    @Test
    void put_sameTurn_replacesOldArtifact() {
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(60_000, System::currentTimeMillis);

        String first = store.put("turn-1", "a.md", bytes("1"));
        String second = store.put("turn-1", "b.md", bytes("2"));

        assertEquals(second, store.findByTurn("turn-1").orElseThrow().id());
        assertTrue(store.take(first).isEmpty(), "旧工件应被覆盖，不能留下孤儿");
    }

    @Test
    void take_removesArtifact() {
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(60_000, System::currentTimeMillis);
        String id = store.put("turn-1", "a.md", bytes("x"));

        assertTrue(store.take(id).isPresent());
        assertTrue(store.take(id).isEmpty(), "take 是一次性消费");
        assertTrue(store.findByTurn("turn-1").isEmpty());
    }

    @Test
    void expiredArtifacts_areDropped() {
        AtomicLong now = new AtomicLong(1_000);
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(5_000, now::get);
        store.put("turn-1", "a.md", bytes("x"));

        now.addAndGet(5_000);

        assertTrue(store.findByTurn("turn-1").isEmpty(), "超过 TTL 应视为不存在");
    }

    @Test
    void capacity_evictsOldest() {
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(60_000, System::currentTimeMillis);
        for (int i = 0; i < 12; i++) {
            store.put("turn-" + i, "f" + i + ".md", bytes("x"));
        }

        assertTrue(store.findByTurn("turn-0").isEmpty(), "最旧的应被淘汰");
        assertTrue(store.findByTurn("turn-11").isPresent(), "最新的必须还在");
    }

    @Test
    void find_missingTurn_isEmpty() {
        InMemoryFileArtifactStore store = new InMemoryFileArtifactStore(60_000, System::currentTimeMillis);

        Optional<FileArtifactStore.FileArtifact> artifact = store.findByTurn("nope");

        assertTrue(artifact.isEmpty());
    }
}
