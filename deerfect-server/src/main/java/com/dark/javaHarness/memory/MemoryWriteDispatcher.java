package com.dark.javaHarness.memory;

import com.dark.javaHarness.memory.plugin.MemoryPlugin;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import com.dark.javaHarness.plugin.PluginCapability;
import com.dark.javaHarness.plugin.PluginRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 记忆写入调度器：把「一轮成功对话」投递给当前启用的记忆插件。
 *
 * <ul>
 *   <li>单线程守护池 + 有界队列 + DiscardPolicy：写入不阻塞对话线程，队列满时丢弃新任务；</li>
 *   <li>同 turnId 去重（有界 LRU）：COMPLEX 降级重答等路径不会重复写同一条记忆；</li>
 *   <li>任务入口捕获所有异常：mem0 故障不向主链路冒泡。</li>
 * </ul>
 *
 * <p>队列容量与去重容量来自 yaml（app.memory.*），本类不含默认业务数字。
 */
@Component
public class MemoryWriteDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteDispatcher.class);

    private final PluginRegistry pluginRegistry;
    private final ExecutorService executor;
    private final Map<String, Boolean> seenTurnIds;

    public MemoryWriteDispatcher(PluginRegistry pluginRegistry,
                                 @Value("${app.memory.write-queue-size:200}") int queueSize,
                                 @Value("${app.memory.dedupe-capacity:1024}") int dedupeCapacity) {
        this.pluginRegistry = pluginRegistry;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                runnable -> {
                    Thread thread = new Thread(runnable, "memory-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        this.seenTurnIds = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > dedupeCapacity;
            }
        };
    }

    /** 当前是否有启用的记忆插件（无插件时调用方跳过身份查询） */
    public boolean active() {
        return pluginRegistry.active(PluginCapability.MEMORY, MemoryPlugin.class).isPresent();
    }

    /** 投递一轮成功对话；重复 turnId 或队列满时静默丢弃 */
    public void submit(MemoryTurn turn) {
        if (turn == null || turn.turnId() == null || turn.turnId().isBlank() || !active()) {
            return;
        }
        if (!markSeen(turn.turnId())) {
            log.debug("[memory] 跳过重复 turnId={}", turn.turnId());
            return;
        }
        executor.execute(() -> dispatch(turn));
    }

    private void dispatch(MemoryTurn turn) {
        try {
            pluginRegistry.active(PluginCapability.MEMORY, MemoryPlugin.class)
                    .ifPresent(plugin -> plugin.remember(turn));
        } catch (Exception e) {
            log.warn("[memory] 插件写入异常 plugin=memory turnId={} errorClass={}",
                    turn.turnId(), e.getClass().getSimpleName());
        }
    }

    private boolean markSeen(String turnId) {
        synchronized (seenTurnIds) {
            return seenTurnIds.put(turnId, Boolean.TRUE) == null;
        }
    }
}
