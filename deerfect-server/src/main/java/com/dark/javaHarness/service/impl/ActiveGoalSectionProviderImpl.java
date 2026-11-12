package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.prompt.ActiveGoalSectionProvider;
import com.dark.javaHarness.service.GoalService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 活跃目标段实现：把会话当前 ACTIVE 状态的目标渲染成一段 system 提示。
 *
 * <p>为什么用 ACTIVE 而不是 RUNNING：RUNNING 属于「这一次执行正在进行」，会被
 * {@code StaleGoalWatchdog} 按超时判死、被 failAllRunning 在重启时清理；长期目标
 * 可能几天不动，混用会被误杀。
 *
 * <p>为什么不让模型自行宣布完成：误判「已完成」会让目标悄悄消失，用户再也看不到它；
 * 完成的代价是用户执行一条命令，误判的代价是目标丢失——不对称，所以只保留手动通道。
 */
@Component
public class ActiveGoalSectionProviderImpl implements ActiveGoalSectionProvider {

    /** 目标描述在 system 里的上限：长目标不该挤占本轮上下文 */
    private static final int OBJECTIVE_LIMIT = 200;

    private final GoalService goalService;

    public ActiveGoalSectionProviderImpl(GoalService goalService) {
        this.goalService = goalService;
    }

    @Override
    public String provide(String sessionId) {
        return goalService.activeBySession(sessionId)
                .map(ActiveGoalSectionProviderImpl::render)
                .orElse(null);
    }

    private static String render(Goal goal) {
        String objective = goal.objective() == null ? "" : goal.objective().strip();
        if (objective.length() > OBJECTIVE_LIMIT) {
            objective = objective.substring(0, OBJECTIVE_LIMIT) + "…";
        }
        long days = goal.createdAt() == null ? 0
                : ChronoUnit.DAYS.between(goal.createdAt(), LocalDateTime.now()) + 1;
        return "【当前目标】" + objective + "\n"
                + "（第 " + days + " 天，id=" + goal.id() + "）\n"
                + "本轮若与它相关，先说明推进了什么；无关就正常回答，不要强行关联。"
                + "目标是否完成由用户判定（/goal done " + goal.id() + "），你不要自行宣布完成。";
    }
}
