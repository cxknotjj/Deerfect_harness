package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.agent.RagPrefetcher;
import com.dark.javaHarness.config.StreamConnectionLimiter;
import com.dark.javaHarness.contract.model.AgentConfig;
import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.contract.model.GoalStatus;
import com.dark.javaHarness.exception.ConcurrentRequestException;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.contract.spi.ReplyPostProcessor;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.contract.model.SessionMessageEntry;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * ChatServiceImpl 多轮会话记忆单测：
 * - 首次不带 sessionId → newSession=true 且自动建档
 * - 后续带 sessionId → newSession=false 且不重复建档
 * - 执行成功后 user/assistant 上下文写回 session_messages
 * - resume：goal 校验（不存在 400 / RUNNING 409）+ 复用 goal 断点续跑
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private AgentService agentService;
    @Mock
    private SessionService sessionService;
    @Mock
    private RouteJudge routeJudge;
    @Mock
    private GoalService goalService;
    @Mock
    private KnowledgeRetriever knowledgeRetriever;
    @Mock
    private ObjectProvider<RagPrefetcher> ragPrefetcherProvider;
    @Mock
    private ObjectProvider<ReplyPostProcessor> replyPostProcessors;
    @Mock
    private ObjectProvider<KnowledgeRetriever> knowledgeRetrieverProvider;
    @Mock
    private VoiceArtifactStore voiceArtifacts;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        // 门面只持有执行段与流式段；其余协作件按组件语义装配：null 知识检索器（知识库禁用语义）、
        // ObjectProvider 未打桩（getIfAvailable 返回 null → 预取器 bean 缺位，零行为）、
        // 限流器 0=不限制（计数语义由 StreamConnectionLimiterTest 覆盖）、降级开关开启（降级用例依赖）。
        chatService = buildChatService(new StreamConnectionLimiter(0), null);
        // 默认判定桩：Agentic（各用例可覆盖为 Graph/绑定 Agent/异常等场景）
        lenient().when(routeJudge.plan(anyString(), any(), any())).thenReturn(agenticPlan());
    }

    /** 按 ChatServiceImpl 的组装语义构建门面（与 Spring 组件装配保持同一依赖图） */
    private ChatServiceImpl buildChatService(StreamConnectionLimiter limiter, KnowledgeRetriever knowledgeRetriever) {
        if (knowledgeRetriever != null) {
            lenient().when(knowledgeRetrieverProvider.getIfAvailable()).thenReturn(knowledgeRetriever);
        }
        TurnPersistence persistence = new TurnPersistence(sessionService, agentService, goalService);
        ReplyDecorator decorator = new ReplyDecorator(replyPostProcessors, voiceArtifacts,
                knowledgeRetrieverProvider);
        RouteDecider routeDecider = new RouteDecider(routeJudge, ragPrefetcherProvider);
        return new ChatServiceImpl(
                new ChatTurnExecutor(agentService, routeDecider, persistence, decorator, true),
                new ChatStreamPipeline(agentService, limiter, routeDecider, persistence, decorator, true));
    }

    private Goal succeededGoal(String sessionId, String summary) {
        Goal g = new Goal("goal-1", "hi", sessionId);
        g.succeed(summary);
        return g;
    }

    /** 判定桩：Agentic 路径（LLM 来源，高置信度） */
    private static RoutePlan agenticPlan() {
        return RoutePlan.agentic(0.9, "test: agentic", RoutePlan.Source.LLM);
    }

    /** 判定桩：Graph 路径（2 个可独立工作流，高置信度） */
    private static RoutePlan graphPlan() {
        return RoutePlan.graph(0.9, List.of("调研A", "调研B"), List.of("报告"),
                "test: graph", RoutePlan.Source.LLM);
    }

    @Test
    void chat_withoutSessionId_shouldCreateNewSession() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("42");
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("42", "你好，我是AI"));

        ChatResponse resp = chatService.chat(req);

        assertTrue(resp.newSession(), "首次不带 sessionId 应 newSession=true");
        assertEquals("42", resp.sessionId());
        verify(sessionService).createSession("anonymous", "你好");
    }

    @Test
    void chat_withSessionId_shouldReuseSession() {
        ChatRequest req = new ChatRequest("继续说", "42", null);
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("42", "好的，继续说"));

        ChatResponse resp = chatService.chat(req);

        assertFalse(resp.newSession(), "带已有 sessionId 应 newSession=false");
        verify(sessionService, never()).createSession(anyString(), anyString());
    }

    @Test
    void chat_syncSuccess_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("帮我写首诗", "7", null);
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("7", "风急天高猿啸哀"));

        chatService.chat(req);

        List<SessionMessageEntry> entries = capturedBatch("7");
        verify(sessionService).touchSession(eq("7"), eq("帮我写首诗"));

        assertEquals("帮我写首诗", batchText(entries, false));
        assertEquals("风急天高猿啸哀", batchText(entries, true));
        assertEquals(2, entries.size(), "一轮只应产生 user+assistant 一条批量写入");
    }

    @Test
    void chat_failure_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "1", null);
        Goal failed = new Goal("goal-2", "hi", "1");
        failed.fail("invalid_api_key");
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(failed);

        ChatResponse resp = chatService.chat(req);

        assertEquals(GoalStatus.FAILED.name(), resp.status());
        verify(sessionService, never()).saveContextBatch(anyString(), any());
    }

    /** 注入回复后处理器：出参应为清洗后文本，并透出处理器声明的通用 flag */
    @Test
    void chat_replyPostProcessor_cleansTextAndExposesFlags() {
        ChatRequest req = new ChatRequest("你好", "50", null);
        when(routeJudge.plan(eq("你好"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeSync(anyString(), eq("你好"), eq("50"), any(), any(), any()))
                .thenReturn(succeededGoal("50", "RAW-[[x]]你好"));
        ReplyPostProcessor processor = raw ->
                new ReplyPostProcessor.Processed(raw.replace("[[x]]", ""), Set.of("stub"));
        when(replyPostProcessors.orderedStream()).thenReturn(java.util.stream.Stream.of(processor));

        ChatResponse resp = chatService.chat(req);

        assertEquals("RAW-你好", resp.reply(), "出参应为清洗后文本");
        assertTrue(resp.flags().contains("stub"), "应透出处理器声明的 flag: " + resp.flags());
        // 核心不变量：清洗后文本既出参、又写回会话记忆，两处必须一致
        assertEquals("RAW-你好", batchText(capturedBatch("50"), true),
                "写回会话记忆的 assistant 文本应为清洗后文本");
    }

    /** 同步降级路径：编排 FAILED + 会话 Agent 重答成功时，出参与写回均应使用清洗后文本并透出 flag */
    @Test
    void chat_complexFallback_shouldPostProcessRetryReplyAndWriteBackCleanedText() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());
        Goal orchestration = new Goal("g-orch", "调研竞品", "50");
        orchestration.fail("子任务 LLM 调用超时");
        Goal retry = new Goal("g-retry", "调研竞品", "50");
        retry.succeed("RAW-[[x]]重答结果");
        when(agentService.executeSync(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(orchestration);
        when(agentService.executeSync(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(retry);
        ReplyPostProcessor processor = raw ->
                new ReplyPostProcessor.Processed(raw.replace("[[x]]", ""), Set.of("stub"));
        when(replyPostProcessors.orderedStream()).thenReturn(java.util.stream.Stream.of(processor));

        ChatResponse resp = chatService.chat(req);

        assertEquals("RAW-重答结果", resp.reply(), "降级重答出参应为清洗后文本");
        assertTrue(resp.flags().contains("stub"), "应透出处理器声明的 flag: " + resp.flags());
        // 降级重答成功后的写回同样要落清洗后文本（出参/写回两处一致）
        assertEquals("RAW-重答结果", batchText(capturedBatch("50"), true),
                "降级重答写回会话记忆的 assistant 文本应为清洗后文本");
    }

    /** 无回复后处理器（缺省缺位）时文本原样、flags 空集：行为与改造前一致 */
    @Test
    void chat_withoutReplyPostProcessor_keepsRawTextAndEmptyFlags() {
        ChatRequest req = new ChatRequest("继续说", "42", null);
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("42", "RAW-[[x]]你好"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("RAW-[[x]]你好", resp.reply(), "无处理器时文本应原样透出");
        assertTrue(resp.flags().isEmpty(), "无处理器时 flags 应为空集: " + resp.flags());
    }

    /** 本轮工具写入了语音工件：成功响应应透出 voice:<id>（渠道无关，QQ 渠道消费） */
    @Test
    void chat_withVoiceArtifact_addsVoiceFlag() {
        ChatRequest req = new ChatRequest("用语音说", "50", null);
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("50", "好的"));
        when(voiceArtifacts.findByTurn(anyString())).thenReturn(Optional.of(
                new VoiceArtifactStore.VoiceArtifact("abc", "turn", "好的", new byte[]{1}, "mp3", 0L)));

        ChatResponse resp = chatService.chat(req);

        assertTrue(resp.flags().contains("voice:abc"), "应透出语音 flag: " + resp.flags());
    }

    /** 失败响应不带语音 flag（失败文案走文字，不消费工件） */
    @Test
    void chat_failed_doesNotAddVoiceFlag() {
        ChatRequest req = new ChatRequest("用语音说", "50", null);
        Goal failed = new Goal("goal-voice", "用语音说", "50");
        failed.fail("boom");
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(failed);

        ChatResponse resp = chatService.chat(req);

        // 失败路径不查语音工件（flags 固定空集），无需打桩 findByTurn
        verify(voiceArtifacts, never()).findByTurn(anyString());
        assertEquals(GoalStatus.FAILED.name(), resp.status());
        assertTrue(resp.flags().isEmpty(), "失败响应不应透出语音 flag: " + resp.flags());
    }

    @Test
    void streamReactive_emitsSseTokensAndMeta() {
        ChatRequest req = new ChatRequest("hi", null, null);
        when(sessionService.createSession("anonymous", "hi")).thenReturn("50");
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("a", "b"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: a"), "应包含第 1 个 token（显式 event 声明）");
        assertTrue(lines.contains("event: token\ndata: b"), "应包含第 2 个 token（显式 event 声明）");
        assertTrue(lines.contains("event: token\ndata: [DONE]"), "token 结束后应包含 [DONE]（同块结构）");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta")), "末尾应包含 meta 事件");
        String metaData = lines.stream()
                .filter(l -> l.contains("\"sessionId\":\"50\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应包含 meta 的事件块"));
        assertTrue(metaData.startsWith("event: meta"), "meta 的 event 与 data 应在同一元素内");
        assertTrue(metaData.contains("\"status\":\"SUCCEEDED\""), "meta 应包含 status=SUCCEEDED");
    }

    /** 后处理器抛异常应透出（不再静默跳过）：否则出参与写回会带着未清洗内容 */
    @Test
    void chat_replyPostProcessorThrows_shouldPropagate() {
        ChatRequest req = new ChatRequest("你好", "50", null);
        when(routeJudge.plan(eq("你好"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeSync(anyString(), eq("你好"), eq("50"), any(), any(), any()))
                .thenReturn(succeededGoal("50", "回答"));
        when(replyPostProcessors.orderedStream()).thenReturn(java.util.stream.Stream.of(raw -> {
            throw new IllegalStateException("processor boom");
        }));

        assertThrows(IllegalStateException.class, () -> chatService.chat(req));
        verify(sessionService, never()).saveContextBatch(anyString(), any());
    }

    /** 会话 Agent 解析失败（DB 异常）应上抛：不再静默回退 general，避免专属 Agent 的会话跑错 Agent */
    @Test
    void chat_sessionLookupFails_shouldPropagateInsteadOfFallingBack() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(sessionService.getSession("50")).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> chatService.chat(req));
        verify(agentService, never()).executeSync(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void streamReactive_success_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("a", "b"));

        chatService.streamReactive(req).collectList().block();

        List<SessionMessageEntry> entries = capturedBatch("50");
        verify(sessionService).touchSession(eq("50"), eq("hi"));
        assertEquals("hi", batchText(entries, false), "写回 user 应与原始提问一致");
        assertEquals("ab", batchText(entries, true), "写回 assistant 应为完整回复");
        assertNotNull(entries.get(0).ts(), "user 消息应带真实发送时刻（批量条目携带 ts）");
    }

    @Test
    void streamReactive_onError_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        chatService.streamReactive(req).collectList().block();

        verify(sessionService, never()).saveContextBatch(anyString(), any());
        verify(sessionService, never()).touchSession(anyString(), anyString());
    }

    @Test
    void streamReactive_withAgentId_directAnswer_skipsJudge() {
        // 路由策略（2026-09-27）：绑定专属 Agent（≠general）跳过 routeJudge，由该 Agent 携带自身工具直答
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeStreamReactive(eq("writer"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("writer-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: writer-token"), "专属 Agent 应直接流式直答");
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService).executeStreamReactive(eq("writer"), eq("hi"), eq("50"), any(), any(), any());
    }

    @Test
    void streamReactive_withAgentId_switchSyncFails_shouldNotBreakStream() {
        // 会话 Agent 同步失败只告警不中断：判定后 SIMPLE 按会话绑定（无绑定回退 general）
        ChatRequest req = new ChatRequest("hi", "50", 99L);
        doThrow(new IllegalArgumentException("agent 不存在: 99"))
                .when(sessionService).switchAgent("50", 99L);
        when(routeJudge.plan(eq("hi"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("fallback-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: fallback-token"), "同步失败不得影响本次聊天流");
        verify(routeJudge).plan(eq("hi"), any(), any());
    }

    @Test
    void streamReactive_withAgentId_complexWording_stillDirectAnswerWithoutJudge() {
        // 显式指定 Agent（绑定成功）固定走该 Agent 的 agentic 链路：即便措辞像 COMPLEX 也不再判定
        ChatRequest req = new ChatRequest("调研竞品", "50", 2L);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeStreamReactive(eq("writer"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("writer-direct"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: writer-direct"), "指定 Agent 应直接流式直答");
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService, never()).executeStreamReactive(eq("multi-agent"), any(), any(), any(), any(), any());
    }

    @Test
    void streamReactive_withAgentId_general_skipsJudgeAndKeepsDirectBanner() {
        // 显式指定 general 同样跳过判定（仍属 agentic 链路），首帧直接是「直答」而非「判定中…」
        ChatRequest req = new ChatRequest("hi", "50", 1L);
        SessionInfo session = sessionInfo(1);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("general-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(sessionService).switchAgent("50", 1L);
        verify(routeJudge, never()).plan(any(), any(), any());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"detail\":\"直答\"")),
                "显式 Agent 首帧应为「直答」");
        assertTrue(lines.stream().noneMatch(l -> l.contains("判定中")), "显式 Agent 不应出现判定提示");
    }

    @Test
    void chat_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("1");
        when(agentService.executeSync(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(succeededGoal("1", "你好，我是AI"));
        when(routeJudge.plan(eq("你好"), any(), any())).thenReturn(agenticPlan());

        chatService.chat(req);

        verify(routeJudge).plan(eq("你好"), any(), any());
    }

    @Test
    void chat_withAgentId_directAnswer_skipsJudge() {
        // 路由策略（2026-09-27）：绑定专属 Agent（≠general）跳过 routeJudge，由该 Agent 直答
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeSync(eq("writer"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(succeededGoal("50", "writer 的回答"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status());
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService).executeSync(eq("writer"), eq("hi"), eq("50"), any(), any(), any());
    }

    @Test
    void chat_withAgentId_switchSyncFails_shouldNotBreakChat() {
        // switchAgent 失败（如 agentId 非法）只告警不中断：判定后 SIMPLE 按会话绑定
        // （会话无绑定/失效时回退 general），请求照常出响应
        ChatRequest req = new ChatRequest("hi", "50", 99L);
        doThrow(new IllegalArgumentException("agent 不存在: 99"))
                .when(sessionService).switchAgent("50", 99L);
        when(routeJudge.plan(eq("hi"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeSync(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(succeededGoal("50", "general 的回答"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status(), "会话同步失败不得影响本次聊天");
        verify(routeJudge).plan(eq("hi"), any(), any());
        verify(agentService).executeSync(eq("general"), eq("hi"), eq("50"), any(), any(), any());
    }

    @Test
    void chat_withAgentId_complexWording_stillDirectAnswerWithoutJudge() {
        // 显式指定 Agent + COMPLEX 措辞：不再进 multi-agent 编排，固定由指定 Agent 直答
        ChatRequest req = new ChatRequest("调研竞品", "50", 2L);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeSync(eq("writer"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(succeededGoal("50", "writer 的回答"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status());
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService, never()).executeSync(eq("multi-agent"), any(), any(), any(), any(), any());
    }

    /* ---------------- COMPLEX 编排失败降级重答（同步 + 流式） ---------------- */

    /** 过载保护接线：上限 1 时第二路流被并发超限拒绝；首路终结 doFinally 释放后恢复 */
    @Test
    void streamReactive_limiterFull_secondRequestRejected_thenReleasedAfterTermination() {
        chatService = buildChatService(new StreamConnectionLimiter(1), null);
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("a"));  // 冷流：未订阅不发射，名额保持占用

        Flux<String> first = chatService.streamReactive(req);
        assertThrows(ConcurrentRequestException.class, () -> chatService.streamReactive(req),
                "上限 1 已占用时第二路流应立即拒绝（429）");

        first.collectList().block();  // 首路终结 → doFinally 释放
        assertTrue(chatService.streamReactive(req).collectList().block().contains("event: token\ndata: a"),
                "释放后新流应可正常进入");
    }

    /** 同步路径：COMPLEX 编排失败 → 降级为会话绑定 Agent 单模型重答一次，成功按正常响应返回 */
    @Test
    void chat_complexOrchestrationFailed_shouldFallbackToSessionAgentRetry() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());
        Goal orchestration = new Goal("g-orch", "调研竞品", "50");
        orchestration.fail("子任务 LLM 调用超时");
        Goal retry = new Goal("g-retry", "调研竞品", "50");
        retry.succeed("重答结果");
        when(agentService.executeSync(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(orchestration);
        when(agentService.executeSync(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(retry);

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status(), "重答成功应按正常响应返回");
        assertEquals("重答结果", resp.reply());
        assertEquals("g-retry", resp.goalId(), "goalId 应为重答的 goal");
        assertEquals("general", resp.agent(), "降级重答应透出实际使用的会话 Agent");
        // 会话未绑定 Agent → sessionAgentName 回退默认 general
        verify(agentService).executeSync(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any());
    }

    /** 同步路径：重答也失败 → FAILED 响应，错误信息保留「编排失败 + 重答失败」两段 */
    @Test
    void chat_complexOrchestrationFailed_retryAlsoFails_shouldKeepBothErrors() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());
        Goal orchestration = new Goal("g-orch", "调研竞品", "50");
        orchestration.fail("子任务 LLM 调用超时");
        Goal retry = new Goal("g-retry", "调研竞品", "50");
        retry.fail("重答同样超时");
        when(agentService.executeSync(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(orchestration);
        when(agentService.executeSync(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any())).thenReturn(retry);

        ChatResponse resp = chatService.chat(req);

        assertEquals("FAILED", resp.status());
        assertTrue(resp.error().contains("编排失败: 子任务 LLM 调用超时"),
                "应保留编排失败原因: " + resp.error());
        assertTrue(resp.error().contains("重答失败: 重答同样超时"),
                "应保留重答失败原因: " + resp.error());
        // 重答失败不得写回会话记忆
        verify(sessionService, never()).saveContextBatch(anyString(), any());
        verify(sessionService, never()).touchSession(anyString(), anyString());
    }

    /** 流式路径：COMPLEX 编排流异常 → 先发降级进度行，再用会话 Agent 流式重答，meta SUCCEEDED */
    @Test
    void streamReactive_complexOrchestrationError_shouldFallbackToSessionAgentRetry() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("图执行异常")));
        when(agentService.executeStreamReactive(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("重答A", "重答B"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress") && l.contains("降级")),
                "应先发降级进度行: " + lines);
        assertTrue(lines.contains("event: token\ndata: 重答A") && lines.contains("event: token\ndata: 重答B"),
                "重答内容应按 token 事件输出: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta") && l.contains("\"status\":\"SUCCEEDED\"")),
                "重答成功 meta 应为 SUCCEEDED: " + lines);
        // 成功后写回会话记忆（user + assistant 共 2 条）
        verify(sessionService).saveContextBatch(eq("50"), any());
    }

    /** 流式路径：重答也失败 → error 事件与 meta FAILED，错误信息保留「编排失败 + 重答失败」两段 */
    @Test
    void streamReactive_complexOrchestrationError_retryAlsoFails_shouldKeepBothErrors() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("图执行异常")));
        when(agentService.executeStreamReactive(eq("general"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("重答同样异常")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error")
                        && l.contains("编排失败") && l.contains("图执行异常")
                        && l.contains("重答失败") && l.contains("重答同样异常")),
                "error 事件应保留两段错误: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta") && l.contains("\"status\":\"FAILED\"")),
                "重答失败 meta 应为 FAILED: " + lines);
        verify(sessionService, never()).saveContextBatch(anyString(), any());
        verify(sessionService, never()).touchSession(anyString(), anyString());
    }

    @Test
    void streamReactive_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("a"));
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());

        chatService.streamReactive(req).collectList().block();

        verify(routeJudge).plan(eq("调研竞品"), any(), any());
        verify(agentService).executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any());
    }

    /** 简单路径不再一律压回 general：绑定专属 Agent（≠general）跳过判定直接由其回答 */
    @Test
    void streamReactive_simple_shouldRouteToSessionBoundAgent() {
        ChatRequest req = new ChatRequest("你好啊", "50", null);
        SessionInfo session = sessionInfo(10);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(10L)).thenReturn(Optional.of("coder"));
        when(agentService.executeStreamReactive(eq("coder"), eq("你好啊"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("coder 的回答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService).executeStreamReactive(eq("coder"), eq("你好啊"), eq("50"), any(), any(), any());
        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService, never()).executeStreamReactive(eq("general"), anyString(), anyString(), any(), any(), any());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"stage\":\"agent\"") && l.contains("\"detail\":\"coder\"")),
                "流首 agent 进度行应为会话绑定 Agent，而非 general: " + lines);
    }

    /** 会话绑定失效（agent 行已删）时回退 general，不得让请求失败 */
    @Test
    void streamReactive_sessionAgentMissing_shouldFallbackToGeneral() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        SessionInfo session = sessionInfo(10);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(10L)).thenReturn(Optional.empty());
        when(routeJudge.plan(eq("hi"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("fallback"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService).executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any());
        assertTrue(lines.contains("event: token\ndata: fallback"), "回退 general 后流应正常输出: " + lines);
    }

    @Test
    void streamReactive_onError_emitsErrorEvent() {
        // 绑定专属 Agent（跳过 Agentic→Graph 升级：升级会先尝试编排，掩盖原始错误事件断言）
        ChatRequest req = new ChatRequest("hi", "50", null);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeStreamReactive(eq("writer"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error")), "出错时应包含 error 事件");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error") && l.contains("data: boom")),
                "error 的 event 与 data 应在同一元素内且包含错误信息");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta")), "出错后应以 meta 收尾");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"status\":\"FAILED\"")),
                "出错后 meta 应为 FAILED");
    }

    /** 复杂多 Agent 流的「进度行」应转成 event:progress + data JSON，且不作为内容 token 输出 */
    @Test
    void streamReactive_shouldMapProgressRowToProgressEvent() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        // 一条进度行（MARK 前缀 + stage\u0001detail）+ 一条内容行
        String progressRow = ProgressLine.encode("拆解", "2 个子任务已就绪");
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just(progressRow, "最终回答A"));
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")), "应包含 event: progress");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress") && l.contains("\"stage\":\"拆解\"")),
                "进度行的 event 与 data 应在同一元素内并带 stage JSON");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")
                        && l.contains("\"stage\":\"agent\"") && l.contains("\"detail\":\"multi-agent\"")),
                "流首应带 agent 归属进度行（CLI 据此渲染回答前缀）");
        assertTrue(lines.contains("event: token\ndata: 最终回答A"),
                "内容行应按 token 事件输出（progress 之后必须显式声明 event: token，否则 SSE 粘滞会吞掉 token）");
        assertFalse(lines.stream().anyMatch(l -> l.contains("{\"stage\"") == false && l.contains("子任务已就绪")),
                "进度行不应以内容 token 形式泄漏");
    }

    /** 流首 agent 进度行：指定 agentId 时带上实际直答的会话绑定 Agent 名（跳过判定） */
    @Test
    void streamReactive_agentIdPath_emitsAgentProgressWithResolvedName() {
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentService.executeStreamReactive(eq("writer"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("writer 的回答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")
                        && l.contains("\"stage\":\"agent\"") && l.contains("\"detail\":\"writer\"")),
                "应带解析出的 agent 名: " + lines);
        assertTrue(lines.contains("event: token\ndata: writer 的回答"), "内容照常输出: " + lines);
    }

    /**
     * 思考行（stage=「思考 · 归属」/「思考N · 专家名」）应与其他进度行同通道转 event:progress，
     * 且绝不作为内容 token 泄漏（会话内容收集口径 isProgress 过滤——思考不落 session_messages）
     */
    @Test
    void streamReactive_shouldMapThinkingRowsToProgressEvent_notContent() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just(
                        ProgressLine.encode("思考1 · researcher", "先检索新闻来源"),
                        ProgressLine.encode("思考 · 聚合", "汇总各子任务结果"),
                        "最终回答B"));
        when(routeJudge.plan(eq("调研竞品"), any(), any())).thenReturn(graphPlan());

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")
                        && l.contains("\"stage\":\"思考1 · researcher\"")),
                "子任务思考行应转 event:progress 并携带思考N·专家 stage: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")
                        && l.contains("\"stage\":\"思考 · 聚合\"")),
                "聚合思考行应转 event:progress: " + lines);
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("event: token") && (l.contains("先检索新闻来源")
                        || l.contains("汇总各子任务结果"))),
                "思考行不得以内容 token 形式泄漏（不落会话内容）: " + lines);
        assertTrue(lines.contains("event: token\ndata: 最终回答B"), "内容行照常输出: " + lines);
    }

    /**
     * 内容行含换行时必须转义为单条 SSE data 行：
     * 裸 \n 会把一条 data 断成多个物理行，CLI 按行解析只认前缀行，断行后半段被静默丢弃（结果不全）。
     */
    @Test
    void streamReactive_contentRowWithLineBreaks_shouldBeEscapedInSingleDataLine() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("第一段\n第二段\r\n第三段"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: 第一段\\n第二段\\r\\n第三段"),
                "换行应转义为 \\n/\\r 字面量并保持在同一条 token 块内, 实际输出: " + lines);
        // 不允许出现任何裸内容行（不带 data:/event: 前缀的非空行）
        assertTrue(lines.stream().noneMatch(l -> !l.startsWith("data:") && !l.startsWith("event:")),
                "流中不得出现脱前缀的物理断行, 实际输出: " + lines);
        // 写回记忆仍为 user+assistant 两条（转义只发生在传输层，不污染存储原文）
        verify(sessionService).saveContextBatch(eq("50"), any());
    }

    /* ---------------- resume（断点续跑） ---------------- */

    /** goal 不存在 → IllegalArgumentException（全局异常处理映射 400） */
    @Test
    void resume_goalNotFound_throwsIllegalArgument() {
        when(goalService.get("g404")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> chatService.resume("g404"));
    }

    /** goal 仍在执行中（RUNNING）→ ResumeConflictException（映射 409，防同一检查点双跑） */
    @Test
    void resume_runningGoal_throwsConflict() {
        Goal running = new Goal("g-run", "复杂任务", "s1");
        running.markRunning();
        when(goalService.get("g-run")).thenReturn(Optional.of(running));

        assertThrows(ResumeConflictException.class, () -> chatService.resume("g-run"));
        verify(agentService, never()).resumeStreamReactive(any());
    }

    /** 已成功的 goal（SUCCEEDED）→ 拒绝续跑：检查点回放无新工作，且会把状态拉回 RUNNING */
    @Test
    void resume_succeededGoal_throwsConflict() {
        Goal done = new Goal("g-done", "复杂任务", "s1");
        done.succeed("最终结果");
        when(goalService.get("g-done")).thenReturn(Optional.of(done));

        assertThrows(ResumeConflictException.class, () -> chatService.resume("g-done"));
        verify(agentService, never()).resumeStreamReactive(any());
    }

    /** 正常续跑：复用 goal 对象路由 multi-agent，SSE 输出带 goal 的 sessionId 与 goalId */
    @Test
    void resume_success_delegatesToAgentServiceWithSameGoal() {
        Goal goal = new Goal("g-ok", "复杂任务", "s9");
        goal.fail("客户端断开，编排已取消");  // 非 RUNNING（断点续跑的核心场景：上次中断的 FAILED goal）
        when(goalService.get("g-ok")).thenReturn(Optional.of(goal));
        when(agentService.resumeStreamReactive(goal)).thenReturn(Flux.just("续跑答案"));

        List<String> lines = chatService.resume("g-ok").collectList().block();

        assertNotNull(lines);
        // 复用同一 goal 实例（threadId=goalId 的检查点归属）
        verify(agentService).resumeStreamReactive(goal);
        assertTrue(lines.contains("event: token\ndata: 续跑答案"), "续跑内容应按 token 事件输出");
        String meta = lines.stream()
                .filter(l -> l.startsWith("event: meta"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("续跑流应以 meta 收尾"));
        assertTrue(meta.contains("\"sessionId\":\"s9\""), "meta 应带 goal 的会话ID");
        assertTrue(meta.contains("\"goalId\":\"g-ok\""), "meta 应带 goalId（CLI 供 /resume 复用）");
        assertTrue(meta.contains("\"status\":\"SUCCEEDED\""));
        // 成功后写回会话记忆（user=objective + assistant=续跑完整回复，共 2 条）
        verify(sessionService).saveContextBatch(eq("s9"), any());
    }

    /* ---------------- RouteJudge 与 RAG 预取并行汇合 ---------------- */

    /** 预取器注入桩：ObjectProvider 返回真实共享池实例（装配语义镜像 KnowledgeConfig.ragPrefetcher），端到端验证显式提交链路 */
    private void stubRealPrefetcher() {
        when(ragPrefetcherProvider.getIfAvailable())
                .thenReturn(new RagPrefetcher(knowledgeRetriever, agentService));
    }

    /** 绑定知识库的会话聊天：judge 前提交预取、judge 后汇合——入口解析会话 agent 名后显式提交，预取以该 agent 的 kb 绑定发起 */
    @Test
    void chat_withKnowledgeBinding_shouldPrefetchBeforeJudge() {
        stubRealPrefetcher();
        chatService = buildChatService(new StreamConnectionLimiter(0), knowledgeRetriever);
        SessionInfo session = sessionInfo(1);
        when(sessionService.getSession("42")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(routeJudge.plan(eq("什么是知识库"), any(), any())).thenReturn(agenticPlan());
        when(agentService.executeSync(eq("general"), eq("什么是知识库"), eq("42"), any(), any(), any()))
                .thenReturn(succeededGoal("42", "回答"));

        ChatResponse resp = chatService.chat(new ChatRequest("什么是知识库", "42", null));

        assertEquals("SUCCEEDED", resp.status());
        verify(knowledgeRetriever).prefetch(eq("general"), eq("42"), eq("什么是知识库"), eq(List.of("kb1")), eq(KnowledgeRetriever.SOURCE_ENTRY_PREFETCH));
    }

    /** 流式路径同样并行预取：绑定知识库会话的 streamReactive 亦显式提交预取（agentName/query 显式透传） */
    @Test
    void streamReactive_withKnowledgeBinding_shouldPrefetchBeforeJudge() {
        stubRealPrefetcher();
        chatService = buildChatService(new StreamConnectionLimiter(0), knowledgeRetriever);
        SessionInfo session = sessionInfo(1);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(agentService.executeStreamReactive(eq("general"), eq("hi"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("a"));

        chatService.streamReactive(new ChatRequest("hi", "50", null)).collectList().block();

        verify(knowledgeRetriever).prefetch(eq("general"), eq("50"), eq("hi"), eq(List.of("kb1")), eq(KnowledgeRetriever.SOURCE_ENTRY_PREFETCH));
    }

    /** 预取异常静默：预取任务抛异常只记日志，聊天主流程照常返回（纯加速语义） */
    @Test
    void chat_prefetchFails_shouldNotBreakChat() {
        stubRealPrefetcher();
        chatService = buildChatService(new StreamConnectionLimiter(0), knowledgeRetriever);
        SessionInfo session = sessionInfo(1);
        when(sessionService.getSession("42")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(agentService.executeSync(eq("general"), eq("hi"), eq("42"), any(), any(), any()))
                .thenReturn(succeededGoal("42", "回答"));
        doThrow(new RuntimeException("boom")).when(knowledgeRetriever)
                .prefetch(eq("general"), anyString(), eq("hi"), eq(List.of("kb1")), eq(KnowledgeRetriever.SOURCE_ENTRY_PREFETCH));

        ChatResponse resp = chatService.chat(new ChatRequest("hi", "42", null));

        assertEquals("SUCCEEDED", resp.status(), "预取异常不得影响聊天主流程");
    }

    /* ---------------- 路由策略：显式开关 / 置信度透出 / 运行时升级 ---------------- */

    /** 显式 routing=graph：不询问判定器，直接 Graph 编排，meta 透出 OVERRIDE 决策 */
    @Test
    void streamReactive_routingGraphOverride_shouldOrchestrateWithoutJudge() {
        ChatRequest req = new ChatRequest("整理一份结构化总结", "50", null, "graph");
        when(agentService.executeStreamReactive(eq("multi-agent"), eq("整理一份结构化总结"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("编排结果"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(routeJudge, never()).plan(any(), any(), any());
        verify(agentService).executeStreamReactive(eq("multi-agent"), eq("整理一份结构化总结"), eq("50"), any(), any(), any());
        String meta = lines.stream().filter(l -> l.startsWith("event: meta")).findFirst().orElseThrow();
        assertTrue(meta.contains("\"path\":\"graph\""), "meta 应透出显式 Graph 决策: " + meta);
        assertTrue(meta.contains("\"source\":\"OVERRIDE\""), "meta 应标记显式覆盖来源: " + meta);
    }

    /** 显式 routing=agentic：不询问判定器，固定 general 单 Agent 直答 */
    @Test
    void streamReactive_routingAgenticOverride_shouldDirectAnswer() {
        ChatRequest req = new ChatRequest("帮我分析这个问题", "50", null, "agentic");
        when(agentService.executeStreamReactive(eq("general"), eq("帮我分析这个问题"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("直答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(routeJudge, never()).plan(any(), any(), any());
        assertTrue(lines.contains("event: token\ndata: 直答"));
        String meta = lines.stream().filter(l -> l.startsWith("event: meta")).findFirst().orElseThrow();
        assertTrue(meta.contains("\"path\":\"agentic\""));
        assertTrue(meta.contains("\"source\":\"OVERRIDE\""));
    }

    /** 绑定专属 Agent 时显式 graph 只告警：Graph 专家没有该 Agent 的领域工具，保持单 Agent 执行 */
    @Test
    void streamReactive_boundAgentWithGraphOverride_shouldStaySingleAgent() {
        ChatRequest req = new ChatRequest("查仓库库存", "50", null, "graph");
        SessionInfo session = sessionInfo(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("wms-agent"));
        when(agentService.executeStreamReactive(eq("wms-agent"), eq("查仓库库存"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("库存结果"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService, never()).executeStreamReactive(eq("multi-agent"), any(), any(), any(), any(), any());
        assertTrue(lines.contains("event: token\ndata: 库存结果"));
        String meta = lines.stream().filter(l -> l.startsWith("event: meta")).findFirst().orElseThrow();
        assertTrue(meta.contains("\"source\":\"BOUND_AGENT\""));
    }

    /** Agentic 链路不做 Graph 升级：正常收尾，绝不转 multi-agent 编排 */
    @Test
    void streamReactive_agenticExhausted_shouldNotEscalateToGraph() {
        ChatRequest req = new ChatRequest("这个任务有点复杂", "50", null);
        when(routeJudge.plan(eq("这个任务有点复杂"), any(), any()))
                .thenReturn(RoutePlan.agentic(0.95, "rule: short", RoutePlan.Source.RULE));
        when(agentService.executeStreamReactive(eq("general"), eq("这个任务有点复杂"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.just("兜底回答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService, never()).executeStreamReactive(eq("multi-agent"), any(), any(), any(), any(), any());
        assertTrue(lines.contains("event: token\ndata: 兜底回答"));
        String meta = lines.stream().filter(l -> l.startsWith("event: meta")).findFirst().orElseThrow();
        assertTrue(meta.contains("\"path\":\"agentic\""), "meta 应保持 agentic: " + meta);
    }

    /** Agentic 执行失败：不升级 Graph，错误按原样透出（含 meta FAILED） */
    @Test
    void streamReactive_agenticFails_shouldNotEscalateAndEmitError() {
        ChatRequest req = new ChatRequest("这个任务有点复杂", "50", null);
        when(routeJudge.plan(eq("这个任务有点复杂"), any(), any()))
                .thenReturn(RoutePlan.agentic(0.95, "rule: short", RoutePlan.Source.RULE));
        when(agentService.executeStreamReactive(eq("general"), eq("这个任务有点复杂"), eq("50"), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("model down")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService, never()).executeStreamReactive(eq("multi-agent"), any(), any(), any(), any(), any());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error")), "失败应透出 error 事件: " + lines);
        String meta = lines.stream().filter(l -> l.startsWith("event: meta")).findFirst().orElseThrow();
        assertTrue(meta.contains("\"path\":\"agentic\""), "meta 应保持 agentic: " + meta);
    }

    /** 捕获一轮批量写回的条目（user+assistant 一次落盘）；同时断言该轮写回恰好发生一次 */
    @SuppressWarnings("unchecked")
    private List<SessionMessageEntry> capturedBatch(String sessionId) {
        ArgumentCaptor<List<SessionMessageEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(sessionService).saveContextBatch(eq(sessionId), captor.capture());
        return captor.getValue();
    }

    /** 从批量条目里按角色取消息文本（同角色多条时取第一条） */
    private static String batchText(List<SessionMessageEntry> entries, boolean assistant) {
        return entries.stream()
                .filter(e -> (e.message() instanceof AssistantMessage) == assistant)
                .map(e -> e.message().getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "批量写回缺少 " + (assistant ? "assistant" : "user") + " 消息"));
    }
    /** 会话视图替身：本测试只关心「会话绑定到哪个 Agent」，其余字段留空 */
    private static SessionInfo sessionInfo(Integer agentId) {
        return new SessionInfo(50L, "会话", "test", null, agentId, null);
    }

}