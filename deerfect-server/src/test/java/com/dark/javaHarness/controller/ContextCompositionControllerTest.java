package com.dark.javaHarness.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.prompt.ContextCompositionStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 上下文快照查询接口单测：命中 200、无快照/空 sessionId 404。
 */
class ContextCompositionControllerTest {

    @Test
    void get_existingSnapshot_returns200() throws Exception {
        ContextCompositionStore store = new ContextCompositionStore();
        store.put(new ContextComposition("s1", "general", "m1", "t1", 0, 10,
                List.of(new ContextComposition.Part("system.role", "角色提示词", "system",
                        10, 100.0))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContextCompositionController(store)).build();

        mvc.perform(get("/api/monitor/context").param("sessionId", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.agentName").value("general"))
                .andExpect(jsonPath("$.turnId").value("t1"))
                .andExpect(jsonPath("$.estimatedTotalTokens").value(10))
                .andExpect(jsonPath("$.parts[0].key").value("system.role"))
                .andExpect(jsonPath("$.parts[0].percent").value(100.0));
    }

    @Test
    void get_missingOrBlankSession_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ContextCompositionController(new ContextCompositionStore())).build();

        mvc.perform(get("/api/monitor/context").param("sessionId", "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitor/context").param("sessionId", "  "))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitor/context"))
                .andExpect(status().isNotFound());
    }
}
