package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.Schedule;
import com.dark.javaHarness.domain.entity.ScheduleEntity;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.mapper.ScheduleMapper;
import com.dark.javaHarness.message.DeliveryOutboxService;
import com.dark.javaHarness.message.MessageSenderRegistry;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.ScheduleService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 定时任务实现。
 *
 * <p>派发链路刻意做得很薄：扫描到期行 → {@link AgentService#submit} → 推进 next_run_at。
 * 执行、重试、断点续跑全部复用 goal 既有能力，本类不持有线程、不维护第二套状态机。
 *
 * <p>为什么每次派发都在 finally 里推进时间：任务失败（agent 被删、队列满）时若不推进，
 * 下一轮扫描（默认 10 秒）会再次命中同一条，日志被刷屏且永远卡在队首。失败的重投是
 * P1-4 的职责，由 goal 侧按退避策略做，这里只保证「不卡住调度器」。
 *
 * <p>单实例假设：扫描与推进同线程串行。多实例部署需要分布式锁（ShedLock）或改消息队列。
 */
@Service
public class ScheduleServiceImpl implements ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleServiceImpl.class);

    /** 单轮最多派发条数：防一次扫描把整个队列塞满（goalExecutor 有界，超了会拒绝） */
    private static final int DISPATCH_BATCH = 20;

    /** 推送正文里的结果摘要上限：IM 消息不是报告，超长内容截断 */
    private static final int RESULT_SUMMARY_LIMIT = 300;

    /**
     * 两次触发的最小间隔（秒）：必须大于扫描周期（10s）的两倍。
     *
     * <p>为什么：推送靠 {@code last_goal_id} + {@code notified_at} 定位「上一轮跑完的结果」，
     * 而每次派发都会覆盖 last_goal_id。间隔太密时上一轮还没推完就被新一轮覆盖，
     * 结果被静默丢弃（2026-11-06 用每 10 秒一条的 cron 复现）。30 秒给「派发 →
     * goal 完成 → 下一轮推送」留出至少两轮节拍。
     */
    private static final long MIN_TRIGGER_INTERVAL_SECONDS = 30;

    private final ScheduleMapper mapper;
    private final AgentService agentService;
    private final GoalService goalService;
    private final MessageSenderRegistry senders;
    /** 投递 outbox：推送不再直发，而是「先入队」——送达状态只由 outbox 表达 */
    private final DeliveryOutboxService outbox;

    /**
     * 推送专用单线程（守护）：渠道 HTTP 会超时重试，放在调度线程上会把「下一轮派发」
     * 一起拖住——NapCat 连不上时实测重试链路可占用数秒，任务越多叠加越久。
     */
    private final ExecutorService notifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schedule-notify");
        t.setDaemon(true);
        return t;
    });

    /** 推送防重入：上一轮没跑完就跳过本轮，避免慢渠道把任务堆在队列里 */
    private final AtomicBoolean notifying = new AtomicBoolean();

    public ScheduleServiceImpl(ScheduleMapper mapper, AgentService agentService,
                               GoalService goalService, MessageSenderRegistry senders,
                               DeliveryOutboxService outbox) {
        this.mapper = mapper;
        this.agentService = agentService;
        this.goalService = goalService;
        this.senders = senders;
        this.outbox = outbox;
    }

    /**
     * 调度节拍：先派发到期任务，再补推送已完成但未通知的。
     *
     * <p>两件事放同一个节拍而不是各起一个 @Scheduled：派发与推送共享同一批任务，
     * 分开调度会出现「推的比发的早」的空转窗口，也会让「同一条任务的日志被两条时间线切碎」。
     */
    @Scheduled(fixedDelayString = "${app.schedule.scan-interval-ms:10000}")
    public void tick() {
        // 顺序不能反：dispatchDue 会覆盖 last_goal_id。先收集上一轮的结果快照，
        // 再派发新的，否则高频 cron（间隔 ≤ 扫描周期）下永远只看到刚创建、还在 PENDING
        // 的那个 goal，notified_at 永远置不上——推送被自己的派发饿死。
        List<PendingNotify> pending = collectPending();
        dispatchDue();
        if (pending.isEmpty() || !notifying.compareAndSet(false, true)) {
            return;
        }
        notifyExecutor.execute(() -> {
            try {
                enqueue(pending);
            } catch (Throwable t) {
                log.warn("[schedule] 入队轮次异常: {}", t.toString());
            } finally {
                notifying.set(false);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        notifyExecutor.shutdownNow();
    }

    @Override
    public Schedule create(String objective, String agentName, String cron, String notifyChannel) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective 不能为空");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }
        CronExpression expression = parseCron(cron);
        requireAgentExists(agentName);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = expression.next(now);
        if (next == null) {
            throw new IllegalArgumentException("cron 永不触发: " + cron);
        }
        LocalDateTime following = expression.next(next);
        if (following != null
                && Duration.between(next, following).getSeconds() < MIN_TRIGGER_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                    "cron 间隔过短（最小 " + MIN_TRIGGER_INTERVAL_SECONDS + " 秒，实际 "
                            + Duration.between(next, following).getSeconds() + " 秒）："
                            + "调度按 10 秒节拍扫描，更密的触发会让上一轮结果来不及推送就被覆盖");
        }

        ScheduleEntity row = new ScheduleEntity();
        row.setId("sched-" + UUID.randomUUID());
        row.setObjective(objective.trim());
        row.setAgentName(agentName.trim());
        row.setCron(cron.trim());
        row.setEnabled(1);
        String channelRef = blankToNull(notifyChannel);
        if (channelRef != null) {
            // 校验提前到创建时：配错（未知渠道 / 缺 target / 空段）当场报，不等到真触发任务
            senders.chainOf(channelRef);
        }
        row.setNotifyChannel(channelRef);
        row.setNextRunAt(next);
        row.setCreatedAt(now);
        mapper.insert(row);
        log.info("[schedule] 创建任务 {}（agent={}，cron={}），首次触发 {}", row.getId(),
                row.getAgentName(), row.getCron(), next);
        return toDomain(row);
    }

    @Override
    public List<Schedule> all() {
        return mapper.selectList(new QueryWrapper<ScheduleEntity>()
                        .orderByDesc("created_at"))
                .stream().map(ScheduleServiceImpl::toDomain).toList();
    }

    @Override
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return mapper.deleteById(id) > 0;
    }

    @Override
    public int dispatchDue() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleEntity> due;
        try {
            due = mapper.selectList(new QueryWrapper<ScheduleEntity>()
                    .eq("enabled", 1)
                    .le("next_run_at", now)
                    .orderByAsc("next_run_at")
                    .last("LIMIT " + DISPATCH_BATCH));
        } catch (Exception e) {
            // 扫描失败（DB 抖动）只影响本轮：下一轮重试，不向上抛打断 Spring 调度线程
            log.warn("[schedule] 到期任务扫描失败，本轮跳过: {}", e.toString());
            return 0;
        }
        if (due == null || due.isEmpty()) {
            return 0;
        }
        for (ScheduleEntity row : due) {
            fire(row, now);
        }
        return due.size();
    }

    /**
     * 推送已完成任务的结果：按 {@code last_goal_id} 查 goal 状态，终态才发。
     *
     * <p>为什么轮询而不发事件：Goal 的完成点有三处（直跑、流式成功、流式失败），事件容易
     * 漏发或重发；轮询 + notified_at 幂等标记只需读两张表，且失败天然下一轮补上。
     *
     * <p>`notified_at` 的语义是「已入队」而不是「已送达」：投递成败由
     * {@link DeliveryOutboxService} 记录并按退避重投，调度侧不再关心。
     * 入队失败时才不标记——下一轮会重新收集同一条，不会丢。
     */
    @Override
    public int notifyFinished() {
        return enqueue(collectPending());
    }

    /** 待推送快照：查询时就把 goal 终态与正文定下来，发送阶段不再读库（避免派发覆盖导致串行错乱） */
    private record PendingNotify(String scheduleId, String goalId, String channelRef, String text) {
    }

    /** 收集「已触发、有推送目标、尚未通知、goal 已终态」的任务快照 */
    private List<PendingNotify> collectPending() {
        List<ScheduleEntity> rows;
        try {
            rows = mapper.selectList(new QueryWrapper<ScheduleEntity>()
                    .isNotNull("last_goal_id")
                    .isNull("notified_at")
                    .isNotNull("notify_channel")
                    .last("LIMIT " + DISPATCH_BATCH));
        } catch (Exception e) {
            log.warn("[schedule] 待推送任务扫描失败，本轮跳过: {}", e.toString());
            return List.of();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<PendingNotify> pending = new ArrayList<>();
        for (ScheduleEntity row : rows) {
            Goal goal = goalService.get(row.getLastGoalId()).orElse(null);
            if (goal == null) {
                // goal 已过保留期被清理：标记掉，否则永远停留在待推送
                log.warn("[schedule] 任务 {} 的 goal {} 已不存在，跳过推送", row.getId(), row.getLastGoalId());
                markNotified(row.getId(), row.getLastGoalId());
                continue;
            }
            if (goal.status() == GoalStatus.PENDING || goal.status() == GoalStatus.RUNNING) {
                continue; // 还在跑，下一轮再看
            }
            pending.add(new PendingNotify(row.getId(), row.getLastGoalId(),
                    row.getNotifyChannel(), formatResult(row, goal)));
        }
        return pending;
    }

    /**
     * 写入投递 outbox 并标记「已入队」。
     *
     * <p>与改造前的关键差别：不再在这里发送，也不再看发送结果。发送、重试、降级、
     * 终态全部交给 outbox —— 一条消息的投递过程有唯一事实来源。
     */
    private int enqueue(List<PendingNotify> pending) {
        int queued = 0;
        for (PendingNotify item : pending) {
            try {
                outbox.enqueue("schedule", item.scheduleId() + "/" + item.goalId(),
                        item.channelRef(), item.text());
            } catch (Exception e) {
                // 入队失败不标记：下一轮会重新收集这条，属于「可恢复失败」
                log.warn("[schedule] 任务 {} 入队失败，下轮重试: {}", item.scheduleId(), e.toString());
                continue;
            }
            markNotified(item.scheduleId(), item.goalId());
            queued++;
        }
        return queued;
    }

    /**
     * 条件更新：只在 last_goal_id 仍是我们推送的那一条时才置 notified_at。
     *
     * <p>不加这个条件会串轮次——发送期间 dispatchDue 已把 last_goal_id 换成新 goal，
     * 直接按 id 更新会把「新一轮」标记成已通知，导致它永远不推。
     */
    private void markNotified(String scheduleId, String goalId) {
        UpdateWrapper<ScheduleEntity> update = new UpdateWrapper<>();
        update.eq("id", scheduleId)
                .eq("last_goal_id", goalId)
                .set("notified_at", LocalDateTime.now());
        try {
            mapper.update(null, update);
        } catch (Exception e) {
            log.warn("[schedule] 标记推送状态失败 {}: {}", scheduleId, e.toString());
        }
    }

    /** 推送正文：任务目标 + 结果 + 摘要/原因 + goal 指针（便于回查完整记录） */
    private static String formatResult(ScheduleEntity row, Goal goal) {
        boolean succeeded = goal.status() == GoalStatus.SUCCEEDED;
        String summary = goal.summary() == null ? "" : goal.summary().strip();
        if (summary.length() > RESULT_SUMMARY_LIMIT) {
            summary = summary.substring(0, RESULT_SUMMARY_LIMIT) + "…";
        }
        StringBuilder text = new StringBuilder("【定时任务】").append(row.getObjective().strip());
        text.append("\n结果：").append(succeeded ? "成功" : "失败");
        if (!summary.isEmpty()) {
            text.append("\n").append(succeeded ? "摘要：" : "原因：").append(summary);
        }
        text.append("\n(goal ").append(row.getLastGoalId()).append(")");
        return text.toString();
    }

    /** 触发单条并推进下次时间；失败也推进（重投是 goal 侧职责，见类注释） */
    private void fire(ScheduleEntity row, LocalDateTime now) {
        String goalId = null;
        try {
            Goal goal = agentService.submit(row.getAgentName(), row.getObjective());
            goalId = goal.id();
            log.info("[schedule] 触发 {}（agent={}）-> goal={}", row.getId(), row.getAgentName(), goalId);
        } catch (Exception e) {
            log.warn("[schedule] 触发失败 {}（agent={}）: {}", row.getId(), row.getAgentName(), e.toString());
        }

        UpdateWrapper<ScheduleEntity> update = new UpdateWrapper<>();
        update.eq("id", row.getId())
                .set("last_run_at", now)
                .set("last_goal_id", goalId);
        LocalDateTime next = null;
        try {
            next = CronExpression.parse(row.getCron()).next(now);
        } catch (Exception e) {
            log.warn("[schedule] cron 解析失败，任务将停用 {}: {}", row.getId(), row.getCron());
        }
        if (next == null) {
            update.set("enabled", 0);
            log.warn("[schedule] 任务 {} 无后续触发时间，已停用", row.getId());
        } else {
            update.set("next_run_at", next);
        }
        try {
            mapper.update(null, update);
        } catch (Exception e) {
            // 推进失败只影响这条：下一轮会再触发一次（可能重复执行），但不会永久卡死
            log.warn("[schedule] 推进下次触发时间失败 {}: {}", row.getId(), e.toString());
        }
    }

    private static CronExpression parseCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空（6 段：秒 分 时 日 月 周）");
        }
        try {
            return CronExpression.parse(cron.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "cron 表达式非法（需 6 段：秒 分 时 日 月 周）: " + cron + "，原因: " + e.getMessage());
        }
    }

    private void requireAgentExists(String agentName) {
        boolean exists = agentService.agentItems().stream()
                .anyMatch(item -> agentName.trim().equals(item.name()));
        if (!exists) {
            throw new IllegalArgumentException("agent 不存在: " + agentName);
        }
    }

    private static Schedule toDomain(ScheduleEntity row) {
        return new Schedule(
                row.getId(),
                row.getObjective(),
                row.getAgentName(),
                row.getCron(),
                row.getNotifyChannel(),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getLastRunAt(),
                row.getNextRunAt(),
                row.getLastGoalId(),
                row.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
