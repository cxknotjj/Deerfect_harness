package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.config.StreamConnectionLimiter;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.exception.ConcurrentRequestException;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        // 手动构造（7 参 @Autowired 构造加入后 @InjectMocks 无法实例化）：null 知识检索器
        // （知识库禁用语义）、限流器 0=不限制（计数语义由 StreamConnectionLimiterTest 覆盖）、
        // 降级开关开启（降级用例依赖；既有用例无「COMPLEX + 失败」组合，不受影响）
        chatService = new ChatServiceImpl(agentService, sessionService, routeJudge, goalService,
                null, new StreamConnectionLimiter(0), true);
    }

    private Goal succeededGoal(String sessionId, String summary) {
        Goal g = new Goal("goal-1", "hi", sessionId);
        g.succeed(summary);
        return g;
    }

    @Test
    void chat_withoutSessionId_shouldCreateNewSession() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("42");
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("42", "你好，我是AI"));

        ChatResponse resp = chatService.chat(req);

        assertTrue(resp.newSession(), "首次不带 sessionId 应 newSession=true");
        assertEquals("42", resp.sessionId());
        verify(sessionService).createSession("anonymous", "你好");
    }

    @Test
    void chat_withSessionId_shouldReuseSession() {
        ChatRequest req = new ChatRequest("继续说", "42", null);
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("42", "好的，继续说"));

        ChatResponse resp = chatService.chat(req);

        assertFalse(resp.newSession(), "带已有 sessionId 应 newSession=false");
        verify(sessionService, never()).createSession(anyString(), anyString());
    }

    @Test
    void chat_syncSuccess_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("帮我写首诗", "7", null);
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("7", "风急天高猿啸哀"));

        chatService.chat(req);

        ArgumentCaptor<UserMessage> userCapture = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<AssistantMessage> assistantCapture = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(sessionService).saveContext(anyString(), userCapture.capture());
        verify(sessionService).saveContext(anyString(), assistantCapture.capture());
        verify(sessionService).touchSession(eq("7"), eq("帮我写首诗"));

        assertEquals("帮我写首诗", userCapture.getValue().getText());
        assertEquals("风急天高猿啸哀", assistantCapture.getValue().getText());
    }

    @Test
    void chat_failure_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "1", null);
        Goal failed = new Goal("goal-2", "hi", "1");
        failed.fail("invalid_api_key");
        when(agentService.executeSync(anyString(), anyString(), anyString())).thenReturn(failed);

        ChatResponse resp = chatService.chat(req);

        assertEquals(GoalStatus.FAILED.name(), resp.status());
        verify(sessionService, never()).saveContext(anyString(), any());
    }

    @Test
    void streamReactive_emitsSseTokensAndMeta() {
        ChatRequest req = new ChatRequest("hi", null, null);
        when(sessionService.createSession("anonymous", "hi")).thenReturn("50");
        when(agentService.executeStreamReactive("general", "hi", "50"))
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

    @Test
    void streamReactive_success_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("a", "b"));

        chatService.streamReactive(req).collectList().block();

        ArgumentCaptor<UserMessage> userCapture = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<AssistantMessage> assistantCapture = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(sessionService).saveContext(eq("50"), userCapture.capture());
        verify(sessionService).saveContext(eq("50"), assistantCapture.capture());
        verify(sessionService).touchSession(eq("50"), eq("hi"));
        assertEquals("hi", userCapture.getValue().getText(), "写回 user 应与原始提问一致");
        assertEquals("ab", assistantCapture.getValue().getText(), "写回 assistant 应为完整回复");
    }

    @Test
    void streamReactive_onError_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        chatService.streamReactive(req).collectList().block();

        verify(sessionService, never()).saveContext(anyString(), any());
        verify(sessionService, never()).touchSession(anyString(), anyString());
    }

    @Test
    void streamReactive_withAgentId_simple_shouldJudgeAndRouteToSessionAgent() {
        // 指定 agentId：先同步会话绑定，再统一判定；SIMPLE 流由会话绑定 Agent 产出
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionEntity session = new SessionEntity();
        session.setAgentId(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeStreamReactive("writer", "hi", "50"))
                .thenReturn(Flux.just("writer-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: writer-token"), "SIMPLE 应由会话绑定 Agent 流式直答");
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge).judge(eq("hi"), any());
        verify(agentService).executeStreamReactive("writer", "hi", "50");
    }

    @Test
    void streamReactive_withAgentId_switchSyncFails_shouldNotBreakStream() {
        // 会话 Agent 同步失败只告警不中断：判定后 SIMPLE 按会话绑定（无绑定回退 general）
        ChatRequest req = new ChatRequest("hi", "50", 99L);
        doThrow(new IllegalArgumentException("agent 不存在: 99"))
                .when(sessionService).switchAgent("50", 99L);
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("fallback-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: fallback-token"), "同步失败不得影响本次聊天流");
        verify(routeJudge).judge(eq("hi"), any());
    }

    @Test
    void streamReactive_withAgentId_complex_shouldOrchestrate() {
        // 指定 agentId + COMPLEX：照常进 multi-agent 编排（含 complexFallback 接线）
        ChatRequest req = new ChatRequest("调研竞品", "50", 2L);
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.just("编排结果"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge).judge(eq("调研竞品"), any());
        verify(agentService).executeStreamReactive("multi-agent", "调研竞品", "50");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"stage\":\"agent\"")
                        && l.contains("\"detail\":\"multi-agent\"")),
                "流首 agent 进度行应为 multi-agent（编排归属）");
    }

    @Test
    void chat_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("1");
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("1", "你好，我是AI"));
        when(routeJudge.judge(eq("你好"), any())).thenReturn(RouteDecision.SIMPLE);

        chatService.chat(req);

        verify(routeJudge).judge(eq("你好"), any());
    }

    @Test
    void chat_withAgentId_simple_shouldJudgeAndRouteToSessionAgent() {
        // 指定 agentId 仅同步会话绑定，路由仍统一判定：SIMPLE → 会话绑定 Agent 直答
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionEntity session = new SessionEntity();
        session.setAgentId(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeSync("writer", "hi", "50"))
                .thenReturn(succeededGoal("50", "writer 的回答"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status());
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge).judge(eq("hi"), any());
        verify(agentService).executeSync("writer", "hi", "50");
    }

    @Test
    void chat_withAgentId_switchSyncFails_shouldNotBreakChat() {
        // switchAgent 失败（如 agentId 非法）只告警不中断：判定后 SIMPLE 按会话绑定
        // （会话无绑定/失效时回退 general），请求照常出响应
        ChatRequest req = new ChatRequest("hi", "50", 99L);
        doThrow(new IllegalArgumentException("agent 不存在: 99"))
                .when(sessionService).switchAgent("50", 99L);
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeSync("general", "hi", "50"))
                .thenReturn(succeededGoal("50", "general 的回答"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status(), "会话同步失败不得影响本次聊天");
        verify(routeJudge).judge(eq("hi"), any());
        verify(agentService).executeSync("general", "hi", "50");
    }

    @Test
    void chat_withAgentId_complex_shouldOrchestrate() {
        // 指定 agentId + COMPLEX：不再钉死单 Agent，照常进 multi-agent 编排
        ChatRequest req = new ChatRequest("调研竞品", "50", 2L);
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        when(agentService.executeSync("multi-agent", "调研竞品", "50"))
                .thenReturn(succeededGoal("50", "编排结果"));

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status());
        verify(sessionService).switchAgent("50", 2L);
        verify(routeJudge).judge(eq("调研竞品"), any());
        verify(agentService).executeSync("multi-agent", "调研竞品", "50");
    }

    /* ---------------- COMPLEX 编排失败降级重答（同步 + 流式） ---------------- */

    /** 过载保护接线：上限 1 时第二路流被并发超限拒绝；首路终结 doFinally 释放后恢复 */
    @Test
    void streamReactive_limiterFull_secondRequestRejected_thenReleasedAfterTermination() {
        chatService = new ChatServiceImpl(agentService, sessionService, routeJudge, goalService,
                null, new StreamConnectionLimiter(1), true);
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
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
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        Goal orchestration = new Goal("g-orch", "调研竞品", "50");
        orchestration.fail("子任务 LLM 调用超时");
        Goal retry = new Goal("g-retry", "调研竞品", "50");
        retry.succeed("重答结果");
        when(agentService.executeSync("multi-agent", "调研竞品", "50")).thenReturn(orchestration);
        when(agentService.executeSync("general", "调研竞品", "50")).thenReturn(retry);

        ChatResponse resp = chatService.chat(req);

        assertEquals("SUCCEEDED", resp.status(), "重答成功应按正常响应返回");
        assertEquals("重答结果", resp.reply());
        assertEquals("g-retry", resp.goalId(), "goalId 应为重答的 goal");
        assertEquals("general", resp.agent(), "降级重答应透出实际使用的会话 Agent");
        // 会话未绑定 Agent → sessionAgentName 回退默认 general
        verify(agentService).executeSync("general", "调研竞品", "50");
    }

    /** 同步路径：重答也失败 → FAILED 响应，错误信息保留「编排失败 + 重答失败」两段 */
    @Test
    void chat_complexOrchestrationFailed_retryAlsoFails_shouldKeepBothErrors() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        Goal orchestration = new Goal("g-orch", "调研竞品", "50");
        orchestration.fail("子任务 LLM 调用超时");
        Goal retry = new Goal("g-retry", "调研竞品", "50");
        retry.fail("重答同样超时");
        when(agentService.executeSync("multi-agent", "调研竞品", "50")).thenReturn(orchestration);
        when(agentService.executeSync("general", "调研竞品", "50")).thenReturn(retry);

        ChatResponse resp = chatService.chat(req);

        assertEquals("FAILED", resp.status());
        assertTrue(resp.error().contains("编排失败: 子任务 LLM 调用超时"),
                "应保留编排失败原因: " + resp.error());
        assertTrue(resp.error().contains("重答失败: 重答同样超时"),
                "应保留重答失败原因: " + resp.error());
        // 重答失败不得写回会话记忆
        verify(sessionService, never()).saveContext(anyString(), any());
    }

    /** 流式路径：COMPLEX 编排流异常 → 先发降级进度行，再用会话 Agent 流式重答，meta SUCCEEDED */
    @Test
    void streamReactive_complexOrchestrationError_shouldFallbackToSessionAgentRetry() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.error(new IllegalStateException("图执行异常")));
        when(agentService.executeStreamReactive("general", "调研竞品", "50"))
                .thenReturn(Flux.just("重答A", "重答B"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress") && l.contains("降级")),
                "应先发降级进度行: " + lines);
        assertTrue(lines.contains("event: token\ndata: 重答A") && lines.contains("event: token\ndata: 重答B"),
                "重答内容应按 token 事件输出: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta") && l.contains("\"status\":\"SUCCEEDED\"")),
                "重答成功 meta 应为 SUCCEEDED: " + lines);
        // 成功后写回会话记忆（user + assistant 共 2 条）
        verify(sessionService, times(2)).saveContext(eq("50"), any());
    }

    /** 流式路径：重答也失败 → error 事件与 meta FAILED，错误信息保留「编排失败 + 重答失败」两段 */
    @Test
    void streamReactive_complexOrchestrationError_retryAlsoFails_shouldKeepBothErrors() {
        ChatRequest req = new ChatRequest("调研竞品", "50", null);
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.error(new IllegalStateException("图执行异常")));
        when(agentService.executeStreamReactive("general", "调研竞品", "50"))
                .thenReturn(Flux.error(new IllegalStateException("重答同样异常")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error")
                        && l.contains("编排失败") && l.contains("图执行异常")
                        && l.contains("重答失败") && l.contains("重答同样异常")),
                "error 事件应保留两段错误: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta") && l.contains("\"status\":\"FAILED\"")),
                "重答失败 meta 应为 FAILED: " + lines);
        verify(sessionService, never()).saveContext(anyString(), any());
    }

    @Test
    void streamReactive_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.just("a"));
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);

        chatService.streamReactive(req).collectList().block();

        verify(routeJudge).judge(eq("调研竞品"), any());
        verify(agentService).executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"));
    }

    /** 简单路径不再一律压回 general：按 session.agent_id 路由到会话绑定的 Agent */
    @Test
    void streamReactive_simple_shouldRouteToSessionBoundAgent() {
        ChatRequest req = new ChatRequest("你好啊", "50", null);
        SessionEntity session = new SessionEntity();
        session.setAgentId(10);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(10L)).thenReturn(Optional.of("coder"));
        when(routeJudge.judge(eq("你好啊"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeStreamReactive("coder", "你好啊", "50"))
                .thenReturn(Flux.just("coder 的回答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService).executeStreamReactive("coder", "你好啊", "50");
        verify(agentService, never()).executeStreamReactive(eq("general"), anyString(), anyString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"stage\":\"agent\"") && l.contains("\"detail\":\"coder\"")),
                "流首 agent 进度行应为会话绑定 Agent，而非 general: " + lines);
    }

    /** 会话绑定失效（agent 行已删）时回退 general，不得让请求失败 */
    @Test
    void streamReactive_sessionAgentMissing_shouldFallbackToGeneral() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        SessionEntity session = new SessionEntity();
        session.setAgentId(10);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(10L)).thenReturn(Optional.empty());
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("fallback"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        verify(agentService).executeStreamReactive("general", "hi", "50");
        assertTrue(lines.contains("event: token\ndata: fallback"), "回退 general 后流应正常输出: " + lines);
    }

    @Test
    void streamReactive_onError_emitsErrorEvent() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
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
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.just(progressRow, "最终回答A"));
        when(routeJudge.judge(eq("调研竞品"), any())).thenReturn(RouteDecision.COMPLEX);

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

    /** 流首 agent 进度行：指定 agentId + SIMPLE 时带上判定后实际路由的会话绑定 Agent 名 */
    @Test
    void streamReactive_agentIdPath_emitsAgentProgressWithResolvedName() {
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        SessionEntity session = new SessionEntity();
        session.setAgentId(2);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(routeJudge.judge(eq("hi"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeStreamReactive("writer", "hi", "50"))
                .thenReturn(Flux.just("writer 的回答"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")
                        && l.contains("\"stage\":\"agent\"") && l.contains("\"detail\":\"writer\"")),
                "应带解析出的 agent 名: " + lines);
        assertTrue(lines.contains("event: token\ndata: writer 的回答"), "内容照常输出: " + lines);
    }

    /**
     * 内容行含换行时必须转义为单条 SSE data 行：
     * 裸 \n 会把一条 data 断成多个物理行，CLI 按行解析只认前缀行，断行后半段被静默丢弃（结果不全）。
     */
    @Test
    void streamReactive_contentRowWithLineBreaks_shouldBeEscapedInSingleDataLine() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("第一段\n第二段\r\n第三段"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: 第一段\\n第二段\\r\\n第三段"),
                "换行应转义为 \\n/\\r 字面量并保持在同一条 token 块内, 实际输出: " + lines);
        // 不允许出现任何裸内容行（不带 data:/event: 前缀的非空行）
        assertTrue(lines.stream().noneMatch(l -> !l.startsWith("data:") && !l.startsWith("event:")),
                "流中不得出现脱前缀的物理断行, 实际输出: " + lines);
        // 写回记忆仍为 user+assistant 两条（转义只发生在传输层，不污染存储原文）
        verify(sessionService, times(2)).saveContext(eq("50"), any());
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
        verify(sessionService, times(2)).saveContext(eq("s9"), any());
    }

    /* ---------------- RouteJudge 与 RAG 预取并行汇合 ---------------- */

    /** 绑定知识库的会话聊天：judge 前提交预取、judge 后汇合——prefetch 应以会话 Agent 的 kb 绑定被调用 */
    @Test
    void chat_withKnowledgeBinding_shouldPrefetchBeforeJudge() {
        chatService = new ChatServiceImpl(agentService, sessionService, routeJudge, goalService,
                knowledgeRetriever, new StreamConnectionLimiter(0), true);
        SessionEntity session = new SessionEntity();
        session.setAgentId(1);
        when(sessionService.getSession("42")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(routeJudge.judge(eq("什么是知识库"), any())).thenReturn(RouteDecision.SIMPLE);
        when(agentService.executeSync("general", "什么是知识库", "42"))
                .thenReturn(succeededGoal("42", "回答"));

        ChatResponse resp = chatService.chat(new ChatRequest("什么是知识库", "42", null));

        assertEquals("SUCCEEDED", resp.status());
        verify(knowledgeRetriever).prefetch(eq("general"), anyString(), eq("什么是知识库"), eq(List.of("kb1")));
    }

    /** 流式路径同样并行预取：绑定知识库会话的 streamReactive 亦提交预取 */
    @Test
    void streamReactive_withKnowledgeBinding_shouldPrefetchBeforeJudge() {
        chatService = new ChatServiceImpl(agentService, sessionService, routeJudge, goalService,
                knowledgeRetriever, new StreamConnectionLimiter(0), true);
        SessionEntity session = new SessionEntity();
        session.setAgentId(1);
        when(sessionService.getSession("50")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("a"));

        chatService.streamReactive(new ChatRequest("hi", "50", null)).collectList().block();

        verify(knowledgeRetriever).prefetch(eq("general"), anyString(), eq("hi"), eq(List.of("kb1")));
    }

    /** 预取异常静默：预取任务抛异常只记日志，聊天主流程照常返回（纯加速语义） */
    @Test
    void chat_prefetchFails_shouldNotBreakChat() {
        chatService = new ChatServiceImpl(agentService, sessionService, routeJudge, goalService,
                knowledgeRetriever, new StreamConnectionLimiter(0), true);
        SessionEntity session = new SessionEntity();
        session.setAgentId(1);
        when(sessionService.getSession("42")).thenReturn(session);
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        when(agentService.executeSync("general", "hi", "42"))
                .thenReturn(succeededGoal("42", "回答"));
        doThrow(new RuntimeException("boom")).when(knowledgeRetriever)
                .prefetch(eq("general"), anyString(), eq("hi"), eq(List.of("kb1")));

        ChatResponse resp = chatService.chat(new ChatRequest("hi", "42", null));

        assertEquals("SUCCEEDED", resp.status(), "预取异常不得影响聊天主流程");
    }
}