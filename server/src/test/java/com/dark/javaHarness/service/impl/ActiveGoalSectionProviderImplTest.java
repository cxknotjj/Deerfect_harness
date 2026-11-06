package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.GoalService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 活跃目标段渲染单测：无目标跳过、内容含 id 与天数、超长目标截断。
 *
 * <p>这段文本会进每轮 system，长度与措辞都是行为的一部分：太长挤占上下文，
 * 少了 id 用户没法用 /goal done 收口。
 */
@ExtendWith(MockitoExtension.class)
class ActiveGoalSectionProviderImplTest {

    @Mock
    private GoalService goalService;

    private ActiveGoalSectionProviderImpl provider() {
        return new ActiveGoalSectionProviderImpl(goalService);
    }

    @Test
    void noActiveGoal_returnsNull() {
        when(goalService.activeBySession("s1")).thenReturn(Optional.empty());

        assertNull(provider().provide("s1"), "无目标时应返回 null（空段跳过，不留空标题）");
    }

    @Test
    void rendersObjectiveIdAndDuration() {
        Goal goal = new Goal("goal-1", "整理 WMS 库存异常", "s1", GoalStatus.ACTIVE,
                LocalDateTime.now().minusDays(2), null, null, null, null, null, null);
        when(goalService.activeBySession("s1")).thenReturn(Optional.of(goal));

        String text = provider().provide("s1");

        assertTrue(text.contains("【当前目标】整理 WMS 库存异常"), text);
        assertTrue(text.contains("goal-1"), "必须带 id，用户才能 /goal done 收口: " + text);
        assertTrue(text.contains("第 3 天"), "含今天应是第 3 天: " + text);
        assertTrue(text.contains("/goal done goal-1"), text);
    }

    /** 目标描述可能很长：截断到 200 字，别让一段目标吃掉本轮预算 */
    @Test
    void longObjective_truncated() {
        Goal goal = new Goal("goal-2", "x".repeat(500), "s1", GoalStatus.ACTIVE,
                LocalDateTime.now(), null, null, null, null, null, null);
        when(goalService.activeBySession("s1")).thenReturn(Optional.of(goal));

        String text = provider().provide("s1");

        assertTrue(text.contains("…"), "超长目标应截断并留省略号: " + text);
        assertTrue(text.length() < 400, "整段应远小于 500 字目标，实际 " + text.length());
    }
}
