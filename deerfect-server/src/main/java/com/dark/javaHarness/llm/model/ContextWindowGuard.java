package com.dark.javaHarness.llm.model;

/**
 * per-call 上下文窗口守卫（纯函数，无状态、不查库）：把 yaml 输入预算收敛到模型窗口之内。
 *
 * <p>窗口约束的是<b>单次 LLM 请求</b>，故 agentic 直出与 graph 各节点（lead/专家/聚合）
 * 共用同一公式、逐调用生效；节点间不共享"总上下文"。编排全程累计消耗由
 * {@code app.context.orchestration-budget}（BudgetLedger）独立门控，与本守卫正交。
 *
 * <p>公式：{@code cap = max(window - maxOutput - reserve, 1024)}，reserve = min(window*5%, 2048)；
 * yaml 预算为 0（不限制）时由窗口兜底取 cap，否则取 min(yaml, cap)。
 * window ≤ 0（未声明）时原样返回 yaml 预算，行为与引入守卫前完全一致。
 */
public final class ContextWindowGuard {

    /** 输入下限：窗口再小也保留最低可用输入，避免负预算 */
    private static final int FLOOR = 1024;
    /** 输出/协议预留上限 */
    private static final int RESERVE_CAP = 2048;

    private ContextWindowGuard() {
    }

    public static int effective(int yamlBudget, int contextWindow, int maxOutput) {
        if (contextWindow <= 0) {
            return yamlBudget;
        }
        int reserve = Math.min(contextWindow / 20, RESERVE_CAP);
        int cap = Math.max(contextWindow - Math.max(maxOutput, 0) - reserve, FLOOR);
        return yamlBudget <= 0 ? cap : Math.min(yamlBudget, cap);
    }
}
