package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.domain.entity.ContextCompositionLogEntity;
import com.dark.javaHarness.mapper.ContextCompositionLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** 快照冷读：实体映射、坏行跳过、limit 收敛。 */
class ContextCompositionQueryServiceTest {

    private final ContextCompositionLogMapper mapper = mock(ContextCompositionLogMapper.class);
    private final ContextCompositionQueryService service =
            new ContextCompositionQueryService(mapper, new ObjectMapper());

    private ContextCompositionLogEntity row(long id, String sessionId, String parts) {
        ContextCompositionLogEntity e = new ContextCompositionLogEntity();
        e.setId(id);
        e.setSessionId(sessionId);
        e.setAgentName("general");
        e.setModel("qwen-plus");
        e.setTurnId("turn-" + id);
        e.setEstimatedTotalTokens(42);
        e.setParts(parts);
        e.setCreatedAt(LocalDateTime.of(2026, 11, 19, 12, 0));
        return e;
    }

    @Test
    void latest_mapsEntity() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row(7, "s1",
                "[{\"key\":\"user\",\"label\":\"用户输入\",\"group\":\"input\",\"tokens\":42,\"percent\":100.0}]")));

        ContextComposition c = service.latest("s1").orElseThrow();

        assertEquals("s1", c.sessionId());
        assertEquals("general", c.agentName());
        assertEquals(42, c.estimatedTotalTokens());
        assertEquals(1, c.parts().size());
        assertTrue(c.capturedAt() > 0);
    }

    @Test
    void latest_noRowsOrBrokenParts_empty() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertTrue(service.latest("s1").isEmpty());

        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row(1, "s1", "{broken")));
        assertTrue(service.latest("s1").isEmpty());
    }

    @Test
    void history_skipsBrokenRows() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                row(2, "s1", "[]"),
                row(1, "s1", "{broken")));

        List<ContextComposition> history = service.history("s1", 20);

        assertEquals(1, history.size());
        assertEquals("turn-2", history.get(0).turnId());
    }

    @Test
    void history_limitClampedTo1And100() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        service.history("s1", 999);
        service.history("s1", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ContextCompositionLogEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        assertTrue(captor.getAllValues().get(0).getSqlSegment().contains("LIMIT 100"));
        assertTrue(captor.getAllValues().get(1).getSqlSegment().contains("LIMIT 1"));
    }
}
