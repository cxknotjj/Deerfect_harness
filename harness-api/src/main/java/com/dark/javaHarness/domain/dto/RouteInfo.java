package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 一次请求的路由决策快照（对外透出 + 观测用）。
 *
 * <p>{@code path} 为 {@code agentic} 或 {@code graph}；{@code confidence} 为 LLM 判定的置信度
 * （规则/显式指定为 1.0）；{@code workstreams} 是判定出的可并行工作流；
 * {@code source} 标识决策来源（OVERRIDE/RULE/LLM/FALLBACK/BOUND_AGENT/RESUME）。
 */
public record RouteInfo(
        String path,
        Double confidence,
        List<String> workstreams,
        List<String> deliverables,
        String reason,
        String source) {
}
