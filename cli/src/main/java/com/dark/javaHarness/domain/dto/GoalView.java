package com.dark.javaHarness.domain.dto;

/**
 * 目标视图对象（/api/harness 返回用），隔离 domain 模型与表现层；
 * 领域模型 Goal 的装配见 server 侧 HarnessController。
 */
public record GoalView(
        String id,
        String objective,
        String status,
        String summary) {
}
