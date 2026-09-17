package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * UserProfileService 用户偏好画像提取单测（真实临时目录文件 IO + mock LLM 链）：
 * - stale 会话触发提炼：合并结果原子落盘 + profile_extracted 标记 + llm_call_log 落库（memory-profile）
 * - LLM 失败/空输出不落盘不标记（防抹掉画像）；文件写失败不标记（下轮重试）
 * - 空快照直接标记跳过（不调 LLM）；扫描查询条件含 profile_extracted=0 + last_active_at 阈值
 * - 多会话逐个独立处理：单个失败不影响后续
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionService sessionService;
    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private LlmCallRecorder recorder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @TempDir
    Path tempDir;

    private UserProfileService service;
    private Path profileFile;

    @BeforeEach
    void setUp() {
        profileFile = tempDir.resolve("user-profile.md");
        service = new UserProfileService(sessionMapper, sessionService, clientRegistry, recorder,
                profileFile.toString(), 30, 4000, 300000);
    }

    private SessionEntity staleSession(long id) {
        SessionEntity s = new SessionEntity();
        s.setSessionId(id);
        s.setLastActiveAt(LocalDateTime.now().minusMinutes(60));
        s.setProfileExtracted(0);
        return s;
    }

    private void stubScan(SessionEntity... sessions) {
        when(sessionMapper.selectList(any())).thenReturn(List.of(sessions));
    }

    private void stubLlm(String content) {
        when(clientRegistry.getByModel(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
    }

    private void stubContext(String sessionId, String... dialogLines) {
        if (dialogLines.length == 0) {
            when(sessionService.loadContext(sessionId)).thenReturn(List.of());
            return;
        }
        java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < dialogLines.length; i++) {
            messages.add(i % 2 == 0 ? new UserMessage(dialogLines[i]) : new AssistantMessage(dialogLines[i]));
        }
        when(sessionService.loadContext(sessionId)).thenReturn(messages);
    }

    private String markedSessionIds() {
        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        StringBuilder ids = new StringBuilder();
        for (Wrapper<SessionEntity> w : captor.getAllValues()) {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw =
                    (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w;
            // 先渲染 SQL（惰性求值：eq 条件参数此刻才注册进 paramNameValuePairs），再取参数值
            aw.getSqlSegment();
            ids.append(aw.getParamNameValuePairs().values());
        }
        return ids.toString();
    }

    /* ---------------- 核心流程：提炼合并落盘 + 标记 ---------------- */

    @Test
    void staleSession_mergesWritesAndMarks() throws Exception {
        stubScan(staleSession(9L));
        stubContext("9", "以后 SQL 都帮我写成批量的", "好的，已记住");
        stubLlm("- 偏好简洁回复\n- SQL 倾向批量写法");

        service.scanOnce();

        assertEquals("- 偏好简洁回复\n- SQL 倾向批量写法", Files.readString(profileFile),
                "合并结果应原子落盘到画像文件");
        assertTrue(markedSessionIds().contains("9"), "落盘成功后应标记 profile_extracted=1");
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(recorder).record(logCaptor.capture());
        assertEquals("memory-profile", logCaptor.getValue().agentName(), "llm_call_log 应以 memory-profile 口径落库");
    }

    /* ---------------- 降级：失败不标记（下轮重试） ---------------- */

    @Test
    void llmFailure_keepsFileAndSkipsMark() throws Exception {
        stubScan(staleSession(9L));
        stubContext("9", "用户提到了偏好");
        when(clientRegistry.getByModel(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("boom"));

        service.scanOnce();

        assertFalse(Files.exists(profileFile), "LLM 失败不应写盘");
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void blankLlmOutput_doesNotWipeExistingProfile() throws Exception {
        Files.writeString(profileFile, "- 既有偏好条目");
        stubScan(staleSession(9L));
        stubContext("9", "聊天内容");
        stubLlm("   ");

        service.scanOnce();

        assertEquals("- 既有偏好条目", Files.readString(profileFile), "空输出不得抹掉既有画像");
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void fileWriteFailure_doesNotMark() {
        // profileFile 的父目录被一个普通文件占用 → createDirectories 必败 → 写盘失败 → 不标记
        Path occupied = tempDir.resolve("occupied");
        Path blocked = occupied.resolve("user-profile.md");
        service = new UserProfileService(sessionMapper, sessionService, clientRegistry, recorder,
                blocked.toString(), 30, 4000, 300000);
        try {
            Files.writeString(occupied, "i-am-a-file");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        stubScan(staleSession(9L));
        stubContext("9", "聊天内容");
        stubLlm("- 新画像");

        service.scanOnce();

        verify(sessionMapper, never()).update(any(), any());
    }

    /* ---------------- 边界：空快照 / 查询条件 / 多会话独立处理 ---------------- */

    @Test
    void emptySnapshot_marksWithoutLlm() {
        stubScan(staleSession(9L));
        stubContext("9");

        service.scanOnce();

        verify(clientRegistry, never()).getByModel(anyString());
        assertTrue(markedSessionIds().contains("9"), "无内容可提炼也应标记（避免空转重扫）");
    }

    @Test
    void scanQuery_filtersPendingAndStale() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        service.scanOnce();

        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).selectList(captor.capture());
        QueryWrapper<?> qw = (QueryWrapper<?>) captor.getValue();
        String sql = qw.getSqlSegment();
        assertTrue(sql.contains("profile_extracted"), "扫描应限定待提炼会话");
        assertTrue(sql.contains("last_active_at"), "扫描应限定静默超阈值的会话");
    }

    @Test
    void multipleSessions_processedIndependently() throws Exception {
        SessionEntity s9 = staleSession(9L);
        SessionEntity s10 = staleSession(10L);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s9, s10));
        stubContext("9", "会话九");
        stubContext("10", "会话十", "回复");
        // 第一次调用（会话 9）抛异常，第二次（会话 10）成功
        when(chatClient.prompt()).thenThrow(new RuntimeException("first fails"))
                .thenReturn(requestSpec);
        when(clientRegistry.getByModel(anyString())).thenReturn(chatClient);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("- 会话十的偏好")))));

        service.scanOnce();

        String marked = markedSessionIds();
        assertTrue(marked.contains("10"), "后一个会话应正常提炼标记");
        assertFalse(marked.contains("9"), "失败的会话不应被标记");
        assertEquals("- 会话十的偏好", Files.readString(profileFile));
    }
}
