package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SessionServiceImpl 会话切换 Agent 单测：
 * - 合法切换更新 session 表 agent_id
 * - 与当前值相同跳过写库（幂等）
 * - 会话不存在 / agentId 不存在 / 非法入参 → IllegalArgumentException 且不写库
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionMessageMapper messageMapper;
    @Mock
    private AgentConfigProvider agentConfigProvider;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionServiceImpl(sessionMapper, messageMapper,
                new ObjectMapper(), agentConfigProvider);
    }

    private SessionEntity session(long id, int agentId) {
        SessionEntity entity = new SessionEntity();
        entity.setSessionId(id);
        entity.setAgentId(agentId);
        return entity;
    }

    @Test
    void switchAgent_updatesSessionAgentId() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 1));
        when(agentConfigProvider.findAgentNameById(3L)).thenReturn(Optional.of("deepseek"));

        sessionService.switchAgent("9", 3L);

        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).update(eq(null), captor.capture());
        UpdateWrapper<?> uw = (UpdateWrapper<?>) captor.getValue();
        assertTrue(uw.getSqlSet().contains("agent_id"), "应更新 agent_id 字段");
        assertTrue(uw.getTargetSql().contains("session_id"), "更新条件应限定在该会话");
        assertTrue(uw.getParamNameValuePairs().containsValue(3L), "目标值应为新 agentId");
    }

    /* ---------------- 画像提取支持：session 表时间列（V19） ---------------- */

    /** 建档初始化画像扫描依据列：last_active_at=now、profile_extracted=0（待提炼） */
    @Test
    void createSession_initializesProfileColumns() {
        org.mockito.ArgumentCaptor<SessionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(SessionEntity.class);

        sessionService.createSession("cli", "你好");

        verify(sessionMapper).insert(captor.capture());
        SessionEntity inserted = captor.getValue();
        assertTrue(inserted.getLastActiveAt() != null, "建档应初始化 last_active_at");
        assertTrue(inserted.getProfileExtracted() != null && inserted.getProfileExtracted() == 0,
                "建档应初始化 profile_extracted=0（待提炼）");
    }

    /** touchSession 应同步刷新 last_active_at（画像扫描的活跃依据） */
    @Test
    void touchSession_updatesLastActiveAt() {
        sessionService.touchSession("9", "最新提问");

        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).update(eq(null), captor.capture());
        UpdateWrapper<?> uw = (UpdateWrapper<?>) captor.getValue();
        assertTrue(uw.getSqlSet().contains("last_active_at"), "应刷新 last_active_at 字段");
        assertTrue(uw.getSqlSet().contains("last_question"), "保留既有 last_question 更新");
    }

    @Test
    void switchAgent_sameAgent_skipsUpdate() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 3));
        when(agentConfigProvider.findAgentNameById(3L)).thenReturn(Optional.of("deepseek"));

        sessionService.switchAgent("9", 3L);

        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void switchAgent_sessionMissing_throws() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> sessionService.switchAgent("99", 3L));

        verifyNoInteractions(agentConfigProvider);
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void switchAgent_agentMissing_throws() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 1));
        when(agentConfigProvider.findAgentNameById(42L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> sessionService.switchAgent("9", 42L));

        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void switchAgent_illegalArguments_throws() {
        assertThrows(IllegalArgumentException.class, () -> sessionService.switchAgent("abc", 3L),
                "非法 sessionId 应拒绝");
        assertThrows(IllegalArgumentException.class, () -> sessionService.switchAgent("9", null),
                "agentId 为空应拒绝");

        verify(sessionMapper, never()).update(any(), any());
    }
}
