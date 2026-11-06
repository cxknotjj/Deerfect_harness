package com.dark.javaHarness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.view.AgentItemView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * AgentService 端口契约（高扇入基座）：用内存替身固化「调用方可以依赖的方法语义」，
 * 不依赖 MySQL / Spring 上下文。
 *
 * <p>替身按既有实现（server AgentServiceImpl）的可观察语义实现：埋点 Goal 回显请求上下文、
 * 同步执行落到 SUCCEEDED、流式执行返回 token Flux、续跑先置 RUNNING、查不到的映射返回 empty。
 */
class AgentServiceContractTest {

    private final InMemoryAgentService service = new InMemoryAgentService();

    @BeforeEach
    void registerAgents() {
        service.register(1L, "general", new AgentConfig(1L, "gpt-4o", "你是助手", null));
        service.register(2L, "writer", new AgentConfig(2L, "deepseek", "写作专家", "kb1"));
    }

    @Test
    void submit_returnsPendingGoalForAsyncExecution() {
        Goal goal = service.submit("general", "总结一下");

        assertEquals("总结一下", goal.objective());
        assertEquals(GoalStatus.PENDING, goal.status(), "异步提交应返回待执行 Goal");
        assertEquals("g-async", goal.id());
    }

    @Test
    void executeSync_echoesSessionTurnTraceAndChannel() {
        Goal goal = service.executeSync("general", "你好", "s1", "t1", "tr1", "cli");

        assertEquals("你好", goal.objective());
        assertEquals("s1", goal.sessionId());
        assertEquals("t1", goal.turnId());
        assertEquals("tr1", goal.traceId());
        assertEquals("cli", goal.channel());
        assertEquals(GoalStatus.SUCCEEDED, goal.status(), "同步执行完成后应为 SUCCEEDED");
        assertEquals("done", goal.summary());
    }

    @Test
    void executeSync_overloads_defaultOptionalContext() {
        Goal goal = service.executeSync("general", "你好");

        assertEquals(null, goal.sessionId());
        assertEquals(null, goal.turnId());
        assertEquals(null, goal.traceId());
        assertEquals(null, goal.channel());

        Goal withSession = service.executeSync("general", "你好", "s2", "t2");
        assertEquals("s2", withSession.sessionId());
        assertEquals("t2", withSession.turnId());
    }

    @Test
    void executeStreamReactive_emitsTokensAsFlux() {
        List<String> tokens = service.executeStreamReactive("general", "你好", "s3", "t3", "tr3", "web")
                .collectList()
                .block();

        assertEquals(List.of("token1", "token2"), tokens);
    }

    @Test
    void resumeStreamReactive_movesGoalBackToRunningBeforeEmitting() {
        Goal goal = new Goal("g-resume", "复杂任务", "s4");
        goal.fail("客户端断开");

        Flux<String> stream = service.resumeStreamReactive(goal);

        assertEquals(GoalStatus.RUNNING, goal.status(), "续跑应先置回 RUNNING（防同一检查点双跑）");
        assertEquals(List.of("resumed"), stream.collectList().block());
    }

    @Test
    void lookupMethods_returnRegisteredDataOrEmpty() {
        assertEquals(Set.of("general", "writer"), service.agentNames());
        assertEquals(List.of(new AgentItemView(1L, "general"), new AgentItemView(2L, "writer")),
                service.agentItems());
        assertEquals(Optional.of("writer"), service.findAgentNameById(2L));
        assertEquals(Optional.empty(), service.findAgentNameById(99L));
        assertEquals(Optional.of(service.configs.get("general")), service.getAgentConfig("general"));
        assertEquals(Optional.empty(), service.getAgentConfig("missing"));
    }

    /** 内存替身：仅实现契约测试需要的可观察语义，不模拟持久化 */
    private static final class InMemoryAgentService implements AgentService {

        private final Map<Long, String> namesById = new LinkedHashMap<>();
        private final Map<String, AgentConfig> configs = new HashMap<>();

        void register(long id, String name, AgentConfig config) {
            namesById.put(id, name);
            configs.put(name, config);
        }

        @Override
        public Goal submit(String agentName, String objective) {
            return new Goal("g-async", objective);
        }

        /** 契约只要求「受理与否」可判定；内存替身不做真实重投 */
        @Override
        public boolean retry(String goalId) {
            return goalId != null && goalId.startsWith("g-");
        }

        @Override
        public Goal executeSync(String agentName, String objective) {
            return executeSync(agentName, objective, null, null, null, null);
        }

        @Override
        public Goal executeSync(String agentName, String objective, String sessionId, String turnId) {
            return executeSync(agentName, objective, sessionId, turnId, null, null);
        }

        @Override
        public Goal executeSync(String agentName, String objective, String sessionId, String turnId,
                                String traceId) {
            return executeSync(agentName, objective, sessionId, turnId, traceId, null);
        }

        @Override
        public Goal executeSync(String agentName, String objective, String sessionId, String turnId,
                                String traceId, String channel) {
            Goal goal = new Goal("g-sync", objective, sessionId, turnId, traceId, channel);
            goal.markRunning();
            goal.succeed("done");
            return goal;
        }

        @Override
        public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId,
                                                  String turnId) {
            return Flux.just("token1", "token2");
        }

        @Override
        public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId,
                                                  String turnId, String traceId) {
            return executeStreamReactive(agentName, objective, sessionId, turnId);
        }

        @Override
        public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId,
                                                  String turnId, String traceId, String channel) {
            return Flux.just("token1", "token2");
        }

        @Override
        public Flux<String> resumeStreamReactive(Goal goal) {
            goal.markRunning();
            return Flux.just("resumed");
        }

        @Override
        public Set<String> agentNames() {
            return Set.copyOf(namesById.values());
        }

        @Override
        public List<AgentItemView> agentItems() {
            return namesById.entrySet().stream()
                    .map(e -> new AgentItemView(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public Optional<String> findAgentNameById(Long agentId) {
            return Optional.ofNullable(namesById.get(agentId));
        }

        @Override
        public Optional<AgentConfig> getAgentConfig(String agentName) {
            return Optional.ofNullable(configs.get(agentName));
        }
    }
}
