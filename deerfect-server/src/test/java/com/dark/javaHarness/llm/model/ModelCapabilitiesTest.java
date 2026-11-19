package com.dark.javaHarness.llm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 能力 JSON 解析。 */
class ModelCapabilitiesTest {

    @Test
    void parse_allFields() {
        ModelCapabilities caps = ModelCapabilities.parse(
                "{\"tool_calling\":true,\"vision\":true,\"thinking\":true,\"json_mode\":true,\"context_window\":131072}");

        assertEquals(true, caps.toolCalling());
        assertEquals(true, caps.vision());
        assertEquals(true, caps.thinking());
        assertEquals(true, caps.jsonMode());
        assertEquals(131072, caps.contextWindow());
    }

    @Test
    void parse_missingFields_defaultFalse() {
        ModelCapabilities caps = ModelCapabilities.parse("{}");

        assertEquals(false, caps.toolCalling());
        assertEquals(0, caps.contextWindow());
    }

    @Test
    void parse_blank_returnsEmptyCapabilities() {
        assertEquals(false, ModelCapabilities.parse(" ").toolCalling());
    }

    @Test
    void parse_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> ModelCapabilities.parse("{oops"));
    }

    @Test
    void parseObject_blank_returnsMutableEmptyObject() {
        var node = ModelCapabilities.parseObject(null);
        node.put("context_window", 600000);
        assertEquals(600000, ModelCapabilities.parse(node.toString()).contextWindow());
    }

    @Test
    void parseObject_nonObject_throws() {
        assertThrows(IllegalArgumentException.class, () -> ModelCapabilities.parseObject("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> ModelCapabilities.parseObject("42"));
    }

    @Test
    void parseObject_replacingOneKey_keepsOthers() {
        var node = ModelCapabilities.parseObject("{\"tool_calling\":true,\"context_window\":1}");
        node.put("context_window", 2);
        assertEquals(true, node.path("tool_calling").asBoolean());
        assertEquals(2, node.path("context_window").asInt());
    }
}
