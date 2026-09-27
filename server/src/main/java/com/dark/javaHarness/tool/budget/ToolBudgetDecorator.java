package com.dark.javaHarness.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

import com.dark.javaHarness.config.ContextBudgetProperties;

/**
 * 预算装饰器：给工具列表套上 {@link ToolCallBudget#limit(List, int, int)} 护栏，
 * 简单（直答）与复杂（编排）两条路径统一生效——调用次数上限 + 单次结果 token 截断
 * 双维硬限制（2026-09-27 决策：MCP 大结果工具接入后，直答路径不再豁免次数上限）。
 *
 * <p>顺序契约中位于 Order 200——包在观测（Order 100）外层：预算耗尽时
 * 返回的引导文本不经过观测层，不产生假观测记录；观测层的执行计数只统计真实发生
 * 的工具执行。包装本身在请求组装期完成，零工具执行时零开销。
 *
 * <p>装配不走 Spring 容器：由 DefaultToolDecorators.defaults 静态工厂构造，
 * 构造注入 {@link ContextBudgetProperties} 读取 app.context 预算数值。
 */
public class ToolBudgetDecorator implements ToolCallbackDecorator {

    /** 固定顺序 200：包观测（100）外、懒加载（300）内 */
    public static final int ORDER = 200;

    private final ContextBudgetProperties budgets;

    public ToolBudgetDecorator(ContextBudgetProperties budgets) {
        this.budgets = budgets;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools) {
        return ToolCallBudget.limit(tools, budgets.getToolCallLimit(), budgets.getToolResultBudget());
    }
}
