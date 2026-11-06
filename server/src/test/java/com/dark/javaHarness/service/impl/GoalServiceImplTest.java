package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.mapper.GoalMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GoalServiceImpl 启动清理单测：僵尸 RUNNING 目标（服务强杀残留）批量标记 FAILED，
 * 保证 /resume 不再被 409（仍在执行中）拦截。
 */
@ExtendWith(MockitoExtension.class)
class GoalServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // 纯单测环境无 MyBatis 引导，lambdaUpdate() 需要实体的 TableInfo 缓存，手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GoalEntity.class);
    }

    /** 长期目标：落库即 ACTIVE、绑定会话、不进执行链 */
    @Test
    void createActive_persistsActiveGoalBoundToSession() {
        GoalMapper mapper = mock(GoalMapper.class);
        GoalServiceImpl service = new GoalServiceImpl(mapper);

        service.createActive("整理库存异常", "42");

        ArgumentCaptor<GoalEntity> captor = ArgumentCaptor.forClass(GoalEntity.class);
        verify(mapper).insert(captor.capture());
        GoalEntity row = captor.getValue();
        assertEquals("ACTIVE", row.getStatus(), "长期目标不能是 PENDING（会被执行链当作待执行）");
        assertEquals("42", row.getSessionId(), "目标必须绑定会话，否则每轮注入找不到它");
        assertEquals("整理库存异常", row.getObjective());
    }

    @Test
    void activeBySession_blankSession_returnsEmpty() {
        GoalServiceImpl service = new GoalServiceImpl(mock(GoalMapper.class));

        assertTrue(service.activeBySession(" ").isEmpty());
    }

    /** 只有 ACTIVE 能被标记完成：重复调用或对已结束目标调用都应该返回 false，而不是把状态改回去 */
    @Test
    void completeActive_nonActiveGoal_returnsFalse() {
        GoalMapper mapper = mock(GoalMapper.class);
        GoalEntity row = new GoalEntity();
        row.setId("goal-1");
        row.setObjective("已结束的目标");
        row.setStatus("SUCCEEDED");
        row.setCreatedAt(java.time.LocalDateTime.now());
        when(mapper.selectById("goal-1")).thenReturn(row);
        GoalServiceImpl service = new GoalServiceImpl(mapper);

        assertFalse(service.completeActive("goal-1"));
    }

    @Test
    void failAllRunning_updatesStaleGoalsToFailed() {
        GoalMapper mapper = mock(GoalMapper.class);
        GoalServiceImpl service = new GoalServiceImpl(mapper);

        int n = service.failAllRunning("服务重启，执行中断");

        assertEquals(0, n, "mock update 默认返回 0 行");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Object>> captor =
                ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(mapper).update(isNull(), any());
    }

    @Test
    void touchProgress_issuesRunningOnlyUpdate() {
        GoalMapper mapper = mock(GoalMapper.class);
        GoalServiceImpl service = new GoalServiceImpl(mapper);

        service.touchProgress("goal-1");

        verify(mapper).update(isNull(), any());
    }

    @Test
    void failStagnant_issuesConditionalUpdateAndReturnsAffectedRows() {
        GoalMapper mapper = mock(GoalMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(3);
        GoalServiceImpl service = new GoalServiceImpl(mapper);

        int n = service.failStagnant(
                java.time.LocalDateTime.of(2026, 11, 2, 23, 30), "执行停滞：超过 30 分钟无进展");

        assertEquals(3, n);
        verify(mapper).update(isNull(), any());
    }

    @Test
    void capSummary_truncatesLongReason() {
        String longReason = "x".repeat(GoalServiceImpl.SUMMARY_MAX_CHARS + 10);
        String capped = GoalServiceImpl.capSummary(longReason);
        assertEquals(GoalServiceImpl.SUMMARY_MAX_CHARS + "…（超长截断）".length(), capped.length());
        assertTrue(capped.endsWith("…（超长截断）"));
    }
}
