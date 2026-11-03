package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 会话归档检索单测：会话范围强制过滤、messageId/关键词二选一、条数上限。
 */
class SessionHistoryArchiveServiceTest {

    @Test
    void search_filtersBySessionAndMapsRows() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        SessionMessageArchiveEntity row = new SessionMessageArchiveEntity();
        row.setId(1L);
        row.setSessionId("9");
        row.setMessageId("m1");
        row.setRole("user");
        row.setContent("早期内容");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        List<SessionHistoryArchiveService.ArchivedMessage> hits = service.search("9", "m1", null, 3);

        assertEquals(1, hits.size());
        assertEquals("m1", hits.get(0).messageId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<SessionMessageArchiveEntity>> captor =
                ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getTargetSql().contains("session_id"), "查询必须限定会话");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("9"), "会话值应参数绑定");
    }

    @Test
    void search_clampsLimitToMaximum() {
        SessionMessageArchiveMapper mapper = mock(SessionMessageArchiveMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        SessionHistoryArchiveService service = new SessionHistoryArchiveService(mapper);

        service.search("9", null, "关键词", 99);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<SessionMessageArchiveEntity>> captor =
                ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getTargetSql().contains("LIMIT " + SessionHistoryArchiveService.MAX_LIMIT));
    }
}
