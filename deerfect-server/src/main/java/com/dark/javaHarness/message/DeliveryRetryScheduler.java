package com.dark.javaHarness.message;

import com.dark.javaHarness.domain.DeliveryTask;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投递重试调度：把 outbox 里到期的消息真正发出去。
 *
 * <p>一条消息的推进规则（都发生在单实例内，不涉及并发控制）：
 * <pre>
 * 送达                    → SENT
 * 可重试失败且本跳未到上限 → 保持 PENDING，按 60s / 180s / 600s 退避
 * 可重试失败且本跳到上限   → chain_index+1（换下一跳），本跳计数归零
 * 不可重试失败 / 链已走完  → DEAD（带最后一次错误）
 * </pre>
 *
 * <p>只发「当前这一跳」，不用 {@link MessageSenderRegistry#sendWithFallback}——那会一次
 * 跑完整条链，使「每跳重试 3 次」失去意义。
 */
@Component
public class DeliveryRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryScheduler.class);

    private final DeliveryOutboxService outbox;
    private final MessageSenderRegistry senders;
    private final int batch;
    private final long leaseSeconds;
    private final int maxPerHop;
    private final List<Long> backoffSeconds;

    public DeliveryRetryScheduler(DeliveryOutboxService outbox,
                                  MessageSenderRegistry senders,
                                  @Value("${app.delivery.batch:50}") int batch,
                                  @Value("${app.delivery.lease-seconds:60}") long leaseSeconds,
                                  @Value("${app.delivery.max-per-hop:3}") int maxPerHop,
                                  @Value("${app.delivery.backoff-seconds:60,180,600}") List<Long> backoffSeconds) {
        this.outbox = outbox;
        this.senders = senders;
        this.batch = Math.max(1, batch);
        this.leaseSeconds = Math.max(10, leaseSeconds);
        this.maxPerHop = Math.max(1, maxPerHop);
        this.backoffSeconds = backoffSeconds == null || backoffSeconds.isEmpty()
                ? List.of(60L, 180L, 600L)
                : List.copyOf(backoffSeconds);
    }

    /**
     * 调度节拍：先领取到期任务，再逐条投递。
     *
     * <p>异常全部吞在本层：投递失败不该影响其它 @Scheduled（画像提炼、Goal 派发）。
     */
    @Scheduled(fixedDelayString = "${app.delivery.scan-interval-ms:30000}")
    public void tick() {
        try {
            int delivered = deliverDue();
            if (delivered > 0) {
                log.info("[outbox] 本轮送达 {} 条", delivered);
            }
        } catch (Exception e) {
            log.warn("[outbox] 本轮投递异常，下轮重试: {}", e.toString());
        }
    }

    /**
     * 执行一轮投递。
     *
     * @return 本轮送达条数
     */
    public int deliverDue() {
        List<DeliveryTask> due = outbox.claimDue(batch, leaseSeconds);
        if (due.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (DeliveryTask task : due) {
            try {
                if (deliverOne(task)) {
                    delivered++;
                }
            } catch (Exception e) {
                // 单条异常不影响同批其它行；该行仍是 PENDING，租约到期后会重试
                log.warn("[outbox] 投递 {} 异常（保持 PENDING）: {}", task.id(), e.toString());
            }
        }
        return delivered;
    }

    private boolean deliverOne(DeliveryTask task) {
        String segment = segmentAt(task.channelChain(), task.chainIndex());
        if (segment == null) {
            outbox.markDead(task.id(), "链上取不到第 " + (task.chainIndex() + 1) + " 跳：" + task.channelChain());
            return false;
        }
        MessageSenderRegistry.Attempt attempt = senders.sendOnce(segment, task.content());
        if (attempt.sent()) {
            outbox.markSent(task.id());
            log.info("[outbox] 送达 id={} 第 {}/{} 跳 {}",
                    task.id(), task.chainIndex() + 1, task.chainLength(), segment);
            return true;
        }

        String reason = attempt.reason() == null ? "未提供原因" : attempt.reason();
        if (!attempt.retryable()) {
            outbox.markDead(task.id(), reason);
            return false;
        }
        if (task.hopAttempts() >= maxPerHop) {
            log.warn("[outbox] id={} 第 {} 跳重试 {} 次仍失败，换下一跳：{}",
                    task.id(), task.chainIndex() + 1, task.hopAttempts(), reason);
            outbox.advanceChain(task, reason);
            return false;
        }
        long delay = backoffSeconds.get(Math.min(task.hopAttempts() - 1, backoffSeconds.size() - 1));
        outbox.scheduleRetry(task.id(), reason, delay);
        return false;
    }

    /** 取链上第 index 跳；越界返回 null（配置被改短时会走到这里） */
    static String segmentAt(String chain, int index) {
        if (chain == null || chain.isBlank() || index < 0) {
            return null;
        }
        String[] parts = chain.split("\\" + MessageSenderRegistry.CHAIN_SEPARATOR, -1);
        if (index >= parts.length) {
            return null;
        }
        String segment = parts[index].trim();
        return segment.isEmpty() ? null : segment;
    }
}
