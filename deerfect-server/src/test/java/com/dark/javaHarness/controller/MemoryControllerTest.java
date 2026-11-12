package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import com.dark.javaHarness.view.MemoryCreateRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MemoryController 单测：状态参数解析、CLI/管理通道的来源固定为 manual、
 * 删除不存在的记忆返回可读错误（由全局处理器映射 400）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryControllerTest {

    @Mock
    private MemoryService memoryService;

    @InjectMocks
    private MemoryController controller;

    @Test
    void list_activeByDefault_mapsStatus() {
        when(memoryService.list(Memory.Status.ACTIVE, 50))
                .thenReturn(List.of(memory(3L, "车牌", "沪A12345", Memory.Status.ACTIVE)));

        var view = controller.list("active", 50);

        assertEquals(1, view.items().size());
        assertEquals(3L, view.items().get(0).id());
        assertEquals("车牌", view.items().get(0).key());
        assertEquals("ACTIVE", view.items().get(0).status());
    }

    @Test
    void list_statusIsCaseInsensitive() {
        when(memoryService.list(Memory.Status.EXPIRED, 10)).thenReturn(List.of());
        controller.list("EXPIRED", 10);
        verify(memoryService).list(Memory.Status.EXPIRED, 10);
    }

    @Test
    void list_invalidStatus_rejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.list("bogus", 50));
        assertTrue(e.getMessage().contains("active / expired / deleted"));
    }

    @Test
    void create_usesManualSource() {
        when(memoryService.remember("我的车牌是沪A12345", "车牌", "manual", null, null))
                .thenReturn(memory(8L, "车牌", "我的车牌是沪A12345", Memory.Status.ACTIVE));

        var view = controller.create(new MemoryCreateRequest("我的车牌是沪A12345", "车牌", null));

        assertEquals(8L, view.id());
        assertEquals("manual", view.source());
    }

    @Test
    void create_nullBody_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.create(null));
    }

    @Test
    void delete_existing_delegates() {
        when(memoryService.forget(3L)).thenReturn(true);
        controller.delete(3L);
        verify(memoryService).forget(3L);
    }

    @Test
    void delete_missing_rejected() {
        when(memoryService.forget(99L)).thenReturn(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.delete(99L));
        assertTrue(e.getMessage().contains("不存在或已删除"));
    }

    private static Memory memory(long id, String key, String content, Memory.Status status) {
        return new Memory(id, key, content, "manual", null,
                LocalDateTime.of(2026, 11, 7, 10, 0), null, status);
    }
}
