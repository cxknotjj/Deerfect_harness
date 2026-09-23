package com.dark.javaHarness.agent;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * LlmCallObserver 装配名单透传契约：attachments 三名单经 LlmCallLog 组件落入
 * llm_call_log 三列（recorder.toCsv 口径）；null attachments 直连口径不变（三列 NULL）。
 */
class LlmCallObserverTest {

    @Test
    void okStream_withAttachments_recordedListColumns() {
        LlmCallLogMapper mapper = Mockito.mock(LlmCallLogMapper.class);
        LlmCallObserver observer = new LlmCallObserver(new LlmCallRecorder(mapper,
                Mockito.mock(com.dark.javaHarness.mapper.ToolCallLogMapper.class)));

        observer.okStream("s1", "general", "m", System.currentTimeMillis(), "答",
                null,
                new PromptAssembler.PromptAttachments(List.of("pdf"), List.of("fetchUrl", "tavily_search"),
                        List.of("tavily_search")),
                System.currentTimeMillis(), 1, 1);

        verify(mapper, timeout(2000)).insert(Mockito.argThat((LlmCallLogEntity e) ->
                "pdf".equals(e.getSkillNames())
                        && "fetchUrl,tavily_search".equals(e.getToolNames())
                        && "tavily_search".equals(e.getMcpToolNames())));
    }

    @Test
    void okStream_nullAttachments_listColumnsNull() {
        LlmCallLogMapper mapper = Mockito.mock(LlmCallLogMapper.class);
        LlmCallObserver observer = new LlmCallObserver(new LlmCallRecorder(mapper,
                Mockito.mock(com.dark.javaHarness.mapper.ToolCallLogMapper.class)));

        observer.okStream("s1", "general", "m", System.currentTimeMillis(), "答", null, null,
                System.currentTimeMillis(), 1, 1);

        verify(mapper, timeout(2000)).insert(Mockito.argThat((LlmCallLogEntity e) ->
                e.getSkillNames() == null && e.getToolNames() == null && e.getMcpToolNames() == null));
    }
}
