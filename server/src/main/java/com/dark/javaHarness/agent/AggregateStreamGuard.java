package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.PromptBudgetAdvisor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * 流式聚合护栏（从 {@link MultiAgentGraphAgent} 的 predictAggregateStreaming 提取）：
 * 聚合是编排唯一对用户可见的 token 通道，失败自愈策略为带护栏的流式重试（不回退阻塞调用）：
 * <ul>
 *   <li>流式异常且未推出任何 token（挂死超时等首 token 前失败）→ 流式重试一次；
 *   <li>流式成功但 0 个内容 token（思考模型输出全在 reasoning_content 等）→ 流式重试一次；
 *   <li>已推出 token 后失败 → 不重试（重试会造成内容重复），以已收内容为准；
 *   <li>重试后仍失败 / 仍 0 token → 上抛（编排按失败收尾）。
 * </ul>
 * 例外：客户端断连中止（取消异常）不重试、部分输出不按成功返回，取消异常向上传播。
 */
final class AggregateStreamGuard {

    private static final Logger log = LoggerFactory.getLogger(AggregateStreamGuard.class);

    private final AgentChatCaller chatCaller;
    private final String aggregatorRole;
    private final String aggregatorPrompt;
    /** 聚合预算 advisor 供给（按「【子任务N】」节边界等份额截断；每次求值与原实现一致） */
    private final java.util.function.Supplier<PromptBudgetAdvisor> aggregateAdvisor;

    AggregateStreamGuard(AgentChatCaller chatCaller,
                         String aggregatorRole,
                         String aggregatorPrompt,
                         java.util.function.Supplier<PromptBudgetAdvisor> aggregateAdvisor) {
        this.chatCaller = chatCaller;
        this.aggregatorRole = aggregatorRole;
        this.aggregatorPrompt = aggregatorPrompt;
        this.aggregateAdvisor = aggregateAdvisor;
    }

    /**
     * 流式聚合：首个内容 token 前推「聚合」进度行，随后逐 token 实时发射（聚合只有流式一条语义路径）。
     * budgetLedger 为 record-only 句柄（聚合不受熔断，仅记账；可 null）。
     * trace 为轨迹标识（聚合为派生调用：parent_span=lead 的 span_id，与子任务同树）。
     */
    String predictStreaming(String sessionId,
                            String user,
                            Sinks.Many<String> liveTokens,
                            AtomicBoolean contentSent,
                            AtomicBoolean cancelled,
                            BudgetLedger budgetLedger,
                            CallTrace trace) {
        BranchProgressListener.tryEmitSerialized(liveTokens,
                ProgressLine.encode("聚合", "汇总子任务结果，生成最终回答"));
        StringBuilder collected = new StringBuilder();
        try {
            streamOnce(sessionId, user, collected, liveTokens, contentSent, cancelled, budgetLedger, trace);
            if (collected.length() > 0) {
                return collected.toString();
            }
            log.warn("[multi-agent][aggregate] 流式聚合 0 个内容 token，流式重试一次");
        } catch (Exception e) {
            // 客户端断连中止：取消不是流式失败，禁止重试/以部分输出充数——原样上抛
            if (e instanceof CancellationException ce) {
                throw ce;
            }
            if (isCancelled(cancelled)) {
                throw CallCancellation.cancelException();
            }
            if (collected.length() > 0) {
                // 已推 token 后失败：重试会内容重复，以已收内容为准
                log.warn("[multi-agent][aggregate] 流式聚合失败（已推出部分 token，不重试）：{}", safe(e));
                return collected.toString();
            }
            log.warn("[multi-agent][aggregate] 流式聚合失败，流式重试一次：{}", safe(e));
        }
        // 护栏重试：到达此处必然未推出任何 token（重试零内容重复风险）
        try {
            streamOnce(sessionId, user, collected, liveTokens, contentSent, cancelled, budgetLedger, trace);
        } catch (Exception e2) {
            if (e2 instanceof CancellationException ce) {
                throw ce;
            }
            if (isCancelled(cancelled)) {
                throw CallCancellation.cancelException();
            }
            log.warn("[multi-agent][aggregate] 聚合流式重试仍失败：{}", safe(e2));
            throw e2;
        }
        if (collected.length() == 0) {
            throw new IllegalStateException("聚合流式重试后仍无内容输出");
        }
        return collected.toString();
    }

    /** 聚合单次流式尝试：token 追加进 collected 并经旁路发射（成败处置由调用方负责） */
    private void streamOnce(String sessionId, String user, StringBuilder collected,
                            Sinks.Many<String> liveTokens, AtomicBoolean contentSent,
                            AtomicBoolean cancelled,
                            BudgetLedger budgetLedger, CallTrace trace) {
        chatCaller.stream(sessionId, aggregatorRole, aggregatorPrompt,
                user,
                token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    collected.append(token);
                    contentSent.set(true);
                    BranchProgressListener.tryEmitSerialized(liveTokens, token);
                },
                null,
                new PromptBudgetAdvisor[]{aggregateAdvisor.get()},
                cancelled == null ? null : cancelled::get,
                budgetLedger, trace);
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
