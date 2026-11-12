package com.dark.javaHarness.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 元工具入参解析公共工具：统一对象键、JSON 裸字符串与非 JSON 裸名的兜底口径。
 * 供 expand_tool / load_skill 等元工具复用，避免各写一份提取逻辑。
 */
public final class ToolInputs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInputs() {
    }

    /**
     * 提取字符串入参：JSON 对象按 keys 顺序取第一个非空文本；JSON 字符串直接取值；
     * 非 JSON 入参去引号后作为裸值。全部为空返回 null。
     */
    public static String text(String input, String... keys) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root.isObject()) {
                for (String key : keys) {
                    JsonNode value = root.path(key);
                    if (value.isValueNode() && !value.asText().isBlank()) {
                        return value.asText().trim();
                    }
                }
                return null;
            }
            if (root.isTextual() && !root.asText().isBlank()) {
                return root.asText().trim();
            }
            return null;
        } catch (Exception ignored) {
            String bare = input.trim();
            if (bare.length() >= 2 && bare.startsWith("\"") && bare.endsWith("\"")) {
                bare = bare.substring(1, bare.length() - 1).trim();
            }
            return bare.isEmpty() ? null : bare;
        }
    }

    /** 只从 JSON 对象提取字段：裸字符串/非 JSON 入参返回 null（避免把裸技能名误当附件名） */
    public static String objectText(String input, String key) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root.isObject()) {
                JsonNode value = root.path(key);
                if (value.isValueNode() && !value.asText().isBlank()) {
                    return value.asText().trim();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 入参没有结构化字段
        }
        return null;
    }

    /** 提取整数入参：JSON 整数或可解析的文本整数；缺失/非法返回 0 */
    public static int integer(String input, String key) {
        if (input == null || input.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            JsonNode value = root.path(key);
            if (value.isIntegralNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 入参没有结构化字段
        }
        return 0;
    }
}
