package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.domain.entity.ContextCompositionLogEntity;
import com.dark.javaHarness.mapper.ContextCompositionLogMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * ContextCompositionRecorder 单测：
 * - 空 sessionId 跳过（不写内存、不落库）
 * - 正常请求写内存 + 异步落库（parts JSON、总量、采集时间透传）
 * - 落库异常不向调用方传播；队列打满静默丢弃
 */
class ContextCompositionRecorderTest {

    private final ObjectMapper json = new ObjectMapper();

    private ContextComposition composition(String sessionId) {
        return ContextCompositionCalculator.build(sessionId, "general", "qwen-plus", "turn-1",
                new SystemAssembly("系统提示", List.of(), 0), 0, List.of(new UserMessage("你好")));
    }

    @Test
    void record_blankSession_skipped() {
        ContextCompositionStore store = mock(ContextCompositionStore.class);
        ContextCompositionLogMapper mapper = mock(ContextCompositionLogMapper.class);
        ContextCompositionRecorder recorder = new ContextCompositionRecorder(store, mapper, json);

        recorder.record(" ", "general", "m", "t", new SystemAssembly("sys", List.of(), 0), 0, List.of());

        verifyNoInteractions(store, mapper);
    }

    @Test
    void record_writesMemoryAndPersistsAsync() {
        ContextCompositionStore store = mock(ContextCompositionStore.class);
        ContextCompositionLogMapper mapper = mock(ContextCompositionLogMapper.class);
        ContextCompositionRecorder recorder = new ContextCompositionRecorder(store, mapper, json);

        recorder.record("s1", "general", "qwen-plus", "turn-1",
                new SystemAssembly("系统提示", List.of(), 0), 0, List.of(new UserMessage("你好")));

        verify(store).put(any(ContextComposition.class));
        verify(mapper, timeout(2000)).insert(org.mockito.ArgumentMatchers.argThat((ContextCompositionLogEntity e) -> {
            if (!"s1".equals(e.getSessionId()) || !"general".equals(e.getAgentName())
                    || !"turn-1".equals(e.getTurnId()) || e.getEstimatedTotalTokens() <= 0
                    || e.getCreatedAt() == null) {
                return false;
            }
            return json.readTree(e.getParts()).isArray() && json.readTree(e.getParts()).size() > 0;
        }));
    }

    @Test
    void record_insertFailure_doesNotThrow() {
        ContextCompositionStore store = mock(ContextCompositionStore.class);
        ContextCompositionLogMapper mapper = mock(ContextCompositionLogMapper.class);
        when(mapper.insert(any(ContextCompositionLogEntity.class))).thenThrow(new RuntimeException("db down"));
        ContextCompositionRecorder recorder = new ContextCompositionRecorder(store, mapper, json);

        assertDoesNotThrow(() -> recorder.record("s1", "general", "m", "t",
                new SystemAssembly("sys", List.of(), 0), 0, List.of(new UserMessage("hi"))));
        verify(mapper, timeout(2000)).insert(any(ContextCompositionLogEntity.class));
    }

    @Test
    void record_queueFull_dropsSilently() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ContextCompositionLogMapper mapper = mock(ContextCompositionLogMapper.class);
        when(mapper.insert(any(ContextCompositionLogEntity.class))).thenAnswer(inv -> {
            blocked.countDown();
            release.await(2, TimeUnit.SECONDS);
            return 1;
        });
        ContextCompositionRecorder recorder = new ContextCompositionRecorder(
                mock(ContextCompositionStore.class), mapper, json, 1);

        assertDoesNotThrow(() -> {
            recorder.record("s1", "general", "m", "t", new SystemAssembly("s", List.of(), 0), 0, List.of());
            blocked.await(2, TimeUnit.SECONDS); // 第一条正在执行，占满唯一 worker
            recorder.record("s2", "general", "m", "t", new SystemAssembly("s", List.of(), 0), 0, List.of());
            recorder.record("s3", "general", "m", "t", new SystemAssembly("s", List.of(), 0), 0, List.of());
        });
        release.countDown();
    }
}
