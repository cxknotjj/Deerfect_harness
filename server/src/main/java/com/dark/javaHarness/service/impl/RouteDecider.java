package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.RagPrefetcher;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.service.RouteJudge;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 路由判定与 RAG 预取汇合（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 绑定/显式 Agent 直通、显式 routing 覆盖、自动判定、入口预取并行汇合。
 *
 * <p>sync/stream 两入口共用同一份判定语义；知识库禁用时预取器 bean 缺位，判定完成即返回。
 */
@Component
class RouteDecider {

    private static final Logger log = LoggerFactory.getLogger(RouteDecider.class);

    private final RouteJudge routeJudge;

    /** RAG 入口预取器（进程级共享池 bean）；知识库禁用时缺位 null */
    private final RagPrefetcher ragPrefetcher;

    RouteDecider(RouteJudge routeJudge, ObjectProvider<RagPrefetcher> ragPrefetcherProvider) {
        this.routeJudge = routeJudge;
        this.ragPrefetcher = ragPrefetcherProvider == null ? null : ragPrefetcherProvider.getIfAvailable();
    }

    /** 路径决策 + 实际执行 Agent 的绑定结果。 */
    record RoutingOutcome(RoutePlan plan, String agent) {
    }

    /** 绑定专属 Agent 的会话跳过判定（Graph 专家没有该 Agent 的领域工具），显式 graph 仅告警。 */
    RoutingOutcome resolve(String boundAgent, ChatRequest request, String sessionId,
                           String turnId, boolean explicitAgent) {
        if (explicitAgent) {
            // 用户显式指定 Agent（绑定成功）：本轮固定走该 Agent 的 agentic 链路，不做路由判定；
            // 显式 routing=graph 与「指定 Agent」语义冲突，忽略并告警
            if ("graph".equalsIgnoreCase(request.routing())) {
                log.warn("[route] 请求显式指定 Agent {}，忽略 routing=graph，保持单 Agent 执行", boundAgent);
            }
            RoutePlan plan = RoutePlan.agentic(1.0, "explicit agent: " + boundAgent,
                    RoutePlan.Source.OVERRIDE);
            if (AgentConstants.DEFAULT_AGENT.equals(boundAgent)) {
                // 显式选 general 仍属 agentic 链路：跳过判定，但保留入口 RAG 预取（与自动判定同待遇）
                return resolveRouteWithPrefetch(boundAgent, request.message(), sessionId, turnId, null, plan);
            }
            return new RoutingOutcome(plan, boundAgent);
        }
        if (!AgentConstants.DEFAULT_AGENT.equals(boundAgent)) {
            if ("graph".equalsIgnoreCase(request.routing())) {
                log.warn("[route] 会话绑定专属 Agent {}，忽略 routing=graph，保持单 Agent 执行", boundAgent);
            }
            RoutePlan plan = RoutePlan.agentic(1.0, "session bound agent: " + boundAgent,
                    RoutePlan.Source.BOUND_AGENT);
            return new RoutingOutcome(plan, boundAgent);
        }
        return resolveRouteWithPrefetch(boundAgent, request.message(), sessionId, turnId,
                request.routing(), null);
    }

    /**
     * 预取提交 + 路径判定并行 + 汇合的收敛入口：sync/stream 共用。
     * agentName 由入口解析后显式传入（kb 绑定解析内聚预取器，null/空白不入池），
     * 判定结果也直接复用它——不再二次查会话绑定（同一值，省一轮 session+agent 查询）。
     *
     * @param forcedPlan 非空时跳过判定（显式指定 Agent 的 agentic 链路），入口 RAG 预取照常提交/汇合
     */
    private RoutingOutcome resolveRouteWithPrefetch(String agentName, String message, String sessionId,
                                                    String turnId, String routing, RoutePlan forcedPlan) {
        Future<?> prefetch = ragPrefetcher == null ? null
                : ragPrefetcher.submit(agentName, sessionId, message, KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        try {
            RoutePlan plan = forcedPlan != null ? forcedPlan : decidePlan(message, sessionId, turnId, routing);
            // 路径分布是 Graph 降级率的分母（fallbacks / graph decisions），与判定同点记账
            HarnessMetrics.routeDecision(plan.graph());
            String resolvedAgent = plan.graph() ? AgentConstants.MULTI_AGENT : agentName;
            if (ragPrefetcher != null) { // 知识库禁用时预取器缺位，判定完成即返回
                ragPrefetcher.await(prefetch);
            }
            log.info("[route] message '{}' -> path={} confidence={} source={} reason='{}' -> agent={}",
                    trimForLog(message), plan.path(), formatConfidence(plan.confidence()),
                    plan.source(), plan.reason(), resolvedAgent);
            return new RoutingOutcome(plan, resolvedAgent);
        } finally {
            if (prefetch != null) {
                prefetch.cancel(true); // 汇合窗口外放弃：中断预取任务，占用即归还共享池
            }
        }
    }

    /**
     * 路径决策：显式 routing 优先，否则交给判定器（规则短路 + 灰区 LLM + 置信度策略；
     * 判定器内部已兜底 Agentic，此处不再重复防御）。
     */
    private RoutePlan decidePlan(String message, String sessionId, String turnId, String routing) {
        String mode = routing == null ? "auto" : routing.trim().toLowerCase(Locale.ROOT);
        if ("agentic".equals(mode)) {
            return RoutePlan.agentic(1.0, "routing override: agentic", RoutePlan.Source.OVERRIDE);
        }
        if ("graph".equals(mode)) {
            return RoutePlan.graph(1.0, List.of(), List.of(), "routing override: graph",
                    RoutePlan.Source.OVERRIDE);
        }
        if (!"auto".equals(mode) && !mode.isBlank()) {
            log.warn("[route] 未知 routing='{}'，按 auto 处理", routing);
        }
        return routeJudge.plan(message, sessionId, turnId);
    }

    /** 置信度展示（两位小数，便于进度行/日志阅读）。 */
    static String formatConfidence(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }

    /** 截断过长的 message 用于日志，避免刷屏 */
    private static String trimForLog(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("[\\r\\n]+", " ");
        return single.length() > 80 ? single.substring(0, 80) + "..." : single;
    }
}
