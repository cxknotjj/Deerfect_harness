package com.dark.javaHarness.llm.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 参数合并：模型默认值 &lt; Agent 覆盖；thinking 语义拆分。 */
class LlmCallOptionsTest {

    @Test
    void agentOverridesModelDefaults() {
        LlmCallOptions options = LlmCallOptions.merge("m",
                "{\"temperature\":0.7,\"max_tokens\":2048}",
                "{\"temperature\":0.2,\"max_tokens\":8192,\"show_thinking\":true}");

        assertEquals(0.2, options.temperature());
        assertEquals(8192, options.maxTokens());
        assertEquals(true, options.showThinking());
        assertEquals(LlmCallOptions.ThinkingMode.AUTO, options.thinkingMode());
    }

    @Test
    void thinkingModeOff_recognized() {
        LlmCallOptions options = LlmCallOptions.merge("m", null, "{\"thinking_mode\":\"off\"}");
        assertEquals(LlmCallOptions.ThinkingMode.OFF, options.thinkingMode());
    }

    @Test
    void emptyParams_yieldNulls() {
        LlmCallOptions options = LlmCallOptions.merge("m", null, null);

        assertNull(options.temperature());
        assertNull(options.maxTokens());
        assertEquals(false, options.showThinking());
    }
}
