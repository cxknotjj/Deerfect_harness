package com.dark.javaHarness.channel.feishu.ws;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「正在处理」回执：一轮对话拖到超过阈值还没结束时，给原消息贴一个表情回应，结束后撤销。
 *
 * <p><b>为什么是延迟贴而不是立刻贴</b>：绝大多数回复在 2~5 秒内完成，立刻贴会让每条消息
 * 都闪一下表情，反而更吵。延迟窗口（默认 3 秒）只覆盖真正慢的那部分，快速回复零调用。
 *
 * <p><b>为什么不发「收到，处理中」文本</b>：群里每条消息都回的模式下，文本回执会把消息量翻倍；
 * 表情回应不占消息位，也不需要用户点开。
 *
 * <p>线程安全：{@link #clear()} 可能在贴表情的同时被调用（回复先完成）。两个动作都走
 * {@link #remove()}（synchronized），贴完之后再检查一次 finished，避免留下一个永远不撤的回执。
 */
final class FeishuReceipt {

    private static final Logger log = LoggerFactory.getLogger(FeishuReceipt.class);

    private final FeishuApiClient api;
    private final String messageId;
    private final String emojiType;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /** 已贴上的 reaction_id；null = 还没贴上或已撤销 */
    private volatile String reactionId;

    /** 回执权限缺失只提醒一次：每条消息刷一行 warn 没有意义 */
    private static final AtomicBoolean WARNED_PERMISSION = new AtomicBoolean(false);

    FeishuReceipt(Executor executor, FeishuApiClient api, String messageId, String emojiType, long delayMs) {
        this.api = api;
        this.messageId = messageId;
        this.emojiType = emojiType;
        executor.execute(() -> {
            if (!sleep(delayMs) || finished.get()) {
                return;
            }
            FeishuApiClient.Reaction added = api.addReaction(messageId, emojiType);
            if (!added.ok()) {
                if (WARNED_PERMISSION.compareAndSet(false, true)) {
                    log.warn("[feishu] 处理回执添加失败（多半缺 im:message.reaction:write 权限）：{}",
                            added.reason());
                } else {
                    log.debug("[feishu] 处理回执添加失败：{}", added.reason());
                }
                return;
            }
            reactionId = added.reactionId();
            if (finished.get()) {
                // 回复在贴表情的同时完成：立刻撤回，别留一个卡住的表情
                remove();
            }
        });
    }

    /** 一轮结束（成功/失败/超时都算）：撤销已贴的回执，并取消尚未贴的那次 */
    void clear() {
        finished.set(true);
        remove();
    }

    private synchronized void remove() {
        String id = reactionId;
        if (id == null) {
            return;
        }
        reactionId = null;
        FeishuApiClient.Reaction deleted = api.deleteReaction(messageId, id);
        if (!deleted.ok()) {
            log.debug("[feishu] 处理回执撤销失败（不影响回复）：{}", deleted.reason());
        }
    }

    /** 延迟窗口；被中断说明进程要停，直接放弃这次回执 */
    private static boolean sleep(long delayMs) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
