package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

/**
 * 流管道看门狗单测：首帧 deadline 与帧间空闲看门狗分离——
 * 首帧 deadline（短，默认 20s）专抓黑洞连接（零帧请求快速失败进重试自愈）；
 * 帧间看门狗（长，默认 120s）容忍模型慢启动。两通道失败均为 TimeoutException（可重试口径）。
 * blockLast 会把受检 TimeoutException 包装为 ReactiveException，断言前先 unwrap。
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
        // 首帧 200ms / 帧间 200ms：测试内快速触发；生产默认 20s / 120s
        pipeline = new AgentChatPipeline(clientRegistry, mock(AgentRequestSpecFactory.class),
                Duration.ofMillis(200), Duration.ofMillis(200));
    }

    private void stubStream(Flux<ChatResponse> frames) {
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(frames);
    }

    private static Flux<ChatResponse> frameOf(String token) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
    }

    private void runStream() {
        pipeline.tokenStreamWithWatchdog(spec,
                        new java.util.concurrent.atomic.AtomicReference<>(), null, null,
                        new AtomicLong(), "m1", null)
                .blockLast();
    }

    /** blockLast 包装链解包：断言超时类型（Reactor 生成标准文案，可观测性由触发时 WARN 日志承载） */
    private TimeoutException expectTimeout(Runnable stream) {
        try {
            stream.run();
            fail("应触发看门狗超时");
            throw new IllegalStateException("unreachable");
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            assertTrue(cause instanceof TimeoutException,
                    "应为 TimeoutException，实际 " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return (TimeoutException) cause;
        }
    }

    @Test
    void watchdog_firstFrameDeadline_firesFastOnSilentConnection() {
        stubStream(Flux.never());
        long t0 = System.currentTimeMillis();
        expectTimeout(this::runStream);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 3000, "首帧超时应快速失败（黑洞快速判定），实际 " + elapsed + "ms");
        verify(clientRegistry).invalidateByModel("m1");
    }

    @Test
    void watchdog_interFrameDeadline_firesAfterFirstFrameGap() {
        // 首帧立即到达（首帧 deadline 不触发）；此后零帧 → 帧间看门狗触发
        stubStream(Flux.concat(frameOf("你"), Flux.never()));
        expectTimeout(this::runStream);
        verify(clientRegistry).invalidateByModel("m1");
    }

    @Test
    void watchdog_healthyStream_completesWithoutTimeout() {
        stubStream(Flux.concat(frameOf("你"), frameOf("好"),
                Flux.just(new ChatResponse(List.of())))); // usage 末帧空帧跳过
        try {
            runStream();
        } catch (Throwable t) {
            fail("健康流不应触发看门狗: " + t);
        }
    }

    @Test
    void watchdog_4xxError_skipsPoolInvalidation() {
        stubStream(Flux.error(WebClientResponseException.create(400, "Bad Request",
                HttpHeaders.EMPTY, (byte[]) null, null)));

        try {
            runStream();
        } catch (WebClientResponseException expected) {
            // 错误原样上抛
        }
        verify(clientRegistry, never()).invalidateByModel(anyString());
    }

    @Test
    void watchdog_5xxError_invalidatesPool() {
        stubStream(Flux.error(WebClientResponseException.create(503, "Service Unavailable",
                HttpHeaders.EMPTY, (byte[]) null, null)));

        try {
            runStream();
        } catch (WebClientResponseException expected) {
            // 错误原样上抛
        }
        verify(clientRegistry).invalidateByModel("m1");
    }

    @Test
    void watchdog_errorCarriesModelForPoolInvalidation() {
        stubStream(Flux.error(WebClientResponseException.create(500, "Internal Server Error",
                HttpHeaders.EMPTY, (byte[]) null, null)));
        try {
            pipeline.tokenStreamWithWatchdog(spec,
                            new java.util.concurrent.atomic.AtomicReference<>(), null, null,
                            new AtomicLong(), "deepseek-v4-pro-0813", null).blockLast();
        } catch (WebClientResponseException expected) {
            // 错误原样上抛
        }
        verify(clientRegistry).invalidateByModel("deepseek-v4-pro-0813");
    }
}
