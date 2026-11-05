package com.dark.javaHarness.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.enums.GoalStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Goal 生命周期契约（harness-api 高扇入基座）：PENDING → RUNNING → SUCCEEDED / FAILED。
 *
 * <p>Goal 本身不设状态守卫（断点续跑会把 SUCCEEDED/FAILED 的 Goal 重新置为 RUNNING，
 * 合法性由调用方与服务层判定），因此这里同时固化「无守卫」这一既定语义。
 */
class GoalStateMachineTest {

    @Test
    void newGoal_startsPendingWithoutFinishInfo() {
        Goal goal = new Goal("g1", "写一份报告");

        assertEquals(GoalStatus.PENDING, goal.status());
        assertNotNull(goal.createdAt(), "创建时间应在构造时记录");
        assertNull(goal.finishedAt(), "未完成不应有完成时间");
        assertNull(goal.summary(), "未完成不应有摘要");
        assertNull(goal.lastProgressAt(), "未进入 RUNNING 不应有进展时间");
    }

    @Test
    void pendingToRunningToSucceeded_isLegalPath() {
        Goal goal = new Goal("g2", "写一份报告");
        goal.markRunning();
        assertEquals(GoalStatus.RUNNING, goal.status());
        assertNotNull(goal.lastProgressAt(), "进入 RUNNING 应初始化进展时间");

        goal.succeed("报告已生成");

        assertEquals(GoalStatus.SUCCEEDED, goal.status());
        assertEquals("报告已生成", goal.summary());
        assertNotNull(goal.finishedAt(), "成功应记录完成时间");
    }

    @Test
    void pendingToRunningToFailed_isLegalPath() {
        Goal goal = new Goal("g3", "写一份报告");
        goal.markRunning();
        goal.fail("子任务超时");

        assertEquals(GoalStatus.FAILED, goal.status());
        assertEquals("子任务超时", goal.summary());
        assertNotNull(goal.finishedAt(), "失败同样应记录完成时间");
    }

    @Test
    void touchProgress_refreshesTimestampWithoutChangingStatus() {
        Goal goal = new Goal("g4", "写一份报告");
        goal.markRunning();
        LocalDateTime before = goal.lastProgressAt();

        goal.touchProgress();

        assertEquals(GoalStatus.RUNNING, goal.status(), "刷新进展不应改变状态");
        assertNotNull(goal.lastProgressAt());
        assertFalse(goal.lastProgressAt().isBefore(before), "进展时间只能前进");
    }

    /** 既定语义：Goal 无状态守卫，非法迁移由调用方守护（本类只记录字段变化） */
    @Test
    void succeedFromPending_isNotRejectedByGoalItself() {
        Goal goal = new Goal("g5", "写一份报告");

        goal.succeed("直接成功");

        assertEquals(GoalStatus.SUCCEEDED, goal.status());
        assertEquals("直接成功", goal.summary());
    }

    /** 断点续跑语义：已结束的 Goal 允许重新进入 RUNNING（服务层负责拒绝无需续跑的场景） */
    @Test
    void markRunningAfterFinished_isAllowedForResume() {
        Goal goal = new Goal("g6", "写一份报告");
        goal.fail("客户端断开");
        assertNotNull(goal.finishedAt());

        goal.markRunning();

        assertEquals(GoalStatus.RUNNING, goal.status());
        assertNotNull(goal.lastProgressAt(), "续跑应刷新进展时间");
        // finishedAt 不清空：类不负责回滚完成时间，由持久化层按状态解释
        assertNotNull(goal.finishedAt());
    }

    @Test
    void restorableConstructor_preservesPersistedState() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime finished = LocalDateTime.of(2026, 1, 1, 10, 5);
        LocalDateTime progress = LocalDateTime.of(2026, 1, 1, 10, 2);

        Goal goal = new Goal("g7", "写一份报告", "s1", GoalStatus.SUCCEEDED, created, finished,
                "完成", "t1", null, "qq", progress);

        assertEquals("g7", goal.id());
        assertEquals("写一份报告", goal.objective());
        assertEquals("s1", goal.sessionId());
        assertEquals(GoalStatus.SUCCEEDED, goal.status());
        assertEquals(created, goal.createdAt());
        assertEquals(finished, goal.finishedAt());
        assertEquals("完成", goal.summary());
        assertEquals("t1", goal.turnId());
        assertNull(goal.traceId(), "轨迹标识不入库，恢复后应为 null");
        assertEquals("qq", goal.channel());
        assertEquals(progress, goal.lastProgressAt());
    }

    @Test
    void convenienceConstructors_defaultOptionalContextToNull() {
        Goal twoArgs = new Goal("g8", "目标");
        assertNull(twoArgs.sessionId());
        assertNull(twoArgs.turnId());
        assertNull(twoArgs.traceId());
        assertNull(twoArgs.channel());

        Goal threeArgs = new Goal("g9", "目标", "s2");
        assertEquals("s2", threeArgs.sessionId());

        Goal fiveArgs = new Goal("g10", "目标", "s3", "t3", "tr3");
        assertEquals("t3", fiveArgs.turnId());
        assertEquals("tr3", fiveArgs.traceId());
        assertNull(fiveArgs.channel());

        Goal sixArgs = new Goal("g11", "目标", "s4", "t4", "tr4", "cli");
        assertEquals("cli", sixArgs.channel());
    }

    @Test
    void identityAccessors_echoConstructorInputs() {
        Goal goal = new Goal("g12", "目标", "s5", "t5", "tr5", "web");

        assertEquals("g12", goal.id());
        assertEquals("目标", goal.objective());
        assertEquals("s5", goal.sessionId());
        assertEquals("t5", goal.turnId());
        assertEquals("tr5", goal.traceId());
        assertEquals("web", goal.channel());
        assertTrue(goal.createdAt().isBefore(LocalDateTime.now().plusSeconds(1)));
    }
}
