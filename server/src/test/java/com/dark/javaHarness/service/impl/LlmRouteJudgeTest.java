package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.RouteDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * LlmRouteJudge 单测：
 * - 合法 JSON 返回 complex / simple 时判定正确
 * - 非 JSON / 空 / 调用异常时兜底 SIMPLE（TODO ⑤ 宁可简单）
 * - 判定走轻量客户端（短读超时、无默认工具），失败时丢弃其连接池缓存
 */
@ExtendWith(MockitoExtension.class)
class LlmRouteJudgeTest {

    /** 判定固定使用的模型名（与 LlmRouteJudge.ROUTE_MODEL 一致） */
    private static final String ROUTE_MODEL = "qwen3.8-27b";

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClientRequestSpec requestSpec;
    @Mock
    private CallResponseSpec responseSpec;

    private LlmRouteJudge judge;

    /** 组装：registry.getLightweightByModel(判定模型) 返回 mock client，prompt 链式 stub 到 chatResponse() */
    private void stubContent(String content) {
        when(clientRegistry.getLightweightByModel(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
        judge = new LlmRouteJudge(clientRegistry, null, null);
    }

    @Test
    void judge_whenLlmReturnsComplex_shouldReturnComplex() {
        stubContent("{\"route\":\"complex\"}");
        assertEquals(RouteDecision.COMPLEX, judge.judge("调研竞品并输出一份报告"));
    }

    @Test
    void judge_whenLlmReturnsSimple_shouldReturnSimple() {
        stubContent("{\"route\":\"simple\"}");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    @Test
    void judge_whenLlmReturnsInvalidJson_shouldFallbackSimple() {
        stubContent("这不是合法的 JSON");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    @Test
    void judge_whenLlmReturnsBlank_shouldFallbackSimple() {
        stubContent("");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    /** 调用异常兜底 SIMPLE，且失败即丢弃轻量客户端缓存（连接可能已成黑洞，重试需换新连接） */
    @Test
    void judge_whenCallThrows_shouldFallbackSimpleAndDropPool() {
        when(clientRegistry.getLightweightByModel(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalStateException("llm down"));
        judge = new LlmRouteJudge(clientRegistry, null, null);

        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"), "调用异常应兜底 SIMPLE 而不抛出");
        verify(clientRegistry).invalidateLightweight(ROUTE_MODEL);
    }

    /** 判定读超时来自配置（app.chat.timeouts.judge-read-timeout-seconds），透传给轻量客户端取用 */
    @Test
    void judge_passesConfiguredJudgeTimeout() {
        stubContent("{\"route\":\"simple\"}");
        ChatTimeoutProperties props = new ChatTimeoutProperties();
        props.setJudgeReadTimeoutSeconds(7);
        judge = new LlmRouteJudge(clientRegistry, null, props);

        judge.judge("你好");

        verify(clientRegistry).getLightweightByModel(ROUTE_MODEL, 7);
    }

    @Test
    void judge_whenMessageBlank_shouldReturnSimpleWithoutCall() {
        judge = new LlmRouteJudge(clientRegistry, null, null);
        assertEquals(RouteDecision.SIMPLE, judge.judge("  "));
        assertEquals(RouteDecision.SIMPLE, judge.judge(null));
    }
}