package com.dark.javaHarness.service;

import com.dark.javaHarness.view.Schedule;
import java.util.List;

/**
 * 定时任务服务：任务的定义与到点派发。
 *
 * <p>派发只做「把 objective 交给 AgentService.submit」——执行、重试、状态机都复用 goal
 * 既有链路，本服务不另建执行体系，否则两套状态机会互相打架。
 */
public interface ScheduleService {

    /**
     * 创建任务并计算首次触发时间。
     *
     * @throws IllegalArgumentException cron 非法 / 永不触发 / agent 不存在 / 必填为空
     */
    Schedule create(String objective, String agentName, String cron, String notifyChannel);

    /** 全部任务（按创建时间倒序） */
    List<Schedule> all();

    /** 删除任务；不存在返回 false */
    boolean delete(String id);

    /**
     * 派发所有到期任务（由调度器周期调用，也可手动触发）。
     *
     * <p>单实例假设：扫描与推进在同一线程串行执行，不做分布式抢占——若将来多实例部署，
     * 需要 ShedLock 或把触发改成消息队列。
     *
     * @return 本轮触发的任务数
     */
    int dispatchDue();

    /**
     * 推送已结束任务的结果（按 last_goal_id 查 goal 终态；notified_at 幂等防重复）。
     *
     * <p>未配置 notify_channel 的任务不参与；goal 尚未结束的留到下一轮；
     * 发送失败也标记已通知，避免每轮重试刷屏渠道。
     *
     * @return 本轮成功送达的条数
     */
    int notifyFinished();
}
