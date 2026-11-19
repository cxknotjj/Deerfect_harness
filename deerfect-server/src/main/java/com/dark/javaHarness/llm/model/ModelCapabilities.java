package com.dark.javaHarness.llm.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
        JsonNode node = parseObject(json);
        return new ModelCapabilities(
                node.path("tool_calling").asBoolean(false),
                node.path("vision").asBoolean(false),
                node.path("thinking").asBoolean(false),
                node.path("json_mode").asBoolean(false),
                node.path("context_window").asInt(0));
    }

    /** 可变对象视图：空/缺失返回空对象（供管理端仅替换单个键时保留其余能力字段） */
    public static ObjectNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型 capabilities JSON 非法: " + e.getMessage(), e);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("模型 capabilities 必须是 JSON 对象");
        }
        return (ObjectNode) node;
    }
}
