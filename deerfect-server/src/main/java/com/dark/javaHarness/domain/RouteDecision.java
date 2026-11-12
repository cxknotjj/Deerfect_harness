package com.dark.javaHarness.domain;

/**
 * 主 Agent 前置判断的路由决策（对外叙事：路径）。
 *
 * <p>只表达「当前请求应走哪条路径」，不携带任何执行逻辑：
 * - {@link #SIMPLE}：Agentic 路径——单一工作流，由一个 Agent 自主推理-行动、按需多步调用工具。
 * - {@link #COMPLEX}：Graph 编排路径——存在多个可独立并行推进的工作流或多份交付物。
 */
public enum RouteDecision {

    SIMPLE,
    COMPLEX;

    /**
     * 由 LLM 返回的 route 文本归一化为枚举。
     * 仅 {@code complex}/{@code graph}（大小写不敏感）为 Graph；其余任意值
     * （含 null、{@code simple}、{@code agentic}、乱码）一律归一为 {@link #SIMPLE}，
     * 符合「宁可简单，不强行走编排」的兜底原则。
     */
    public static RouteDecision fromRaw(String raw) {
        if (raw != null) {
            String value = raw.trim();
            if ("complex".equalsIgnoreCase(value) || "graph".equalsIgnoreCase(value)) {
                return COMPLEX;
            }
        }
        return SIMPLE;
    }
}