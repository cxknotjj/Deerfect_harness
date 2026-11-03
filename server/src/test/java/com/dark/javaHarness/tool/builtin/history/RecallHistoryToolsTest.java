package com.dark.javaHarness.tool.builtin.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.service.impl.SessionHistoryArchiveService;
import com.dark.javaHarness.service.impl.SessionHistoryArchiveService.ArchivedMessage;
import com.dark.javaHarness.tool.ToolContextKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * recall_history 工具单测：会话 ID 经 ToolContext 注入、参数校验、结果格式化与条数钳制。
 */
class RecallHistoryToolsTest {

    private final SessionHistoryArchiveService service = mock(SessionHistoryArchiveService.class);
    private final RecallHistoryTools tools = new RecallHistoryTools(service);

    private static ToolContext ctx(String sessionId) {
        return new ToolContext(sessionId == null ? Map.of() : Map.of(ToolContextKeys.SESSION_ID, sessionId));
    }

    @Test
    void missingSessionId_refusesWithoutQuerying() {
        String result = tools.recallHistory("q", null, null, ctx(null));

        assertTrue(result.contains("无法定位当前会话"));
        verifyNoInteractions(service);
    }

    @Test
    void missingQueryAndMessageId_returnsGuidance() {
        String result = tools.recallHistory(null, " ", null, ctx("9"));

        assertTrue(result.contains("请提供关键词"));
        verifyNoInteractions(service);
    }

    @Test
    void hits_areFormattedWithMessageIdAndRole() {
        when(service.search(eq("9"), eq("m1"), eq(null), anyInt()))
                .thenReturn(List.of(new ArchivedMessage(1L, "m1", "user", "早期内容", 1730000000000L)));

        String result = tools.recallHistory(null, "m1", null, ctx("9"));

        assertTrue(result.contains("命中 1 条归档消息"));
        assertTrue(result.contains("m1"));
        assertTrue(result.contains("user"));
        assertTrue(result.contains("早期内容"));
    }

    @Test
    void limit_isClampedToServiceMaximum() {
        when(service.search(eq("9"), eq(null), eq("关键词"), eq(SessionHistoryArchiveService.MAX_LIMIT)))
                .thenReturn(List.of());

        tools.recallHistory("关键词", null, 99, ctx("9"));

        verify(service).search("9", null, "关键词", SessionHistoryArchiveService.MAX_LIMIT);
    }

    @Test
    void emptyResult_returnsNoHitHint() {
        when(service.search(eq("9"), eq(null), eq("q"), anyInt())).thenReturn(List.of());

        String result = tools.recallHistory("q", null, null, ctx("9"));

        assertEquals("未命中归档消息（可能未超出上下文窗口，或关键词不匹配）。", result);
    }
}
