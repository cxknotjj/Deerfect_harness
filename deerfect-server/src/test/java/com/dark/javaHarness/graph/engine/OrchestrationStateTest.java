package com.dark.javaHarness.graph.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestrationStateTest {

    @Test
    void typedValueAndSnapshot() {
        OrchestrationState state = new OrchestrationState("goal-1", Map.of("objective", "查天气"));
        assertTrue(state.value("objective", String.class).isPresent());
        assertEquals("查天气", state.value("objective", String.class).orElseThrow());
        assertFalse(state.value("missing", String.class).isPresent());
        assertEquals("goal-1", state.goalId());
        assertEquals("查天气", state.snapshot().get("objective"));
    }

    @Test
    void putAllMergesDelta() {
        OrchestrationState state = new OrchestrationState("goal-2", Map.of());
        state.putAll(Map.of("result_0", "ok"));
        assertEquals("ok", state.value("result_0", String.class).orElseThrow());
    }
}
