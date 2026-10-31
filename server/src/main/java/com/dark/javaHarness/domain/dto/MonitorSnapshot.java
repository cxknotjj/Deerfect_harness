package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 运行监控快照：全部渠道（Web / CLI / QQ）的会话、LLM 调用（含首 token）、工具调用与时间窗汇总。
 *
 * <p>渠道口径为 session.creator 冒号前的段：{@code qq:<uid> → qq}、{@code cli → cli}、
 * {@code anonymous/web → web}；找不到会话的调用归入 {@code unknown}（前端展示为「未知」）。
 */
public record MonitorSnapshot(
        Summary summary,
        List<Channel> channels,
        List<Session> sessions,
        List<LlmCall> llmCalls,
        List<ToolCall> toolCalls,
        List<Hourly> hourly,
        RuntimeMetrics runtime,
        List<ComponentStatus> components) {

    /** 时间窗汇总：调用/错误、首 token 与耗时（P95 为最近样本近似）、token 用量、工具调用。 */
    public record Summary(long calls, long errors, double errorRate,
                          long avgTtftMs, long p95TtftMs,
                          long avgDurationMs, long p95DurationMs,
                          long promptTokens, long completionTokens, long totalTokens,
                          long toolCalls, long toolErrors) {
    }

    /** 单渠道时间窗统计（calls/errors/tokens 仅 LLM；工具调用单列）。 */
    public record Channel(String channel, long calls, long errors, long totalTokens,
                          long toolCalls, long toolErrors) {
    }

    /** 时间窗内活跃会话；calls/tokens 为该会话在窗口内的 LLM 聚合。 */
    public record Session(long sessionId, String channel, String creator,
                          String agentName, String sessionName, String lastActiveAt,
                          long calls, long totalTokens) {
    }

    /** 单次 LLM 调用；firstTokenMs 为 null 表示该路径未采集 TTFT。 */
    public record LlmCall(long id, String sessionId, String channel, String agentName, String model,
                          String callKind, String status, Long firstTokenMs,
                          long durationMs, Integer promptTokens, Integer completionTokens,
                          Integer totalTokens, String traceId, String createdAt) {
    }

    /** 单次工具调用。 */
    public record ToolCall(long id, String sessionId, String channel, String agentName,
                           String toolName, String serverName, String status,
                           long durationMs, String traceId, String createdAt) {
    }

    /** 小时桶：调用量、token 与平均首 token（无样本为 0）。 */
    public record Hourly(String bucket, long calls, long tokens, long avgTtftMs) {
    }
}
