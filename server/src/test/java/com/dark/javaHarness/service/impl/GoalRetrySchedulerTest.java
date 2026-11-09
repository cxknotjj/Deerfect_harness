package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.mapper.GoalMapper;
import com.dark.javaHarness.contract.api.AgentService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 重投调度器单测：到点受理、不受理时清标记、异常不中断调度。
 *
 * <p>「不受理要清 next_retry_at」是这里最关键的行为：agent 被删后若不清，
 * 每 30 秒会命中同一条，日志刷屏且永远占着扫描名额（单轮上限 10 条）。
 */
@ExtendWith(MockitoExtension.class)
class GoalRetrySchedulerTest {

    @Mock
    private GoalMapper goalMapper;

    @Mock
    private AgentService agentService;

    @BeforeAll
    static void initTableInfo() {
        // lambdaQuery/lambdaUpdate 需要实体的 TableInfo 缓存，纯单测环境手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GoalEntity.class);
    }

    private static GoalEntity failedGoal(String id) {
        GoalEntity row = new GoalEntity();
        row.setId(id);
        row.setStatus("FAILED");
        row.setAgentName("general");
        row.setRetryCount(0);
        row.setNextRetryAt(LocalDateTime.now().minusSeconds(5));
        return row;
    }

    @Test
    void noDueTasks_returnsZero() {
        when(goalMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, new GoalRetryScheduler(goalMapper, agentService).retryDue());
        verify(agentService, never()).retry(any());
    }

    /** 受理后也要清标记：否则 SUCCEEDED 的目标会带着一个陈旧的「下次重投时间」 */
    @Test
    void dueTask_accepted_countsAndClearsMarker() {
        when(goalMapper.selectList(any())).thenReturn(List.of(failedGoal("goal-1")));
        when(agentService.retry("goal-1")).thenReturn(true);

        assertEquals(1, new GoalRetryScheduler(goalMapper, agentService).retryDue());
        verify(goalMapper).update(any(), any());
    }

    /** 不受理（agent 被删 / 状态已变 / 超上限）必须清标记，否则每轮重扫同一条 */
    @Test
    void dueTask_rejected_clearsNextRetry() {
        when(goalMapper.selectList(any())).thenReturn(List.of(failedGoal("goal-2")));
        when(agentService.retry("goal-2")).thenReturn(false);

        assertEquals(0, new GoalRetryScheduler(goalMapper, agentService).retryDue());

        verify(agentService).retry("goal-2");
        // 断言「有清零动作」而不是抠 SQL 文本：Wrapper 实现类与 set 片段随 MP 版本变化
        verify(goalMapper).update(any(), any());
    }

    /** 重投本身抛异常也不能让调度线程挂掉：当成「本轮不受理」处理 */
    @Test
    void dueTask_retryThrows_treatedAsRejected() {
        when(goalMapper.selectList(any())).thenReturn(List.of(failedGoal("goal-3")));
        when(agentService.retry("goal-3")).thenThrow(new IllegalStateException("registry down"));

        assertEquals(0, new GoalRetryScheduler(goalMapper, agentService).retryDue());
        verify(goalMapper).update(any(), any());
    }

    @Test
    void scanFailure_returnsZeroWithoutThrowing() {
        when(goalMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, new GoalRetryScheduler(goalMapper, agentService).retryDue());
        verify(agentService, never()).retry(any());
    }
}
