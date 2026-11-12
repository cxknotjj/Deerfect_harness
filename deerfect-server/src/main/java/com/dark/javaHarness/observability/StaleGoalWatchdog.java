package com.dark.javaHarness.observability;

import com.dark.javaHarness.service.GoalService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Goal 停滞看门狗（主系统之外的外部控制回路）：
 * 定时扫描 RUNNING 且 {@code last_progress_at} 为空或早于阈值的目标，条件更新为 FAILED。
 *
 * <p>覆盖的异常是「进程还活着、任务已无进展」——启动清理 {@code failAllRunning} 只处理
 * 非正常退出残留，运行中卡住的 Goal 需要本回路发现。只标失败，不自动重投
 * （重投由 HARNESS_TODO P2「Goal 失败重投」另立任务）。
 *
 * <p>默认关闭（{@code app.watchdog.enabled=false}）：阈值必须大于 LLM 读超时与流空闲超时，
 * 先观察一轮实际进展间隔再开启。单实例假设与 {@code failAllRunning} 一致；
 * 条件更新（WHERE status=RUNNING）保证多实例并发扫描也不会覆盖已结束目标。
 */
@Component
@ConditionalOnProperty(name = "app.watchdog.enabled", havingValue = "true")
public class StaleGoalWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StaleGoalWatchdog.class);

    private final GoalService goalService;
    private final int stagnantMinutes;
    private final Clock clock;

    @Autowired
    public StaleGoalWatchdog(GoalService goalService,
                             @Value("${app.watchdog.stagnant-minutes:30}") int stagnantMinutes) {
        this(goalService, stagnantMinutes, Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟，避免依赖真实时间 */
    StaleGoalWatchdog(GoalService goalService, int stagnantMinutes, Clock clock) {
        this.goalService = goalService;
        this.stagnantMinutes = stagnantMinutes;
        this.clock = clock;
    }

    /** 定时扫描（间隔可配；关闭开关时本 bean 不装配，方法不会执行） */
    @Scheduled(fixedDelayString = "${app.watchdog.scan-interval-ms:60000}")
    public void scan() {
        if (stagnantMinutes <= 0) {
            log.warn("[goal-watchdog] stagnant-minutes={} 非法，跳过扫描（必须为正数）", stagnantMinutes);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(stagnantMinutes);
        String reason = "执行停滞：超过 " + stagnantMinutes + " 分钟无进展";
        int failed = goalService.failStagnant(cutoff, reason);
        if (failed > 0) {
            log.warn("[goal-watchdog] 判定 {} 个 Goal 停滞并标记 FAILED（cutoff={}）", failed, cutoff);
            HarnessMetrics.goalWatchdogStale(failed);
        }
    }
}
