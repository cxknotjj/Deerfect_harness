package com.dark.javaHarness.domain;

import java.util.List;

/**
 * 一次 LLM 调用的观测记录（不可变值对象，由调用出口组装、LlmCallRecorder 落库）。
 *
 * @param sessionId       关联会话ID（路由判断等无会话场景为 null）
 * @param agentName       调用方角色（lead/researcher/aggregator/general/route-judge 等）
 * @param model           实际使用的模型名
 * @param stream          true-流式调用，false-阻塞调用
 * @param ok              true-成功，false-失败（errorMsg 有效）
 * @param promptTokens    输入 token（流式无 usage 回包时为 null）
 * @param completionTokens 输出 token（流式为近似估算）
 * @param totalTokens     总 token（流式为近似估算）
 * @param tokensEstimated token 是否为近似估算
 * @param durationMs      调用耗时（毫秒）
 * @param errorMsg        失败原因（成功为 null）
 * @param skillNames      prompt 装配的技能名单（装配名单口径，非实际调用；未采集为 null）
 * @param toolNames       prompt 装配的工具名单（disableTools 时为空表；未采集为 null）
 * @param mcpToolNames    prompt 装配的 MCP 工具名单（toolNames 子集；未采集为 null）
 * @param outputSummary   回复文本摘要（截断 200 字符；失败/无输出为 null）
 * @param firstTokenMs    首 token 到达延迟（毫秒；流式成功调用有值，SYNC 与失败为 null）
 * @param cachedTokens    供应商缓存命中 token（原生 usage 无该信息为 null）
 * @param attempt         第几次尝试（1 起；无重试通道记 1；路由判定/画像为 null）
 * @param maxAttempts     重试上限（无重试通道记 1；路由判定/画像为 null）
 * @param turnId          轮次标识，一条用户消息触发的完整处理（/submit 直发与历史数据为 null）
 * @param traceId         调用链标识，一次 Agent 执行链（route-judge/画像等非执行树调用为 null）
 * @param spanId          单次调用标识（调用发起前生成；观测行必有）
 * @param parentSpan      父调用的 span_id（根调用为 null）
 * @param startedAt       调用发起时刻（轨迹时序排序键：落库晚于工具行，排序须用发起时刻；历史行/未采集为 null）
 * @param routeId         命中的路由 id（无路由/非路由调用为 null）
 * @param routeCode       命中的路由代码（main/fast/judge 等）
 * @param modelId         实际使用的模型部署 id
 * @param providerCode    实际供应商代码
 * @param upstreamName    实际请求下发的模型名
 * @param fallbackFromModelId 从哪个模型切换而来（首选成员为 null）
 * @param errorClass      失败分类：timeout/connection/4xx/5xx/cancel/other（成功为 null）
 */
public record LlmCallLog(String sessionId, String agentName, String model,
                         boolean stream, boolean ok,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         boolean tokensEstimated,
                         long durationMs, String errorMsg,
                         List<String> skillNames,
                         List<String> toolNames,
                         List<String> mcpToolNames,
                         String outputSummary, Long firstTokenMs, Integer cachedTokens,
                         Integer attempt, Integer maxAttempts,
                         String turnId, String traceId, String spanId, String parentSpan,
                         java.time.LocalDateTime startedAt,
                         Long routeId, String routeCode, Long modelId, String providerCode,
                         String upstreamName, Long fallbackFromModelId, String errorClass) {

    /** 语义分组 Builder：31 个字段按调用/用量/耗时/装配/轨迹/路由分组，避免位置参数错位。 */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String sessionId;
        private String agentName;
        private String model;
        private boolean stream;
        private boolean ok;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private boolean tokensEstimated;
        private Integer cachedTokens;
        private long durationMs;
        private Long firstTokenMs;
        private String errorMsg;
        private String errorClass;
        private List<String> skillNames;
        private List<String> toolNames;
        private List<String> mcpToolNames;
        private String outputSummary;
        private Integer attempt;
        private Integer maxAttempts;
        private String turnId;
        private String traceId;
        private String spanId;
        private String parentSpan;
        private java.time.LocalDateTime startedAt;
        private Long routeId;
        private String routeCode;
        private Long modelId;
        private String providerCode;
        private String upstreamName;
        private Long fallbackFromModelId;

        private Builder() {
        }

        /** 调用身份与结果：谁发起、用的哪个模型、是否流式、是否成功。 */
        public Builder call(String sessionId, String agentName, String model,
                            boolean stream, boolean ok) {
            this.sessionId = sessionId;
            this.agentName = agentName;
            this.model = model;
            this.stream = stream;
            this.ok = ok;
            return this;
        }

        /** token 用量：原生 usage 缺失时 prompt/completion/total 为 null，cached 为供应商命中数。 */
        public Builder usage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                             boolean tokensEstimated, Integer cachedTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.tokensEstimated = tokensEstimated;
            this.cachedTokens = cachedTokens;
            return this;
        }

        /** 耗时：durationMs 必填；firstTokenMs 仅流式成功调用有值。 */
        public Builder timing(long durationMs, Long firstTokenMs) {
            this.durationMs = durationMs;
            this.firstTokenMs = firstTokenMs;
            return this;
        }

        /** 失败信息：成功时为 null；errorClass 见 FailurePolicy 分类。 */
        public Builder error(String errorMsg, String errorClass) {
            this.errorMsg = errorMsg;
            this.errorClass = errorClass;
            return this;
        }

        /** prompt 装配名单（装配口径，非实际调用；未采集为 null）。 */
        public Builder promptAssembly(List<String> skillNames, List<String> toolNames,
                                      List<String> mcpToolNames) {
            this.skillNames = skillNames;
            this.toolNames = toolNames;
            this.mcpToolNames = mcpToolNames;
            return this;
        }

        /** 回复摘要（截断 200 字符；失败/无输出为 null）。 */
        public Builder output(String outputSummary) {
            this.outputSummary = outputSummary;
            return this;
        }

        /** 重试通道的尝试序号与上限；无重试通道记 1。 */
        public Builder attempt(Integer attempt, Integer maxAttempts) {
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** 执行树标识：turnId/traceId/spanId/parentSpan 与发起时刻。 */
        public Builder trace(String turnId, String traceId, String spanId, String parentSpan,
                             java.time.LocalDateTime startedAt) {
            this.turnId = turnId;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpan = parentSpan;
            this.startedAt = startedAt;
            return this;
        }

        /** 路由命中口径：routeId/routeCode/modelId/providerCode/upstreamName/fallbackFromModelId。 */
        public Builder route(Long routeId, String routeCode, Long modelId, String providerCode,
                             String upstreamName, Long fallbackFromModelId) {
            this.routeId = routeId;
            this.routeCode = routeCode;
            this.modelId = modelId;
            this.providerCode = providerCode;
            this.upstreamName = upstreamName;
            this.fallbackFromModelId = fallbackFromModelId;
            return this;
        }

        public LlmCallLog build() {
            return new LlmCallLog(sessionId, agentName, model, stream, ok,
                    promptTokens, completionTokens, totalTokens, tokensEstimated,
                    durationMs, errorMsg, skillNames, toolNames, mcpToolNames,
                    outputSummary, firstTokenMs, cachedTokens, attempt, maxAttempts,
                    turnId, traceId, spanId, parentSpan, startedAt,
                    routeId, routeCode, modelId, providerCode, upstreamName,
                    fallbackFromModelId, errorClass);
        }
    }
}
