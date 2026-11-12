package com.dark.javaHarness.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dark.javaHarness.prompt.SkillRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillControllerTest {

    @Test
    void list_returnsInspectionResult() throws Exception {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.inspect()).thenReturn(List.of(
                new SkillRepository.SkillInfo("alpha", "第一个技能", Set.of("researcher"),
                        "alpha/SKILL.md", 42, null),
                new SkillRepository.SkillInfo("broken", "", null,
                        "broken/SKILL.md", 10, "front-matter 未闭合")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SkillController(repository)).build();
        mvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].description").value("第一个技能"))
                .andExpect(jsonPath("$[0].entryFile").value("alpha/SKILL.md"))
                .andExpect(jsonPath("$[0].size").value(42))
                .andExpect(jsonPath("$[1].name").value("broken"))
                .andExpect(jsonPath("$[1].error").value("front-matter 未闭合"));
    }
}
