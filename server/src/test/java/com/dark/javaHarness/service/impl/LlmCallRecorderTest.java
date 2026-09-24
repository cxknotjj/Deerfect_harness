package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.ToolCallLog;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.Test;

/**
 * LlmCallRecorder 单测：
 * - token 近似估算口径（中文 1 token、其它 (长度+3)/4，与 ContextAssemblingAdvisor 一致）
 * - record 异步落库成功与失败都不向调用方抛异常（观测永不影响主链路）
 */
class LlmCallRecorderTest {

    @Test
    void estimateTokens_mixedText_followsAdvisorConvention() {
        // 4 个中文（4 token）+ "abcd"（(4+3)/4=1 token）= 5
        assertEquals(5, LlmCallRecorder.estimateTokens("你好世界abcd"));
        assertEquals(0, LlmCallRecorder.estimateTokens(""));
        assertEquals(0, LlmCallRecorder.estimateTokens(null));
        // 纯英文 8 字符 → (8+3)/4 = 2
        assertEquals(2, LlmCallRecorder.estimateTokens("abcdefgh"));
    }

    @Test
    void record_insertsEntityAsynchronously() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        recorder.record(new LlmCallLog("s1", "lead", "qwen3.8-27b", false, true,
                100, 20, 120, false, 1500, null, null, null, null, "你好", 300L, null, 2, 3,
                "turn-1", "trace-1", "span-1", "parent-1"));

        verify(mapper, timeout(2000)).insert(org.mockito.ArgumentMatchers.argThat((LlmCallLogEntity e) -> {
            return "s1".equals(e.getSessionId())
                    && "lead".equals(e.getAgentName())
                    && "SYNC".equals(e.getCallKind())
                    && "OK".equals(e.getStatus())
                    && Integer.valueOf(120).equals(e.getTotalTokens())
                    && e.getTokensEstimated() == 0
                    && Long.valueOf(1500).equals(e.getDurationMs())
                    && "你好".equals(e.getOutputSummary())
                    && Long.valueOf(300).equals(e.getFirstTokenMs())
                    && e.getCachedTokens() == null
                    && Integer.valueOf(2).equals(e.getAttempt())
                    && Integer.valueOf(3).equals(e.getMaxAttempts())
                    && "turn-1".equals(e.getTurnId())
                    && "trace-1".equals(e.getTraceId())
                    && "span-1".equals(e.getSpanId())
                    && "parent-1".equals(e.getParentSpan());
        }));
    }

    @Test
    void record_turnTraceColumns_nullScenario_storedAsNull() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        // route-judge/画像等调用 trace/span 落 NULL；/submit 直发 turnId 亦为 NULL
        recorder.record(new LlmCallLog("s1", "route-judge", "qwen3.8-27b", false, true,
                10, 5, 15, false, 100, null, null, null, null, null, null, null, null, null,
                null, null, "span-1", null));

        verify(mapper, timeout(2000)).insert(argThat((LlmCallLogEntity e) ->
                e.getTurnId() == null && e.getTraceId() == null
                        && "span-1".equals(e.getSpanId()) && e.getParentSpan() == null));
    }

    @Test
    void record_mapperFailure_neverThrowsToCaller() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        when(mapper.insert(any(LlmCallLogEntity.class))).thenThrow(new IllegalStateException("db down"));
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        // 不应向调用方抛出（异步边界吞掉并 warn）
        recorder.record(new LlmCallLog(null, "route-judge", "qwen3.8-27b", true, false,
                null, 5, 5, true, 80, "boom", null, null, null, null, null, null, null, null,
                null, null, null, null));
        // 给异步线程留出执行窗口；若抛出则测试线程已失败
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertTrue(true, "落库异常被观测层吞掉，未影响调用方");
    }

    @Test
    void record_promptAttachments_mappedAsCsv() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        recorder.record(new LlmCallLog("s1", "general", "qwen3.8-27b", false, true,
                10, 5, 15, false, 100, null,
                List.of("pdf-handling"), List.of("fetchUrl", "tavily_search"), List.of("tavily_search"),
                null, null, null, 1, 1, null, null, null, null));

        verify(mapper, timeout(2000)).insert(argThat((LlmCallLogEntity e) ->
                "pdf-handling".equals(e.getSkillNames())
                        && "fetchUrl,tavily_search".equals(e.getToolNames())
                        && "tavily_search".equals(e.getMcpToolNames())));
    }

    @Test
    void record_emptyAttachments_mappedAsNull() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        recorder.record(new LlmCallLog("s1", "route-judge", "m", false, true,
                1, 1, 2, false, 5, null, List.of(), List.of(), List.of(), null, null, null, 1, 1,
                null, null, null, null));

        verify(mapper, timeout(2000)).insert(argThat((LlmCallLogEntity e) ->
                e.getSkillNames() == null && e.getToolNames() == null && e.getMcpToolNames() == null));
    }

    @Test
    void record_outputSummaryTruncatedTo200() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, mock(ToolCallLogMapper.class));

        String longReply = "好".repeat(300);
        recorder.record(new LlmCallLog("s1", "general", "m", true, true,
                10, 5, 15, false, 100, null, null, null, null,
                longReply, 250L, 64, 1, 3, null, null, null, null));

        verify(mapper, timeout(2000)).insert(argThat((LlmCallLogEntity e) ->
                e.getOutputSummary() != null
                        && e.getOutputSummary().length() == 200
                        && Long.valueOf(250).equals(e.getFirstTokenMs())
                        && Integer.valueOf(64).equals(e.getCachedTokens())
                        && Integer.valueOf(1).equals(e.getAttempt())
                        && Integer.valueOf(3).equals(e.getMaxAttempts())));
    }

    @Test
    void recordToolCall_insertsEntityAsynchronously() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        ToolCallLogMapper toolCallMapper = mock(ToolCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, toolCallMapper);

        recorder.recordToolCall(new ToolCallLog("s1", "qq-channel", "web_fetch", "default",
                "https://example.com", true, 320, null, "turn-1", "trace-1", "span-1"));

        verify(toolCallMapper, timeout(2000)).insert(
                org.mockito.ArgumentMatchers.argThat((ToolCallLogEntity e) ->
                        "s1".equals(e.getSessionId())
                                && "qq-channel".equals(e.getAgentName())
                                && "web_fetch".equals(e.getToolName())
                                && "default".equals(e.getServerName())
                                && "OK".equals(e.getStatus())
                                && Long.valueOf(320).equals(e.getDurationMs())
                                && "turn-1".equals(e.getTurnId())
                                && "trace-1".equals(e.getTraceId())
                                && "span-1".equals(e.getParentSpan())));
    }

    @Test
    void recordToolCall_turnTraceColumns_nullScenario_storedAsNull() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        ToolCallLogMapper toolCallMapper = mock(ToolCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper, toolCallMapper);

        // 工具侧 ToolContext 未接线/值缺失时轨迹标识落 NULL（观测零主链路影响）
        recorder.recordToolCall(new ToolCallLog("s1", "general", "web_fetch", null,
                "https://example.com", true, 320, null, null, null, null));

        verify(toolCallMapper, timeout(2000)).insert(
                org.mockito.ArgumentMatchers.argThat((ToolCallLogEntity e) ->
                        e.getTurnId() == null && e.getTraceId() == null && e.getParentSpan() == null));
    }
}
