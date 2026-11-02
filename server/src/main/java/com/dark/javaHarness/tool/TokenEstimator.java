package com.dark.javaHarness.tool;

/**
 * Token 近似估算器（全项目统一口径）：
 * CJK 及全角区字符按 1 token，其余字符按 4 字符 ≈ 1 token。
 *
 * <p>消费方：LlmCallRecorder（无 usage 回包时的观测估算）、
 * ContextAssemblingAdvisor（会话历史裁剪预算）、ToolCallBudget（工具结果上下文预算）。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 近似估算 token 数：中文按 1 token，其它按 (长度+3)/4 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) > 0x2E80 ? 1 : 0; // CJK 及全角区按 1 token
        }
        int other = text.length() - tokens;
        return tokens + (other + 3) / 4;
    }

    /**
     * 截取最大前缀，使估算 token 数 ≤ limit（估算对长度单调，二分查找）；
     * 截断点恰落在 UTF-16 代理对之间时回退一位，避免孤立代理产生坏字符。
     */
    public static String truncateToTokens(String text, int limit) {
        if (text == null || text.isEmpty() || limit <= 0) {
            return "";
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (estimateTokens(text.substring(0, mid)) <= limit) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo > 0 && lo < text.length()
                && Character.isHighSurrogate(text.charAt(lo - 1))
                && Character.isLowSurrogate(text.charAt(lo))) {
            lo--;
        }
        return text.substring(0, lo);
    }
}
