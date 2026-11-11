package com.dark.javaHarness.view;

/**
 * 登记跨轮长期目标入参（POST /api/harness/goals/active）。
 *
 * @param sessionId 目标归属的会话（每轮注入按此会话取）
 * @param objective 目标描述
 */
public record ActiveGoalRequest(String sessionId, String objective) {
}
