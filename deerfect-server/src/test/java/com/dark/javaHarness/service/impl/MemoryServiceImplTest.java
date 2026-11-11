package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.domain.entity.MemoryEntity;
import com.dark.javaHarness.mapper.MemoryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MemoryServiceImpl 单测：同 key 覆盖、软删、状态判定（有效/过期/已删）、关键词定位与参数校验。
 *
 * <p>状态是读取时按 now 计算的，所以这里用「mock 返回一行，改了 expiresAt/deletedAt」
 * 来驱动三种状态，而不是断言 SQL 片段——SQL 过滤在真实库里由索引承担。
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceImplTest {

    @Mock
    private MemoryMapper mapper;

    private MemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemoryServiceImpl(mapper);
    }

    @Test
    void remember_normalizesAndPersists() {
        stubInsertAssignsId(7L);

        Memory memory = service.remember("  我的车牌是沪A12345 ", " 车牌 ", "user", "12", null);

        assertEquals(7L, memory.id());
        assertEquals("车牌", memory.key(), "key 应去除首尾空白");
        assertEquals("我的车牌是沪A12345", memory.content(), "正文应去除首尾空白");
        assertEquals("user", memory.source());
        assertEquals("12", memory.sessionId());
        assertEquals(Memory.Status.ACTIVE, memory.status());
        verify(mapper).insert(any(MemoryEntity.class));
    }

    @Test
    void remember_sameKey_softDeletesOldBeforeInsert() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        stubInsertAssignsId(8L);

        Memory memory = service.remember("新车牌是沪B00001", "车牌", "user", null, null);

        assertEquals(8L, memory.id());
        verify(mapper).update(isNull(), any());
    }

    @Test
    void remember_blankContent_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.remember("   ", null, "user", null, null));
        verify(mapper, never()).insert(any(MemoryEntity.class));
    }

    @Test
    void remember_tooLongContent_rejected() {
        String tooLong = "记".repeat(1001);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.remember(tooLong, null, "user", null, null));
        assertTrue(e.getMessage().contains("不能超过"));
    }

    @Test
    void remember_tooLongKey_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.remember("内容", "标".repeat(65), "user", null, null));
    }

    @Test
    void remember_invalidSource_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.remember("内容", null, "bot", null, null));
    }

    @Test
    void countActiveByKey_blankKeyReturnsZero() {
        assertEquals(0, service.countActiveByKey(" "));
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void countActiveByKey_returnsCount() {
        when(mapper.selectCount(any())).thenReturn(2L);
        assertEquals(2, service.countActiveByKey("技术栈"));
    }

    @Test
    void forget_returnsTrueWhenRowUpdated() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertTrue(service.forget(3));
    }

    @Test
    void forget_returnsFalseWhenAlreadyDeleted() {
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertFalse(service.forget(3));
    }

    @Test
    void forgetByKey_blankKeySkipsDatabase() {
        assertEquals(0, service.forgetByKey("  "));
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void forgetByKey_softDeletesAllWithKey() {
        when(mapper.update(isNull(), any())).thenReturn(2);
        assertEquals(2, service.forgetByKey("车牌"));
    }

    @Test
    void list_mapsThreeStatesByTime() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.selectList(any())).thenReturn(List.of(
                row(1L, "有效", null, now.plusDays(1)),
                row(2L, "过期", null, now.minusDays(1)),
                row(3L, "已删", now.minusHours(1), null)));

        List<Memory> all = service.list(null, 10);

        assertEquals(3, all.size());
        assertEquals(Memory.Status.ACTIVE, all.get(0).status());
        assertEquals(Memory.Status.EXPIRED, all.get(1).status(), "已过 expires_at 应判为过期");
        assertEquals(Memory.Status.DELETED, all.get(2).status(), "有 deleted_at 应判为已删");
    }

    @Test
    void findActive_expiredRowReturnsEmpty() {
        when(mapper.selectById(2L)).thenReturn(row(2L, "过期", null, LocalDateTime.now().minusDays(1)));
        assertTrue(service.findActive(2L).isEmpty());
    }

    @Test
    void findActive_deletedRowReturnsEmpty() {
        when(mapper.selectById(3L)).thenReturn(row(3L, "已删", LocalDateTime.now(), null));
        assertTrue(service.findActive(3L).isEmpty());
    }

    @Test
    void findActive_activeRowReturned() {
        when(mapper.selectById(1L)).thenReturn(row(1L, "有效", null, null));
        Optional<Memory> memory = service.findActive(1L);
        assertTrue(memory.isPresent());
        assertEquals("有效", memory.get().content());
    }

    @Test
    void findActiveByKeyword_blankReturnsEmptyWithoutQuery() {
        assertEquals(List.of(), service.findActiveByKeyword("  "));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void findActiveByKeyword_filtersExpiredRows() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.selectList(any())).thenReturn(List.of(
                row(1L, "车牌沪A", null, null),
                row(2L, "车牌沪B", null, now.minusDays(1))));

        List<Memory> hits = service.findActiveByKeyword("车牌");

        assertEquals(1, hits.size(), "过期记忆不应参与 forget 定位");
        assertEquals(1L, hits.get(0).id());
    }

    private void stubInsertAssignsId(long id) {
        doAnswer(invocation -> {
            MemoryEntity row = invocation.getArgument(0);
            row.setId(id);
            return 1;
        }).when(mapper).insert(any(MemoryEntity.class));
    }

    private static MemoryEntity row(long id, String content, LocalDateTime deletedAt, LocalDateTime expiresAt) {
        MemoryEntity row = new MemoryEntity();
        row.setId(id);
        row.setTenantId("default");
        row.setMemoryKey("车牌");
        row.setContent(content);
        row.setSource("user");
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        row.setExpiresAt(expiresAt);
        row.setDeletedAt(deletedAt);
        return row;
    }
}
