package com.dark.javaHarness.message;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.DeliveryTask;
import com.dark.javaHarness.domain.entity.DeliveryOutboxEntity;
import com.dark.javaHarness.mapper.DeliveryOutboxMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 投递 outbox：把「发一条消息」变成「先记账、再发送、最后结案」。
 *
 * <p>核心是 {@link #claimDue} 里的条件更新：发送**之前**原子递增 {@code attempts} 与
 * {@code hop_attempts}，并把 {@code next_retry_at} 推到未来当作租约。这不是并发控制
 * （本方案是单实例部署），而是为了让「发送中进程被杀」不会卡成死行——租约到期后自动重试，
 * 且次数已经记上，不会无限重试。
 *
 * <p>两个计数的分工：{@code hopAttempts} 决定退避与链推进（换跳归零），{@code attempts}
 * 只做审计（跨通道累加）。混用一个会导致「第二跳一开始就被判定为已重试 3 次」。
 */
@Service
public class DeliveryOutboxService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutboxService.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DEAD = "DEAD";

    /** 当前单用户固定租户 */
    private static final String TENANT = "default";

    /** last_error 列宽 512，留 12 字符余量 */
    private static final int ERROR_MAX_LENGTH = 500;

    /** 邮件渠道名（与 EmailMessageSender.channel() 一致）：长文优先走它 */
    private static final String EMAIL_CHANNEL = "email";

    private final DeliveryOutboxMapper mapper;

    /** 超长正文阈值：超过则把 email 提到链首（webhook 20KB、IM 单条都有长度上限） */
    private final int longTextThreshold;

    /** 测试用：默认阈值 */
    public DeliveryOutboxService(DeliveryOutboxMapper mapper) {
        this(mapper, 6000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryOutboxService(DeliveryOutboxMapper mapper,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${app.delivery.long-text-threshold:6000}") int longTextThreshold) {
        this.mapper = mapper;
        this.longTextThreshold = longTextThreshold;
    }

    /**
     * 入队一条待投消息（立即可领取）。
     *
     * <p>调用方必须先用 {@link MessageSenderRegistry#chainOf} 校验链——这里不再校验，
     * 避免「校验两次、两处口径不一致」。
     *
     * @return outbox 行主键
     */
    public long enqueue(String source, String refId, String chain, String content) {
        LocalDateTime now = LocalDateTime.now();
        DeliveryOutboxEntity row = new DeliveryOutboxEntity();
        row.setTenantId(TENANT);
        row.setSource(source);
        row.setRefId(refId);
        row.setChannelChain(preferEmailForLongText(chain, content));
        row.setChainIndex(0);
        row.setContent(content);
        row.setStatus(STATUS_PENDING);
        row.setAttempts(0);
        row.setHopAttempts(0);
        row.setNextRetryAt(now);
        row.setCreatedAt(now);
        mapper.insert(row);
        log.info("[outbox] 入队 id={} source={} ref={} 链={}", row.getId(), source, refId, chain);
        return row.getId();
    }

    /**
     * 领取到期任务：发送前原子递增计数并推后下次尝试时刻（租约）。
     *
     * <p>「先查再逐条条件更新」而不是一条 UPDATE：MySQL 没有 UPDATE ... RETURNING，
     * 而调度器需要知道本次领到了哪些行。条件更新保证同一条不会被两轮同时领走，
     * 不依赖调用顺序，也不依赖内存标志。
     *
     * @param leaseSeconds 租约时长；发送耗时超过它，下一轮可能重复领取同一条（个人用量下
     *                     远小于 SMTP/webhook 超时，取 60 秒足够）
     */
    public List<DeliveryTask> claimDue(int limit, long leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        List<DeliveryOutboxEntity> rows;
        try {
            rows = mapper.selectList(new QueryWrapper<DeliveryOutboxEntity>()
                    .eq("status", STATUS_PENDING)
                    .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", now))
                    .orderByAsc("next_retry_at")
                    .last("LIMIT " + Math.max(1, limit)));
        } catch (Exception e) {
            log.warn("[outbox] 扫描到期任务失败，本轮跳过: {}", e.toString());
            return List.of();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DeliveryTask> tasks = new ArrayList<>(rows.size());
        for (DeliveryOutboxEntity row : rows) {
            int updated;
            try {
                updated = mapper.update(null, new UpdateWrapper<DeliveryOutboxEntity>()
                        .eq("id", row.getId())
                        .eq("status", STATUS_PENDING)
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", now))
                        .setSql("attempts = attempts + 1")
                        .setSql("hop_attempts = hop_attempts + 1")
                        .set("next_retry_at", now.plusSeconds(leaseSeconds)));
            } catch (Exception e) {
                log.warn("[outbox] 领取 {} 失败: {}", row.getId(), e.toString());
                continue;
            }
            if (updated != 1) {
                // 已被别的轮次领走（或状态刚被改），本轮跳过即可
                continue;
            }
            tasks.add(new DeliveryTask(row.getId(), row.getSource(), row.getRefId(),
                    row.getChannelChain(), nz(row.getChainIndex()), row.getContent(),
                    nz(row.getAttempts()) + 1, nz(row.getHopAttempts()) + 1,
                    chainLengthOf(row.getChannelChain())));
        }
        return List.copyOf(tasks);
    }

    /** 结案：送达 */
    public boolean markSent(long id) {
        return updateById(id, new UpdateWrapper<DeliveryOutboxEntity>()
                .set("status", STATUS_SENT)
                .set("sent_at", LocalDateTime.now())
                .setSql("next_retry_at = NULL"));
    }

    /**
     * 同一跳稍后重试：保留 `PENDING`，把下次尝试推到 `delaySeconds` 之后。
     *
     * <p>不重置 `hop_attempts`——退避序列的下一档就是靠它选的。
     */
    public boolean scheduleRetry(long id, String reason, long delaySeconds) {
        return updateById(id, new UpdateWrapper<DeliveryOutboxEntity>()
                .set("last_error", truncate(reason))
                .set("next_retry_at", LocalDateTime.now().plusSeconds(delaySeconds)));
    }

    /**
     * 换下一跳：`chain_index + 1`、`hop_attempts` 归零、立即尝试；已是最后一跳则置 `DEAD`。
     *
     * @return true 表示还能继续投（已换跳），false 表示已判定为 `DEAD`
     */
    public boolean advanceChain(DeliveryTask task, String reason) {
        if (!task.hasNextHop()) {
            markDead(task.id(), reason);
            return false;
        }
        boolean ok = updateById(task.id(), new UpdateWrapper<DeliveryOutboxEntity>()
                .setSql("chain_index = chain_index + 1")
                .set("hop_attempts", 0)
                .set("last_error", truncate(reason))
                .set("next_retry_at", LocalDateTime.now()));
        return ok;
    }

    /** 终态：不可重试或链已走完 */
    public boolean markDead(long id, String reason) {
        log.warn("[outbox] 判定 DEAD id={} 原因={}", id, reason);
        return updateById(id, new UpdateWrapper<DeliveryOutboxEntity>()
                .set("status", STATUS_DEAD)
                .set("last_error", truncate(reason))
                .setSql("next_retry_at = NULL"));
    }

    /** 按状态计数（监控页与排障用） */
    public long countByStatus(String status) {
        Long count = mapper.selectCount(new QueryWrapper<DeliveryOutboxEntity>().eq("status", status));
        return count == null ? 0 : count;
    }

    /**
     * 条件更新：只在仍是 `PENDING` 时改状态，避免把「刚被标 SENT 又被重投失败覆盖」这类竞态写进库。
     */
    private boolean updateById(long id, UpdateWrapper<DeliveryOutboxEntity> update) {
        update.eq("id", id).eq("status", STATUS_PENDING);
        try {
            return mapper.update(null, update) > 0;
        } catch (Exception e) {
            log.warn("[outbox] 更新 {} 失败: {}", id, e.toString());
            return false;
        }
    }

    /**
     * 长文分流：正文超过阈值且链上有 email 时，把 email 提到最前。
     *
     * <p>为什么不是截断：截断会丢信息，而「这条消息太长」恰恰是邮件比 IM 更合适的场景。
     * 为什么在入队时做而不是发送失败后降级：不同渠道的长度上限各不一样，等它失败一次再换
     * 路，等于用一次必然失败去换一个已知结论。
     */
    String preferEmailForLongText(String chain, String content) {
        if (chain == null || content == null || content.length() <= longTextThreshold) {
            return chain;
        }
        String[] parts = chain.split("\\" + MessageSenderRegistry.CHAIN_SEPARATOR, -1);
        if (parts.length < 2) {
            return chain;
        }
        int emailIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().startsWith(EMAIL_CHANNEL + ":")) {
                emailIndex = i;
                break;
            }
        }
        if (emailIndex <= 0) {
            return chain;
        }
        List<String> reordered = new ArrayList<>(parts.length);
        reordered.add(parts[emailIndex].trim());
        for (int i = 0; i < parts.length; i++) {
            if (i != emailIndex) {
                reordered.add(parts[i].trim());
            }
        }
        String result = String.join(MessageSenderRegistry.CHAIN_SEPARATOR, reordered);
        log.info("[outbox] 正文 {} 字符超过阈值 {}，链调整为 {}", content.length(), longTextThreshold, result);
        return result;
    }

    private static int chainLengthOf(String chain) {
        if (chain == null || chain.isBlank()) {
            return 1;
        }
        return chain.split("\\" + MessageSenderRegistry.CHAIN_SEPARATOR, -1).length;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= ERROR_MAX_LENGTH ? reason : reason.substring(0, ERROR_MAX_LENGTH) + "…";
    }
}
