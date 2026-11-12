package com.dark.javaHarness.llm.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型能力：端点是否支持工具调用、图片、思考开关、JSON 模式与上下文窗口。
 *
 * <p>这些是模型/端点的技术事实，不是 Agent 的授权；Agent 能用哪些工具由 agent.tools 决定。
 */
public record ModelCapabilities(boolean toolCalling,
                                boolean vision,
                                boolean thinking,
                                boolean jsonMode,
                                int contextWindow) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ModelCapabilities parse(String json) {
        if (json == null || json.isBlank()) {
            return new ModelCapabilities(false, false, false, false, 0);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new ModelCapabilities(
                    node.path("tool_calling").asBoolean(false),
                    node.path("vision").asBoolean(false),
                    node.path("thinking").asBoolean(false),
                    node.path("json_mode").asBoolean(false),
                    node.path("context_window").asInt(0));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型 capabilities JSON 非法: " + e.getMessage(), e);
        }
    }
}
