package com.dark.javaHarness.agent;

/**
 * 编排预算账本句柄（MultiAgentGraphAgent 按节点注入，同一编排共享同一账本）：
 * <ul>
 *   <li>{@link #overBudget()}：熔断判定（budget>0 且已消耗 ≥ 上限）。调用器在两个时点
 *       检查——发起调用前（零 HTTP 短路）与每次 LLM roundtrip 的 usage 帧到达时（含
 *       单次 call 内部工具循环的每轮，超限即断流，阻止下一轮发起）；
 *   <li>{@link #recordUsage(int, boolean)}：按 roundtrip 增量同步累计消耗（同一调用栈内
 *       可读，不依赖 llm_call_log 异步落库时序）。真实 usage 优先（streamUsage 帧），
 *       全程无 usage 时按输出文本估算并置 estimated=true（口径与 tokens_estimated 一致）。
 * </ul>
 * 聚合等「必发不受熔断」的调用方传 record-only 句柄（overBudget 恒 false，仅记账）。
 */
interface BudgetLedger {

    /** 熔断判定：true = 已达编排消费上限 */
    boolean overBudget();

    /** 累计消耗（estimated=true 表示含估算值，降级说明注明口径） */
    void recordUsage(int totalTokens, boolean estimated);

    /**
     * 编排预算熔断异常：超限断流/拒绝发起新调用时抛出。编排节点捕获后写
     * 「预算超限跳过」占位并注入聚合降级说明；非可重试错误（LlmRetry 天然旁路）。
     */
    final class BudgetExceededException extends RuntimeException {

        BudgetExceededException() {
            super("budget-exceeded: 编排 token 消费已达上限，熔断中止调用");
        }
    }
}
