package com.dark.javaHarness.service.impl.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.service.AgentConfigProvider;
import java.util.List;
import java.util.Optional;
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
 * - 表驱动配置（agent 表 route-judge 行）：命中走库，无行/异常回退内置常量
 */
@ExtendWith(MockitoExtension.class)
class LlmRouteJudgeTest {

    /** 判定固定使用的模型名（与 LlmRouteJudge.ROUTE_MODEL 一致） */
    private static final String ROUTE_MODEL = "qwen3.8-27b";

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private AgentConfigProvider agentConfigProvider;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClientRequestSpec requestSpec;
    @Mock
    private CallResponseSpec responseSpec;

    private LlmRouteJudge judge;

    /** 组装：registry.getLightweightByModel(判定模型) 返回 mock client，prompt 链式 stub 到 chatResponse()。
     *  agentConfigProvider 不显式 stub 时 Mockito 默认返回 Optional.empty（= 无行，走常量回退） */
    private void stubContent(String content) {
        when(clientRegistry.getLightweightByModel(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);
    }

    /** 灰区消息（>12 字且不含规则强信号），确保走到 LLM 判定 */
    private static final String GREY_MESSAGE = "帮我看看这个问题的处理思路";

    @Test
    void plan_whenLlmReturnsGraph_shouldKeepGraph() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.9,\"workstreams\":[\"竞品A\",\"竞品B\"]}");
        assertEquals("graph", judge.plan(GREY_MESSAGE, null, null).path());
    }

    @Test
    void plan_whenLlmReturnsAgentic_shouldReturnAgentic() {
        stubContent("{\"route\":\"agentic\",\"confidence\":0.9}");
        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path());
    }

    @Test
    void plan_whenLlmReturnsBlank_shouldFallbackAgentic() {
        stubContent("");
        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path());
    }

    /** 调用异常兜底 Agentic，且失败即丢弃轻量客户端缓存（连接可能已成黑洞，重试需换新连接） */
    @Test
    void plan_whenCallThrows_shouldFallbackAgenticAndDropPool() {
        when(clientRegistry.getLightweightByModel(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalStateException("llm down"));
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);

        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path(), "调用异常应兜底 Agentic 而不抛出");
        verify(clientRegistry).invalidateLightweight(ROUTE_MODEL);
    }

    /** 判定读超时来自配置（app.chat.timeouts.judge-read-timeout-seconds），透传给轻量客户端取用 */
    @Test
    void plan_passesConfiguredJudgeTimeout() {
        stubContent("{\"route\":\"agentic\"}");
        ChatTimeoutProperties props = new ChatTimeoutProperties();
        props.setJudgeReadTimeoutSeconds(7);
        judge = new LlmRouteJudge(clientRegistry, null, props, agentConfigProvider);

        judge.plan(GREY_MESSAGE, null, null);

        verify(clientRegistry).getLightweightByModel(ROUTE_MODEL, 7);
    }

    @Test
    void plan_whenMessageBlank_shouldReturnAgenticWithoutCall() {
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);
        assertEquals("agentic", judge.plan("  ", null, null).path());
        assertEquals("agentic", judge.plan(null, null, null).path());
    }

    /** 表驱动：agent 表 route-judge 行命中时，用库中 model/prompt 判定（改库即生效） */
    @Test
    void plan_whenAgentRowPresent_shouldUseConfiguredModelAndPrompt() {
        when(agentConfigProvider.getAgentConfig("route-judge")).thenReturn(Optional.of(
                new AgentConfig(1L, "test-route-model", "表驱动判定提示词", null)));
        when(clientRegistry.getLightweightByModel(eq("test-route-model"), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"route\":\"agentic\"}")))));
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);

        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path());

        verify(clientRegistry).getLightweightByModel("test-route-model", null);
        // 表驱动 prompt 原文作为前缀下发，输出契约随后追加（保证富 JSON 形状）
        verify(requestSpec).system((String) org.mockito.ArgumentMatchers.argThat(
                (String s) -> s.startsWith("表驱动判定提示词") && s.contains("\"workstreams\"")));
    }

    /** agent 表无 route-judge 行：回退内置常量 */
    @Test
    void plan_whenAgentRowMissing_shouldFallbackToConstants() {
        stubContent("{\"route\":\"agentic\"}");

        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path());
        verify(clientRegistry).getLightweightByModel(ROUTE_MODEL, null);
    }

    /** agent 表查询异常：回退内置常量，判定不阻塞（兜底语义不受配置读取影响） */
    @Test
    void plan_whenConfigReadThrows_shouldFallbackToConstants() {
        when(agentConfigProvider.getAgentConfig("route-judge")).thenThrow(new IllegalStateException("db down"));
        stubContent("{\"route\":\"agentic\"}");

        assertEquals("agentic", judge.plan(GREY_MESSAGE, null, null).path());
        verify(clientRegistry).getLightweightByModel(ROUTE_MODEL, null);
    }

    /** 规则短路：短消息（≤12 字符）直接 Agentic，不付判定调用 */
    @Test
    void plan_whenMessageShort_shouldShortCircuitToAgentic() {
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);

        RoutePlan plan = judge.plan("你好", null, null);

        assertEquals("agentic", plan.path());
        assertEquals(RoutePlan.Source.RULE, plan.source());
        assertEquals(0.95, plan.confidence());
        verify(clientRegistry, org.mockito.Mockito.never()).getLightweightByModel(anyString(), any());
    }

    /** 规则短路：强多工作流措辞直接 Graph，不付判定调用 */
    @Test
    void plan_whenMultiWorkstreamPhrase_shouldShortCircuitToGraph() {
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);

        RoutePlan plan = judge.plan("帮我同时调研三家竞品的定价策略与用户口碑", null, null);

        assertEquals("graph", plan.path());
        assertEquals(RoutePlan.Source.RULE, plan.source());
        assertEquals(0.9, plan.confidence());
        verify(clientRegistry, org.mockito.Mockito.never()).getLightweightByModel(anyString(), any());
    }

    /** LLM 判灰区：Graph + 2 个工作流 + 高置信度 → 保持 Graph，字段完整透出 */
    @Test
    void plan_whenLlmGraphWithEnoughWorkstreamsAndConfidence_shouldKeepGraph() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.91,"
                + "\"workstreams\":[\"竞品定价\",\"用户口碑\"],\"deliverables\":[\"对比报告\"],"
                + "\"reason\":\"两条独立调研线\"}");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("graph", plan.path());
        assertEquals(RoutePlan.Source.LLM, plan.source());
        assertEquals(2, plan.workstreams().size());
        assertEquals(List.of("对比报告"), plan.deliverables());
        assertEquals("两条独立调研线", plan.reason());
        assertEquals(0.91, plan.confidence());
    }

    /** 置信度策略：Graph 但置信度低于 0.75 → 收敛为 Agentic（编排成本让位于先直答） */
    @Test
    void plan_whenGraphConfidenceBelowThreshold_shouldCollapseToAgentic() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.55,"
                + "\"workstreams\":[\"A\",\"B\"],\"reason\":\"maybe parallel\"}");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("agentic", plan.path());
        assertEquals(RoutePlan.Source.FALLBACK, plan.source());
        assertEquals(0.55, plan.confidence());
    }

    /** 阈值下调到 0.75 后：0.78 + 2 个工作流应保留 Graph（此前会被误收敛为 Agentic） */
    @Test
    void plan_whenGraphConfidenceAtLoweredThreshold_shouldKeepGraph() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.78,"
                + "\"workstreams\":[\"竞品定价\",\"用户口碑\"],\"reason\":\"两条独立调研线\"}");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("graph", plan.path());
        assertEquals(0.78, plan.confidence());
    }

    /** workstreams 去重：同一工作流的两种表述（仅空格/标点差异）不得虚增为并行流 */
    @Test
    void plan_whenWorkstreamsNearDuplicates_shouldCollapseToAgentic() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.95,"
                + "\"workstreams\":[\"竞品调研\",\"竞品 调研！\"],\"reason\":\"same stream twice\"}");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("agentic", plan.path());
        assertEquals(RoutePlan.Source.FALLBACK, plan.source());
        assertEquals("llm: workstreams<2, collapse to agentic", plan.reason());
    }

    /** 规则信号支持英文触发词（大小写不敏感） */
    @Test
    void plan_whenEnglishParallelPhrase_shouldShortCircuitToGraph() {
        judge = new LlmRouteJudge(clientRegistry, null, null, agentConfigProvider);

        RoutePlan plan = judge.plan("Research each competitor separately and give me the comparison", null, null);

        assertEquals("graph", plan.path());
        assertEquals(RoutePlan.Source.RULE, plan.source());
        verify(clientRegistry, org.mockito.Mockito.never()).getLightweightByModel(anyString(), any());
    }

    /** 工作流形态：Graph 但可独立工作流不足 2 个 → 收敛为 Agentic（单工作流无并行收益） */
    @Test
    void plan_whenGraphHasSingleWorkstream_shouldCollapseToAgentic() {
        stubContent("{\"route\":\"graph\",\"confidence\":0.95,"
                + "\"workstreams\":[\"唯一工作流\"],\"reason\":\"single\"}");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("agentic", plan.path());
        assertEquals(RoutePlan.Source.FALLBACK, plan.source());
        assertEquals("llm: workstreams<2, collapse to agentic", plan.reason());
    }

    /** 非法 JSON 在 plan 路径同样兜底 Agentic，不阻塞请求 */
    @Test
    void plan_whenLlmReturnsInvalidJson_shouldFallbackAgentic() {
        stubContent("not json at all");

        RoutePlan plan = judge.plan("先梳理一下这几家竞品的情况再给我结论", null, null);

        assertEquals("agentic", plan.path());
        assertEquals(RoutePlan.Source.FALLBACK, plan.source());
        assertEquals(0.0, plan.confidence());
    }
}
