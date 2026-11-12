package com.dark.javaHarness.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 目录：按名命中、未命中抛错、懒加载。 */
class AgentCatalogTest {

    @Test
    void require_hitsLoadedAgent() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(agent("general", 1)));
        AgentCatalog catalog = new AgentCatalog(mapper);

        assertEquals("general", catalog.require("general").getAgentName());
        verify(mapper).selectList(any());
    }

    @Test
    void require_missingAgent_throws() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(agent("general", 1)));
        AgentCatalog catalog = new AgentCatalog(mapper);

        assertThrows(IllegalArgumentException.class, () -> catalog.require("nope"));
    }

    private static AgentEntity agent(String name, int status) {
        AgentEntity agent = new AgentEntity();
        agent.setAgentName(name);
        agent.setStatus(status);
        return agent;
    }
}
