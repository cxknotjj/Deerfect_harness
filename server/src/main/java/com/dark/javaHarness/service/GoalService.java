package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Goal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 目标服务：负责创建、查询和更新 Goal 的生命周期状态。
 */
public interface GoalService {

    /**
     * 创建目标（带会话记忆与轨迹标识），初始状态 PENDING。
     * turnId 由聊天入口生成（/submit 直发为 null）；traceId 由 AgentService
     * 创建执行链时生成——两者随 Goal 供执行链与观测贯通读取。
     */
    Goal create(String objective, String sessionId, String turnId, String traceId, String channel);

    /** 按 id 查询目标 */
    Optional<Goal> get(String id);

    /**
     * 登记一个跨轮长期目标（状态 ACTIVE，不进入执行链）。
     *
     * <p>与 {@link #create} 的区别：create 创建的是「这一轮要执行的任务」（PENDING → RUNNING），
     * 本方法创建的是「用户希望长期推进的事」，只参与每轮 system 注入，不消耗 goalExecutor。
     */
    Goal createActive(String objective, String sessionId);

    /** 查询会话当前活跃的长期目标（多条时取最近创建的一条） */
    Optional<Goal> activeBySession(String sessionId);

    /** 标记长期目标完成；不存在或不是 ACTIVE 返回 false */
    boolean completeActive(String id);

    /** 查询全部目标 */
    List<Goal> all();

    /** 将 Goal 当前状态持久化（状态变更后调用） */
    void update(Goal goal);

    /**
     * 将全部 RUNNING 状态的目标批量标记为 FAILED（服务启动时清理僵尸目标）。
     *
     * <p>服务非正常退出（强杀进程）时 Reactor 的 cancel/error 回调不会执行，
     * goal 会永久残留 RUNNING，导致 /resume 被 409 拦截。单实例部署下
     * 启动时必然没有正在执行的编排，可安全统一清理。
     *
     * @return 清理的目标数量
     */
    int failAllRunning(String reason);

    /**
     * 刷新目标进展时间：仅当目标仍为 RUNNING 时更新，避免覆盖已结束状态。
     * 执行链路在阶段推进或节流心跳时调用，供 Watchdog 判断是否停滞。
     */
    void touchProgress(String goalId);

    /**
     * 将停滞的 RUNNING 目标批量标记为 FAILED：
     * {@code last_progress_at} 为空或早于 cutoff 的目标视为无进展。
     * 条件更新（WHERE status=RUNNING）保证不覆盖已结束目标。
     *
     * @return 本次标记失败的目标数量
     */
    int failStagnant(LocalDateTime cutoff, String reason);
}