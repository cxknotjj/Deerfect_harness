package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.Schedule;
import com.dark.javaHarness.domain.entity.ScheduleEntity;
import com.dark.javaHarness.mapper.ScheduleMapper;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ScheduleService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

    private final ScheduleMapper mapper;
    private final AgentService agentService;

    public ScheduleServiceImpl(ScheduleMapper mapper, AgentService agentService) {
        this.mapper = mapper;
        this.agentService = agentService;
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

        ScheduleEntity row = new ScheduleEntity();
        row.setId("sched-" + UUID.randomUUID());
        row.setObjective(objective.trim());
        row.setAgentName(agentName.trim());
        row.setCron(cron.trim());
        row.setEnabled(1);
        row.setNotifyChannel(blankToNull(notifyChannel));
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
    @Scheduled(fixedDelayString = "${app.schedule.scan-interval-ms:10000}")
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
