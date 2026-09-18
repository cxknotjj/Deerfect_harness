package com.dark.javaHarness.knowledge;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识目录监听器（app.knowledge.watch-enabled=true 时由 {@code KnowledgeConfig} 装配）：
 * NIO WatchService 监听知识目录变更，静默期（debounce）过后自动触发一次
 * {@link KnowledgeService#sync()}，免手动 POST /api/knowledge/sync——文档增删改落盘即生效。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>监听范围</b>：根目录 + 一级子目录（与扫描器两层口径一致）；监听期间新建的
 *       一级子目录在 ENTRY_CREATE 时补注册（更深层目录不在摄取范围内，不监听）。</li>
 *   <li><b>事件合并</b>：单守护线程 ScheduledExecutor 做 debounce——任一文件事件到达即
 *       重置静默期定时器，批量拷贝 / 编辑器原子写只触发一次 sync，不按事件数放大。</li>
 *   <li><b>不致命</b>：sync 抛「已有知识库同步在执行」时 info 跳过（手动 sync 持锁，
 *       静默期后的下一轮事件自然补齐）；监听线程异常仅 warn 并重注册后继续，绝不中断应用。
 *       目录不存在时启动即降级（warn + 不建线程）——启动时目录必须存在（Docker 挂载天然
 *       保证），不做监听热注册。</li>
 *   <li><b>事件不筛类型</b>：.md/.txt 之外的事件也触发 debounce（sync 增量幂等，无变更时
 *       空转成本为一次目录扫描 + 台账比对）；不做事件侧过滤，保持监听器无业务语义。</li>
 * </ul>
 *
 * <p>装配说明：不做 {@code @Lazy}——无消费方注入，懒加载会静默不启动；构造只解析目录、
 * 建 WatchService 与线程，不触碰 PG/嵌入端点，与「启动零依赖连接」降级语义不冲突。
 * 构造即开始监听，{@link #close()}（bean destroyMethod）停止线程并释放句柄。
 */
public class KnowledgeDirectoryWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDirectoryWatcher.class);

    /** 监听线程异常后的重试间隔（秒）：目录暂不可达等瞬时故障的恢复节奏 */
    private static final int RESUME_DELAY_SECONDS = 5;

    private final KnowledgeService knowledgeService;
    private final Path dir;
    private final int debounceSeconds;

    /** WatchService：异常恢复路径整体重建（关旧建新），故非 final；降级路径为 null */
    private volatile WatchService watchService;
    /** WatchKey → 其注册目录（事件 relativize 与新建子目录补注册需要） */
    private final Map<WatchKey, Path> keyDirs = new HashMap<>();

    /** debounce 定时器：事件到达取消未触发的旧任务再排新任务，静默期满触发 sync */
    private final ScheduledExecutorService debounceExecutor;
    private ScheduledFuture<?> pendingSync;

    /** 监听线程停止标记（close 置位；volatile 保证可见性） */
    private volatile boolean running = true;

    public KnowledgeDirectoryWatcher(KnowledgeService knowledgeService, Path dir, int debounceSeconds) {
        this.knowledgeService = knowledgeService;
        this.dir = dir;
        this.debounceSeconds = Math.max(1, debounceSeconds);
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kb-watcher-sync");
            t.setDaemon(true);
            return t;
        });
        if (!Files.isDirectory(dir)) {
            // 启动即降级：不做监听热注册（目录必须存在，Docker 挂载天然保证），warn 后不建监听
            log.warn("[knowledge] 目录监听未启动：知识目录不存在（{}）", dir.toAbsolutePath());
            this.watchService = null;
            return;
        }
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            // WatchService 不可用属环境级故障：降级为无监听（手动 sync 仍可用），不炸启动
            log.warn("[knowledge] 目录监听未启动：WatchService 创建失败（{}）——手动 sync 仍可用", e.toString());
            this.watchService = null;
            return;
        }
        registerTree();
        Thread loop = new Thread(this::watchLoop, "kb-watcher");
        loop.setDaemon(true);
        loop.start();
        log.info("[knowledge] 目录监听已启动：{}（静默期 {}s，变更后自动增量摄取）",
                dir.toAbsolutePath(), this.debounceSeconds);
    }

    /** 注册根目录 + 一级子目录（与扫描器两层口径一致）；单目录失败 warn 跳过不抛 */
    private void registerTree() {
        register(dir);
        try (var children = Files.list(dir)) {
            children.filter(Files::isDirectory).forEach(this::register);
        } catch (IOException e) {
            log.warn("[knowledge] 列举一级子目录失败（仅监听根目录）: {}", e.toString());
        }
    }

    /** 异常恢复：整体重建 WatchService 并重注册目录树（失败则下轮循环按节奏重试） */
    private void rebuildWatchService() {
        WatchService old = watchService;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
                // 旧实例已损坏，关闭失败忽略
            }
        }
        if (!Files.isDirectory(dir)) {
            // 根目录被移除：保留损坏实例让 take() 持续抛异常，维持恢复节奏直到目录回来
            log.warn("[knowledge] 知识目录仍不存在（{}），继续等待恢复", dir);
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("[knowledge] 目录监听恢复失败（下轮继续重试）: {}", e.toString());
            return;
        }
        keyDirs.clear();
        registerTree();
        log.info("[knowledge] 目录监听已恢复: {}", dir.toAbsolutePath());
    }

    private void register(Path path) {
        try {
            WatchKey key = path.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.OVERFLOW);
            keyDirs.put(key, path);
        } catch (Exception e) {
            log.warn("[knowledge] 监听注册失败（{}）: {}", path, e.toString());
        }
    }

    /** 监听主循环：take 阻塞 → 事件分流（新建一级子目录补注册）→ debounce 重置；异常恢复后继续 */
    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                WatchService service = watchService;
                if (service == null) {
                    return;
                }
                key = service.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // WatchService 损坏等环境级故障：warn → 间隔后整体重建（关旧建新 + 重注册）继续监听；
                // close 场景 running=false 直接退出
                if (!running) {
                    return;
                }
                log.warn("[knowledge] 目录监听异常（{}s 后尝试恢复）: {}", RESUME_DELAY_SECONDS, e.toString());
                sleepQuietly();
                if (!running) {
                    return;
                }
                rebuildWatchService();
                continue;
            }
            Path watched = keyDirs.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                try {
                    handleEvent(watched, event);
                } catch (Exception e) {
                    // 单事件处理失败不影响后续事件（监听不致命）
                    log.warn("[knowledge] 监听事件处理失败: {}", e.toString());
                }
            }
            if (!key.reset()) {
                // 目录被删除：注销其 key；根目录被删后由下一轮异常/空转恢复路径兜底
                keyDirs.remove(key);
                if (dir.equals(watched)) {
                    log.warn("[knowledge] 知识目录被移除（{}），{}s 后尝试重新监听", dir, RESUME_DELAY_SECONDS);
                    sleepQuietly();
                    if (running && Files.isDirectory(dir)) {
                        registerTree();
                    }
                }
            }
        }
    }

    /** 单事件处理：新建一级子目录补注册；任意事件重置 debounce 定时器 */
    private void handleEvent(Path watched, WatchEvent<?> event) {
        if (watched == null) {
            return;
        }
        Object context = event.context();
        if (context instanceof Path changed) {
            Path full = watched.resolve(changed);
            // 新建子目录补注册：仅 watched == 根目录时（一级），更深层不在摄取范围
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && dir.equals(watched) && Files.isDirectory(full)) {
                register(full);
            }
        }
        scheduleSync();
    }

    /** debounce 重置：取消未触发的旧 sync 任务，静默期满后触发一次 */
    private synchronized void scheduleSync() {
        if (pendingSync != null && !pendingSync.isDone()) {
            pendingSync.cancel(false);
        }
        pendingSync = debounceExecutor.schedule(this::triggerSync, debounceSeconds, TimeUnit.SECONDS);
    }

    /** 触发增量摄取：与手动 sync 同一入口同一语义（并发防护由服务层 syncLock 保证） */
    private void triggerSync() {
        try {
            KnowledgeSyncView view = knowledgeService.sync();
            if (view != null) {
                log.info("[knowledge] 目录变更自动摄取完成：扫描 {} 更新 {} 跳过 {}（chunk {}）",
                        view.scanned(), view.updated(), view.skipped(), view.chunks());
            }
        } catch (IllegalStateException e) {
            // 「已有同步在执行」= 手动 sync 持锁：本轮跳过，静默期后的下一轮事件自然补齐
            if (e.getMessage() != null && e.getMessage().contains("已有知识库同步在执行")) {
                log.info("[knowledge] 目录变更触发 sync 跳过（已有手动同步在执行）");
            } else {
                log.warn("[knowledge] 目录变更自动摄取失败（不影响监听）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[knowledge] 目录变更自动摄取失败（不影响监听）: {}", e.toString());
        }
    }

    private void sleepQuietly() {
        try {
            TimeUnit.SECONDS.sleep(RESUME_DELAY_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 停止监听：线程中断 + WatchService/定时器释放（bean destroyMethod 调用） */
    @Override
    public synchronized void close() {
        running = false;
        if (pendingSync != null) {
            pendingSync.cancel(false);
        }
        debounceExecutor.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("[knowledge] WatchService 关闭异常（忽略）: {}", e.toString());
            }
        }
    }
}
