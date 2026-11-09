package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.view.ChatRequest;
import com.dark.javaHarness.view.ChatResponse;
import com.dark.javaHarness.view.RouteInfo;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.contract.model.GoalStatus;
import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.contract.api.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 同步聊天执行段（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 建档 → 绑定 → 路由 → 单次执行 → COMPLEX 降级重答 → 后处理/写回/响应组装。
 * 无 sessionId 时自动建档；执行成功后写回会话记忆。
 */
@Component
class ChatTurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnExecutor.class);

    private final AgentService agentService;
    private final RouteDecider routeDecider;
    private final TurnPersistence persistence;
    private final ReplyDecorator decorator;

    /** COMPLEX 编排失败降级开关（app.chat.complex-fallback.enabled）：开启时编排失败降级为会话 Agent 单模型重答一次 */
    private final boolean complexFallbackEnabled;

    ChatTurnExecutor(AgentService agentService, RouteDecider routeDecider, TurnPersistence persistence,
                     ReplyDecorator decorator,
                     @Value("${app.chat.complex-fallback.enabled:true}") boolean complexFallbackEnabled) {
        this.agentService = agentService;
        this.routeDecider = routeDecider;
        this.persistence = persistence;
        this.decorator = decorator;
        this.complexFallbackEnabled = complexFallbackEnabled;
    }

    /** 同步聊天：无 sessionId 时自动建档，调 resolved Agent 同步执行并写回会话记忆 */
    ChatResponse execute(ChatRequest request) {
        // 发送时刻在入口捕获（写回 user 消息快照用，历史回显真实发送时间）
        long userSentAt = System.currentTimeMillis();
        // 轮次标识：一条用户消息触发的完整处理（route-judge + 直答/编排 + 可能的降级重答）共用
        String turnId = TurnPersistence.newTurnId();
        TurnPersistence.SessionCtx ctx = persistence.ensureSession(request.sessionId(), request.message());

        // agentId 仅作会话绑定副作用（失败告警不中断）；绑定成功即视为「用户显式指定 Agent」，
        // 本轮直接走该 Agent 的 agentic 单 Agent 链路，不再做路由判定
        boolean explicitAgent = false;
        if (request.agentId() != null) {
            explicitAgent = persistence.bindAgentQuietly(ctx.sid(), request.agentId());
        }
        // 路由策略（2026-09-27 用户决策）：绑定专属 Agent（≠general）只走简单链路——跳过
        // routeJudge 与 RAG 预取汇合，由该 Agent 携带自身工具直答（如 wms 仓储专员：编排会把
        // 子任务派给无 wms 工具的通用专家，直答才能查到真实数据）；general 会话保持统一判定
        // （SIMPLE 由 general 直答 / COMPLEX 进 multi-agent 编排）；请求显式携带 agentId 且绑定成功
        // 时（含通用 general），同样跳过判定，由指定 Agent 走 agentic 链路
        String boundAgent = persistence.sessionAgentName(ctx.sid());
        RouteDecider.RoutingOutcome routing =
                routeDecider.resolve(boundAgent, request, ctx.sid(), turnId, explicitAgent);
        String resolvedAgent = routing.agent();

        Goal goal = agentService.executeSync(resolvedAgent, request.message(), ctx.sid(), turnId,
                null, request.channel());
        // COMPLEX 编排失败降级（同步路径无客户端断开，FAILED 即编排自身失败）：
        // 降级为会话 Agent 单模型重答一次，与流式 withComplexFallback 同语义，一层兜底不递归
        if (complexFallbackEnabled && GoalStatus.FAILED == goal.status()
                && AgentConstants.MULTI_AGENT.equals(resolvedAgent)) {
            return fallback(goal, request, ctx, userSentAt, turnId);
        }
        return respond(ctx, request, goal, routing.plan().toInfo(), resolvedAgent, turnId, userSentAt);
    }

    /** 成功/失败响应组装：成功后写回清洗文本（失败轮不落上下文） */
    private ChatResponse respond(TurnPersistence.SessionCtx ctx, ChatRequest request, Goal goal,
                                 RouteInfo route, String agent, String turnId, long userSentAt) {
        ReplyDecorator.Processed pr = decorator.apply(goal.summary());
        persistence.writeBackSucceededTurn(ctx.sid(), request.message(), goal, pr.text(), userSentAt);
        if (goal.status() == GoalStatus.FAILED) {
            // 失败文案不做后处理（保持原始错误信息），flags 走 failure 的空集
            return ChatResponse.failure(ctx.sid(), ctx.newSession(), goal.id(), goal.summary(), agent, route);
        }
        return ChatResponse.success(ctx.sid(), ctx.newSession(), goal.id(), pr.text(),
                decorator.recentSources(ctx.sid()), agent, decorator.withVoiceFlag(turnId, pr.flags()), route);
    }

    /**
     * 同步路径 COMPLEX 编排失败降级重答：编排 FAILED 时用会话绑定 Agent 单模型重答一次。
     * 重答成功按正常响应返回；重答也失败才返回失败响应，错误信息保留编排失败原因 +
     * 重答失败原因两段（与流式降级错误拼接口径一致）。
     */
    private ChatResponse fallback(Goal orchestration, ChatRequest request, TurnPersistence.SessionCtx ctx,
                                  long userSentAt, String turnId) {
        log.warn("[route] 编排失败（goal={}），降级单模型重答：{}", orchestration.id(), orchestration.summary());
        HarnessMetrics.graphFallback("to_agentic");
        String fallbackAgent = persistence.sessionAgentName(ctx.sid());
        RouteInfo fallbackRoute = RoutePlan.agentic(1.0, "graph path failed; fallback to single agent",
                RoutePlan.Source.FALLBACK).toInfo();
        // 降级重答仍属同一轮同一执行链：turnId 沿用本轮值，traceId 复用编排 Goal 的（轨迹同树）
        Goal retry = agentService.executeSync(fallbackAgent, request.message(), ctx.sid(), turnId,
                orchestration.traceId(), request.channel());
        ReplyDecorator.Processed pr = decorator.apply(retry.summary());
        persistence.writeBackSucceededTurn(ctx.sid(), request.message(), retry, pr.text(), userSentAt);
        if (retry.status() == GoalStatus.FAILED) {
            // 失败文案不做后处理（保持原始双段错误信息）
            return ChatResponse.failure(ctx.sid(), ctx.newSession(), retry.id(),
                    "编排失败: " + orchestration.summary() + "；重答失败: " + retry.summary(),
                    fallbackAgent, fallbackRoute);
        }
        return ChatResponse.success(ctx.sid(), ctx.newSession(), retry.id(), pr.text(),
                decorator.recentSources(ctx.sid()), fallbackAgent,
                decorator.withVoiceFlag(turnId, pr.flags()), fallbackRoute);
    }
}
