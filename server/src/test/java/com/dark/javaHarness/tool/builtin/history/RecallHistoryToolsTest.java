package com.dark.javaHarness.tool.builtin.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * recall_history 工具单测：会话 ID 经 ToolContext 注入、scope 解析、参数校验、结果格式化与条数钳制。
 */
class RecallHistoryToolsTest {

    private final SessionHistoryArchiveService service = mock(SessionHistoryArchiveService.class);
    private final RecallHistoryTools tools = new RecallHistoryTools(service);

    private static ToolContext ctx(String sessionId) {
        return new ToolContext(sessionId == null ? Map.of() : Map.of(ToolContextKeys.SESSION_ID, sessionId));
    }

    @Test
    void missingSessionId_refusesWithoutQuerying() {
        String result = tools.recallHistory("q", null, null, null, ctx(null));

        assertTrue(result.contains("无法定位当前会话"));
        verifyNoInteractions(service);
    }

    @Test
    void missingQueryAndMessageId_returnsGuidance() {
        String result = tools.recallHistory(null, " ", null, null, ctx("9"));

        assertTrue(result.contains("请提供关键词"));
        verifyNoInteractions(service);
    }

    @Test
    void hits_areFormattedWithMessageIdAndRole() {
        when(service.search(eq("9"), eq("m1"), eq(null), anyInt(), eq(false)))
                .thenReturn(List.of(new ArchivedMessage(1L, "9", "m1", "user", "早期内容", 1730000000000L)));

        String result = tools.recallHistory(null, "m1", null, null, ctx("9"));

        assertTrue(result.contains("命中 1 条归档消息"));
        assertTrue(result.contains("m1"));
        assertTrue(result.contains("user"));
        assertTrue(result.contains("早期内容"));
        assertFalse(result.contains("会话 9"), "会话内检索不必重复会话 ID");
    }

    @Test
    void limit_isClampedToServiceMaximum() {
        when(service.search(eq("9"), eq(null), eq("关键词"), eq(SessionHistoryArchiveService.MAX_LIMIT), eq(false)))
                .thenReturn(List.of());

        tools.recallHistory("关键词", null, null, 99, ctx("9"));

        verify(service).search("9", null, "关键词", SessionHistoryArchiveService.MAX_LIMIT, false);
    }

    @Test
    void emptyResult_returnsNoHitHint() {
        when(service.search(eq("9"), eq(null), eq("q"), anyInt(), eq(false))).thenReturn(List.of());

        String result = tools.recallHistory("q", null, null, null, ctx("9"));

        assertEquals("未命中归档消息（可能未超出上下文窗口，或关键词不匹配）。", result);
    }

    /* ---------------- 跨会话范围（P2-2） ---------------- */

    /** scope=all 不需要当前会话：上下文缺失也能检索，且结果标注来源会话 */
    @Test
    void allScope_needsNoSessionAndLabelsSource() {
        when(service.search(eq(null), eq(null), eq("上个月"), anyInt(), eq(true)))
                .thenReturn(List.of(new ArchivedMessage(2L, "7", "m7", "assistant", "上次的结论", null)));

        String result = tools.recallHistory("上个月", null, "all", null, ctx(null));

        assertTrue(result.contains("会话 7"), "跨会话结果应标注来源: " + result);
        assertTrue(result.contains("m7"));
        verify(service).search(null, null, "上个月", 3, true);
    }

    @Test
    void allScopeEmpty_returnsCrossSessionHint() {
        when(service.search(eq("9"), eq(null), eq("q"), anyInt(), eq(true))).thenReturn(List.of());

        String result = tools.recallHistory("q", null, "ALL", null, ctx("9"));

        assertTrue(result.contains("全部历史会话"), result);
    }

    /** 拼错的 scope 直接拒绝：静默退回 current 会让模型以为搜了全部，实际只搜了本会话 */
    @Test
    void invalidScope_rejectedWithoutQuerying() {
        String result = tools.recallHistory("q", null, "global", null, ctx("9"));

        assertTrue(result.contains("scope 只支持"), result);
        verifyNoInteractions(service);
    }

    /** 缺省与显式 current 等价（默认行为不变） */
    @Test
    void explicitCurrent_matchesDefault() {
        when(service.search(eq("9"), eq(null), eq("q"), anyInt(), eq(false))).thenReturn(List.of());

        tools.recallHistory("q", null, "current", null, ctx("9"));

        verify(service).search("9", null, "q", 3, false);
    }
}
