package com.dark.javaHarness.service.impl.route;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.service.AgentService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RagPrefetcher 显式签名单测（submit(agentName, sessionId, query)）：
 * - 显式提交 → kb 绑定解析正确并发起预取
 * - agent 名缺失 / 未绑定知识库 → 不入池零行为
 * - 绑定解析异常静默（返回 null 不上抛）
 * - await 超汇合窗口 → cancel 中断放弃
 */
@ExtendWith(MockitoExtension.class)
class RagPrefetcherTest {

    @Mock
    private KnowledgeRetriever knowledgeRetriever;
    @Mock
    private AgentService agentService;

    private RagPrefetcher prefetcher;

    @BeforeEach
    void setUp() {
        prefetcher = new RagPrefetcher(knowledgeRetriever, agentService);
    }

    /** ①显式提交：kb 绑定解析正确（逗号拆分 + trim），并以显式参数（agentName/sessionId/query）发起预取 */
    @Test
    void submit_explicitArgs_shouldResolveKbsAndPrefetch() {
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1, kb2")));

        Future<?> prefetch = prefetcher.submit("general", "42", "什么是知识库");

        assertNotNull(prefetch, "已绑定知识库应入池");
        prefetcher.await(prefetch);
        verify(knowledgeRetriever).prefetch("general", "42", "什么是知识库", List.of("kb1", "kb2"));
    }

    /** ②agent 名为 null/空白不入池：无事发生（不查 config、不发起预取） */
    @Test
    void submit_nullOrBlankAgentName_shouldNotEnqueue() {
        assertNull(prefetcher.submit(null, "42", "q"), "agent 名 null 应不入池");
        assertNull(prefetcher.submit("  ", "42", "q"), "agent 名空白应不入池");
        verifyNoInteractions(agentService, knowledgeRetriever);
    }

    /** ②未绑定知识库不入池：config 缺失 / knowledge 列空 → 不发起任何检索 */
    @Test
    void submit_noKbBinding_shouldNotEnqueue() {
        when(agentService.getAgentConfig("general")).thenReturn(Optional.empty());
        assertNull(prefetcher.submit("general", "42", "q"), "config 缺失应不入池");

        // knowledge 列为空串同样零行为（parseBinding → null）
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "")));
        assertNull(prefetcher.submit("general", "42", "q"), "未绑定知识库应不入池");

        verify(knowledgeRetriever, never()).prefetch(any(), any(), any(), any());
    }

    /** ③解析异常静默：config 查询失败不上抛，返回 null（预取是纯加速，不影响主流程） */
    @Test
    void submit_resolverThrows_shouldSilentlyReturnNull() {
        when(agentService.getAgentConfig("general")).thenThrow(new RuntimeException("db down"));

        assertNull(prefetcher.submit("general", "42", "q"), "解析异常应静默返回 null");
        verify(knowledgeRetriever, never()).prefetch(any(), any(), any(), any());
    }

    /** ④await 超汇合窗口：预取超 2s 未完成 → cancel(true) 中断放弃（占用归还共享池） */
    @Test
    void await_timeout_shouldCancelPrefetch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(agentService.getAgentConfig("general")).thenReturn(Optional.of(
                new AgentConfig(1L, "gpt", null, "kb1")));
        // 模拟超窗口的长检索：入参校验后挂起，cancel(true) 以中断唤醒（守护线程随 JVM 退出）
        doAnswer(inv -> {
            started.countDown();
            release.await();
            return null;
        }).when(knowledgeRetriever).prefetch(any(), any(), any(), any());

        Future<?> prefetch = prefetcher.submit("general", "42", "q");
        assertNotNull(prefetch);
        assertTrue(started.await(5, TimeUnit.SECONDS), "预取任务应已开始");

        long begin = System.nanoTime();
        prefetcher.await(prefetch);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(prefetch.isCancelled(), "汇合窗口外应取消预取");
        assertTrue(waitedMs >= 1_900 && waitedMs < 5_000,
                "应等待约 2s 汇合窗口（不应提前返回），实际 " + waitedMs + "ms");
        release.countDown();
    }
}
