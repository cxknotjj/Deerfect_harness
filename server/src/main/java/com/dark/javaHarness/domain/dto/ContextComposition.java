package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 会话上下文构成快照（GET /api/monitor/context）：
 * 最近一次真实 LLM 请求发往模型前的输入拆分，tokens 为 {@code TokenEstimator} 估算值，
 * percent 为占各组成部分之和的百分比（保留 1 位小数，允许 0.1% 级舍入误差）。
 */
public record ContextComposition(
        String sessionId,
        String agentName,
        String model,
        String turnId,
        long capturedAt,
        int estimatedTotalTokens,
        List<Part> parts) {

    /** 单个组成项：key/label/group 为固定口径，tokens 与 percent 为估算结果。 */
    public record Part(String key, String label, String group,
                       int tokens, double percent) {
    }
}
