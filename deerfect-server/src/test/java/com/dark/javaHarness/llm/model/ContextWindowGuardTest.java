package com.dark.javaHarness.llm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** per-call 窗口守卫纯函数：未声明窗口保持现状；声明后按角色输出档封顶。 */
class ContextWindowGuardTest {

    @Test
    void undeclaredWindow_returnsYamlBudgetUnchanged() {
        assertEquals(4000, ContextWindowGuard.effective(4000, 0, 8000));
        assertEquals(0, ContextWindowGuard.effective(0, 0, 8000));
        assertEquals(4000, ContextWindowGuard.effective(4000, -1, 8000));
    }

    @Test
    void declaredWindow_capsYamlBudget() {
        // window 32768, reserve min(32768/20=1638,2048)=1638, maxOutput 8000 → cap 23130
        assertEquals(4000, ContextWindowGuard.effective(4000, 32768, 8000));
        assertEquals(23130, ContextWindowGuard.effective(50000, 32768, 8000));
    }

    @Test
    void unlimitedYamlBudget_windowBecomesTheCap() {
        assertEquals(23130, ContextWindowGuard.effective(0, 32768, 8000));
    }

    @Test
    void smallWindow_floorAt1024() {
        // window 4096, reserve 204, maxOutput 8000 → 负值 → floor 1024
        assertEquals(1024, ContextWindowGuard.effective(50000, 4096, 8000));
        assertEquals(1024, ContextWindowGuard.effective(0, 4096, 8000));
    }

    @Test
    void reserveCappedAt2048ForLargeWindows() {
        // window 1_000_000, reserve min(50000,2048)=2048, maxOutput 8000 → cap 989952
        assertEquals(989952, ContextWindowGuard.effective(0, 1_000_000, 8000));
    }

    @Test
    void perRoleOutputTier_changesCap() {
        int lead = ContextWindowGuard.effective(0, 131072, 4000);
        int expert = ContextWindowGuard.effective(0, 131072, 4000);
        int finalTier = ContextWindowGuard.effective(0, 131072, 8000);
        assertEquals(lead, expert);
        assertEquals(4000, lead - finalTier);
    }
}
