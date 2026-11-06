package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 会话归档检索单测：会话范围过滤（current）/ 跨会话（all）、messageId/关键词二选一、条数上限。
 */
class SessionHistoryArchiveServiceTest {

    @SuppressWarnings("unchecked")
    private static QueryWrapper<SessionMessageArchiveEntity> captureQuery(
            SessionMessageArchiveMapper mapper) {
        ArgumentCaptor<QueryWrapper<SessionMessageArchiveEntity>> captor =
                ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static SessionMessageArchiveEntity row(String sessionId, String messageId, String content) {
        SessionMessageArchiveEntity row = new SessionMessageArchiveEntity();
        row.setId(1L);
        row.setSessionId(sessionId);
        row.setMessageId(messageId);
        row.setRole("user");
        row.setContent(content);
        return row;
    }

    @Test
    void currentScope_filtersBySessionAndMapsRows() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row("9", "m1", "早期内容")));
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        List<SessionHistoryArchiveService.ArchivedMessage> hits = service.search("9", "m1", null, 3);

        assertEquals(1, hits.size());
        assertEquals("m1", hits.get(0).messageId());
        assertEquals("9", hits.get(0).sessionId(), "记录应带来源会话（跨会话展示用）");
        String where = captureQuery(mapper).getSqlSegment();
        assertTrue(where.contains("session_id"), "默认范围必须限定会话: " + where);
    }

    /** 跨会话：不能出现任何 session_id 过滤条件，否则「上个月那次」永远搜不到 */
    @Test
    void allScope_dropsSessionFilter() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                row("7", "m7", "上个月那次"),
                row("9", "m9", "本会话内容")));
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        List<SessionHistoryArchiveService.ArchivedMessage> hits =
                service.search("9", null, "上个月", 3, true);

        assertEquals(2, hits.size());
        assertEquals("7", hits.get(0).sessionId(), "跨会话结果应保留各自来源会话");
        String where = captureQuery(mapper).getSqlSegment();
        assertFalse(where.contains("session_id"), "跨会话检索不得限定会话: " + where);
        assertTrue(where.contains("content"), "仍应按关键词过滤: " + where);
    }

    @Test
    void allScope_exactMessageIdNeedsNoSession() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row("7", "m7", "x")));
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        service.search(null, "m7", null, 3, true);

        String where = captureQuery(mapper).getSqlSegment();
        assertTrue(where.contains("message_id"), where);
        assertFalse(where.contains("session_id"), where);
    }

    /** 会话内检索缺会话 ID：直接返回空，不查库（防御模型上下文缺失时不误查全库） */
    @Test
    void currentScope_blankSession_returnsEmptyWithoutQuery() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        assertTrue(service.search(" ", null, "关键词", 3).isEmpty());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void search_clampsLimitToMaximum() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        service.search("9", null, "关键词", 99);

        assertTrue(captureQuery(mapper).getTargetSql()
                .contains("LIMIT " + SessionHistoryArchiveService.MAX_LIMIT));
    }

    /** 跨会话按归档时间倒序取数（「最近的先看到」），且 limit 仍受上限保护 */
    @Test
    void allScope_ordersByArchivedAtDesc() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        service.search(null, null, "关键词", 2, true);

        QueryWrapper<SessionMessageArchiveEntity> qw = captureQuery(mapper);
        assertTrue(qw.getTargetSql().contains("ORDER BY archived_at DESC"), qw.getTargetSql());
        assertTrue(qw.getTargetSql().contains("LIMIT 2"), qw.getTargetSql());
    }
}
