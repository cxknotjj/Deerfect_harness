package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.dark.javaHarness.domain.dto.SessionMessagesView;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;

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
    @Mock
    private OneBotSessionBindingMapper bindingMapper;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionServiceImpl(sessionMapper, messageMapper,
                new ObjectMapper(), agentConfigProvider, bindingMapper);
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

    /** listMessages：会话上下文快照 JSON 按角色还原为 role/content 展示列表 */
    @Test
    void listMessages_mapsSnapshotToItems() {
        SessionMessageEntity row = new SessionMessageEntity();
        row.setContent("""
                [{"role":"user","content":"你好"},{"role":"assistant","content":"在的"}]""");
        when(messageMapper.selectOne(any())).thenReturn(row);

        List<SessionMessagesView.Item> items = sessionService.listMessages("9");

        assertEquals(2, items.size(), "应还原快照中的两条消息");
        assertEquals("user", items.get(0).role());
        assertEquals("你好", items.get(0).content());
        assertEquals("assistant", items.get(1).role());
        assertEquals("在的", items.get(1).content());
    }

    /** listMessages：无快照行时返回空列表（会话未产生历史） */
    @Test
    void listMessages_noSnapshot_returnsEmpty() {
        when(messageMapper.selectOne(any())).thenReturn(null);

        assertTrue(sessionService.listMessages("9").isEmpty(), "无历史应返回空列表");
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

    /* ---------------- 删除会话（软删 + 快照清理 + 绑定清理） ---------------- */

    /** deleteSession：软删 session（deleteById 经 @TableLogic 转软删）+ 物理删快照行 + 清 QQ 绑定行 */
    @Test
    void deleteSession_softDeletesAndCleansSnapshotAndBinding() {
        sessionService.deleteSession("9");

        verify(sessionMapper).deleteById(9L);
        ArgumentCaptor<Wrapper<SessionMessageEntity>> msgCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(messageMapper).delete(msgCaptor.capture());
        assertTrue(msgCaptor.getValue().getTargetSql().contains("session_id"), "快照删除应限定在该会话");
        ArgumentCaptor<Wrapper<OneBotSessionBinding>> bindCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(bindingMapper).delete(bindCaptor.capture());
        assertTrue(bindCaptor.getValue().getTargetSql().contains("session_id"), "绑定删除应限定在该会话");
    }

    /** deleteSession：非法 sessionId 幂等静默返回，不触碰任何表 */
    @Test
    void deleteSession_illegalId_noop() {
        sessionService.deleteSession("abc");

        verifyNoInteractions(sessionMapper, messageMapper, bindingMapper);
    }

    /** saveContext 防复活守卫：会话已软删/不存在时跳过快照写回（不产生孤儿快照行） */
    @Test
    void saveContext_deletedSession_skipsWrite() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        sessionService.saveContext("9", new UserMessage("你好"));

        verify(messageMapper, never()).insert(any(SessionMessageEntity.class));
        verify(messageMapper, never()).update(any(), any());
    }

    /** saveContext：会话存在时守卫不误伤，正常落快照 */
    @Test
    void saveContext_sessionExists_writes() {
        when(sessionMapper.selectOne(any())).thenReturn(session(9L, 1));
        when(messageMapper.selectOne(any())).thenReturn(null);

        sessionService.saveContext("9", new UserMessage("你好"));

        verify(messageMapper).insert(any(SessionMessageEntity.class));
    }
}
