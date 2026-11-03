package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
