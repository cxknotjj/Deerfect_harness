package com.dark.javaHarness.llm.call;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 合并后的调用参数：模型默认值 &lt; Agent 覆盖值。
 *
 * <p>thinking 拆成三层：模型能力（是否支持）在 {@code ModelCapabilities}；请求开关由
 * {@code thinkingMode} 表达；是否展示思考内容由 {@code showThinking} 表达。
 */
public record LlmCallOptions(String model,
                             Double temperature,
                             Integer maxTokens,
                             ThinkingMode thinkingMode,
                             boolean showThinking) {

    public enum ThinkingMode {
        /** 保持端点默认 */
        AUTO,
        /** 显式关闭思考 */
        OFF
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合并模型默认参数与 Agent 覆盖参数；两者均可为 null/空白。 */
    public static LlmCallOptions merge(String model, String modelDefaultParams, String agentParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(parse(modelDefaultParams));
        merged.putAll(parse(agentParams));
        return new LlmCallOptions(
                model,
                asDouble(merged.get("temperature")),
                asInt(merged.get("max_tokens")),
                "off".equalsIgnoreCase(String.valueOf(merged.get("thinking_mode"))) || Boolean.FALSE.equals(merged.get("thinking"))
                        ? ThinkingMode.OFF : ThinkingMode.AUTO,
                Boolean.TRUE.equals(merged.get("show_thinking")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("调用参数 JSON 非法: " + e.getMessage(), e);
        }
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
