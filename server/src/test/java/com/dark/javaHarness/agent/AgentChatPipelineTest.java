package com.dark.javaHarness.agent;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * 流管道丢池决策单测：4xx 客户端错误（请求被拒、连接健康）跳过 invalidateByModel；
 * 5xx/看门狗超时等连接类失败保持丢池（重试即全新连接）。回归覆盖 2026-10-03 的
 * 「400 Bad Request 触发整池重建」波及在途请求问题。
 */
@ExtendWith(MockitoExtension.class)
class AgentChatPipelineTest {

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private AgentChatPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new AgentChatPipeline(clientRegistry, mock(AgentRequestSpecFactory.class),
                Duration.ofSeconds(5));
    }

    private void stubStreamError(Throwable error) {
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.error(error));
    }

    private void runStream() {
        pipeline.tokenStreamWithWatchdog(spec, new java.util.concurrent.atomic.AtomicReference<>(),
                        null, null, new AtomicLong(), "m1", null)
                .blockLast();
    }

    @Test
    void watchdog_4xxError_skipsPoolInvalidation() {
        stubStreamError(WebClientResponseException.create(400, "Bad Request",
                HttpHeaders.EMPTY, (byte[]) null, null));

        try {
            runStream();
        } catch (WebClientResponseException expected) {
            // 错误原样上抛
        }
        verify(clientRegistry, never()).invalidateByModel(anyString());
    }

    @Test
    void watchdog_5xxError_invalidatesPool() {
        stubStreamError(WebClientResponseException.create(503, "Service Unavailable",
                HttpHeaders.EMPTY, (byte[]) null, null));

        try {
            runStream();
        } catch (WebClientResponseException expected) {
            // 错误原样上抛
        }
        verify(clientRegistry).invalidateByModel("m1");
    }
}
