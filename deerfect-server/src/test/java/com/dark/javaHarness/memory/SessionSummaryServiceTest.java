package com.dark.javaHarness.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.domain.entity.SessionSummaryEntity;
import com.dark.javaHarness.mapper.SessionSummaryMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * SessionSummaryService 单测（mock LLM 链 + mock 台账）：
 * - 读取：有摘要返回、无行/读失败返回 null（组装侧降级）
 * - 滚动合并：已有摘要 + 新增对话喂给轻量模型，游标推进到最新消息 ID
 * - 增量：游标已覆盖则不再调用 LLM；首次生成插入新行
 * - 护栏：输出超上限截断；LLM 失败不写库（下次重试）
 * - 异步：requestSummary 立即返回，不等待 LLM
 */
@ExtendWith(MockitoExtension.class)
class SessionSummaryServiceTest {

    private static final int MAX_CHARS = 200;

    @Mock
    private SessionSummaryMapper mapper;
    @Mock
    private com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry;
    @Mock
    private com.dark.javaHarness.llm.catalog.AgentCatalog agentCatalog;
    @Mock
    private com.dark.javaHarness.llm.routing.ModelResolver modelResolver;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        service = new SessionSummaryService(mapper, llmClientRegistry, agentCatalog, modelResolver,
                null, MAX_CHARS);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static Message message(String id, String text) {
        return UserMessage.builder()
                .text(text)
                .metadata(Map.of(ChatMessageMetadata.MESSAGE_ID, id))
                .build();
    }

    private static SessionSummaryEntity row(String summary, String covered) {
        SessionSummaryEntity row = new SessionSummaryEntity();
        row.setSessionId(9L);
        row.setSummary(summary);
        row.setCoveredMessageId(covered);
        return row;
    }

    private void stubLlm(String content) {
        stubRoute();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private void stubRoute() {
        com.dark.javaHarness.domain.entity.AgentEntity agent =
                new com.dark.javaHarness.domain.entity.AgentEntity();
        agent.setAgentName("summary");
        agent.setRouteId(1L);
        when(agentCatalog.require("summary")).thenReturn(agent);
        com.dark.javaHarness.llm.routing.RoutePlan.Member member =
                new com.dark.javaHarness.llm.routing.RoutePlan.Member(10L, 1L, "dashscope",
                        "https://example.com", "qwen3.8-27b", "m", 10, "{}", null);
        when(modelResolver.resolve(1L)).thenReturn(
                new com.dark.javaHarness.llm.routing.RoutePlan(1L, "fast", List.of(member)));
        when(llmClientRegistry.get(10L)).thenReturn(chatClient);
    }

    @Test
    void summaryOf_existingRow_returnsSummary() {
        when(mapper.selectById(9L)).thenReturn(row("旧摘要", "5"));
        assertEquals("旧摘要", service.summaryOf("9"));
    }

    @Test
    void summaryOf_missingRowOrFailure_returnsNull() {
        when(mapper.selectById(9L)).thenReturn(null);
        assertNull(service.summaryOf("9"));

        when(mapper.selectById(10L)).thenThrow(new RuntimeException("db down"));
        assertNull(service.summaryOf("10"), "读失败应按无摘要降级");

        assertNull(service.summaryOf("not-a-number"));
    }

    @Test
    void summarizeNow_mergesExistingSummaryAndAdvancesCursor() {
        when(mapper.selectById(9L)).thenReturn(row("旧摘要：用户在写 Java 项目", "5"));
        stubLlm("合并摘要：用户在写 Java 项目，约束是中文回答");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        service.summarizeNow("9", List.of(message("6", "以后都用中文回答"), message("7", "好的")));

        verify(requestSpec).user(prompt.capture());
        assertTrue(prompt.getValue().contains("旧摘要：用户在写 Java 项目"), "已有摘要应参与滚动合并");
        assertTrue(prompt.getValue().contains("以后都用中文回答"));

        ArgumentCaptor<SessionSummaryEntity> saved = ArgumentCaptor.forClass(SessionSummaryEntity.class);
        verify(mapper).updateById(saved.capture());
        assertEquals("合并摘要：用户在写 Java 项目，约束是中文回答", saved.getValue().getSummary());
        assertEquals("7", saved.getValue().getCoveredMessageId(), "游标推进到最新消息 ID");
        assertNotNull(saved.getValue().getUpdateTime());
    }

    @Test
    void summarizeNow_noNewerMessages_skipsLlm() {
        when(mapper.selectById(9L)).thenReturn(row("旧摘要", "9"));

        service.summarizeNow("9", List.of(message("8", "已在摘要内"), message("9", "也在摘要内")));

        verifyNoInteractions(llmClientRegistry, agentCatalog, modelResolver);
        verify(mapper, never()).updateById(any(SessionSummaryEntity.class));
    }

    @Test
    void summarizeNow_firstTime_insertsRow() {
        when(mapper.selectById(9L)).thenReturn(null);
        stubLlm("首份摘要");
        ArgumentCaptor<SessionSummaryEntity> saved = ArgumentCaptor.forClass(SessionSummaryEntity.class);

        service.summarizeNow("9", List.of(message("1", "第一个问题"), message("2", "第一个回答")));

        verify(mapper).insert(saved.capture());
        assertEquals(9L, saved.getValue().getSessionId());
        assertEquals("首份摘要", saved.getValue().getSummary());
        assertEquals("2", saved.getValue().getCoveredMessageId());
    }

    @Test
    void summarizeNow_clampsSummaryToMaxChars() {
        when(mapper.selectById(9L)).thenReturn(row("旧", "5"));
        stubLlm("超".repeat(MAX_CHARS + 100));
        ArgumentCaptor<SessionSummaryEntity> saved = ArgumentCaptor.forClass(SessionSummaryEntity.class);

        service.summarizeNow("9", List.of(message("6", "新内容")));

        verify(mapper).updateById(saved.capture());
        assertEquals(MAX_CHARS, saved.getValue().getSummary().length(), "摘要不得超过字符上限");
    }

    @Test
    void summarizeNow_llmFailure_doesNotWrite() {
        when(mapper.selectById(9L)).thenReturn(row("旧", "5"));
        stubRoute();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalArgumentException("bad model"));

        assertThrows(IllegalArgumentException.class,
                () -> service.summarizeNow("9", List.of(message("6", "新内容"))));

        verify(mapper, never()).updateById(any(SessionSummaryEntity.class));
        verify(mapper, never()).insert(any(SessionSummaryEntity.class));
    }

    @Test
    void requestSummary_returnsWithoutWaitingForLlm() throws Exception {
        when(mapper.selectById(9L)).thenReturn(null);
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubRoute();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenAnswer(inv -> {
            llmStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return responseSpec;
        });

        assertTimeoutPreemptively(Duration.ofMillis(300),
                () -> service.requestSummary("9", List.of(message("6", "新内容"))),
                "摘要生成不得阻塞调用方");

        assertTrue(llmStarted.await(1, TimeUnit.SECONDS), "后台线程应已开始生成摘要");
        release.countDown();
    }
}
