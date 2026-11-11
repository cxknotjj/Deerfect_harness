package com.dark.javaHarness.knowledge;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KnowledgeDirectoryWatcher 集成单测（真实 WatchService + 临时目录）：写入/删除触发
 * debounce 后 sync、事件风暴合并为一次、目录缺失降级不炸、新建一级子目录补注册、
 * sync 异常后监听继续工作。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDirectoryWatcherTest {

    /** 测试用静默期（秒）：真实 debounce 时序，1s 触发 + 10s 超时上限兼顾稳定与速度 */
    private static final int DEBOUNCE_SECONDS = 1;

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeService knowledgeService;

    private KnowledgeDirectoryWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private KnowledgeSyncView view() {
        return new KnowledgeSyncView(0, 0, 0, 0);
    }

    private void startWatcher() {
        watcher = new KnowledgeDirectoryWatcher(knowledgeService, tempDir, DEBOUNCE_SECONDS);
    }

    @Test
    void writeFile_triggersSyncAfterDebounce() throws IOException {
        when(knowledgeService.sync()).thenReturn(view());
        startWatcher();

        Files.writeString(tempDir.resolve("a.md"), "# 标题\n正文");

        verify(knowledgeService, timeout(10_000)).sync();
    }

    @Test
    void eventStorm_mergesIntoSingleSync() throws IOException {
        // 事件风暴去抖：连续 3 个写事件只触发一次 sync（静默期后无第二轮）
        when(knowledgeService.sync()).thenReturn(view());
        startWatcher();

        Files.writeString(tempDir.resolve("a.md"), "内容a");
        Files.writeString(tempDir.resolve("b.md"), "内容b");
        Files.writeString(tempDir.resolve("c.md"), "内容c");

        verify(knowledgeService, timeout(10_000)).sync();
        // 静默期 + 余量内不得出现第二次：during() 在整个窗口内持续轮询，
        // 期间一旦多出一次调用立即失败（sleep 只在醒来那一刻抽查，可能漏判）
        await().during(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .atMost(DEBOUNCE_SECONDS + 2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(knowledgeService, times(1)).sync());
    }

    @Test
    void deleteFile_triggersSyncForOrphanCleanup() throws IOException {
        when(knowledgeService.sync()).thenReturn(view());
        Files.writeString(tempDir.resolve("a.md"), "内容");
        // 目录注册在构造函数内同步完成（registerTree 先于 watch 线程启动），
        // 事件由 WatchService 缓冲，无需等「注册稳定」
        startWatcher();

        Files.delete(tempDir.resolve("a.md"));

        verify(knowledgeService, timeout(10_000)).sync();
    }

    @Test
    void newSubDirectory_registeredAndWatched() throws IOException {
        when(knowledgeService.sync()).thenReturn(view());
        startWatcher();
        // 监听期间新建一级子目录 → 补注册 → 子目录内文件变更仍触发 sync。
        // 补注册由 watchLoop 异步完成，没有可观测的就绪信号：改成重试写入，
        // 而不是睡 500ms 赌它已完成（真实场景同样可能合并/丢事件）
        Path sub = tempDir.resolve("java");
        Files.createDirectory(sub);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Files.writeString(sub.resolve("spring.md"), "# Spring\n正文");
            verify(knowledgeService, timeout(1_000)).sync();
        });
    }

    @Test
    void missingDirectory_degradesWithoutSync() {
        // 目录不存在：构造降级（不建监听线程），不抛异常也不触发 sync
        watcher = new KnowledgeDirectoryWatcher(knowledgeService, tempDir.resolve("ghost"), DEBOUNCE_SECONDS);

        await().during(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .atMost(DEBOUNCE_SECONDS + 2, TimeUnit.SECONDS)
                .untilAsserted(() -> verifyNoInteractions(knowledgeService));
    }

    @Test
    void syncFailure_watcherKeepsWorking() throws IOException {
        // 监听不致命：sync 抛异常仅告警，下一轮事件仍正常触发
        when(knowledgeService.sync()).thenThrow(new RuntimeException("pg down")).thenReturn(view());
        startWatcher();

        Files.writeString(tempDir.resolve("a.md"), "第一轮（失败）");
        verify(knowledgeService, timeout(10_000)).sync();

        Files.writeString(tempDir.resolve("b.md"), "第二轮（成功）");
        verify(knowledgeService, timeout(10_000).times(2)).sync();
    }

    @Test
    void close_stopsTriggeringSync() throws IOException {
        when(knowledgeService.sync()).thenReturn(view());
        startWatcher();
        Files.writeString(tempDir.resolve("a.md"), "内容");
        verify(knowledgeService, timeout(10_000)).sync();

        watcher.close();
        // close 后写文件不应再触发 sync（watchService 已关闭、线程已停）
        Files.writeString(tempDir.resolve("b.md"), "close 后写入");
        await().during(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .atMost(DEBOUNCE_SECONDS + 2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(knowledgeService, times(1)).sync());
    }
}
