package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.dark.javaHarness.config.ContextBudgetProperties;

/**
 * ToolBudgetDecorator（预算装饰器）单测：
 * - 简单（直答）与复杂（编排）路径统一生效：次数上限 + 结果截断双维硬限制
 * - 大结果按结果预算截断并带标记
 * - 空列表包装后仍为空列表
 * - 顺序契约：ORDER = 200，包观测（100）外——引导文本不产生假观测记录
 */
class ToolBudgetDecoratorTest {

    private ToolBudgetDecorator decoratorWithBudgets(int callLimit, int resultBudget) {
        ContextBudgetProperties budgets = new ContextBudgetProperties();
        budgets.setToolCallLimit(callLimit);
        budgets.setToolResultBudget(resultBudget);
        return new ToolBudgetDecorator(budgets);
    }

    @Test
    void decorate_直答路径同样受次数上限() {
        // 次数上限 1：第 2 次调用返回引导文本而非真实执行（两路径统一，2026-09-27 决策）
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        ToolCallback real = mock(ToolCallback.class);
        when(real.call(anyString())).thenReturn("ok");
        List<ToolCallback> tools = List.of(real);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        assertThat(result).isNotSameAs(tools).hasSize(1);
        assertThat(result.get(0).call("1")).isEqualTo("ok");
        assertThat(result.get(0).call("2")).isNotEqualTo("ok");
        verify(real, times(1)).call(anyString());
    }

    @Test
    void decorate_大结果按结果预算截断() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(8, 100);
        ToolCallback real = mock(ToolCallback.class);
        when(real.call("q")).thenReturn("x".repeat(8000)); // ≈2000 token，远超预算 100
        List<ToolCallback> tools = List.of(real);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        String out = result.get(0).call("q");
        assertThat(TokenEstimator.estimateTokens(out)).isLessThanOrEqualTo(100);
        assertThat(out).endsWith(ToolCallBudget.TRUNCATED_SUFFIX);
    }

    @Test
    void decorate_包装后仍委托原工具() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        ToolCallback real = mock(ToolCallback.class);
        when(real.call("q")).thenReturn("ok");
        List<ToolCallback> tools = List.of(real);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        // limit 包装发生：新列表、元素为 BudgetedCallback（不再是原实例）
        assertThat(result).isNotSameAs(tools).hasSize(1);
        assertThat(result.get(0)).isNotSameAs(real);
        // 首次执行仍真实委托原工具
        assertThat(result.get(0).call("q")).isEqualTo("ok");
        verify(real).call("q");
    }

    @Test
    void decorate_wrapsEmptyList() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null);

        List<ToolCallback> result = decorator.decorate(ctx, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void order_is200() {
        assertThat(ToolBudgetDecorator.ORDER).isEqualTo(200);
        assertThat(decoratorWithBudgets(0, 0).getOrder()).isEqualTo(200);
    }
}
