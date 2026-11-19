package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.prompt.ContextCompositionQueryService;
import com.dark.javaHarness.prompt.ContextCompositionStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 快照查询接口：内存优先、DB 回退、双空 404、历史委托。 */
class ContextCompositionControllerTest {

    private final ContextCompositionStore store = mock(ContextCompositionStore.class);
    private final ContextCompositionQueryService query = mock(ContextCompositionQueryService.class);
    private final ContextCompositionController controller = new ContextCompositionController(store, query);

    private ContextComposition snapshot(String sessionId) {
        return new ContextComposition(sessionId, "general", "m", "t", 1L, 10,
                List.of(new ContextComposition.Part("user", "用户输入", "input", 10, 100.0)));
    }

    @Test
    void get_memoryHit_doesNotQueryDb() {
        when(store.get("s1")).thenReturn(Optional.of(snapshot("s1")));

        var response = controller.get("s1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(query);
    }

    @Test
    void get_memoryMiss_fallsBackToDb() {
        when(store.get("s1")).thenReturn(Optional.empty());
        when(query.latest("s1")).thenReturn(Optional.of(snapshot("s1")));

        var response = controller.get("s1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("s1", response.getBody().sessionId());
    }

    @Test
    void get_bothEmpty_notFound() {
        when(store.get("s1")).thenReturn(Optional.empty());
        when(query.latest("s1")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.get("s1").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.get(null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.get(" ").getStatusCode());
    }

    @Test
    void history_delegatesAndBlankReturnsEmpty() {
        when(query.history("s1", 20)).thenReturn(List.of(snapshot("s1")));

        assertEquals(1, controller.history("s1", 20).size());
        assertTrue(controller.history(" ", 20).isEmpty());
        verify(query).history("s1", 20);
    }
}
