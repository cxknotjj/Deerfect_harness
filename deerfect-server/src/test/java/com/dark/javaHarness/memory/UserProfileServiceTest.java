package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.model.SessionSnapshotItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * UserProfileService 增量提炼单测（真实临时目录文件 IO + mock LLM 链）：
 * - 首次提炼：补丁按 key 合并落盘，推进游标并记录提炼时刻
 * - 增量：第二轮只把游标之后的消息喂给模型（旧消息不重复进 prompt）
 * - 去重：同 key 覆盖而非追加；旧版无 key 条目被同值带 key 条目替换
 * - 降级：LLM 失败/空输出/写盘失败都不推进游标（下轮重试），NONE 推进但不写文件
 * - 护栏：单次最多喂 40 条新消息（取尾部）；主画像被清空时从 .bak 恢复
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionService sessionService;
    @Mock
    private com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry;
    @Mock
    private com.dark.javaHarness.llm.catalog.AgentCatalog agentCatalog;
    @Mock
    private com.dark.javaHarness.llm.routing.ModelResolver modelResolver;
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
        service = newService(profileFile);
    }

    private UserProfileService newService(Path file) {
        return new UserProfileService(sessionMapper, sessionService, llmClientRegistry, agentCatalog,
                modelResolver, recorder, file.toString(), 30, 4000, 300000);
    }

    private SessionEntity staleSession(long id) {
        SessionEntity s = new SessionEntity();
        s.setSessionId(id);
        s.setLastActiveAt(LocalDateTime.now().minusMinutes(60));
        return s;
    }

    private static SessionSnapshotItem item(String id, String role, String content) {
        return new SessionSnapshotItem(id, role, content, System.currentTimeMillis());
    }

    private void stubScan(SessionEntity... sessions) {
        when(sessionMapper.selectList(any())).thenReturn(List.of(sessions));
    }

    private void stubItems(String sessionId, SessionSnapshotItem... items) {
        when(sessionService.snapshotItems(sessionId)).thenReturn(List.of(items));
    }

    private void stubLlm(String content) {
        stubRoute();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
    }

    private void stubRoute() {
        com.dark.javaHarness.domain.entity.AgentEntity agent =
                new com.dark.javaHarness.domain.entity.AgentEntity();
        agent.setAgentName("profile");
        agent.setRouteId(1L);
        when(agentCatalog.require("profile")).thenReturn(agent);
        com.dark.javaHarness.llm.routing.RoutePlan.Member member =
                new com.dark.javaHarness.llm.routing.RoutePlan.Member(10L, 1L, "dashscope",
                        "https://example.com", "qwen3.8-27b", "m", 10, "{}", null);
        when(modelResolver.resolve(1L)).thenReturn(
                new com.dark.javaHarness.llm.routing.RoutePlan(1L, "fast", List.of(member)));
        when(llmClientRegistry.get(10L)).thenReturn(chatClient);
    }

    /** 提炼进度更新（markUpToDate）：取最后一次 update 的 Wrapper */
    @SuppressWarnings("unchecked")
    private UpdateWrapper<SessionEntity> markWrapper() {
        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper, atLeastOnce()).update(any(), captor.capture());
        UpdateWrapper<SessionEntity> uw =
                (UpdateWrapper<SessionEntity>) captor.getAllValues().get(captor.getAllValues().size() - 1);
        // 先渲染 SET 与 WHERE 片段（惰性求值，参数此刻才注册进 paramNameValuePairs）
        uw.getSqlSet();
        uw.getSqlSegment();
        return uw;
    }

    private String capturedUserPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, atLeastOnce()).user(captor.capture());
        return captor.getValue();
    }

    /* ---------------- 首次提炼：合并 + 推进游标 ---------------- */

    @Test
    void firstRound_mergesPatchAndAdvancesCursor() throws Exception {
        stubScan(staleSession(9L));
        stubItems("9",
                item("m1", "user", "以后 SQL 都帮我写成批量的"),
                item("m2", "assistant", "好的，已记住"));
        stubLlm("- [沟通风格] 偏好简洁回复\n- [SQL] 倾向批量写法");

        service.scanOnce();

        assertEquals("- [沟通风格] 偏好简洁回复\n- [SQL] 倾向批量写法", Files.readString(profileFile),
                "补丁应按 key 合并落盘");
        UpdateWrapper<SessionEntity> uw = markWrapper();
        assertTrue(uw.getSqlSet().contains("profile_extracted_at"), "应记录提炼时刻: " + uw.getSqlSet());
        assertTrue(uw.getSqlSet().contains("last_extracted_message_id"), "应记录游标: " + uw.getSqlSet());
        assertTrue(uw.getParamNameValuePairs().values().contains("m2"), "游标应为最后一条新消息");
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(recorder).record(logCaptor.capture());
        assertEquals("memory-profile", logCaptor.getValue().agentName(), "llm_call_log 应以 memory-profile 口径落库");
    }

    /* ---------------- 增量：只喂新消息 ---------------- */

    @Test
    void secondRound_feedsOnlyMessagesAfterCursor() throws Exception {
        SessionEntity session = staleSession(9L);
        session.setLastExtractedMessageId("m2");
        stubScan(session);
        stubItems("9",
                item("m1", "user", "旧消息一"),
                item("m2", "assistant", "旧回复二"),
                item("m3", "user", "新消息三"),
                item("m4", "assistant", "新回复四"));
        stubLlm("- [语言] 中文");

        service.scanOnce();

        String prompt = capturedUserPrompt();
        assertTrue(prompt.contains("新消息三") && prompt.contains("新回复四"), "应包含游标之后的消息");
        assertFalse(prompt.contains("旧消息一") || prompt.contains("旧回复二"), "已提炼过的消息不应重复进 prompt");
    }

    /** 游标被裁剪丢弃时：剩余条目都晚于它，应全部按新增处理 */
    @Test
    void cursorTrimmedAway_feedsAllRemaining() {
        SessionEntity session = staleSession(9L);
        session.setLastExtractedMessageId("gone");
        stubScan(session);
        stubItems("9", item("m7", "user", "仅存的消息"));
        stubLlm("- [语言] 中文");

        service.scanOnce();

        assertTrue(capturedUserPrompt().contains("仅存的消息"));
    }

    /* ---------------- 去重：按 key 覆盖 ---------------- */

    @Test
    void sameKey_overwritesInsteadOfAppending() throws Exception {
        Files.writeString(profileFile, "- [沟通风格] 偏好详细解释\n- [语言] 中文");
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "以后说简洁点"));
        stubLlm("- [沟通风格] 偏好简洁回复");

        service.scanOnce();

        assertEquals("- [沟通风格] 偏好简洁回复\n- [语言] 中文", Files.readString(profileFile),
                "同 key 应原位覆盖，既有条目保持原序且不产生重复行");
    }

    /** 旧版画像（无 key 的行）在模型输出同值带 key 条目时应被替换，不留下两条同义条目 */
    @Test
    void legacyLine_replacedByKeyedEquivalent() throws Exception {
        Files.writeString(profileFile, "- 偏好简洁回复");
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "简单点"));
        stubLlm("- [沟通风格] 偏好简洁回复");

        service.scanOnce();

        assertEquals("- [沟通风格] 偏好简洁回复", Files.readString(profileFile));
    }

    /* ---------------- 降级路径 ---------------- */

    @Test
    void noneOutput_marksWithoutTouchingFile() throws Exception {
        Files.writeString(profileFile, "- [语言] 中文");
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "今天天气不错"));
        stubLlm("NONE");

        service.scanOnce();

        assertEquals("- [语言] 中文", Files.readString(profileFile), "NONE 不应写盘");
        assertTrue(markWrapper().getSqlSet().contains("profile_extracted_at"),
                "NONE 也要推进，否则每轮重复问模型");
    }

    @Test
    void blankLlmOutput_doesNotWipeExistingProfile() throws Exception {
        Files.writeString(profileFile, "- 既有偏好条目");
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "聊天内容"));
        stubLlm("   ");

        service.scanOnce();

        assertEquals("- 既有偏好条目", Files.readString(profileFile), "空输出不得抹掉既有画像");
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void llmFailure_keepsFileAndSkipsMark() {
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "用户提到了偏好"));
        stubRoute();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("boom"));

        service.scanOnce();

        assertFalse(Files.exists(profileFile), "LLM 失败不应写盘");
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void fileWriteFailure_doesNotAdvance() {
        // profileFile 的父目录被一个普通文件占用 → createDirectories 必败 → 写盘失败 → 不推进游标
        Path occupied = tempDir.resolve("occupied");
        Path blocked = occupied.resolve("user-profile.md");
        UserProfileService blockedService = newService(blocked);
        try {
            Files.writeString(occupied, "i-am-a-file");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "聊天内容"));
        stubLlm("- [语言] 中文");

        blockedService.scanOnce();

        verify(sessionMapper, never()).update(any(), any());
    }

    /* ---------------- 边界与护栏 ---------------- */

    @Test
    void emptySnapshot_marksWithoutLlm() {
        stubScan(staleSession(9L));
        stubItems("9");

        service.scanOnce();

        verify(llmClientRegistry, never()).get(any());
        assertTrue(markWrapper().getSqlSet().contains("profile_extracted_at"), "无内容也应推进（避免空转重扫）");
    }

    @Test
    void scanQuery_comparesActiveTimeAgainstLastExtraction() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        service.scanOnce();

        ArgumentCaptor<Wrapper<SessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).selectList(captor.capture());
        String sql = ((QueryWrapper<?>) captor.getValue()).getSqlSegment();
        assertTrue(sql.contains("profile_extracted_at"), "应以「提炼时刻」判定待提炼: " + sql);
        assertTrue(sql.contains("last_active_at"), "扫描应限定静默超阈值的会话: " + sql);
        assertTrue(sql.contains("last_active_at > profile_extracted_at"), "应比较活跃时间而非布尔标记: " + sql);
    }

    /** 单次最多喂 40 条：超出时只取尾部（偏好信号集中在最近对话） */
    @Test
    void tooManyNewMessages_keepsOnlyRecentTail() {
        List<SessionSnapshotItem> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(item("m" + i, i % 2 == 0 ? "user" : "assistant", "消息 #" + i));
        }
        stubScan(staleSession(9L));
        when(sessionService.snapshotItems("9")).thenReturn(items);
        stubLlm("- [语言] 中文");

        service.scanOnce();

        String prompt = capturedUserPrompt();
        assertTrue(prompt.contains("消息 #49"), "应包含最新消息");
        assertTrue(prompt.contains("消息 #10"), "第 40 条（索引 10）应保留");
        assertFalse(prompt.contains("消息 #9\n"), "超出上限的最旧消息应被丢弃");
        assertFalse(prompt.contains("消息 #0\n"), "最旧消息应被丢弃");
    }

    @Test
    void writeCreatesBackupOfPreviousVersion() throws Exception {
        Files.writeString(profileFile, "- [语言] 中文");
        stubScan(staleSession(9L));
        stubItems("9", item("m1", "user", "以后简洁点"));
        stubLlm("- [沟通风格] 简洁");

        service.scanOnce();

        Path backup = Path.of(profileFile + ".bak");
        assertTrue(Files.exists(backup), "写盘前应备份上一版");
        assertEquals("- [语言] 中文", Files.readString(backup));
    }

    @Test
    void blankMainFile_restoredFromBackup() throws Exception {
        Files.writeString(profileFile, "");
        Files.writeString(Path.of(profileFile + ".bak"), "- [语言] 中文");

        assertEquals("- [语言] 中文", service.currentProfile(), "主文件被清空时应从备份恢复");
    }

    @Test
    void multipleSessions_processedIndependently() throws Exception {
        SessionEntity s9 = staleSession(9L);
        SessionEntity s10 = staleSession(10L);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s9, s10));
        stubItems("9", item("m1", "user", "会话九"));
        stubItems("10", item("m1", "user", "会话十"), item("m2", "assistant", "回复"));
        // 第一次调用（会话 9）抛异常，第二次（会话 10）成功
        when(chatClient.prompt()).thenThrow(new RuntimeException("first fails"))
                .thenReturn(requestSpec);
        stubRoute();
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("- [主题] 会话十的偏好")))));

        service.scanOnce();

        assertEquals("- [主题] 会话十的偏好", Files.readString(profileFile));
        UpdateWrapper<SessionEntity> uw = markWrapper();
        assertNotNull(uw);
        assertTrue(uw.getParamNameValuePairs().values().contains(10L), "只应推进成功的会话");
    }
}
