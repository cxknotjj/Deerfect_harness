package com.dark.javaHarness.memory.plugin;

import com.dark.javaHarness.plugin.Plugin;
import java.util.List;

/**
 * 长期记忆扩展点：检索注入与异步写入两类能力。
 *
 * <p>实现必须自行消化外部服务故障：{@link #search} 失败返回空列表，{@link #remember} 失败只记录，
 * 不得向对话主流程抛异常（外部系统属于系统边界）。
 */
public interface MemoryPlugin extends Plugin {

    /** 检索与当前用户消息相关的记忆；无结果或调用失败返回空列表 */
    List<MemoryHit> search(MemoryQuery query);

    /** 写入一轮对话；由异步调度器调用，实现内部不阻塞调用线程 */
    void remember(MemoryTurn turn);
}
