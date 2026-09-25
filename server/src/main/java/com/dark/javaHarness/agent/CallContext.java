package com.dark.javaHarness.agent;

import com.dark.javaHarness.prompt.PromptAssembler;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 观测上下文（llm_call_log 落库的值对象，原 {@code AgentChatCaller.CallContext} 嵌套 record，
 * 超长类拆分 2026-09-25 提升为顶层——先例 {@link CallTrace}）：call/stream 两通道共用，
 * 收敛 {@code LlmCallObserver.okStream/error} 的重复参数手拼（11 处）；start 每次尝试各异，
 * 作为方法参数传入而非上下文字段。turnId/traceId/spanId/parentSpan 为轨迹标识四元组
 * （spanId 由调用发起点在构造本对象前生成）。方法均为历史内联口径的逐字面等价替换。
 */
record CallContext(LlmCallObserver observer, String sessionId, String forAgent,
                   String model, PromptAssembler.PromptAttachments attachments,
                   String turnId, String traceId, String spanId, String parentSpan) {

    /** 成功观测对：落库 + 账本估算兜底入账（无真实 usage 帧；ledger null 直通）。
     *  firstTokenAt 为首个 token 到达的绝对时间戳（0=无/SYNC 通道），与 start 差值即 TTFT。
     *  attempt/maxAttempts 为重试可见性（无重试通道记 1/1）。 */
    void ok(BudgetLedger ledger, long start, String content, Usage usage, long firstTokenAt,
            int attempt, int maxAttempts) {
        observer.okStream(sessionId, forAgent, model, start, content, usage, attachments, firstTokenAt,
                attempt, maxAttempts, turnId, traceId, spanId, parentSpan);
        BudgetLedger.recordEstimatedIfNoUsage(ledger, content, usage);
    }

    /** 失败观测（stream=true：两通道底座均为流式信道） */
    void error(long start, Exception e, int attempt, int maxAttempts) {
        observer.error(sessionId, forAgent, model, true, start, e, attachments, attempt, maxAttempts,
                turnId, traceId, spanId, parentSpan);
    }

    /** 去工具重试变体：工具/MCP 名单置空、技能保留（llm_call_log blankTools 口径） */
    CallContext blankTools() {
        return new CallContext(observer, sessionId, forAgent, model, attachments.blankTools(),
                turnId, traceId, spanId, parentSpan);
    }

    /** model 查表解析后派生（stream 入口取消早退发生在查表前，model 尚为 null） */
    CallContext withModel(String newModel) {
        return new CallContext(observer, sessionId, forAgent, newModel, attachments,
                turnId, traceId, spanId, parentSpan);
    }
}
