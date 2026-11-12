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
}
