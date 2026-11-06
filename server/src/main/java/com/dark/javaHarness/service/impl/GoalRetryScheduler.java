package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.mapper.GoalMapper;
import com.dark.javaHarness.service.AgentService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失败 Goal 的重投调度器：按 {@code next_retry_at} 到点重新提交。
 *
 * <p>为什么单独一个组件而不是在失败处直接 sleep/重试：重试要等 1~2 分钟（退避），
 * 在 goalExecutor 线程里睡会白占执行槽；用调度器轮询，失败线程立即释放。
 *
 * <p>与 watchdog 的分工：watchdog 处理「卡在 RUNNING 不动」的目标（判死），
 * 本类处理「已经 FAILED 但还值得再试一次」的目标。两者状态集合不相交，不会打架。
 */
@Component
public class GoalRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoalRetryScheduler.class);

    /** 单轮最多处理条数：失败潮时避免一次把执行队列塞满 */
    private static final int BATCH = 10;

    private final GoalMapper goalMapper;
    private final AgentService agentService;

    public GoalRetryScheduler(GoalMapper goalMapper, AgentService agentService) {
        this.goalMapper = goalMapper;
        this.agentService = agentService;
    }

    /**
     * 扫描到期重投任务并重新提交。
     *
     * @return 本轮受理的重投数
     */
    @Scheduled(fixedDelayString = "${app.goal.retry-scan-interval-ms:30000}")
    public int retryDue() {
        List<GoalEntity> due;
        try {
            due = goalMapper.selectList(Wrappers.<GoalEntity>lambdaQuery()
                    .eq(GoalEntity::getStatus, GoalStatus.FAILED.name())
                    .isNotNull(GoalEntity::getNextRetryAt)
                    .le(GoalEntity::getNextRetryAt, LocalDateTime.now())
                    .orderByAsc(GoalEntity::getNextRetryAt)
                    .last("LIMIT " + BATCH));
        } catch (Exception e) {
            // DB 抖动只影响本轮：下一轮再扫，不打断调度线程
            log.warn("[goal-retry] 待重投扫描失败，本轮跳过: {}", e.toString());
            return 0;
        }
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int retried = 0;
        for (GoalEntity row : due) {
            boolean accepted;
            try {
                accepted = agentService.retry(row.getId());
            } catch (Exception e) {
                log.warn("[goal-retry] 重投异常 {}: {}", row.getId(), e.toString());
                accepted = false;
            }
            if (accepted) {
                retried++;
            }
            // 无论受理与否都清：受理后 goal 已离开 FAILED 集合、不再需要标记；
            // 不受理（agent 被删 / 状态已变 / 超上限）不清则每 30 秒重扫同一条。
            // 另外 GoalServiceImpl.update 走 updateById，MyBatis-Plus 忽略 null 字段，
            // 光靠 markRetry 把内存值置空落不了库——这里显式 set null 才真正清掉。
            clearNextRetry(row.getId());
        }
        return retried;
    }

    private void clearNextRetry(String goalId) {
        try {
            goalMapper.update(null, Wrappers.<GoalEntity>lambdaUpdate()
                    .eq(GoalEntity::getId, goalId)
                    .set(GoalEntity::getNextRetryAt, null));
        } catch (Exception e) {
            log.warn("[goal-retry] 清除重投时间失败 {}: {}", goalId, e.toString());
        }
    }
}
