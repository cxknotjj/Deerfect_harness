package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.RoutePlan;

/**
 * 主 Agent 前置判断（路径判断器）。
 *
 * <p>职责单一：仅做「路径选择」，判断一条用户请求走 Agentic 还是 Graph 编排，
 * 输出携带置信度与工作流形态的 {@link RoutePlan}；不执行具体任务，保持入口薄。
 */
public interface RouteJudge {

    /**
     * 判断一条用户请求应走哪条路径。内部应保证对调用方不抛异常（失败兜底 Agentic）。
     *
     * @param sessionId 关联会话ID（观测落库用；无会话场景可传 null）
     * @param turnId    轮次标识（route-judge 观测行归因到轮次用；无轮次场景可传 null）
     */
    RoutePlan plan(String message, String sessionId, String turnId);
}
