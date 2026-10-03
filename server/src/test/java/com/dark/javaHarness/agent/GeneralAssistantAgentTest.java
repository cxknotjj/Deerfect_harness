package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.observe.LlmCallRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/**
 * GeneralAssistantAgent 响应式流式单测：
 * 核心契约 —— executeStreamReactive 必须**真·逐 token 发射**（stream 内容序列原样透传），
 * 不得退化为「同步整段生成完后一次性产出」（接口 default 的行为）。
 * 流式终结钩子落库需携带 prompt 装配名单（llm_call_log 三名单列）。
 */
@ExtendWith(MockitoExtension.class)
class GeneralAssistantAgentTest {

    /** 文本 token → ChatResponse 流（生产流式链已切 chatResponse 通道以捕获 streamUsage 末帧） */
    private static Flux<ChatResponse> fluxOf(String... tokens) {
        return Flux.fromArray(List.of(tokens).stream()
                .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))))
                .toArray(ChatResponse[]::new));
    }


    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private SessionService memoryStore;
    @Mock
    private AgentService agentService;
    @Mock
    private com.dark.javaHarness.tool.ToolAssignments toolAssignments;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private GeneralAssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new GeneralAssistantAgent("general", clientRegistry, memoryStore, agentService, toolAssignments, null);
        when(agentService.getAgentConfig("general")).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(com.dark.javaHarness.tool.ToolAssignments.ToolSet.EMPTY);
        when(clientRegistry.get(isNull())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
    }

    /** 喂 N 片 token 就应透传 N 个元素；退化实现只会产出单个整段元素 */
    @Test
    void executeStreamReactive_shouldEmitTokensProgressively() {
        List<String> tokens = List.of("你好", "，", "\n", "世界");
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(fluxOf(tokens.toArray(new String[0])));

        List<String> out = agent.executeStreamReactive(new Goal("g1", "自我介绍"))
                .collectList()
                .block();

        assertEquals(tokens, out, "应逐 token 原样透传，元素数量与顺序不变");
    }

    /**
     * 回归（streamUsage）：末帧是只含 usage 的空帧（无 generations，contentOf=null）。
     * Reactor 的 map 不允许 null 返回（「The mapper returned a null value」），空帧必须被
     * handle 跳过而非炸流——修复前该场景表现为：token 已输出、流尾报「执行失败」。
     */
    @Test
    void executeStreamReactive_usageOnlyFinalFrame_skippedWithoutError() {
        List<String> tokens = List.of("你好", "，世界");
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        // 正常 token 帧 + usage 专用空帧（streamOptions.include_usage 的末帧形态）
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                fluxOf(tokens.toArray(new String[0])),
                Flux.just(new ChatResponse(List.of()))));

        List<String> out = agent.executeStreamReactive(new Goal("g2", "自我介绍"))
                .collectList()
                .block();

        assertEquals(tokens, out, "空 usage 末帧应被跳过，token 序列不变");
    }

    /** 流式终结钩子（recordCall）须携带 prompt 装配名单：tool_names 落 CSV，技能/MCP 空表落 NULL */
    @Test
    void executeStreamReactive_recordsToolNamesColumn() {
        ToolCallback localCb = mock(ToolCallback.class);
        when(localCb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("fetchUrl").description("s").inputSchema("{}").build());
        when(toolAssignments.forAgent("general")).thenReturn(
                new com.dark.javaHarness.tool.ToolAssignments.ToolSet(List.of(), List.of(localCb)));
        when(toolAssignments.purposeOf(any())).thenReturn("");
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(fluxOf("你好"));

        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        GeneralAssistantAgent observed = new GeneralAssistantAgent("general", clientRegistry, memoryStore,
                agentService, toolAssignments,
                new LlmCallRecorder(mapper, mock(com.dark.javaHarness.mapper.ToolCallLogMapper.class)));
        observed.executeStreamReactive(new Goal("g3", "自我介绍")).collectList().block();

        verify(mapper, timeout(2000)).insert(argThat((LlmCallLogEntity e) ->
                "fetchUrl".equals(e.getToolNames())
                        && e.getSkillNames() == null
                        && e.getMcpToolNames() == null));
    }

    /**
     * 直答路径响应式重试（补齐 6c1ece9 漏掉的路径 A 自愈）：首尝试看门狗超时
     * （TimeoutException = 池内死连接黑洞表征）应在同一请求内重试自愈，
     * 输出为重试轮内容，spec 按尝试重建（两次真实调用）。
     */
    @Test
    void executeStreamReactive_watchdogTimeout_retriesWithinRequest() {
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse())
                .thenReturn(Flux.error(new TimeoutException("Did not observe any item within 120000ms")))
                .thenReturn(fluxOf("重试", "成功"));

        List<String> out = agent.executeStreamReactive(new Goal("g4", "自我介绍"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("重试", "成功"), out, "可重试错误应一次请求内自愈，输出为重试轮内容");
        verify(requestSpec, times(2)).user(anyString());
    }

    /** 被重试丢弃的失败尝试按尝试粒度落库（attempt/max_attempts 与阻塞路径 ctx.error 同构） */
    @Test
    void executeStreamReactive_retry_recordsAttemptGranularRows() {
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse())
                .thenReturn(Flux.error(new TimeoutException("watchdog")))
                .thenReturn(fluxOf("好"));

        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        GeneralAssistantAgent observed = new GeneralAssistantAgent("general", clientRegistry, memoryStore,
                agentService, toolAssignments,
                new LlmCallRecorder(mapper, mock(com.dark.javaHarness.mapper.ToolCallLogMapper.class)));
        observed.executeStreamReactive(new Goal("g5", "自我介绍")).collectList().block(Duration.ofSeconds(5));

        ArgumentCaptor<LlmCallLogEntity> captor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(mapper, timeout(2000).times(2)).insert(captor.capture());
        List<LlmCallLogEntity> rows = captor.getAllValues();
        assertEquals(1, rows.get(0).getAttempt(), "失败尝试行 attempt=1");
        assertEquals(3, rows.get(0).getMaxAttempts());
        assertEquals("ERROR", rows.get(0).getStatus());
        assertEquals(2, rows.get(1).getAttempt(), "成功行 attempt=2（重试轮）");
        assertEquals(3, rows.get(1).getMaxAttempts());
        assertEquals("OK", rows.get(1).getStatus());
    }

    /** 已有部分 token 下发不可回滚：失败后不得重试（重复输出），错误原样上抛 */
    @Test
    void executeStreamReactive_partialOutput_noRetry() {
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                fluxOf("你"), Flux.error(new TimeoutException("watchdog"))));

        try {
            agent.executeStreamReactive(new Goal("g6", "自我介绍")).collectList().block(Duration.ofSeconds(5));
            assertTrue(false, "部分输出后失败应原样抛出，不应吞错");
        } catch (RuntimeException expected) {
            // 预期：错误上抛（block 包装形态不限）
        }
        verify(requestSpec, times(1)).user(anyString());
    }

    /** 不可重试错误（如供应商 4xx 类业务错误）重试只会重复失败：单次尝试即抛 */
    @Test
    void executeStreamReactive_nonRetryableError_noRetry() {
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new IllegalStateException("供应商 4xx")));

        try {
            agent.executeStreamReactive(new Goal("g7", "自我介绍")).collectList().block(Duration.ofSeconds(5));
            assertTrue(false, "不可重试错误应原样抛出");
        } catch (RuntimeException expected) {
            // 预期：错误上抛
        }
        verify(requestSpec, times(1)).user(anyString());
    }
}
